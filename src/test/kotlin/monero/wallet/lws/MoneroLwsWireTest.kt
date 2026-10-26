package monero.wallet.lws

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import monero.ModelTestSupport.assertSameJson
import monero.ModelTestSupport.fixture
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.wallet.lws.wire.MoneroLwsAddressInfoResponse
import monero.wallet.lws.wire.MoneroLwsAddressRequest
import monero.wallet.lws.wire.MoneroLwsAddressTxsResponse
import monero.wallet.lws.wire.MoneroLwsLoginRequest
import monero.wallet.lws.wire.MoneroLwsLoginResponse
import monero.wallet.lws.wire.MoneroLwsVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * Lws integration tests, on the wire only: the calls are plain http with the request and response models of
 * [monero.wallet.lws.wire], through a server that no client of [MoneroLightWalletClient] talks to yet — what this
 * test reads is what an implementation of it has to answer one day. They need the monero-lws of
 * src/test/integration/docker-compose.yml, on the regtest node behind it, and they log in the account of the
 * captures in src/test/resources/fixtures/lws: the test reads the responses of the empty chain, mines five blocks
 * to the account the way the captures were taken, and reads the responses again. Set the environment variable
 * MONERO_KOTLIN_TEST_LWS_URI to the URI of the server when it is not the one of that file; the node it mines on is
 * the one of MONERO_C_TEST_DAEMON_URI. The test skips when no server answers, like the unit tests skip without
 * monero-c.
 */
@Tag("integration")
class MoneroLwsWireTest {

    private val lwsUri = System.getenv("MONERO_KOTLIN_TEST_LWS_URI") ?: "http://127.0.0.1:8443"
    private val daemonUri = System.getenv("MONERO_C_TEST_DAEMON_URI") ?: "http://127.0.0.1:18081"

    // the throwaway regtest account of the captures: a mainnet address with its view key, generated for them and
    // never used anywhere else
    private val address = "49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj"
    private val viewKey = "6cb8586d26fc0b5526035f1e39566d6aee6ff246dfc55bb49c6c99ea2643380f"

    // the coinbase rewards of the first five heights of the emission curve, what five regtest blocks pay the account
    private val rewards = listOf(
        "35184338534400",
        "35184271425600",
        "35184204316927",
        "35184137208383",
        "35184070099967"
    ).map { BigInteger(it) }

    // no client of the library is involved: the rest server is plain http, the bodies are the wire models
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @BeforeTest
    fun assumeServerUp() {
        assumeTrue(isServerUp, "no monero-lws at $lwsUri: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    @AfterTest
    fun closeClient() {
        http.close()
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
            }
        }
    }

