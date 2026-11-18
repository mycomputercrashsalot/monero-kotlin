package monero.wallet

import org.junit.jupiter.api.Tag
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import monero.wallet.model.MoneroDestination
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxWallet
import monero.wallet.model.MoneroWalletConfig

/**
 * The light wallet tests that need no light wallet server: the configuration errors that the C ABI checks before it
 * talks to one, the random wallet that a server-less connection creates disconnected, and the lifecycle of the
 * wallet. Every test calls the real dylib through MoneroWalletLight against a connection to a port that nothing
 * listens on, and skips when the dylib is not found. The tests that need the server are the integration tests of
 * MoneroWalletLightIntegrationTest, which mirror the rest of the python light suite.
 */
@Tag("unit")
class MoneroWalletLightTest {

    // a connection to a port that nothing listens on: the wallet sees a server it is not connected to
    private val offlineConnection = MoneroRpcConnection("http://127.0.0.1:1")

    // the seed of the python keys suite, which is two words short of a mnemonic
    private val brokenSeed = "memoir desk algebra inbound innocent unplugs fully okay five inflamed giant factual ritual toyed topic snake unhappy guarded tweezers haunted inundate giant"

    @BeforeTest
    fun requireLibrary() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
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
    fun getSeedLanguages_WithoutAServer_ListsTheLanguages() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val languages = wallet.getSeedLanguages()
            assertTrue(languages.contains(MoneroWalletLight.DEFAULT_LANGUAGE), "the default language is one of the list")
            assertTrue(languages.size > 1, "the list has more than the default language")
        } finally {
            wallet.close()
        }
    }

    @Test
    fun getUnrefinedSpendCount_OfAFreshWallet_IsZero() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            assertEquals(0L, wallet.getUnrefinedSpendCount())
        } finally {
            wallet.close()
        }
    }

    @Test
    fun getBalance_OfAFreshWallet_IsZero() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            assertEquals(BigInteger.ZERO, wallet.getBalance())
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance())
        } finally {
            wallet.close()
        }
    }

    @Test
    fun relayTx_WithATxThatHasNoMetadata_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val error = assertFailsWith<MoneroError> { wallet.relayTx(MoneroTxWallet()) }
            assertEquals("The transaction has no metadata to relay", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun close_WhileCallsAreInFlight_EveryCallEndsCleanly() {
        repeat(10) {
            val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
            val threads = 8
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val calls = (0 until threads - 1).map {
                    pool.submit {
                        start.await()
                        repeat(50) {
                            try {
                                wallet.getBalance()
                            } catch (e: MoneroError) {
                                assertEquals("Wallet is closed", e.message)
                            }
                        }
                    }
                }
                val closer = pool.submit {
                    start.await()
                    wallet.close()
                }
                start.countDown()
                for (call in calls) call.get(30, TimeUnit.SECONDS)
                closer.get(30, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
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


    @Test
    fun theChecksOfAClosedWallet_AreErrors() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        wallet.close()
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.getTxKey("a".repeat(64)) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.checkTxKey("a".repeat(64), "b".repeat(64), "48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2") }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.scanTxs(emptyList()) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.freezeOutput("c".repeat(64)) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.thawOutput("c".repeat(64)) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.isOutputFrozen("c".repeat(64)) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.sweepUnlocked(MoneroTxConfig()) }.message)
        assertEquals("Wallet is closed", assertFailsWith<MoneroError> { wallet.sweepOutput(MoneroTxConfig()) }.message)
    }

    @Test
    fun relayedHashes_ReadsTheTransactionsOfTheErrorOfARelay() {
        // the three formats that a relay, a submit and a sweep throw, which name the transactions that
        // the server took before one failed
        val relay = MoneroError("failed to relay 1 of 2 transactions; already relayed: " + "a".repeat(64) + ", " + "b".repeat(64) + "; the server refused one: not enough money")
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), MoneroWalletLight.relayedHashes(relay))
        val submit = MoneroError("the server did not relay 1 of 2 transactions; already relayed: " + "c".repeat(64) + "; not relayed: " + "d".repeat(64) + ": the daemon refused it")
        assertEquals(listOf("c".repeat(64)), MoneroWalletLight.relayedHashes(submit))
        val sweep = MoneroError("failed to sweep 1 of the remaining transactions; already relayed: " + "e".repeat(64) + "; need more money than found")
        assertEquals(listOf("e".repeat(64)), MoneroWalletLight.relayedHashes(sweep))
    }

    @Test
    fun relayedHashes_OfAnErrorThatNamesNone_IsEmpty() {
        assertEquals(emptyList<String>(), MoneroWalletLight.relayedHashes(MoneroError("failed to relay 2 of 2 transactions; the server refused both")))
        assertEquals(emptyList<String>(), MoneroWalletLight.relayedHashes(MoneroError("Wallet is closed")))
        // a failure text that carries the words of the marker names no transaction, and a hash that is
        // not one names none either
        assertEquals(emptyList<String>(), MoneroWalletLight.relayedHashes(MoneroError("failed to relay 1 of 2 transactions; already relayed: not a hash; the server refused one")))
        assertEquals(emptyList<String>(), MoneroWalletLight.relayedHashes(MoneroError("failed to relay 1 of 2 transactions; already relayed: , " + "f".repeat(63) + "; the server refused one")))
    }

    // ------------------------------- SEND ----------------------------------

    @Test
    fun createTxs_WithoutAServer_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.destinations = listOf(MoneroDestination("48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2", BigInteger.ONE))
            val error = assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }
            assertEquals("Wallet is not connected to daemon", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun relayTx_WithMetadataThatIsNotHex_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val error = assertFailsWith<MoneroError> { wallet.relayTx("this is not hex") }
            assertEquals("Failed to parse hex", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun createTxs_WithANegativeAmount_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.destinations = listOf(
                MoneroDestination("48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2", BigInteger.ONE),
                MoneroDestination("49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj", BigInteger.valueOf(-5))
            )
            val error = assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }
            assertEquals("The amount -5 of the config of the transaction is negative", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun createTxs_WithAnAmountAtOrAboveTwoToTheSixtyFour_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.destinations = listOf(
                MoneroDestination("48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2", BigInteger.ONE.shiftLeft(64))
            )
            val error = assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }
            assertEquals("The amount 18446744073709551616 of the config of the transaction is at or above 2^64", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun createTxs_WithADestinationOfTheAmountZero_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.destinations = listOf(
                MoneroDestination("48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2", BigInteger.ZERO)
            )
            val error = assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }
            assertEquals("The destination of the amount zero is not a transfer", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun createTxs_WithANegativeIndex_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = -1
            txConfig.destinations = listOf(
                MoneroDestination("48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2", BigInteger.ONE)
            )
            val error = assertFailsWith<MoneroError> { wallet.createTxs(txConfig) }
            assertEquals("The index -1 of the config of the transaction is negative", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun signTxs_WithHexThatIsNotAnUnsignedSet_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val error = assertFailsWith<MoneroError> { wallet.signTxs("this is not hex") }
            assertEquals("Failed to parse hex.", error.message)
        } finally {
            wallet.close()
        }
    }

    @Test
    fun submitTxs_WithHexThatIsNotASignedSet_IsAnError() {
        val wallet = MoneroWalletLight.createWallet(config(), offlineConnection)
        try {
            val error = assertFailsWith<MoneroError> { wallet.submitTxs("this is not hex") }
            assertEquals("Failed to parse hex.", error.message)
        } finally {
            wallet.close()
        }
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
        val closedCreate = assertFailsWith<MoneroError> { wallet.createTxs(MoneroTxConfig()) }
        assertEquals("Wallet is closed", closedCreate.message)
        val closedRelay = assertFailsWith<MoneroError> { wallet.relayTx("") }
        assertEquals("Wallet is closed", closedRelay.message)

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

    // the shape the offline suite can check: the full validation of MoneroUtils is a call of the native
    // library, which the offline suite does not load
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
