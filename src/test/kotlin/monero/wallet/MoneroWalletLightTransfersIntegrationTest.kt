package monero.wallet

import java.math.BigInteger
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The transfers of the transactions of a light wallet, read with the queries of
 * [MoneroTxQuery] after the send flows: the incoming ones, the outgoing ones, the pending one of a
 * transaction that a relay reached the pool of the node with, and the failed ones, which the history
 * that the server answers holds none of, since a transaction that the chain never held is failed in
 * the answer of its creation alone. The change of a transaction that the wallet sent is told by the
 * address that it pays, which the pending transaction names as the sender's own, and it credits the
 * account of the sender: the light wallet hides the change of its own outgoing transaction from the
 * incoming transfers, which the outgoing amount and the output of the change tell instead. It needs
 * the monero-lws of src/test/integration/docker-compose.yml and the regtest node behind it, whose
 * chain the other tests of the suite mine onto as well, so every wallet is of a random seed of its
 * own and the blocks that the test does not mean to pay anyone mine to an address that no account of
 * the server watches. It skips when no server answers, like the tests of
 * MoneroWalletLightIntegrationTest, which this class runs after.
 */
@Order(12)
@Tag("integration")
class MoneroWalletLightTransfersIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }
    private val daemon: MoneroDaemonRpc by lazy { MoneroDaemonRpc(RegtestSupport.daemonConnection()) }

    // the amount to send, in atomic units: five monero
    private val amount = BigInteger.valueOf(5_000_000_000_000L)

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // the mining of the shared chain races the mining of the other tests of the suite now and then,
    // which makes the node refuse a block that it built on a tip that another one replaced, so a
    // refused block is tried again a few times before it is a failure of the test
    private fun mine(numBlocks: Int, address: String) {
        var tries = 0
        while (true) {
            try {
                RegtestSupport.mineBlocks(numBlocks, address)
                return
            } catch (e: MoneroError) {
                if (++tries >= 5) throw e
                Thread.sleep(1000)
            }
        }
    }

    // the server scans the blocks of an account behind the chain, which the other tests keep mining:
    // this waits longer than the minute of LwsSupport.waitScannedToChain, whose deadline a busy
    // scanner of many accounts passes
    private fun waitScanned(address: String, viewKey: String) {
        val deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos()
        while (true) {
            val info = LwsSupport.post("/get_address_info", """{"address":"$address","view_key":"$viewKey"}""")
            val scanned = Regex("\"scanned_block_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            val chain = Regex("\"blockchain_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            if (scanned != null && chain != null && scanned >= chain) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached the height of the chain: it is at $scanned of $chain")
            Thread.sleep(1000)
        }
    }

    /**
     * An address of mainnet that no account of the server watches, which the blocks that the sends
     * need pay their coinbases to: nobody's balance moves with them. A keys-only wallet has the
     * address without the server.
     *
     * @return an address that the server does not watch
     */
    private fun unwatchedAddress(): String {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        return MoneroWalletKeys.createWalletRandom(config).use { miner -> miner.getPrimaryAddress() }
    }

    // a wallet of a random seed, whose coinbases matured: the funding retries, because a
    // reorganization of the shared chain can drop the blocks that a round of it mined
    private fun fundedWallet(coinbases: Int): MoneroWalletLight {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            for (round in 1..10) {
                mine(coinbases, wallet.getPrimaryAddress())
                mine(63, unwatchedAddress())
                waitScanned(wallet.getPrimaryAddress(), wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getUnlockedBalance() > BigInteger.ZERO) return wallet
            }
            throw AssertionError("the wallet never kept unlocked money: ${wallet.getBalance()}")
        } catch (e: Throwable) {
            wallet.close()
            throw e
        }
    }

    // the sender of a transfer and its receiver, of random seeds: the sender has a single matured
    // coinbase, which makes the change of the transfer the only money that comes back to it
    private fun senderAndReceiver(): Pair<MoneroWalletLight, MoneroWalletLight> {
        val receiverConfig = MoneroWalletConfig()
        receiverConfig.networkType = MoneroNetworkType.MAINNET
        val receiver = MoneroWalletLight.createWallet(receiverConfig, connection)
        val sender = fundedWallet(1)
        return sender to receiver
    }

    // the outgoing and the pending state of a transaction that a relay reached the pool of the node
    // with: the server does not sync the pool, so the sender alone reads the transaction, which the
    // query by the pool finds next to the coinbase that the query by the incoming transfers finds,
    // and the change of the transaction is pending money that the address of it names the sender of
    @Test
    fun testOutgoingAndPendingTransfersAfterASend() {
        val (sender, receiver) = senderAndReceiver()
        try {
            val senderAddress = sender.getPrimaryAddress()
            val receiverAddress = receiver.getPrimaryAddress()
            val before = sender.getBalance()

            val config = MoneroTxConfig()
            config.accountIndex = 0
            config.addDestination(receiverAddress, amount)
            val tx = sender.createTxs(config).single()
            val fee = assertNotNull(tx.fee)
            val hash = assertNotNull(sender.relayTx(assertNotNull(tx.metadata)))

            // the transaction is pending on the sender, and outgoing from its account
            val pending = sender.getTxs(MoneroTxQuery().apply { inTxPool = true })
            assertEquals(listOf(hash), pending.map { tx -> tx.hash }, "the relayed transaction is the pending one of the sender")
            assertEquals(true, pending[0].isOutgoing, "the pending transaction is outgoing")
            assertEquals(false, pending[0].isConfirmed, "the pending transaction is not confirmed")
            assertEquals(0, pending[0].outgoingTransfer!!.accountIndex, "the pending transaction is outgoing from the first account")

            // the outgoing query finds the pending transaction, and the incoming one finds the
            // coinbase of the sender only: the change of a transaction that the wallet built is not
            // an incoming transfer of the answer, which the address and the amount of the change tell
            assertEquals(listOf(hash), sender.getTxs(MoneroTxQuery().apply { isOutgoing = true }).map { tx -> tx.hash }, "the outgoing query finds the sent transaction")
            assertEquals(1, sender.getTxs(MoneroTxQuery().apply { isIncoming = true }).size, "the incoming query finds the coinbase of the sender")
            assertEquals(1, sender.getTxs(MoneroTxQuery().apply { isConfirmed = true }).size, "the coinbase is the confirmed transaction of the sender")

            // the change is told by the address that it pays, which is the sender's own, and it is
            // the pending money of the balance
            val sent = pending[0]
            assertEquals(senderAddress, sent.changeAddress, "the change pays the address of the sender")
            assertEquals(before.subtract(amount).subtract(fee), sent.changeAmount, "the change is the coinbase less the amount and the fee")
            assertEquals(before.subtract(amount).subtract(fee), sender.getBalance(), "the change is pending money of the balance")
        } finally {
            sender.close()
            receiver.close()
        }
    }

    // the change of a transaction that a block holds credits the account that sent it: the light
    // wallet hides the change of its own outgoing transaction from the incoming transfers, so the
    // outgoing amount tells the amount that was paid and the output of the change tells the money
    // that came back, which the balance of the sender counts. the receiver reads its own incoming
    // transfer of the amount that was sent, and the failed query of the history holds none, since
    // the light wallet tracks no transaction that the chain never held
    @Test
    fun testTheChangeTransferCreditsTheSenderThatSentIt() {
        val (sender, receiver) = senderAndReceiver()
        try {
            val senderAddress = sender.getPrimaryAddress()
            val receiverAddress = receiver.getPrimaryAddress()
            val before = sender.getBalance()

            val config = MoneroTxConfig()
            config.accountIndex = 0
            config.addDestination(receiverAddress, amount)
            val tx = sender.createTxs(config).single()
            val fee = assertNotNull(tx.fee)
            val hash = assertNotNull(sender.relayTx(assertNotNull(tx.metadata)))

            // a block holds the transaction, which the pool of the node gives to the next block that
            // it builds: the daemon of the regtest chain takes a moment to make a relayed
            // transaction public to its blocks, and the shared chain reorganizes under the tests now
            // and then, which drops the block that a round mined with the transaction in it, so the
            // round waits for the pool to hold the transaction, mines a block, and reads the wallets
            // again until the chain holds it
            for (round in 1..20) {
                if (daemon.getTxPoolHashes().contains(hash)) mine(1, unwatchedAddress())
                waitScanned(senderAddress, sender.getPrivateViewKey())
                waitScanned(receiverAddress, receiver.getPrivateViewKey())
                sender.sync()
                receiver.sync()
                if (receiver.getBalance() == amount) break
                Thread.sleep(1000)
            }

            // the transaction of the sender is confirmed now: it is outgoing by the amount that it
            // paid, and the change is what the sender keeps of the coinbase, which the balance reads.
            // the change of a transaction that the account sent does not make an incoming transfer
            // of the light wallet, which the outgoing side of the transaction alone tells
            val confirmed = sender.getTxs(MoneroTxQuery().apply { this.hash = hash }).single()
            assertEquals(true, confirmed.isConfirmed, "the transaction that the block holds is confirmed")
            assertEquals(true, confirmed.isOutgoing, "the transaction of the sender is outgoing")
            assertEquals(false, confirmed.isIncoming, "the change of the transaction does not make it incoming")
            assertEquals(amount, confirmed.outgoingTransfer!!.amount, "the outgoing amount of the transaction is the amount that it paid")
            assertEquals(before.subtract(amount).subtract(fee), sender.getBalance(), "the sender keeps the change")

            // the change is the output that the transaction pays the account of the sender with: the
            // address that it pays is the sender's own, which the pending transaction named, and the
            // output credits the account of the sender with the change
            val changeOutput = sender.getTxs(MoneroTxQuery().apply { this.hash = hash; includeOutputs = true }).single().getOutputsWallet().single()
            assertEquals(0, changeOutput.accountIndex, "the change output pays the account of the sender")
            assertEquals(0, changeOutput.subaddressIndex, "the change output pays the primary address of the sender")
            assertEquals(before.subtract(amount).subtract(fee), changeOutput.amount, "the change output is the coinbase less the amount and the fee")

            // the receiver reads its incoming transfer of the amount that was sent
            val received = receiver.getTxs(MoneroTxQuery().apply { this.hash = hash }).single()
            assertEquals(true, received.isIncoming, "the transaction of the receiver is incoming")
            assertEquals(receiverAddress, received.incomingTransfers!!.single().address, "the incoming transfer of the receiver pays its address")
            assertEquals(amount, received.getIncomingAmount(), "the receiver reads the amount that was sent")
            assertEquals(amount, receiver.getBalance(), "the balance of the receiver is the amount")

            // the failed query of the history of both wallets holds no transaction: the sends of the
            // flow relayed, and a transaction whose relay the server refuses is failed in the answer
            // of its creation alone, which MoneroWalletLightTxsIntegrationTest reads
            assertEquals(emptyList(), sender.getTxs(MoneroTxQuery().apply { isFailed = true }).map { tx -> tx.hash }, "the sender tracks no failed transaction")
            assertEquals(emptyList(), receiver.getTxs(MoneroTxQuery().apply { isFailed = true }).map { tx -> tx.hash }, "the receiver tracks no failed transaction")
        } finally {
            sender.close()
            receiver.close()
        }
    }
}
