package monero.wallet

import java.math.BigInteger
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The send path of the light wallet against a live monero-lws: the transactions that a wallet with a
 * seed creates without relaying, which carry the metadata that a relay takes, and the relays of them
 * from that metadata and from the transaction itself, whose hashes agree with the hash that the
 * creation answered. The balances of the sender and of the receiver move by the exact integers that
 * the amounts and the fees say, the fee of a config that names none is the one of the priority, a
 * wallet that only reads refuses to relay what it would build, and a relay that fails names the
 * transactions of it that reached the server, which [MoneroWalletLight.relayedHashes] reads. It needs
 * the monero-lws of src/test/integration/docker-compose.yml and the regtest node behind it, whose
 * chain the other tests of the suite mine onto as well, so every wallet is of a random seed of its
 * own and the blocks that the test does not mean to pay anyone mine to an address that no account of
 * the server watches. It skips when no server answers, like the tests of
 * MoneroWalletLightIntegrationTest, which this class runs after. MoneroWalletSendIntegrationTest
 * walks the same path with the wallets of the python config; this class complements it with the relay
 * lifecycle of the metadata, the exact balance arithmetic of a wallet with a single output, and the
 * report of a submit that fails partway.
 */
@Order(11)
@Tag("integration")
class MoneroWalletLightSendIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }
    private val daemon: MoneroDaemonRpc by lazy { MoneroDaemonRpc(RegtestSupport.daemonConnection()) }

    // the amounts to send, in atomic units: five and two monero, and one monero per destination of
    // the split send, whose seventeen destinations are less money than the two coinbases of its funder
    private val firstAmount = BigInteger.valueOf(5_000_000_000_000L)
    private val secondAmount = BigInteger.valueOf(2_000_000_000_000L)
    private val splitAmount = BigInteger.valueOf(1_000_000_000_000L)

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

    // the configuration of a transfer, which names no fee, whose default is none: the priority of the
    // transaction decides the fee, and the c abi reads the one of the priority whatever the config says
    private fun txConfig(address: String, amount: BigInteger): MoneroTxConfig {
        val config = MoneroTxConfig()
        config.accountIndex = 0
        config.addDestination(address, amount)
        return config
    }

    // a transaction that the wallet creates without relaying carries the metadata that a relay takes,
    // the hash that the relay answers, and a fee that the priority decided; relaying its metadata and
    // relaying the transaction itself answer the same hash, the balances of the sender and of the
    // receiver move by the exact integers of the amounts and the fees, and the metadata of a
    // transaction that the pool already holds the node answers with not relayed, which the relay
    // reports as a failure that names no transaction
    @Test
    fun testRelayFromMetadataAndFromTheTransaction() {
        val receiverConfig = MoneroWalletConfig()
        receiverConfig.networkType = MoneroNetworkType.MAINNET
        val receiver = MoneroWalletLight.createWallet(receiverConfig, connection)
        val sender = fundedWallet(1)
        try {
            val receiverAddress = receiver.getPrimaryAddress()

            // the sender has a single matured coinbase, which makes the arithmetic exact: the
            // transaction that spends it moves the balance by its amount and its fee, and leaves no
            // unlocked money until a block holds its change
            val before = sender.getBalance()
            val unlockedBefore = sender.getUnlockedBalance()
            assertEquals(before, unlockedBefore, "the coinbase of the sender matured")

            val first = sender.createTxs(txConfig(receiverAddress, firstAmount)).single()
            assertEquals(false, first.isRelayed, "a transaction of a config that does not relay is not relayed")
            assertEquals(false, first.inTxPool, "a transaction that is not relayed is not in the pool")
            assertEquals(false, first.isFailed, "a transaction that was never submitted did not fail")
            val firstFee = assertNotNull(first.fee, "the fee of the priority is the fee of the transaction")
            assertTrue(firstFee > BigInteger.ZERO, "the fee of the transaction is money")
            val firstHash = assertNotNull(first.hash)
            assertTrue(firstHash.matches(Regex("[0-9a-f]{64}")), "the hash of the transaction is a hash")
            assertNotNull(first.metadata, "a transaction that is not relayed carries the metadata that a relay takes")

            // the relay of the metadata answers the hash that the creation answered
            assertEquals(firstHash, sender.relayTx(first.metadata!!), "the relay of the metadata answers the hash of the transaction")

            // the balance of the sender is the one before, less the amount and the fee: the change of
            // the transaction is pending money of the balance, and the coinbase that it spent, which
            // was the only unlocked money, leaves the unlocked balance at zero
            assertEquals(before.subtract(firstAmount).subtract(firstFee), sender.getBalance(), "the balance drops by the amount and the fee")
            assertEquals(BigInteger.ZERO, sender.getUnlockedBalance(), "the spent coinbase leaves no unlocked money until the change is on the chain")

            // the metadata of a transaction that the pool already holds relays no transaction: the
            // node takes the duplicate but answers that it did not relay it, which the wallet reports
            // as a failed relay whose report names none, since nothing of that relay reached the
            // server. the report of the transactions that a later failure leaves on the network is
            // the one of the test below
            val refused = assertFailsWith<MoneroError> { sender.relayTx(first.metadata!!) }
            assertTrue(refused.message!!.contains("failed to relay"), refused.message)
            assertEquals(emptyList(), MoneroWalletLight.relayedHashes(refused), "a relay that sent nothing names no transaction")

            // a block holds the transaction and ages its change past the ten blocks that a regtest
            // chain locks a transaction output for, which funds the second transaction. the shared
            // chain reorganizes under the tests now and then, which drops the block that a round
            // mined with the transaction in it, so the round is tried again until the chain holds it
            for (round in 1..10) {
                mine(12, unwatchedAddress())
                waitScanned(sender.getPrimaryAddress(), sender.getPrivateViewKey())
                sender.sync()
                if (sender.getBalance() == before.subtract(firstAmount).subtract(firstFee)) break
            }
            assertEquals(before.subtract(firstAmount).subtract(firstFee), sender.getBalance(), "the change is on the chain")
            assertEquals(before.subtract(firstAmount).subtract(firstFee), sender.getUnlockedBalance(), "the change matured")

            // the second transaction relays from the transaction itself, through its metadata: the
            // hash of that relay is the hash that the creation answered
            val second = sender.createTxs(txConfig(receiverAddress, secondAmount)).single()
            val secondFee = assertNotNull(second.fee)
            val secondHash = assertNotNull(second.hash)
            assertNotNull(second.metadata)
            assertEquals(secondHash, sender.relayTx(second), "the relay of the transaction answers the hash of the transaction")
            assertEquals(before.subtract(firstAmount).subtract(firstFee).subtract(secondAmount).subtract(secondFee), sender.getBalance(), "the balance drops again by the amount and the fee")

            // the receiver reads exactly what was sent, which a block holds, which the pool of the
            // node gives to the next block that it builds: the daemon of the regtest chain takes a
            // moment to make a relayed transaction public to its blocks, and a reorganization that
            // drops the block drops the second transaction with it, which the pool gives back to the
            // next block, so the round waits for the pool to hold the transaction, mines a block,
            // and reads the wallets again until the chain holds it
            for (round in 1..20) {
                if (daemon.getTxPoolHashes().contains(secondHash)) mine(1, unwatchedAddress())
                waitScanned(receiverAddress, receiver.getPrivateViewKey())
                waitScanned(sender.getPrimaryAddress(), sender.getPrivateViewKey())
                receiver.sync()
                sender.sync()
                if (receiver.getBalance() == firstAmount.add(secondAmount)) break
                Thread.sleep(1000)
            }
            assertEquals(firstAmount.add(secondAmount), receiver.getBalance(), "the receiver reads the two amounts")
            assertEquals(firstAmount, receiver.getTxs().single { tx -> tx.hash == firstHash }.getIncomingAmount(), "the first transaction paid its amount")
        } finally {
            sender.close()
            receiver.close()
        }
    }

    // a wallet that only reads has no spend key, so what it would submit is garbage that the server
    // refuses: it refuses the relay before it builds anything, which the configuration of the wallet
    // makes view-only with the address and the view key alone
    @Test
    fun testAViewOnlyWalletRefusesToRelay() {
        val sender = fundedWallet(1)
        val viewOnlyConfig = MoneroWalletConfig()
        viewOnlyConfig.networkType = MoneroNetworkType.MAINNET
        viewOnlyConfig.primaryAddress = sender.getPrimaryAddress()
        viewOnlyConfig.privateViewKey = sender.getPrivateViewKey()
        val viewOnly = if (MoneroWalletLight.walletExists(viewOnlyConfig, connection)) MoneroWalletLight.openWallet(viewOnlyConfig, connection)
        else MoneroWalletLight.createWallet(viewOnlyConfig, connection)
        try {
            assertEquals(true, viewOnly.isViewOnly(), "the wallet of the address and the view key only reads")

            val relay = txConfig(unwatchedAddress(), firstAmount)
            relay.relay = true
            assertEquals("Cannot relay from a view-only wallet", assertFailsWith<MoneroError> { viewOnly.createTxs(relay) }.message, "the wallet that only reads refuses to relay what it builds")
        } finally {
            viewOnly.close()
            sender.close()
        }
    }

    // a submit of a signed set that the server refuses partway names the transactions that reached
    // the server before the failure: the view-only wallet of a funded account builds an unsigned set
    // of two transactions from seventeen destinations, the wallet with the seed signs it, and one of
    // the two coinbases that the set spends is spent again before the submit, which makes the node
    // refuse the transaction that spends it as a double spend. the error of the submit reports the
    // other one as already relayed, which relayedHashes reads, and the chain holds exactly that one
    @Test
    fun testAFailedSubmitReportsTheTransactionsThatReachedTheServer() {
        val funder = fundedWallet(2)
        val funderAddress = funder.getPrimaryAddress()
        val viewOnlyConfig = MoneroWalletConfig()
        viewOnlyConfig.networkType = MoneroNetworkType.MAINNET
        viewOnlyConfig.primaryAddress = funderAddress
        viewOnlyConfig.privateViewKey = funder.getPrivateViewKey()
        val viewOnly = if (MoneroWalletLight.walletExists(viewOnlyConfig, connection)) MoneroWalletLight.openWallet(viewOnlyConfig, connection)
        else MoneroWalletLight.createWallet(viewOnlyConfig, connection)
        try {
            // the wallet that only reads sees the coinbases and the key images of the account before
            // it builds, the way the python suite's wallet fixture works
            viewOnly.sync()
            val exported = funder.exportKeyImages(true)
            viewOnly.importKeyImages(exported.keyImages!!, exported.offset ?: 0)

            // seventeen destinations of one monero are more than the fifteen that a single
            // transaction of the ring construction holds, so the build makes a set of two
            // transactions, one per chunk of destinations, and each chunk spends one of the coinbases
            val config = MoneroTxConfig()
            config.accountIndex = 0
            config.canSplit = true
            val dest = unwatchedAddress()
            repeat(17) { config.addDestination(dest, splitAmount) }
            val unsignedTxs = viewOnly.createTxs(config)
            assertEquals(2, unsignedTxs.size, "the seventeen destinations split into two transactions")
            val unsignedHex = assertNotNull(unsignedTxs[0].txSet?.unsignedTxHex, "the wallet that only reads builds its transactions unsigned")

            val signedTxs = funder.signTxs(unsignedHex)
            assertEquals(2, signedTxs.size)
            val signedHashes = signedTxs.map { tx -> checkNotNull(tx.hash) }
            val signedHex = assertNotNull(signedTxs[0].txSet?.signedTxHex)

            // one of the two coinbases that the set spends is spent again, which the sweep of its key
            // image relays before the submit: whichever transaction of the set spends it, the node
            // refuses that one and takes the other
            val coinbases = funder.getTxs(monero.wallet.model.MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                .filter { output -> output.isSpent != true }
                .mapNotNull { output -> output.keyImage?.hex }
                .toList()
            assertEquals(2, coinbases.size, "the funder has the two coinbases that the set spends")
            val preSpent = funder.sweepOutput(MoneroTxConfig().apply { keyImage = coinbases[0]; address = unwatchedAddress(); relay = true })
            assertEquals(true, preSpent.isRelayed, "the spend that the submit races relayed")

            // the submit takes one of the two transactions and refuses the other as a double spend,
            // and its error names the one that reached the server
            val error = assertFailsWith<MoneroError> { funder.submitTxs(signedHex) }
            val relayed = MoneroWalletLight.relayedHashes(error)
            assertEquals(1, relayed.size, "the report names the one transaction that the server took: ${error.message}")
            assertTrue(relayed[0] in signedHashes, "the reported hash is one of the signed transactions")
            assertTrue(error.message!!.contains("already relayed"), error.message)

            // the chain holds exactly the transaction that the report named, and not the refused one
            mine(1, unwatchedAddress())
            waitScanned(funderAddress, funder.getPrivateViewKey())
            funder.sync()
            val onChain = funder.getTxs().map { tx -> checkNotNull(tx.hash) }
            assertEquals(relayed, signedHashes.filter { hash -> hash in onChain }, "the chain holds exactly the transaction that the report named")
        } finally {
            viewOnly.close()
            funder.close()
        }
    }
}
