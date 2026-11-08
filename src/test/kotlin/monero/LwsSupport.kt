package monero

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.IOException
import java.time.Duration
import kotlin.test.assertTrue
import monero.common.MoneroError

/**
 * The monero-lws of the integration tests: the probe that waits for it to answer, the post it takes and the wait for
 * the scan of an account, which the light wallet tests share. The server is the one of the compose of
 * src/test/integration, at the URI of the environment variable MONERO_KOTLIN_TEST_LWS_URI or at
 * http://127.0.0.1:8443.
 */
object LwsSupport {

    val lwsUri = System.getenv("MONERO_KOTLIN_TEST_LWS_URI") ?: "http://127.0.0.1:8443"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    // the rest server takes a while to come up after the compose starts it, so the probe retries for a minute
    // before it skips. a server that answers but is not an lws fails the probe, since the version of an lws names
    // its server type. the probe waits only while the server refuses the connection, or answers before monero-lws
    // is ready
    val isUp: Boolean by lazy {
        (1..60).any {
            try {
                val version = post("/get_version", "{}")
                assertTrue(version.contains("\"server_type\":\"monero-lws\""), "the server at $lwsUri is not a monero-lws: $version")
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
     * Post a body to a path of the server.
     *
     * @param path is the path of the request
     * @param body is the body of the request
     * @return the body of the answer
     * @throws MoneroError if the server answers anything but 200
     */
    fun post(path: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(lwsUri + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw MoneroError("POST $path answered ${response.statusCode()}: ${response.body()}")
        return response.body()
    }

    /**
     * Wait for the scan of an account to reach the height of the chain: the server sees the new blocks over zmq
     * and scans them in the background.
     *
     * @param address is the address of the account
     * @param viewKey is the private view key of the account
     * @throws MoneroError if the scan does not reach the height of the chain in a minute
     */
    fun waitScannedToChain(address: String, viewKey: String) {
        waitScanned(address, viewKey, null)
    }

    /**
     * Wait for the scan of an account to reach a height.
     *
     * @param address is the address of the account
     * @param viewKey is the private view key of the account
     * @param height is the block height the account has to reach, or null for the height of the chain
     * @throws MoneroError if the scan does not reach the height in a minute
     */
    fun waitScanned(address: String, viewKey: String, height: Long?) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            val info = post("/get_address_info", """{"address":"$address","view_key":"$viewKey"}""")
            val scanned = Regex("\"scanned_block_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            val target = height ?: Regex("\"blockchain_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            if (scanned != null && scanned == target) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached ${height ?: "the height of the chain"}: it is at $scanned ($info)")
            Thread.sleep(500)
        }
    }
}