    // Can read the captured responses of a live server: login, get_address_info and get_address_txs of the
    // account, before and after five blocks are mined to it
    @Test
    fun testLoginAndGetAddressInfoAndGetAddressTxs() {

        // the login that creates the account, and the one that finds it again: the server answers what the
        // capture says, and the model reads it
        val createdBody = login(true)
        assertSameJson(fixture("lws", "login"), createdBody)
        val created = MoneroJson.deserialize(createdBody, MoneroLwsLoginResponse::class.java)
        assertEquals(true, created.isNewAddress,
            "the account is in the database already: bring the stack up fresh, docker compose down and up again")
        assertEquals(false, created.generatedLocally)
        assertEquals(0L, created.lookahead!!.majorIndex)
        assertEquals(0L, created.lookahead!!.minorIndex)

        val existingBody = login(false)
        assertSameJson(fixture("lws", "login_existing"), existingBody)
        assertEquals(false, MoneroJson.deserialize(existingBody, MoneroLwsLoginResponse::class.java).isNewAddress)

        // the account has nothing: a fresh database is seeded with the checkpoints of mainnet, so every height
        // reads the last checkpoint, and spent_outputs is left out entirely
        val freshInfoBody = getAddress("/get_address_info")
        assertSameJson(fixture("lws", "address_info"), freshInfoBody)
        val freshInfo = MoneroJson.deserialize(freshInfoBody, MoneroLwsAddressInfoResponse::class.java)
        assertEquals(BigInteger.ZERO, freshInfo.lockedFunds)
        assertEquals(BigInteger.ZERO, freshInfo.totalReceived)
        assertEquals(BigInteger.ZERO, freshInfo.totalSent)
        assertEquals(3707000L, freshInfo.scannedHeight)
        assertEquals(3707000L, freshInfo.scannedBlockHeight)
        assertEquals(3707000L, freshInfo.startHeight)
        assertEquals(3707000L, freshInfo.blockchainHeight)
        assertNull(freshInfo.spentOutputs)

        val freshTxsBody = getAddress("/get_address_txs")
        assertSameJson(fixture("lws", "address_txs"), freshTxsBody)
        assertNull(MoneroJson.deserialize(freshTxsBody, MoneroLwsAddressTxsResponse::class.java).transactions)

        val versionBody = post(lwsUri, "/get_version", "{}")
        assertSameJson(fixture("lws", "version"), versionBody)
        val version = MoneroJson.deserialize(versionBody, MoneroLwsVersion::class.java)
        assertEquals("monero-lws", version.serverType)
        assertEquals("52cf3fe54b2ddf15351ed7b6d62880edc6c603e8", version.lastGitCommitHash)

        // mine five blocks to the account, then wait for the scan: the chain rolls the database back from the
        // checkpoints to itself, and the account scans from zero
        mineBlocks(5)
        waitScannedHeight(5)

        val minedInfoBody = getAddress("/get_address_info")
        assertSameJson(fixture("lws", "address_info_after_blocks"), minedInfoBody)
        val minedInfo = MoneroJson.deserialize(minedInfoBody, MoneroLwsAddressInfoResponse::class.java)
        assertEquals(BigInteger.valueOf(175921021585277), minedInfo.lockedFunds)
        assertEquals(BigInteger.valueOf(175921021585277), minedInfo.totalReceived)
        assertEquals(BigInteger.ZERO, minedInfo.totalSent)
        assertEquals(5L, minedInfo.scannedHeight)
        assertEquals(5L, minedInfo.scannedBlockHeight)
        assertEquals(0L, minedInfo.startHeight)
        assertEquals(5L, minedInfo.blockchainHeight)

        // the coinbases are locked for 60 blocks, so locked_funds is all of it. the hash and the timestamp of a
        // transaction depend on when it was mined: every field the capture froze is asserted, those two are only
        // checked to be there
        val minedTxs = MoneroJson.deserialize(getAddress("/get_address_txs"), MoneroLwsAddressTxsResponse::class.java)
        assertEquals(BigInteger.valueOf(175921021585277), minedTxs.totalReceived)
        assertEquals(5L, minedTxs.scannedBlockHeight)
        assertEquals(0L, minedTxs.startHeight)
        assertEquals(5L, minedTxs.blockchainHeight)
        val transactions = minedTxs.transactions!!
        assertEquals(5, transactions.size)
        for ((i, tx) in transactions.withIndex()) {
            assertEquals(i.toLong(), tx.id)
            assertEquals(rewards[i], tx.totalReceived)
            assertEquals(BigInteger.ZERO, tx.totalSent)
            assertEquals(BigInteger.ZERO, tx.fee)
            assertEquals(BigInteger.valueOf(i + 61L), tx.unlockTime)
            assertEquals(i + 1L, tx.height)
            assertEquals(true, tx.isCoinbase)
            assertEquals(false, tx.isMempool)
            assertEquals(0L, tx.mixin)
            assertEquals(0L, tx.recipient!!.majorIndex)
            assertEquals(0L, tx.recipient!!.minorIndex)
            assertNull(tx.spentOutputs)
            assertNotNull(tx.hash)
            assertNotNull(tx.timestamp)
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

    private fun login(createAccount: Boolean): String {
        val request = MoneroLwsLoginRequest()
        request.address = address
        request.viewKey = viewKey
        request.createAccount = createAccount
        request.generatedLocally = false
        return post(lwsUri, "/login", MoneroJson.serialize(request))
    }

    // get_address_info and get_address_txs take the same body
    private fun getAddress(path: String): String {
        val request = MoneroLwsAddressRequest()
        request.address = address
        request.viewKey = viewKey
        return post(lwsUri, path, MoneroJson.serialize(request))
    }

    /**
     * Mine blocks to the account on the regtest node behind the server, the way the captures were taken.
     *
     * @param amountOfBlocks is how many blocks to mine
     * @throws MoneroError if the node does not mine them
     */
    private fun mineBlocks(amountOfBlocks: Int) {
        val body = """
            {"jsonrpc": "2.0", "id": "0", "method": "generateblocks",
             "params": {"amount_of_blocks": $amountOfBlocks, "wallet_address": "$address", "starting_nonce": 0}}
        """.trimIndent()
        val response = post(daemonUri, "/json_rpc", body)
        val result = MoneroJson.mapper.readTree(response).path("result")
        if (result.path("status").asText() != "OK") throw MoneroError("generateblocks did not mine: $response")
    }

    /**
     * Wait for the scan of the account to reach a height: the server sees the new blocks over zmq and scans them
     * in the background.
     *
     * @param height is the block height the account has to reach
     * @throws MoneroError if it does not reach it in a minute
     */
    private fun waitScannedHeight(height: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (true) {
            val info = MoneroJson.deserialize(getAddress("/get_address_info"), MoneroLwsAddressInfoResponse::class.java)
            if (info.scannedBlockHeight == height) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached height $height, it is at ${info.scannedBlockHeight}")
            Thread.sleep(500)
        }
    }
}
