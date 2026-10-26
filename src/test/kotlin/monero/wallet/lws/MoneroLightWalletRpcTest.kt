package monero.wallet.lws

import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import monero.ModelTestSupport
import monero.ModelTestSupport.assertSameJson
import monero.common.MoneroError

// the light wallet client against the http server of the JDK, which answers with the captured responses of a real
// monero-lws (52cf3fe) on a regtest node. the captures are the contract: every call decodes them exactly, and the
// auth failures of the real server, which are bare 403s and 500s with empty bodies, become errors that tell them
// apart. what no capture has (an outgoing transaction, one in the mempool) is written by hand, in the same shape.
// every test runs without monero-c, a server or the network
class MoneroLightWalletRpcTest {

    // the throwaway regtest account of the captures, whose request bodies the manifest of the capture records
    private val address = "49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj"
    private val viewKey = "6cb8586d26fc0b5526035f1e39566d6aee6ff246dfc55bb49c6c99ea2643380f"

    private fun fixture(name: String) = ModelTestSupport.fixture("lws", name)

    // login

    @Test
    fun login_ReadsTheStateOfANewAccount() {
        TestServer().use { server ->
            server.answer("/login", 200, fixture("login"))
            val login = run { client(server).login() }
            assertEquals(true, login.isNewAddress)
            assertEquals(false, login.generatedLocally)
            assertEquals(0L, login.lookahead?.majorIndex)
            assertEquals(0L, login.lookahead?.minorIndex)
        }
    }

    @Test
    fun login_ReadsTheStateOfAnExistingAccount() {
        TestServer().use { server ->
            server.answer("/login", 200, fixture("login_existing"))
            val login = run { client(server).login() }
            assertEquals(false, login.isNewAddress)
            assertEquals(false, login.generatedLocally)
            assertEquals(0L, login.lookahead?.majorIndex)
            assertEquals(0L, login.lookahead?.minorIndex)
        }
    }

    @Test
    fun login_SendsTheBodyThatTheCapturesSent() {
        TestServer().use { server ->
            server.answer("/login", 200, fixture("login_existing"))
            run { client(server, createAccount = false).login() }
            assertSameJson(
                """{"address":"$address","view_key":"$viewKey","create_account":false,"generated_locally":false}""",
                server.bodies("/login").single()
            )
        }
    }

    @Test
    fun login_AsksForTheAccountThatItWants() {
        TestServer().use { server ->
            server.answer("/login", 200, fixture("login"))
            run { client(server, createAccount = true).login() }
            assertSameJson(
                """{"address":"$address","view_key":"$viewKey","create_account":true,"generated_locally":false}""",
                server.bodies("/login").single()
            )
        }
    }

    // get_address_info

    @Test
    fun getAddressInfo_ComputesTheBalancesOfTheCaptures() {
        TestServer().use { server ->
            server.answer("/get_address_info", 200, fixture("address_info_after_blocks"))
            val info = run { client(server).getAddressInfo() }
            assertEquals(BigInteger("175921021585277"), info.totalBalance)
            assertEquals(BigInteger.ZERO, info.unlockedBalance)
            assertEquals(5L, info.scannedHeight)
            assertEquals(0L, info.startHeight)
        }
    }

    @Test
    fun getAddressInfo_OfAnAccountWithNoFunds_IsZero() {
        TestServer().use { server ->
            server.answer("/get_address_info", 200, fixture("address_info"))
            val info = run { client(server).getAddressInfo() }
            assertEquals(BigInteger.ZERO, info.totalBalance)
            assertEquals(BigInteger.ZERO, info.unlockedBalance)
            assertEquals(3707000L, info.scannedHeight)
            assertEquals(3707000L, info.startHeight)
        }
    }

    @Test
    fun getAddressInfo_SendsTheBodyThatTheCapturesSent() {
        TestServer().use { server ->
            server.answer("/get_address_info", 200, fixture("address_info"))
            run { client(server).getAddressInfo() }
            assertSameJson("""{"address":"$address","view_key":"$viewKey"}""", server.bodies("/get_address_info").single())
        }
    }

    // get_address_txs

