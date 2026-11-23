package monero.wallet

import java.math.BigInteger
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
import monero.wallet.model.MoneroDestination
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The connection of a light wallet to its light wallet server, which is the connection of the call that
 * makes the wallet and never changes: a wallet that a live server takes registers its address with it
 * and syncs to the chain, which is what connected means for a light wallet, since it has no call that
 * asks the server; a wallet that a dead server cannot take still reads its keys, and every call of it
 * that needs the server refuses with the error of a wallet that is not connected; and a server that
 * answers a forbidden status for an address that it does not have makes walletExists answer false,
 * which is not an error, while a view key that is not the one of the address makes the server answer an
 * error that the call carries. A light wallet cannot take a daemon connection, which the C ABI refuses
 * underneath it, and this surface carries no call for, so the class checks the shapes that it has. It
 * needs the monero-lws of src/test/integration/docker-compose.yml and the node that the compose runs,
 * and it skips when no server answers, like the tests of MoneroWalletLightIntegrationTest, which this
 * class runs after.
 */
@Order(4)
@Tag("integration")
class MoneroWalletLightConnectionIntegrationTest {

    // a connection to a port that nothing listens on, the one of the offline suite of MoneroWalletLightTest
    private val offlineConnection = MoneroRpcConnection("http://127.0.0.1:1")

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    // the seed of the shared test wallet, which restores the keys that a dead server cannot take
    private val seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // a wallet that a live server takes connects and syncs: the create registers the address with the
    // server, the sync answers its result, and the wallet scans the chain that was there when it was made
    @Test
    fun testAWalletOfALiveServer_ConnectsAndSyncs() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val height = daemon.getHeight()
        val wallet = MoneroWalletLight.createWallet(config(), connection)
        try {
            assertEquals(true, MoneroWalletLight.walletExists(config(wallet.getSeed()), connection), "the create registers the address with the server")
            val sync = wallet.sync()
            assertNotNull(sync.numBlocksFetched, "the sync of a connected wallet answers its result")
            syncToHeight(wallet, height)
            assertTrue(wallet.getHeight() >= height, "the wallet scans the chain that was there when it was made: ${wallet.getHeight()} of $height")
            assertEquals(BigInteger.ZERO, wallet.getBalance(), "a wallet that nothing was mined to has no money")
        } finally {
            wallet.close()
        }
    }

    // a wallet of a server that nothing answers keeps its keys, and the calls that need the server
    // refuse with the error of a wallet that is not connected; walletExists answers false, since a
    // server that does not answer names no wallet
    @Test
    fun testAWalletOfADeadServer_RefusesTheCallsThatNeedIt() {
        assertEquals(false, MoneroWalletLight.walletExists(config(seed), offlineConnection), "a server that does not answer has no wallet to find")
        val wallet = MoneroWalletLight.createWallet(config(seed), offlineConnection)
        try {
            assertEquals(false, wallet.isClosed(), "the wallet of a dead server is still a wallet")
            assertEquals("Wallet is not connected to daemon", assertFailsWith<MoneroError> { wallet.sync() }.message)
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.destinations = listOf(MoneroDestination(wallet.getPrimaryAddress(), BigInteger.ONE))
            assertEquals("Wallet is not connected to daemon", assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }.message)
        } finally {
            wallet.close()
        }
    }

    // a server that answers the forbidden of an address that it does not have makes walletExists answer
    // false and not an error, and a view key that is not the one of the address makes it answer an error
    // that the call carries to the caller
    @Test
    fun testAForbiddenAnswer_MakesWalletExistsAnswerFalse() {
        val unregistered = MoneroWalletLight.createWallet(config(), offlineConnection)
        val address: String
        val viewKey: String
        try {
            address = unregistered.getPrimaryAddress()
            viewKey = unregistered.getPrivateViewKey()
        } finally {
            unregistered.close()
        }
        assertEquals(false, MoneroWalletLight.walletExists(config(address = address, viewKey = viewKey), connection), "the server answers no wallet for an address that it does not have")
        val refused = assertFailsWith<MoneroError> { MoneroWalletLight.walletExists(config(address = address, viewKey = "22".repeat(32)), connection) }
        assertEquals("500 ", refused.message, "the server answers an error for a view key that is not the one of the address")
    }

    // the server scans behind the chain, which the shared node moves while other tests mine: the wallet
    // syncs until it has the height that the chain had
    private fun syncToHeight(wallet: MoneroWalletLight, target: Long) {
        var tries = 0
        while (wallet.getHeight() < target && tries++ < 60) {
            wallet.sync()
            Thread.sleep(2000)
        }
    }

    private fun config(seed: String? = null, address: String? = null, viewKey: String? = null): MoneroWalletConfig {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = seed
        config.primaryAddress = address
        config.privateViewKey = viewKey
        return config
    }
}
