package monero.wallet.lws

import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.wallet.lws.model.MoneroLwsAddressInfo
import monero.wallet.lws.model.MoneroLwsTransaction
import monero.wallet.lws.wire.MoneroLwsAddressInfoResponse
import monero.wallet.lws.wire.MoneroLwsAddressRequest
import monero.wallet.lws.wire.MoneroLwsAddressTxsResponse
import monero.wallet.lws.wire.MoneroLwsLoginRequest
import monero.wallet.lws.wire.MoneroLwsLoginResponse
import monero.wallet.lws.wire.MoneroLwsSubmitRawTxRequest
import monero.wallet.lws.wire.MoneroLwsSubmitRawTxResponse
import monero.wallet.lws.wire.MoneroLwsTx

/**
 * Implements a [MoneroLightWalletClient] against the REST API of a [monero-lws](https://github.com/vtnerd/monero-lws)
 * server, with the http client of the JDK. Close it to release the http client.
 *
 * Every request carries the address and the view key of the account, which is all the authentication the server has:
 * it rejects a request with a bare 403 when it has no account for the address, and with a bare 500 when the view key
 * does not match the address, both with an empty body. The server writes the amounts of its responses as unsigned
 * 64-bit numbers in decimal strings, and it leaves a field out entirely when there is nothing to write, which the wire
 * models read.
 *
 * @param uri is the URI of the light wallet server, without the path of an endpoint
 * @param address is the address of the account to scan through the server
 * @param viewKey is the private view key of the account
 * @param createAccount is whether a login asks the server to create the account when it has none for the address
 * @param generatedLocally is whether the wallet generated the keys of the account itself, which a login tells the server
 * @throws MoneroError if the uri is not a valid URI
 */
class MoneroLightWalletRpc(
    uri: String,
    private val address: String,
    private val viewKey: String,
    private val createAccount: Boolean = true,
    private val generatedLocally: Boolean = false
) : MoneroLightWalletClient {

    private val server: URI
    private val http: HttpClient = HttpClient.newHttpClient()
    private var closed = false

    init {
        server = try {
            URI(uri)
        } catch (e: URISyntaxException) {
            throw MoneroError("The uri of the light wallet server is not valid: $uri", e)
        }
    }

    override suspend fun login(): MoneroLwsLoginResponse {
        val request = MoneroLwsLoginRequest()
        request.address = address
        request.viewKey = viewKey
        request.createAccount = createAccount
        request.generatedLocally = generatedLocally
        return MoneroJson.deserialize(json("/login", post("/login", request)), MoneroLwsLoginResponse::class.java)
    }

    override suspend fun getAddressInfo(): MoneroLwsAddressInfo {
        val response = MoneroJson.deserialize(json("/get_address_info", post("/get_address_info", addressRequest())), MoneroLwsAddressInfoResponse::class.java)
        val received = response.totalReceived ?: BigInteger.ZERO
        val sent = response.totalSent ?: BigInteger.ZERO
        val locked = response.lockedFunds ?: BigInteger.ZERO
        val info = MoneroLwsAddressInfo()
        info.totalBalance = received.subtract(sent)
        info.unlockedBalance = received.subtract(sent).subtract(locked)
        info.scannedHeight = response.scannedHeight
        info.startHeight = response.startHeight
        return info
    }

    override suspend fun getAddressTxs(): List<MoneroLwsTransaction> {
        val response = MoneroJson.deserialize(json("/get_address_txs", post("/get_address_txs", addressRequest())), MoneroLwsAddressTxsResponse::class.java)
        return response.transactions.orEmpty().map { tx -> toTransaction(tx) }
    }

    override suspend fun submitRawTx(signedTxHex: String): Boolean {
        val request = MoneroLwsSubmitRawTxRequest()
        request.tx = signedTxHex
        val response = post("/submit_raw_tx", request)
        if (response.statusCode() == 422) return false
        return MoneroJson.deserialize(json("/submit_raw_tx", response), MoneroLwsSubmitRawTxResponse::class.java).status == "OK"
    }

    override fun close() {
        closed = true
        http.close()
    }

    /**
     * A transaction as the server sends it becomes one as the wallet shows it. The amount of an incoming transaction
     * is what it received, and of an outgoing one what it sent plus its fee, which the server always writes: the
     * wallet does not have to guess with the view key. A transaction in the mempool has no height and no time.
     *
     * @param tx is the transaction as the server sends it
     * @return the transaction as the wallet shows it
     * @throws MoneroError if the time of the transaction is not the ISO-8601 time of the server
     */
    private fun toTransaction(tx: MoneroLwsTx): MoneroLwsTransaction {
        val transaction = MoneroLwsTransaction()
        transaction.hash = tx.hash
        val received = tx.totalReceived ?: BigInteger.ZERO
        val sent = tx.totalSent ?: BigInteger.ZERO
        transaction.isIncoming = sent.signum() == 0
        transaction.actualAmount = if (sent.signum() == 0) received else sent.add(tx.fee ?: BigInteger.ZERO)
        transaction.height = tx.height
        transaction.timestamp = tx.timestamp?.let { text ->
            try {
                Instant.parse(text).toEpochMilli()
            } catch (e: DateTimeParseException) {
                throw MoneroError("The light wallet server sent a time of a transaction that is not ISO-8601: $text", e)
            }
        }
        return transaction
    }

    private fun addressRequest(): MoneroLwsAddressRequest {
        val request = MoneroLwsAddressRequest()
        request.address = address
        request.viewKey = viewKey
        return request
    }

    // posts a request body to a path of the server, as the JSON of a wire model. the errors that the endpoints share
    // are thrown here: the server authenticates a request with the address and the view key that the body carries, and
    // it rejects it with a bare 403 when it has no account for the address, and with a bare 500 when the view key
    // does not match, both with an empty body
    private suspend fun post(path: String, requestBody: Any): HttpResponse<String> {
        if (closed) throw MoneroError("The light wallet client is closed")
        val request = HttpRequest.newBuilder(server.resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MoneroJson.serialize(requestBody)))
            .build()
        val response = send(request)
        when (response.statusCode()) {
            403 -> throw MoneroError("The light wallet server has no account for the address: $address")
            500 -> throw MoneroError("The light wallet server rejected the view key: it does not match the address $address")
        }
        return response
    }

    // the body of a response that has to be a 200, or an error that names what the server answered instead
    private fun json(path: String, response: HttpResponse<String>): String {
        val status = response.statusCode()
        if (status != 200) {
            val body = response.body()
            throw MoneroError("The light wallet server answered $status on $path with: ${body.ifEmpty { "an empty body" }}")
        }
        return response.body()
    }

    // sends a request with the http client of the JDK, which is asynchronous, and resumes the call with the response.
    // a server that does not answer becomes a MoneroError
    private suspend fun send(request: HttpRequest): HttpResponse<String> {
        return suspendCoroutine { continuation ->
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
                if (error == null) {
                    continuation.resume(response)
                } else {
                    val cause = error.cause ?: error
                    continuation.resumeWithException(MoneroError("The light wallet server at $server is not reachable: ${cause.message}", error))
                }
            }
        }
    }
}
