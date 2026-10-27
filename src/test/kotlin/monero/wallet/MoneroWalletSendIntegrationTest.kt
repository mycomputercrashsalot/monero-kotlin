package monero.wallet

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.min
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import monero.NativeTestSupport
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
 * behind it, and mines onto whatever chain it finds, so it runs after MoneroWalletLightIntegrationTest, which the
 * class-name order of junit-platform.properties makes sure of. The blocks it mines give the wallet its decoys: every
 * output of a regtest chain is a coinbase, which spends only sixty blocks after it was mined, so the test mines
 * enough of them for sixteen to be spendable in every ring. Set the environment variable
 * MONERO_KOTLIN_TEST_LWS_URI to the URI of the server when it is not the one of that file; the node it mines on is
 * the one of MONERO_C_TEST_DAEMON_URI. The test skips when no server answers, like the unit tests skip without
 * monero-c, which this test also needs.
 */
@Order(2)
@Tag("integration")
class MoneroWalletSendIntegrationTest {

    private val lwsUri = System.getenv("MONERO_KOTLIN_TEST_LWS_URI") ?: "http://127.0.0.1:8443"
    private val daemonUri = System.getenv("MONERO_C_TEST_DAEMON_URI") ?: "http://127.0.0.1:18081"

    // the test wallet of monero-python's config.ini, whose seed carries its spend key
    private val seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"

    // the mining wallet of monero-python's config.ini, which receives as a second account
    private val receiverSeed = "lush waffle neither siblings when sanity pencil grunt ghost fall rabbits adapt items ornament sequence nitrogen orders fall bagpipe width hope fainted hexagon imagine orders"

    // the amounts to send, in atomic units: five and three monero, one per relay path
    private val firstAmount = BigInteger.valueOf(5000000000000L)
    private val secondAmount = BigInteger.valueOf(3000000000000L)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(isServerUp, "no monero-lws at $lwsUri: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    @AfterTest
    fun closeClient() {
        http.close()
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
            mineBlocks(5, senderAddress)
            mineBlocks(130, minerAddress)
            waitScanned(senderAddress, sender.getPrivateViewKey())
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

            // a block holds the relayed transactions, and the receiver reads exactly what was sent
            mineBlocks(1, minerAddress)
            waitScanned(receiverAddress, receiver.getPrivateViewKey())
            receiver.sync()
            assertEquals(receiverBefore.add(firstAmount).add(secondAmount), receiver.getBalance())
            val received = receiver.getTxs().single { tx -> tx.hash == relayedHash }
            assertEquals(true, received.isIncoming)
            assertEquals(firstAmount, received.incomingTransfers!!.single().amount)

            // the sender's balance is the one before, less the amounts and the fees
            sender.sync()
            assertEquals(before.subtract(firstAmount).subtract(firstFee).subtract(secondAmount).subtract(secondFee), sender.getBalance())
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

    // the rest server takes a while to come up after the compose starts it, so the probe retries for a minute
    // before it skips. a server that answers but is not an lws fails the test below, it does not skip it
    private val isServerUp: Boolean by lazy {
        (1..60).any {
            try {
                post(lwsUri, "/get_version", "{}")
                true
            } catch (e: IOException) {
                Thread.sleep(1000)
                false
            } catch (e: MoneroError) {
                // the rest server answers before monero-lws is ready, so a request it refuses is the server not being up yet
                Thread.sleep(1000)
                false
            }
        }
    }

    private fun txConfig(accountIdx: Int?, address: String, amount: BigInteger): MoneroTxConfig {
        val config = MoneroTxConfig()
        config.accountIndex = accountIdx
        config.destinations = listOf(MoneroDestination(address, amount))
        config.relay = false
        return config
    }

    /**
     * Mine blocks to an address with the daemon behind the server. The daemon mines a large number of blocks
     * slower than a call can wait for it, so the blocks are mined in batches.
     *
     * @param numBlocks is the number of blocks to mine
     * @param address is the address that the coinbases pay
     * @throws MoneroError if the daemon does not mine them
     */
    private fun mineBlocks(numBlocks: Int, address: String) {
        var remaining = numBlocks
        while (remaining > 0) {
            val batch = min(25, remaining)
            val body = """{"jsonrpc": "2.0", "id": "0", "method": "generateblocks",""" +
                """ "params": {"amount_of_blocks": $batch, "wallet_address": "$address", "starting_nonce": 0}}"""
            val response = post(daemonUri, "/json_rpc", body, Duration.ofSeconds(120))
            if (!response.contains("\"status\": \"OK\"")) throw MoneroError("generateblocks did not mine: $response")
            remaining -= batch
        }
    }

    /**
     * Wait for the scan of an account to reach the height of the chain as its server sees it: the server sees the
     * new blocks over zmq and scans them in the background. The height of the daemon counts one more than the
     * height that the server reports, so the two heights of the server are compared with each other.
     *
     * @param address is the address of the account
     * @param viewKey is the private view key of the account
     * @throws MoneroError if it does not reach the height in a minute
     */
    private fun waitScanned(address: String, viewKey: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            val info = post(lwsUri, "/get_address_info", """{"address":"$address","view_key":"$viewKey"}""")
            val scanned = Regex("\"scanned_block_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            val chain = Regex("\"blockchain_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            if (scanned != null && scanned == chain) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached the height of the chain, it is at $scanned of $chain: $info")
            Thread.sleep(500)
        }
    }

    /**
     * Post a body to a path of a server, and return what it answers.
     *
     * @param server is the base URI of the server
     * @param path is the path of the call, or /json_rpc of a monerod
     * @param body is the JSON of the request
     * @param timeout is how long the call may take
     * @return the JSON of the response
     * @throws MoneroError if the server does not answer 200
     */
    private fun post(server: String, path: String, body: String, timeout: Duration = Duration.ofSeconds(30)): String {
        val request = HttpRequest.newBuilder(URI.create(server + path))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw MoneroError("POST $path answered ${response.statusCode()}: ${response.body()}")
        return response.body()
    }
}