    @Test
    fun getAddressTxs_OfAnAccountWithNoTransactions_IsEmpty() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, fixture("address_txs"))
            val transactions = run { client(server).getAddressTxs() }
            assertTrue(transactions.isEmpty())
        }
    }

    @Test
    fun getAddressTxs_ReadsTheCaptures() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, fixture("address_txs_after_blocks"))
            val transactions = run { client(server).getAddressTxs() }
            assertEquals(5, transactions.size)
            val first = transactions[0]
            assertEquals("08276167cf4492161019e93fdb7b5029a29ef5022d4723a2ef185fbfc7aa201c", first.hash)
            assertEquals(true, first.isIncoming)
            assertEquals(BigInteger("35184338534400"), first.actualAmount)
            assertEquals(1L, first.height)
            assertEquals(Instant.parse("2026-10-22T11:29:31Z").toEpochMilli(), first.timestamp)
            val last = transactions[4]
            assertEquals("0d593de8745e304a54e91f0a9777dbdd2c55d74765771e5f2a02a1419f529819", last.hash)
            assertEquals(BigInteger("35184070099967"), last.actualAmount)
            assertEquals(5L, last.height)
        }
    }

    @Test
    fun getAddressTxs_SendsTheBodyThatTheCapturesSent() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, fixture("address_txs"))
            run { client(server).getAddressTxs() }
            assertSameJson("""{"address":"$address","view_key":"$viewKey"}""", server.bodies("/get_address_txs").single())
        }
    }

    @Test
    fun getAddressTxs_ComputesTheAmountOfAnOutgoingTransaction() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, """{"total_received":"0","transactions":[""" +
                """{"id":0,"hash":"out","timestamp":"2026-10-22T11:29:31Z","total_received":"100000000000","total_sent":"2400000000000","fee":"87000000","unlock_time":0,"height":6,"coinbase":false,"mempool":false,"mixin":12,"recipient":{"maj_i":0,"min_i":0}}]}""")
            val transactions = run { client(server).getAddressTxs() }
            assertEquals(1, transactions.size)
            val outgoing = transactions[0]
            assertEquals(false, outgoing.isIncoming)
            assertEquals(BigInteger("2400087000000"), outgoing.actualAmount)
            assertEquals(6L, outgoing.height)
        }
    }

    @Test
    fun getAddressTxs_ATransactionInTheMempool_HasNoHeightAndNoTime() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, """{"total_received":"0","transactions":[""" +
                """{"id":0,"hash":"pool","total_received":"5","total_sent":"0","fee":"0","unlock_time":0,"coinbase":false,"mempool":true,"mixin":0,"recipient":{"maj_i":0,"min_i":0}}]}""")
            val transactions = run { client(server).getAddressTxs() }
            assertEquals(1, transactions.size)
            val mempool = transactions[0]
            assertEquals(true, mempool.isIncoming)
            assertEquals(BigInteger("5"), mempool.actualAmount)
            assertNull(mempool.height)
            assertNull(mempool.timestamp)
        }
    }

    // submit_raw_tx

    @Test
    fun submitRawTx_TellsThatTheServerTookTheTransaction() {
        TestServer().use { server ->
            server.answer("/submit_raw_tx", 200, """{"status":"OK"}""")
            assertTrue(run { client(server).submitRawTx("deadbeef") })
            assertSameJson("""{"tx":"deadbeef"}""", server.bodies("/submit_raw_tx").single())
        }
    }

    @Test
    fun submitRawTx_ATransactionThatTheDaemonRejects_IsNotTaken() {
        TestServer().use { server ->
            server.answer("/submit_raw_tx", 422, """{"status":"Failed","reason":"double spend"}""")
            assertFalse(run { client(server).submitRawTx("deadbeef") })
        }
    }

    @Test
    fun submitRawTx_ATransactionThatTheServerTookWithoutRelaying_IsTaken() {
        TestServer().use { server ->
            server.answer("/submit_raw_tx", 200, """{"status":"OK","reason":"Not relayed","untrusted":true}""")
            assertTrue(run { client(server).submitRawTx("deadbeef") })
        }
    }

    // the requests that the client makes

    @Test
    fun theRequests_AreTheJsonThatTheCapturesSent() {
        TestServer().use { server ->
            server.answer("/login", 200, fixture("login"))
            server.answer("/get_address_info", 200, fixture("address_info"))
            server.answer("/get_address_txs", 200, fixture("address_txs"))
            server.answer("/submit_raw_tx", 200, """{"status":"OK"}""")
            val lws = client(server)
            run { lws.login() }
            run { lws.getAddressInfo() }
            run { lws.getAddressTxs() }
            run { lws.submitRawTx("deadbeef") }
            assertEquals(listOf("application/json"), server.contentTypes("/login"))
        }
    }

    // the auth failures of the real server, which are bare statuses with empty bodies

    @Test
    fun anUnknownAddress_IsAnErrorThatNamesTheAddress() {
        TestServer().use { server ->
            server.answer("/login", 403, "")
            val error = assertFailsWith<MoneroError> { run { client(server, createAccount = false).login() } }
            assertEquals("The light wallet server has no account for the address: $address", error.message)
        }
    }

    @Test
    fun aWrongViewKey_IsAnErrorThatNamesTheViewKey() {
        TestServer().use { server ->
            server.answer("/login", 500, "")
            val error = assertFailsWith<MoneroError> { run { client(server, createAccount = false).login() } }
            assertEquals("The light wallet server rejected the view key: it does not match the address $address", error.message)
        }
    }

    @Test
    fun getAddressInfo_OfAnUnknownAddress_IsTheErrorOfTheAddress() {
        TestServer().use { server ->
            server.answer("/get_address_info", 403, "")
            assertFailsWith<MoneroError> { run { client(server).getAddressInfo() } }
        }
    }

    @Test
    fun getAddressTxs_WithAWrongViewKey_IsTheErrorOfTheViewKey() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 500, "")
            assertFailsWith<MoneroError> { run { client(server).getAddressTxs() } }
        }
    }

    // the responses that no capture has

    @Test
    fun aStatusThatIsNeitherASuccessNorAnAuthFailure_IsAnErrorThatNamesIt() {
        TestServer().use { server ->
            server.answer("/login", 404, """{"error":"no such endpoint"}""")
            val error = assertFailsWith<MoneroError> { run { client(server).login() } }
            assertEquals("""The light wallet server answered 404 on /login with: {"error":"no such endpoint"}""", error.message)
        }
    }

    @Test
    fun aResponseThatIsNotJson_IsAnError() {
        TestServer().use { server ->
            server.answer("/get_address_info", 200, "not json at all")
            assertFailsWith<MoneroError> { run { client(server).getAddressInfo() } }
        }
    }

    @Test
    fun anAmountThatIsNotADecimalString_IsAnError() {
        TestServer().use { server ->
            server.answer("/get_address_info", 200, """{"locked_funds":5,"total_received":"0","total_sent":"0"}""")
            assertFailsWith<MoneroError> { run { client(server).getAddressInfo() } }
        }
    }

    @Test
    fun aTimeThatIsNotIso8601_IsAnError() {
        TestServer().use { server ->
            server.answer("/get_address_txs", 200, """{"total_received":"0","transactions":[""" +
                """{"id":0,"hash":"x","timestamp":"not a time","total_received":"5","total_sent":"0","fee":"0","unlock_time":0,"height":1,"coinbase":false,"mempool":false,"mixin":0,"recipient":{"maj_i":0,"min_i":0}}]}""")
            val error = assertFailsWith<MoneroError> { run { client(server).getAddressTxs() } }
            assertEquals("The light wallet server sent a time of a transaction that is not ISO-8601: not a time", error.message)
        }
    }

    @Test
    fun aSubmitAnswerThatIsNotJson_IsAnError() {
        TestServer().use { server ->
            server.answer("/submit_raw_tx", 200, "not json at all")
            assertFailsWith<MoneroError> { run { client(server).submitRawTx("deadbeef") } }
        }
    }

    // the client itself

    @Test
    fun aServerThatDoesNotAnswer_IsAnError() {
        val uri: String
        TestServer().use { server -> uri = server.uri }
        val error = assertFailsWith<MoneroError> { run { MoneroLightWalletRpc(uri, address, viewKey).login() } }
        assertTrue(error.message!!.startsWith("The light wallet server at $uri is not reachable"), error.message)
    }

    @Test
    fun aClosedClient_RejectsCalls() {
        TestServer().use { server ->
            val lws = client(server)
            lws.close()
            val error = assertFailsWith<MoneroError> { run { lws.login() } }
            assertEquals("The light wallet client is closed", error.message)
        }
    }

    @Test
    fun aUriThatIsNotValid_IsRejectedWhenTheClientIsBuilt() {
        assertFailsWith<MoneroError> { MoneroLightWalletRpc("not a uri", address, viewKey) }
    }

    private fun client(server: TestServer, createAccount: Boolean = true): MoneroLightWalletRpc {
        return MoneroLightWalletRpc(server.uri, address, viewKey, createAccount, false)
    }

    // runs a call of the client, which is suspend, to its end without a library of coroutines: the http client of the
    // JDK answers on a thread of its own, and the call resumes there
    private fun <T> run(call: suspend () -> T): T {
        val done = CountDownLatch(1)
        var value: T? = null
        var thrown: Throwable? = null
        call.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                result.fold({ value = it }, { thrown = it })
                done.countDown()
            }
        })
        done.await()
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    // the light wallet server of the tests: it answers a path with a status and a body, and it records the bodies and
    // the content types of the requests that the client sends
    private class TestServer : AutoCloseable {

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val requests = ConcurrentHashMap<String, MutableList<Request>>()
        val uri = "http://127.0.0.1:" + server.address.port

        private class Request(val body: String, val contentType: String?)

        fun answer(path: String, status: Int, body: String) {
            server.createContext(path) { exchange ->
                val bytes = exchange.requestBody.readBytes()
                val request = Request(String(bytes, Charsets.UTF_8), exchange.requestHeaders.getFirst("Content-Type"))
                requests.computeIfAbsent(path) { ArrayList() }.add(request)
                val response = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (response.isEmpty()) -1 else response.size.toLong())
                if (response.isNotEmpty()) exchange.responseBody.use { it.write(response) }
                exchange.close()
            }
        }

        fun bodies(path: String): List<String> = requests[path].orEmpty().map { it.body }

        fun contentTypes(path: String): List<String> = requests[path].orEmpty().mapNotNull { it.contentType }

        init {
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }
}
