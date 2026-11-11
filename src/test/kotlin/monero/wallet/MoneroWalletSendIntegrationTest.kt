package monero.wallet

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
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
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroDestination
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The send path of the light wallet against a live monero-lws: a wallet with a seed, which has its spend key,
 * creates transactions through the RingCT construction of monero-cpp, relays them, and both wallets read the exact
 * movement of the funds. It needs the monero-lws of src/test/integration/docker-compose.yml, on the regtest node
 * behind it, and mines onto whatever chain it finds, so it also stands on its own on a fresh chain, where it
 * mines the first block itself: a wallet that is created on the chain of only a genesis block keeps the height
 * that it found, which is the one that it has scanned, so a sync after the blocks that the test mines for it
 * fetches nothing. It runs second, after MoneroWalletLightIntegrationTest, which the order annotation of
 * junit-platform.properties makes sure of, because that test needs the fresh database of the server. The blocks it mines give the wallet its decoys: every
 * output of a regtest chain is a coinbase, which spends only sixty blocks after it was mined, so the test mines
 * enough of them for sixteen to be spendable in every ring. Set the environment variable
 * MONERO_KOTLIN_TEST_LWS_URI to the URI of the server when it is not the one of that file; the node it mines on is
 * the one of MONERO_C_TEST_DAEMON_URI. The test skips when no server answers, like the unit tests skip without
 * monero-c, which this test also needs.
 */
@Order(2)
@Tag("integration")
class MoneroWalletSendIntegrationTest {

    

    // the test wallet of monero-python's config.ini, whose seed carries its spend key
    private val seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"

    // the mining wallet of monero-python's config.ini, which receives as a second account
    private val receiverSeed = "lush waffle neither siblings when sanity pencil grunt ghost fall rabbits adapt items ornament sequence nitrogen orders fall bagpipe width hope fainted hexagon imagine orders"

    // the amounts to send, in atomic units: five, three and two monero, one per relay path
    private val firstAmount = BigInteger.valueOf(5000000000000L)
    private val secondAmount = BigInteger.valueOf(3000000000000L)
    private val thirdAmount = BigInteger.valueOf(2000000000000L)

        private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")

