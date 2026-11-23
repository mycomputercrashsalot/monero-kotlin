package monero.wallet

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import monero.RegtestSupport
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroWalletConfig

/**
 * The wallets that the integration tests of the functions that every wallet has use, of one kind of wallet: the tests are written once
 * and run for the full wallet and for the wallet of a monero-wallet-rpc server, like the tests of monero-python that its wallet types
 * share (tests/test_monero_wallet_common.py). A kind says how to make a wallet that is connected to the regtest node of src/test/docker.
 */
interface TestWallets {

    /**
     * Skip the test if monero-c can not be loaded, or what the wallets need is not running. The node is started and its chain is mined
     * if it has not been.
     */
    fun assumeAvailable()

    /**
     * Make a wallet that is connected to the node, and has not synced. A wallet with no seed is a random one. The test closes it.
     *
     * @param seed is the seed to restore the wallet from, or null for a random wallet
     * @param restoreHeight is the height to restore it from, or null for the default
     */
    fun create(seed: String? = null, restoreHeight: Long? = null): MoneroWalletNative

    /**
     * Make a view-only wallet of an address, which is connected to the node and has not synced, so it can import outputs, which a wallet
     * that has synced can not. The test closes it.
     *
     * @param address is the primary address of the wallet
     * @param privateViewKey is the private view key of the wallet
     */
    fun createViewOnly(address: String, privateViewKey: String): MoneroWalletNative

    /**
     * Make a wallet of the keys of an address, which can sign transactions and is not connected to a node. The test closes it.
     *
     * @param address is the primary address of the wallet
     * @param privateViewKey is the private view key of the wallet
     * @param privateSpendKey is the private spend key of the wallet
     */
    fun createOffline(address: String, privateViewKey: String, privateSpendKey: String): MoneroWalletNative

    /**
     * Make a wallet that is connected to the node, and has not synced, for [TxHistoryWallet] to keep until the JVM ends, which no test
     * closes. The wallet of a server is not on a server that [create] gives to a test.
     */
    fun createShared(): MoneroWalletNative = create()
}

/**
 * Full wallets, which are kept in a temporary directory that is removed when the JVM ends.
 */
object FullTestWallets : TestWallets {

    private val directory: Path by lazy {
        Files.createTempDirectory("full-wallets-").also { dir -> Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() }) }
    }

    override fun assumeAvailable() {
        RegtestSupport.assumeAvailable()
    }

    override fun create(seed: String?, restoreHeight: Long?): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.path = directory.resolve("wallet-${System.nanoTime()}").toString()
        config.password = PASSWORD
        config.networkType = MoneroNetworkType.MAINNET
        config.server = RegtestSupport.daemonConnection()
        config.regtest = true
        config.seed = seed
        config.restoreHeight = restoreHeight
        return MoneroWalletFull.createWallet(config)
    }

    /**
     * Make a random wallet that is not connected to a node: its server is one that is not there, as the OFFLINE_SERVER_URI of monero-python.
     * The test closes it.
     */
    fun createUnconnected(): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.path = directory.resolve("wallet-${System.nanoTime()}").toString()
        config.password = PASSWORD
        config.networkType = MoneroNetworkType.MAINNET
        config.server = MoneroRpcConnection("offline_server_uri")
        config.regtest = true
        return MoneroWalletFull.createWallet(config)
    }

    override fun createViewOnly(address: String, privateViewKey: String): MoneroWalletNative =
        fromKeys(address, privateViewKey, null, RegtestSupport.daemonConnection())

    // it has no server, so it is offline
    override fun createOffline(address: String, privateViewKey: String, privateSpendKey: String): MoneroWalletNative =
        fromKeys(address, privateViewKey, privateSpendKey, null)

    /**
     * Make a wallet of the keys of an address, which can sign transactions, is connected to the node and has not synced, and scans the chain
     * from a height. The test closes it.
     *
     * @param address is the primary address of the wallet
     * @param privateViewKey is the private view key of the wallet
     * @param privateSpendKey is the private spend key of the wallet
     * @param restoreHeight is the height of the first block that the wallet scans
     */
    fun createFromKeys(address: String, privateViewKey: String, privateSpendKey: String, restoreHeight: Long): MoneroWalletNative =
        fromKeys(address, privateViewKey, privateSpendKey, RegtestSupport.daemonConnection(), restoreHeight)

    private fun fromKeys(
        address: String,
        privateViewKey: String,
        privateSpendKey: String?,
        server: MoneroRpcConnection?,
        restoreHeight: Long = 0
    ): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.path = directory.resolve("wallet-${System.nanoTime()}").toString()
        config.password = PASSWORD
        config.networkType = MoneroNetworkType.MAINNET
        config.regtest = true
        config.server = server
        config.primaryAddress = address
        config.privateViewKey = privateViewKey
        config.privateSpendKey = privateSpendKey
        config.restoreHeight = restoreHeight
        return MoneroWalletFull.createWallet(config)
    }

    const val PASSWORD = "supersecretpassword123"
}

