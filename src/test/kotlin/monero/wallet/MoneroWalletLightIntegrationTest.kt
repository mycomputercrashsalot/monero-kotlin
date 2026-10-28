package monero.wallet

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.IOException
import java.math.BigInteger
import java.time.Duration
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
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * The light wallet against a live monero-lws, the way the python light suite runs: the capture account is created
 * through MoneroWalletLight, five regtest blocks are mined to it the way the captures were taken, the wallet syncs
 * and reads the captured ground truth, and opening the wallet again reads the same. It needs the monero-lws of
 * src/test/integration/docker-compose.yml, whose lws service accepts both the account of a create and the import
 * that a create for a new address asks the server for (--auto-accept-creation and --auto-accept-import): without
 * the import flag, an admin has to accept the import with monero-lws-admin. Set the environment variable
 * MONERO_KOTLIN_TEST_LWS_URI to the URI of the server when it is not the one of that file; the node it mines on is
 * the one of MONERO_C_TEST_DAEMON_URI. The test skips when no server answers, like the unit tests skip without
 * monero-c, which this test also needs, and when the library has no light wallet surface.
 *
 * The test asserts that no account exists for the address yet, so it needs a fresh database, which a `down` of the
 * compose gives, and it must run before any test that mines to the account or registers it. The integration tests
 * run in class name order (junit-platform.properties), which puts this class first; a test that mines and sorts
 * before it breaks this one with the timeout of the scan wait.
 */
@Tag("integration")
class MoneroWalletLightIntegrationTest {

    private val lwsUri = System.getenv("MONERO_KOTLIN_TEST_LWS_URI") ?: "http://127.0.0.1:8443"
    private val daemonUri = System.getenv("MONERO_C_TEST_DAEMON_URI") ?: "http://127.0.0.1:18081"

    // the throwaway regtest account of the captures, whose ground truth the tests assert
    private val address = "49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj"
    private val viewKey = "6cb8586d26fc0b5526035f1e39566d6aee6ff246dfc55bb49c6c99ea2643380f"

    // the coinbase rewards of the first five heights of the emission curve, what five regtest blocks pay the account
    private val rewards = listOf(
        "35184338534400",
        "35184271425600",
        "35184204316927",
        "35184137208383",
        "35184070099967"
    ).map { BigInteger(it) }.fold(BigInteger.ZERO, BigInteger::add)

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
    fun testCreateMineSyncAndReopen() {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.primaryAddress = address
        config.privateViewKey = viewKey

        // a fresh database of the compose has no account for the address, and the create registers it
        assertEquals(false, MoneroWalletLight.walletExists(config, connection))
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            assertEquals(address, wallet.getPrimaryAddress())
            assertEquals(true, wallet.isViewOnly())
            assertEquals(MoneroNetworkType.MAINNET, wallet.getNetworkType())
            assertEquals(true, MoneroWalletLight.walletExists(config, connection))

            // the server has the wallet now, so creating it again is refused
            val exists = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, connection) }
            assertEquals("Wallet already exists", exists.message)

            // mine five blocks to the account the way the captures were taken, then wait for the server to scan them
            mineBlocks(5)
            waitScannedHeight(5)

            // the wallet syncs from its server and reads the captured ground truth
            val sync = wallet.sync()
            assertTrue((sync.numBlocksFetched ?: -1L) >= 0, "the sync result has a fetched count")
            assertNotNull(sync.receivedMoney, "the sync result says whether money was received")
            assertEquals(6L, wallet.getHeight())
            assertEquals(rewards, wallet.getBalance())
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance())
            assertEquals(0L, wallet.getUnrefinedSpendCount())

            // the five coinbase transactions of the blocks are in the wallet
            val txs = wallet.getTxs()
            assertEquals(5, txs.size)
            val heights = txs.mapNotNull { tx -> tx.height }.sorted()
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), heights)
            for (tx in txs) {
                assertEquals(true, tx.isMinerTx)
            }
        } finally {
            wallet.close()
        }

        // the closed wallet refuses its calls
        val closed = assertFailsWith<MoneroError> { wallet.getPrimaryAddress() }
        assertEquals("Wallet is closed", closed.message)

        // opening the wallet again reads the same wallet from the server
        val reopened = MoneroWalletLight.openWallet(config, connection)
        try {
            assertEquals(address, reopened.getPrimaryAddress())
            reopened.sync()
            assertEquals(rewards, reopened.getBalance())
        } finally {
            reopened.close()
        }
    }

    // ------------------------------- PRIVATE ------------------------------------

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

    /**
     * Mine blocks to the account with the daemon behind the server, the way the captures were taken.
     *
     * @param numBlocks is the number of blocks to mine
     * @throws MoneroError if the daemon does not mine them
     */
    private fun mineBlocks(numBlocks: Int) {
        val body = """{"jsonrpc": "2.0", "id": "0", "method": "generateblocks",""" +
            """ "params": {"amount_of_blocks": $numBlocks, "wallet_address": "$address", "starting_nonce": 0}}"""
        val response = post(daemonUri, "/json_rpc", body)
        if (!response.contains("\"status\": \"OK\"")) throw MoneroError("generateblocks did not mine: $response")
    }

    /**
     * Wait for the scan of the account to reach a height: the server sees the new blocks over zmq and scans them
     * in the background.
     *
     * @param height is the block height the account has to reach
     * @throws MoneroError if it does not reach it in a minute
     */
    private fun waitScannedHeight(height: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            val info = post(lwsUri, "/get_address_info", """{"address":"$address","view_key":"$viewKey"}""")
            val scanned = Regex("\"scanned_block_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            if (scanned == height) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached height $height, it is at $scanned: $info")
            Thread.sleep(500)
        }
    }

    /**
     * Post a body to a path of a server, and return what it answers.
     *
     * @param server is the base URI of the server
     * @param path is the path of the call, or /json_rpc of a monerod
     * @param body is the JSON of the request
     * @return the JSON of the response
     * @throws MoneroError if the server does not answer 200
     */
    private fun post(server: String, path: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(server + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw MoneroError("POST $path answered ${response.statusCode()}: ${response.body()}")
        return response.body()
    }
}
