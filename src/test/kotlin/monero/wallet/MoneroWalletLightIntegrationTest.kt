package monero.wallet

import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
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
 * run in the order of their annotations (junit-platform.properties), which puts this class first; a test that mines and sorts
 * before it breaks this one with the timeout of the scan wait.
 */
@Order(1)
@Tag("integration")
class MoneroWalletLightIntegrationTest {

    

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

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
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
            RegtestSupport.mineBlocks(5, address)
            LwsSupport.waitScanned(address, viewKey, 5)

            // the wallet syncs from its server and reads the captured ground truth
            val sync = wallet.sync()
            assertEquals(5L, sync.numBlocksFetched)
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

    @Test
    fun close_FromManyThreadsAtOnce_ClosesOnce() {
        repeat(5) {
            val config = MoneroWalletConfig()
            config.networkType = MoneroNetworkType.MAINNET

            // a wallet of its own at random, so the race does not touch the account of the capture, whose ground
            // truth the test above asserts
            val wallet = MoneroWalletLight.createWallet(config, connection)
            try {
                val threads = 8
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(threads)
                try {
                    val closes = (0 until threads).map {
                        pool.submit {
                            start.await()
                            wallet.close()
                        }
                    }
                    start.countDown()
                    for (close in closes) close.get(30, TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
                assertTrue(wallet.isClosed())
            } finally {
                if (!wallet.isClosed()) wallet.close()
            }
        }
    }


}
