import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.databind.ObjectMapper
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.MoneroWalletLight
import monero.wallet.model.MoneroWalletConfig

// The runtime smoke of the minified library: it opens a light wallet, mines the blocks of the
// regtest node that pay it, syncs it, and reads its balance, its transactions, its address, its
// seed, and its view key, which is every surface that minification can break: the jna binding of
// the library, the json of the models through their fields and their accessors, the generic
// signatures of the reads, and the enumerations that the mapper reflects over.
object SmokeMain {
    @JvmStatic fun main(args: Array<String>) {
        val uri = System.getenv("LWS_URI") ?: "http://127.0.0.1:8443"
        val daemonUri = System.getenv("MONERO_C_TEST_DAEMON_URI") ?: "http://127.0.0.1:18081"
        val connection = MoneroRpcConnection(uri)
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"
        val wallet = if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
        try {
            // a fresh chain pays the wallet nothing, and a chain that pays it nothing reads no
            // transaction: mine blocks to it so that the smoke reads money and its history
            mine(daemonUri, wallet.getPrimaryAddress(), 10)
            var result = wallet.sync()
            // the server scans the blocks of an account behind the mining, which a sync that
            // comes too early does not see: the wallet syncs until the server has them
            var tries = 0
            while (wallet.getBalance() == BigInteger.ZERO && tries++ < 10) {
                result = wallet.sync()
                if (wallet.getBalance() > BigInteger.ZERO) break
                Thread.sleep(2000)
            }
            val balance = wallet.getBalance()
            val txs = wallet.getTxs()
            val address = wallet.getPrimaryAddress()
            val words = wallet.getSeed()
            val viewKey = wallet.getPrivateViewKey()
            val heights = txs.mapNotNull { it.height }
            println("SMOKE-OK synced=" + result.numBlocksFetched + " balance=" + balance + " txs=" + txs.size + " firstTxHeight=" + (heights.maxOrNull() ?: -1))
            println("SMOKE-ADDRESS " + address.substring(0, 20) + "...")
            println("SMOKE-SEED-WORDS " + words.split(" ").size)
            check(balance > BigInteger.ZERO) { "balance read" }
            check(txs.isNotEmpty()) { "transactions read" }
            check(heights.isNotEmpty()) { "tx heights parsed from json" }
            check(address.startsWith("4") || address.startsWith("9")) { "address parsed" }
            check(words.split(" ").size == 25) { "seed roundtrip" }
            check(viewKey.length == 64) { "view key read" }
        } finally { wallet.close(false) }
    }

    // the generateblocks of the regtest node, which the support of the tests calls the same way
    private fun mine(daemonUri: String, address: String, numBlocks: Int) {
        val body = """{"jsonrpc":"2.0","id":"0","method":"generateblocks","params":{"amount_of_blocks":$numBlocks,"wallet_address":"$address","starting_nonce":0}}"""
        val request = HttpRequest.newBuilder(URI.create("$daemonUri/json_rpc"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        val status = ObjectMapper().readTree(response.body()).path("result").path("status").asText()
        check(status == "OK") { "generateblocks did not mine: ${response.body()}" }
    }
}