        // one block first, so the chain is past its genesis block whatever ran before: a wallet made on a
        // genesis-only chain keeps a scan height that a later sync fetches nothing after. The block pays an
        // address no wallet of the test watches, since the mining address of the support is the sender
        RegtestSupport.mineBlocks(1, unwatchedAddress())
    }


    @Test
    fun testSendFromASpendKeyWallet() {
        // the sender has the seed of the test wallet, which spends; the receiver has the seed of the mining wallet,
        // which receives. both are restored from a seed, so the server imports their accounts from the first block:
        // an account that a login makes, which the random wallets of monero-cpp register through, scans only from
        // the height of the login, and that scan does not find the outputs of a chain that regtest mining runs ahead
        // of. the miner address belongs to no account of the server, so the blocks that the sends need pay a
        // coinbase that nobody's balance reads
        val senderConfig = MoneroWalletConfig()
        senderConfig.networkType = MoneroNetworkType.MAINNET
        senderConfig.seed = seed
        // the sender is created when the server does not have the account, and opened when it does, the way the
        // python suite's wallet fixture works, so that the test also runs on a chain that was mined onto already
        val sender = if (MoneroWalletLight.walletExists(senderConfig, connection)) {
            MoneroWalletLight.openWallet(senderConfig, connection)
        } else {
            MoneroWalletLight.createWallet(senderConfig, connection)
        }
        val receiverConfig = MoneroWalletConfig()
        receiverConfig.networkType = MoneroNetworkType.MAINNET
        receiverConfig.seed = receiverSeed
        val receiver = if (MoneroWalletLight.walletExists(receiverConfig, connection)) {
            MoneroWalletLight.openWallet(receiverConfig, connection)
        } else {
            MoneroWalletLight.createWallet(receiverConfig, connection)
        }
        val minerAddress = unwatchedAddress()
        try {
            val senderAddress = sender.getPrimaryAddress()
            val receiverAddress = receiver.getPrimaryAddress()

            // the balance of the receiver before the transfers, which the test reads as a movement: on a fresh
            // server it is zero, and on a server that an earlier run mined onto it is what that run received
            receiver.sync()
            val receiverBefore = receiver.getBalance()

            // mine to the sender, then enough blocks for its coinbases to unlock, and for the decoys of its rings:
            // a coinbase of a regtest chain locks for 60 blocks, every output of the chain is a coinbase, and the
            // wallet needs sixteen unlocked ones for every ring it builds
            RegtestSupport.mineBlocks(5, senderAddress)
            RegtestSupport.mineBlocks(130, minerAddress)
            LwsSupport.waitScannedToChain(senderAddress, sender.getPrivateViewKey())
            sender.sync()
            val before = sender.getBalance()
            val unlockedBefore = sender.getUnlockedBalance()
            assertTrue(before > BigInteger.ZERO, "the sender has no funds")
            assertEquals(before, unlockedBefore, "the coinbases of the sender are still locked")

            // the configuration of a transfer is checked before the transfer is made
            val noAccount = assertFailsWith<MoneroError> { sender.createTxs(txConfig(null, receiverAddress, firstAmount)) }
            assertEquals("Must specify account index to send from", noAccount.message)
            val badAddress = assertFailsWith<MoneroError> { sender.createTxs(txConfig(0, "notanaddress", firstAmount)) }
            assertEquals("Invalid destination address", badAddress.message)
            val tooMuch = assertFailsWith<MoneroError> { sender.createTxs(txConfig(0, receiverAddress, before.add(BigInteger.ONE))) }
            assertTrue(tooMuch.message!!.contains("need more money than found"), tooMuch.message)

            // the first transaction relays from its metadata, the way the python wallet does
            val firstTxs = sender.createTxs(txConfig(0, receiverAddress, firstAmount))
            assertEquals(1, firstTxs.size)
            val first = firstTxs[0]
            assertEquals(false, first.isRelayed, "a transaction of a config that does not relay is not relayed")
            val firstFee = assertNotNull(first.fee)
            assertTrue(firstFee > BigInteger.ZERO)
            val firstHash = assertNotNull(first.hash)
            val firstMetadata = assertNotNull(first.metadata)
            val relayedHash = sender.relayTx(firstMetadata)
            assertEquals(firstHash, relayedHash)

            // the second transaction relays from the transaction itself
            val secondTxs = sender.createTxs(txConfig(0, receiverAddress, secondAmount))
            assertEquals(1, secondTxs.size)
            val second = secondTxs[0]
            val secondFee = assertNotNull(second.fee)
            val secondHash = assertNotNull(second.hash)
            assertEquals(secondHash, sender.relayTx(second))

            // a block holds the two relayed transactions before the wallet that only reads builds the third: the
            // server does not sync the pool, so its unspent outputs still name the ones that the two transactions
            // spend, and a transaction built on them is a double spend that the server refuses. The blocks after it
            // age the outputs of the chain, since a ring takes no decoy from a block that the chain has just added
            RegtestSupport.mineBlocks(1, minerAddress)
            RegtestSupport.mineBlocks(10, minerAddress)
            LwsSupport.waitScannedToChain(senderAddress, sender.getPrivateViewKey())

            // the third transaction goes the way of a wallet that only reads: a wallet with the view key alone
            // builds it unsigned, and the wallet with the seed signs it and submits it
            val viewOnlyConfig = MoneroWalletConfig()
            viewOnlyConfig.networkType = MoneroNetworkType.MAINNET
            viewOnlyConfig.primaryAddress = senderAddress
            viewOnlyConfig.privateViewKey = sender.getPrivateViewKey()
            val viewOnly = if (MoneroWalletLight.walletExists(viewOnlyConfig, connection)) {
                MoneroWalletLight.openWallet(viewOnlyConfig, connection)
            } else {
                MoneroWalletLight.createWallet(viewOnlyConfig, connection)
            }
            val thirdFee: BigInteger
            val thirdHash: String
            viewOnly.use {
                it.sync()
                val unsignedTxs = it.createTxs(txConfig(0, receiverAddress, thirdAmount))
                val unsigned = unsignedTxs.single()
                val unsignedTxHex = assertNotNull(unsigned.txSet?.unsignedTxHex, "a wallet that only reads builds its transactions unsigned")
                // the signature changes nothing of the transaction, so the fee of the signed one is the fee of the
                // construction, which the unsigned set carries
                thirdFee = assertNotNull(unsigned.fee)
                val signedTxs = sender.signTxs(unsignedTxHex)
                val signed = signedTxs.single()
                val signedTxHex = assertNotNull(signed.txSet?.signedTxHex)
                thirdHash = assertNotNull(signed.hash)
                assertEquals(listOf(thirdHash), sender.submitTxs(signedTxHex))
            }

            // a block holds the relayed transactions, and the receiver reads exactly what was sent
            RegtestSupport.mineBlocks(1, minerAddress)
            LwsSupport.waitScannedToChain(receiverAddress, receiver.getPrivateViewKey())
            receiver.sync()
            assertEquals(receiverBefore.add(firstAmount).add(secondAmount).add(thirdAmount), receiver.getBalance())
            val received = receiver.getTxs().single { tx -> tx.hash == relayedHash }
            assertEquals(true, received.isIncoming)
            assertEquals(firstAmount, received.incomingTransfers!!.single().amount)

            // the sender's balance is the one before, less the amounts and the fees
            sender.sync()
            assertEquals(before.subtract(firstAmount).subtract(firstFee).subtract(secondAmount).subtract(secondFee).subtract(thirdAmount).subtract(thirdFee), sender.getBalance())
            val sent = sender.getTxs().single { tx -> tx.hash == relayedHash }
            assertEquals(firstFee, sent.fee)
            assertEquals(false, sent.isIncoming)
        } finally {
            sender.close()
            receiver.close()
        }
    }

    // ------------------------------- PRIVATE ------------------------------------

    /**
     * An address of mainnet that no account of the server watches, which the blocks that the sends need pay their
     * coinbases to: nobody's balance moves with them. A keys-only wallet has the address without the server.
     *
     * @return an address that the server does not watch
     */
    private fun unwatchedAddress(): String {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        return MoneroWalletKeys.createWalletRandom(config).use { miner -> miner.getPrimaryAddress() }
    }


    private fun txConfig(accountIdx: Int?, address: String, amount: BigInteger): MoneroTxConfig {
        val config = MoneroTxConfig()
        config.accountIndex = accountIdx
        config.destinations = listOf(MoneroDestination(address, amount))
        config.relay = false
        return config
    }


}
