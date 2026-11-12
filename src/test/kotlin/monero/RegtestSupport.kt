package monero

import java.net.URI
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.common.MoneroRpcConnection
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * What the integration tests share: the regtest nodes and the wallet RPC servers that integration-tests.yml starts with
 * src/test/docker/docker-compose.yml, which are the ones of monero-python, and the mining that moves the chain. The tests use the second
 * node and the three wallet RPC servers: a server has one open wallet, so the first one holds the wallet that the tests share, and the
 * next two the wallets that a test makes. Each of the settings is the one of that file, and an environment variable changes it:
 * MONERO_C_TEST_DAEMON_URI, MONERO_C_TEST_DAEMON_USERNAME, MONERO_C_TEST_DAEMON_PASSWORD, MONERO_C_TEST_WALLET_RPC_URI (of the first
 * server: the next ones are on the ports that follow), MONERO_C_TEST_WALLET_RPC_USERNAME and MONERO_C_TEST_WALLET_RPC_PASSWORD.
 */
object RegtestSupport {

    val daemonUri: String = System.getenv("MONERO_C_TEST_DAEMON_URI") ?: "http://127.0.0.1:18081"
    val daemonUsername: String = System.getenv("MONERO_C_TEST_DAEMON_USERNAME") ?: "rpc_daemon_user"
    val daemonPassword: String = System.getenv("MONERO_C_TEST_DAEMON_PASSWORD") ?: "abc123"

    val walletRpcUri: String = System.getenv("MONERO_C_TEST_WALLET_RPC_URI") ?: "http://127.0.0.1:18082"
    val walletRpcUsername: String = System.getenv("MONERO_C_TEST_WALLET_RPC_USERNAME") ?: "rpc_user"
    val walletRpcPassword: String = System.getenv("MONERO_C_TEST_WALLET_RPC_PASSWORD") ?: "abc123"

    // the primary address of the test wallet of monero-python's config.ini, which the mining calls pay by default
    const val MINING_ADDRESS = "48W9YHwPzRz9aPTeXCA6kmSpW6HsvmWx578jj3of2gT3JwZzwTf33amESBoNDkL6SVK34Q2HTKqgYbGyE1hBws3wCrcBDR2"

    private const val CHECK_TIMEOUT_MS = 2000L

    /**
     * A connection to the node, with its login. The caller closes it.
     */
    fun daemonConnection() = MoneroRpcConnection(daemonUri, daemonUsername, daemonPassword)

    /**
     * The URI of a wallet RPC server. The first one is walletRpcUri, and the next ones are on the ports that follow, as in the compose.
     *
     * @param server is the index of the server, 0 for the first
     */
    fun walletRpcServerUri(server: Int): String {
        if (server == 0) return walletRpcUri
        val uri = URI(walletRpcUri)
        require(uri.port >= 0) { "The URI of the wallet RPC server has no port, so the next servers are not on the port that follows: $walletRpcUri" }
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port + server, uri.path, uri.query, uri.fragment).toString()
    }

    /**
     * A connection to a wallet RPC server, with its login. The caller closes it.
     *
     * @param server is the index of the server, 0 for the first
     */
    fun walletRpcConnection(server: Int = 0) = MoneroRpcConnection(walletRpcServerUri(server), walletRpcUsername, walletRpcPassword)

    /**
     * Skip the test if monero-c can not be loaded, or no node answers at the URI.
     *
     * @throws MoneroError if the node answers, and does not accept the login
     */
    fun assumeAvailable() {
        NativeTestSupport.assumeAvailable()
        assumeTrue(isUp(daemonConnection()), "no monerod at $daemonUri: start the compose of src/test/docker, or set MONERO_C_TEST_DAEMON_URI")
        startChain()
    }

    /**
     * Skip the test if monero-c can not be loaded, or no wallet RPC server answers at its URI.
     *
     * @param server is the index of the server, 0 for the first
     * @throws MoneroError if the server answers, and does not accept the login
     */
    fun assumeWalletRpcAvailable(server: Int = 0) {
        NativeTestSupport.assumeAvailable()
        assumeTrue(isUp(walletRpcConnection(server)), "no monero-wallet-rpc at ${walletRpcServerUri(server)}: start the compose of src/test/docker, or set MONERO_C_TEST_WALLET_RPC_URI")
    }

    // a server that answers and refuses the login is a mistake of the setup, and a skipped test would hide it
    private fun isUp(connection: MoneroRpcConnection): Boolean {
        connection.use {
            it.checkConnection(CHECK_TIMEOUT_MS)
            if (it.isOnline == true && it.isAuthenticated == false) {
                throw MoneroError("The server at ${it.uri} does not accept the login of the tests: set the user name and the password of the compose of src/test/docker in the environment variables")
            }
            return it.isConnected() == true
        }
    }

    /**
     * Mine a block if the chain is only its genesis block. A wallet that is created on such a chain keeps the height
     * that it found, which is the one that it has scanned, so a sync after the blocks that the test mines for it
     * fetches nothing.
     */
    private fun startChain() {
        // a node that goes away after the check of assumeAvailable skips the test, it does not fail it
        val json = try {
            daemonConnection().use { it.sendPathRequest("get_height", timeoutMs = CHECK_TIMEOUT_MS) }
        } catch (e: MoneroError) {
            abort("the node at $daemonUri went away: $e")
        }
        val height = MoneroJson.mapper.readTree(json).path("height").asLong(-1)
        if (height in 0..1) mineBlocks(1)
    }

    /**
     * Mine blocks on the regtest chain.
     *
     * @param numBlocks is the number of blocks to mine
     * @param address is the address that the blocks pay
     * @throws MoneroError if the daemon does not mine them
     */
    fun mineBlocks(numBlocks: Int, address: String = MINING_ADDRESS) {
        // the daemon mines a large number of blocks slower than one call can wait for, so the blocks are mined in
        // batches of 25 and a slow host stays inside the timeout of a call
        var mined = 0
        while (mined < numBlocks) {
            val batch = minOf(25, numBlocks - mined)
            val params = mapOf("amount_of_blocks" to batch, "wallet_address" to address, "starting_nonce" to 0)
            val json = daemonConnection().use { it.sendJsonRequest("generateblocks", params, 180_000) }
            val status = MoneroJson.mapper.readTree(json).path("status").asText()
            if (status != "OK") throw MoneroError("generateblocks did not mine: $json")
            mined += batch
        }
    }
}