/**
 * Wallets of the monero-wallet-rpc servers of src/test/docker, which a server keeps under a name that it has not another wallet with. A
 * server has one open wallet, as the docker wallet manager of monero-python knows, so the first server holds the wallet that the tests
 * share ([createShared]), and the wallets that a test makes go to the next two in turn: a test can have two wallets open, such as the
 * one that sends and the one that receives, and none of them takes the place of the shared one.
 */
object RpcTestWallets : TestWallets {

    // the address of a node that is not there, as the OFFLINE_SERVER_URI of monero-python
    private const val OFFLINE_SERVER_URI = "offline_server_uri"

    // the servers of the wallets of the tests, which are used in turn
    private val SERVERS = listOf(1, 2)
    private val turn = AtomicInteger()

    override fun assumeAvailable() {
        RegtestSupport.assumeAvailable()
        for (server in 0..SERVERS.last()) RegtestSupport.assumeWalletRpcAvailable(server)
    }

    override fun create(seed: String?, restoreHeight: Long?): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.seed = seed
        config.restoreHeight = restoreHeight
        return create(config, nextServer())
    }

    // The server refreshes its open wallet by itself every 20 seconds, and a wallet that has refreshed is a hot wallet, which does not import
    // outputs ("Hot wallets cannot import outputs"): a test that syncs or waits before it imports, or that is not quick enough, would fail
    // when the refresh comes in between. The wallet that imports stops the refresh at once, as the shared wallet does
    override fun createViewOnly(address: String, privateViewKey: String): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.primaryAddress = address
        config.privateViewKey = privateViewKey
        config.restoreHeight = 0
        return create(config, nextServer(), refreshes = false)
    }

    // the server is always connected to a node, so the wallet is given one that is not there, as monero-python does. It imports outputs too,
    // so the server does not refresh it, as it does not refresh the view-only wallet
    override fun createOffline(address: String, privateViewKey: String, privateSpendKey: String): MoneroWalletNative {
        val config = MoneroWalletConfig()
        config.primaryAddress = address
        config.privateViewKey = privateViewKey
        config.privateSpendKey = privateSpendKey
        config.restoreHeight = 0
        config.server = MoneroRpcConnection(OFFLINE_SERVER_URI)
        return create(config, nextServer(), refreshes = false)
    }

    // The server refreshes its open wallet by itself every 20 seconds, and the transaction that TxHistoryWallet leaves in the pool would be
    // in a block, and not in the pool, as soon as another test mines one: the shared wallet refreshes when it is asked to sync
    override fun createShared(): MoneroWalletNative {
        val wallet = create(MoneroWalletConfig(), 0)
        try {
            wallet.stopSyncing()
            return wallet
        } catch (e: Throwable) {
            runCatching { wallet.close() }
            throw e
        }
    }

    private fun nextServer(): Int = SERVERS[Math.floorMod(turn.getAndIncrement(), SERVERS.size)]

    // the refresh of the server, which stopSyncing() of a wallet of a server stops, is of the server and not of one wallet, so it stays stopped for
    // the next wallets of that server, which sync when they are asked to
    private fun create(config: MoneroWalletConfig, server: Int, refreshes: Boolean = true): MoneroWalletNative {
        // the server refreshes the wallet that opens on it by itself, from the daemon that it starts with and
        // before the daemon of the config applies, which a wallet that has not synced can not have imported
        // outputs into: the refresh of the server stops before the wallet opens, and the wallets that refresh
        // ask for it themselves
        if (!refreshes) RegtestSupport.walletRpcConnection(server).use { it.sendJsonRequest("auto_refresh", mapOf("enable" to false)) }
        val wallet = MoneroWalletRpc(RegtestSupport.walletRpcConnection(server))
        try {
            config.path = "kt-" + UUID.randomUUID().toString().replace("-", "")
            config.password = FullTestWallets.PASSWORD
            wallet.createWallet(config)
            return wallet
        } catch (e: Throwable) {
            runCatching { wallet.close() }
            throw e
        }
    }
}
