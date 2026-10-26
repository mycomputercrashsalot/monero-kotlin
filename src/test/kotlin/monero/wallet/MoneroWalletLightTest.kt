package monero.wallet

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import monero.NativeTestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroWalletConfig

/**
 * The light wallet tests that need no light wallet server: the configuration errors that the C ABI checks before it
 * talks to one, the random wallet that a server-less connection creates disconnected, and the lifecycle of the
 * wallet. Every test calls the real dylib through MoneroWalletLight against a connection to a port that nothing
 * listens on, and skips when the dylib is not found. The tests that need the server are the integration tests of
 * MoneroWalletLightIntegrationTest, which mirror the rest of the python light suite.
 */
class MoneroWalletLightTest {

    // a connection to a port that nothing listens on: the wallet sees a server it is not connected to
    private val offlineConnection = MoneroRpcConnection("http://127.0.0.1:1")

    // the seed of the python keys suite, which is two words short of a mnemonic
    private val brokenSeed = "memoir desk algebra inbound innocent unplugs fully okay five inflamed giant factual ritual toyed topic snake unhappy guarded tweezers haunted inundate giant"

    @BeforeTest
    fun requireLibrary() {
        NativeTestSupport.assumeAvailable()
    }

    // ------------------------------- CREATE WALLET ----------------------------------

    @Test
    fun createWalletRandom_WithoutAServer_ReadsItsKeys() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            validateMnemonic(wallet.getSeed())
            validateAddress(wallet.getPrimaryAddress())
            validatePrivateKey(wallet.getPrivateViewKey())
            validatePrivateKey(wallet.getPrivateSpendKey())
            assertEquals(MoneroWalletLight.DEFAULT_LANGUAGE, wallet.getSeedLanguage())
            assertEquals(MoneroNetworkType.MAINNET, wallet.getNetworkType())
            assertEquals(false, wallet.isViewOnly())
        } finally {
            wallet.close()
        }
    }

    @Test
    fun createWallet_WithAServerInTheConfig_IsAnError() {
        val config = config()
        config.server = offlineConnection
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("Cannot provide server config for light wallet", error.message)
    }

    @Test
    fun createWallet_WithASeedAndKeys_IsAnError() {
        val config = config(brokenSeed)
        config.primaryAddress = "48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2"
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("Wallet may be initialized with a seed or keys but not both", error.message)
    }

    @Test
    fun createWallet_FromABrokenSeed_IsAnError() {
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config(brokenSeed), offlineConnection) }
        assertEquals("Invalid mnemonic", error.message)
    }

    @Test
    fun createWallet_WithASubaddressLookaheadAlone_IsAnError() {
        val config = config()
        config.subaddressLookahead = 5
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("No account lookahead provided with subaddress lookahead", error.message)
    }

    @Test
    fun createWallet_WithAnAccountLookaheadAlone_IsAnError() {
        val config = config()
        config.accountLookahead = 5
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("No subaddress lookahead provided with account lookahead", error.message)
    }

    @Test
    fun createWallet_WithAnUnknownLanguage_IsAnError() {
        val config = config()
        config.language = "english"
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("Unknown language: english", error.message)
    }

    @Test
    fun createWallet_WithoutANetworkType_IsAnError() {
        val config = MoneroWalletConfig()
        config.seed = brokenSeed
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.createWallet(config, offlineConnection) }
        assertEquals("Must provide wallet network type", error.message)
    }

    @Test
    fun walletExists_WithAnAddressButNoViewKey_IsAnError() {
        val config = config()
        config.primaryAddress = "48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2"
        val error = assertFailsWith<MoneroError> { MoneroWalletLight.walletExists(config, offlineConnection) }
        assertEquals("Must provide both primary address and private view key to check if wallet exists", error.message)
    }

    // ------------------------------- SYNC AND LIFECYCLE ----------------------------------

    @Test
    fun sync_WithoutAServer_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val error = assertFailsWith<MoneroError> { wallet.sync() }
            assertEquals("Wallet is not connected to daemon", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun closeWithASave_IsNotSupported() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        val error = assertFailsWith<MoneroError> { wallet.close(true) }
        assertTrue(error.message!!.contains("does not support saving"), error.message)
        assertFalse(wallet.isClosed())
        wallet.close()
    }

    @Test
    fun isClosed_ThroughTheLifecycleOfAWallet() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        assertFalse(wallet.isClosed())
        val address = wallet.getPrimaryAddress()

        wallet.close()
        assertTrue(wallet.isClosed())
        val closed = assertFailsWith<MoneroError> { wallet.getPrimaryAddress() }
        assertEquals("Wallet is closed", closed.message)
        val closedSync = assertFailsWith<MoneroError> { wallet.sync() }
        assertEquals("Wallet is closed", closedSync.message)

        wallet.close()
        assertTrue(wallet.isClosed())
        validateAddress(address)
    }

    // ------------------------------- PRIVATE ------------------------------------

    private fun config(seed: String? = null): MoneroWalletConfig {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = seed
        return config
    }

    // the shape checks of the python suite, which validates with MoneroUtils
    private fun validateMnemonic(seed: String) {
        assertEquals(25, seed.split(" ").size, seed)
    }

    private fun validatePrivateKey(key: String) {
        assertTrue(Regex("[0-9a-f]{64}").matches(key), key)
    }

    private fun validateAddress(address: String) {
        // a standard address of mainnet starts with 4, a subaddress with 8, and both are 95 characters
        assertEquals(95, address.length, address)
        assertTrue(address.startsWith("4") || address.startsWith("8"), address)
    }
}
