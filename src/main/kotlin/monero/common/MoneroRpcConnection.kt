package monero.common

import com.fasterxml.jackson.annotation.JsonIgnore
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import monero.internal.MoneroC
import monero.internal.MoneroNative

/**
 * Models a connection to a daemon or to a wallet RPC server, and talks to the server.
 *
 * The fields are the configuration of the connection and what is known of the server. The functions that talk to the server, or
 * that keep something for the connection (the credentials, the attributes), use a handle of monero-c, which is made the first time
 * that one of them is called. A connection that is only used as the configuration of a daemon or a wallet never makes one.
 * [close] releases the handle. The connection can be used after that, and makes a new one.
 *
 * If a field of the configuration (the URI, the credentials, the proxy, the priority, the timeout or the verification of the
 * certificate) changes after the handle was made, the next call makes a new handle with the new configuration, and what the old one
 * had is gone: the attributes, and the state of the server that [checkConnection] found. [setCredentials] keeps them.
 *
 * It can be used by several threads. [close] waits for the calls that are running.
 */
open class MoneroRpcConnection : AutoCloseable {
    var uri: String? = null
    var username: String? = null
    var password: String? = null
    var proxyUri: String? = null
    var zmqUri: String? = null
    // priority of the connection compared to the others, where 1 is the highest, then 2, and so on, and 0 is the lowest
    var priority: Int? = null
    var timeoutMs: Long? = null
    // what the last call of checkConnection found out
    var responseTime: Long? = null
    var sslVerify: Boolean? = null
    var isOnline: Boolean? = null
    var isAuthenticated: Boolean? = null

    // the fields below are not part of the model: they are not written to JSON, and are not copied
    @field:JsonIgnore
    private val handleLock = ReentrantReadWriteLock()
    @field:JsonIgnore
    private var handle: Pointer? = null
    // the configuration that the handle was made with
    @field:JsonIgnore
    private var handleConfiguration: Configuration? = null

    constructor()

    constructor(other: MoneroRpcConnection) {
        this.uri = other.uri
        this.username = other.username
        this.password = other.password
        this.proxyUri = other.proxyUri
        this.zmqUri = other.zmqUri
        this.priority = other.priority
        this.timeoutMs = other.timeoutMs
        this.responseTime = other.responseTime
        this.sslVerify = other.sslVerify
        this.isOnline = other.isOnline
        this.isAuthenticated = other.isAuthenticated
    }

    constructor(uri: String?, username: String? = null, password: String? = null) {
        this.uri = uri
        this.username = username
        this.password = password
    }

    open fun copy(): MoneroRpcConnection {
        return MoneroRpcConnection(this)
    }

    /**
     * Set the credentials of the connection. The state of the server that [checkConnection] found is not valid for them, so it
     * is cleared.
     *
     * @param username is the username, or null or empty for none
     * @param password is the password, or null or empty for none
     * @throws MoneroError if one of them is empty and the other is not
     */
    fun setCredentials(username: String?, password: String?) {
        handleLock.write {
            val lib = MoneroNative.lib
            val h = ensureHandle(lib)
            MoneroNative.ok(lib.monero_rpc_connection_set_credentials(h, username ?: "", password ?: ""))
            this.username = username?.ifEmpty { null }
            this.password = password?.ifEmpty { null }
            // the handle has these credentials now, so it is not made again for them
            handleConfiguration = configuration()
            readState(lib, h)
        }
    }

    /**
     * Get an attribute of the connection. Attributes are kept in memory only, and they are not copied.
     *
     * @param key is the key of the attribute
     * @return the value of the attribute, or an empty string if it is not set
     */
    fun getAttribute(key: String): String {
        return withHandle { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_rpc_connection_get_attribute(h, key, out), out) ?: ""
        }
    }

    /**
     * Set an attribute of the connection. Attributes are kept in memory only, and they are not copied.
     *
     * @param key is the key of the attribute
     * @param value is the value of the attribute
     */
    fun setAttribute(key: String, value: String) {
        withHandle { lib, h -> MoneroNative.ok(lib.monero_rpc_connection_set_attribute(h, key, value)) }
    }

    /**
     * Indicates if the URI is a Tor onion address.
     *
     * @return true if the URI is a .onion address
     */
    fun isOnion(): Boolean {
        return withHandle { lib, h ->
            val out = ByteByReference()
            MoneroNative.ok(lib.monero_rpc_connection_is_onion(h, out))
            out.value != 0.toByte()
        }
    }

    /**
     * Indicates if the URI is an I2P address.
     *
     * @return true if the URI is a .b32.i2p address
     */
    fun isI2p(): Boolean {
        return withHandle { lib, h ->
            val out = ByteByReference()
            MoneroNative.ok(lib.monero_rpc_connection_is_i2p(h, out))
            out.value != 0.toByte()
        }
    }

    /**
     * Indicates if the server was online and accepted the credentials at the last [checkConnection]. The fields [isOnline] and
     * [isAuthenticated] have each of the two.
     *
     * @return true if it was, false if it was not, or null if the connection was not checked
     */
    fun isConnected(): Boolean? {
        return withHandle { lib, h ->
            val out = IntByReference(-1)
            MoneroNative.ok(lib.monero_rpc_connection_is_connected(h, out))
            when (out.value) {
                -1 -> null
                0 -> false
                else -> true
            }
        }
    }

    /**
     * Check the connection, and update [isOnline], [isAuthenticated] and [responseTime]. A server that does not answer is not an
     * error: it is offline.
     *
     * @param timeoutMs is the time to wait for the server in milliseconds, or null for the timeout of the connection
     * @return true if the online or the authentication status changed
     * @throws MoneroError if the timeout is not from 0 to 4294967295
     */
    fun checkConnection(timeoutMs: Long? = null): Boolean {
        val timeout = timeout(timeoutMs)
        return withHandle { lib, h ->
            val changed = ByteByReference()
            MoneroNative.ok(lib.monero_rpc_connection_check_connection(h, timeout, changed))
            readState(lib, h)
            changed.value != 0.toByte()
        }
    }

    /**
     * Send a JSON-RPC request to /json_rpc of the server.
     *
     * @param method is the JSON-RPC method, e.g. "get_version"
     * @param params are the parameters of the method, or null for none
     * @param timeoutMs is the time to wait for the server in milliseconds, or null for the timeout of the connection
     * @return the JSON of the result of the response. monero-cpp reads it without types, so a number or a boolean that the server
     *         wrote as a string is not a string, and an empty array or object is an empty string
     * @throws MoneroError if the request fails, or the server answers with an error. The message is the one of the server
     */
    fun sendJsonRequest(method: String, params: Map<String, Any?>? = null, timeoutMs: Long? = null): String {
        val json = params?.let { MoneroJson.serialize(it) }
        val timeout = timeout(timeoutMs)
        return withHandle { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_rpc_connection_send_json_request(h, method, json, timeout, out), out) ?: ""
        }
    }

    /**
     * Send a JSON request to a path of the server, e.g. "get_height" for /get_height.
     *
     * @param path is the path, without the leading slash
     * @param params are the parameters, or null for none
     * @param timeoutMs is the time to wait for the server in milliseconds, or null for the timeout of the connection
     * @return the JSON of the response, which has the types that [sendJsonRequest] has
     * @throws MoneroError if the request fails, or the server answers with an error
     */
    fun sendPathRequest(path: String, params: Map<String, Any?>? = null, timeoutMs: Long? = null): String {
        val json = params?.let { MoneroJson.serialize(it) }
        val timeout = timeout(timeoutMs)
        return withHandle { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_rpc_connection_send_path_request(h, path, json, timeout, out), out) ?: ""
        }
    }

    /**
     * Send a binary request to a path of the server, e.g. "get_blocks_by_height.bin". The parameters are sent in the binary form.
     * [monero.common.MoneroUtils.binaryBlocksToJson] converts the response of that one.
     *
     * @param path is the path, without the leading slash
     * @param params are the parameters, or null for none
     * @param timeoutMs is the time to wait for the server in milliseconds, or null for the timeout of the connection
     * @return the binary response
     * @throws MoneroError if the request fails, or the server answers with an error
     */
    fun sendBinaryRequest(path: String, params: Map<String, Any?>? = null, timeoutMs: Long? = null): ByteArray {
        val json = params?.let { MoneroJson.serialize(it) }
        val timeout = timeout(timeoutMs)
        return withHandle { lib, h ->
            val out = PointerByReference()
            val count = MoneroNative.newSize()
            MoneroNative.bytes(lib.monero_rpc_connection_send_binary_request(h, path, json, timeout, out, count), out, count)
        }
    }

    /**
     * Serialize the connection, as monero-c does: with the password, and with the state of the server that [checkConnection]
     * found. The JSON of the fields is [MoneroJson.serialize].
     *
     * @return the JSON of the connection
     */
    fun serialize(): String {
        return withHandle { lib, h -> serialize(lib, h) }
    }

    /**
     * Release the handle of monero-c, if the connection made one. Waits for the calls that are running.
     */
    override fun close() {
        handleLock.write {
            handle?.let { MoneroNative.lib.monero_rpc_connection_free(it) }
            handle = null
            handleConfiguration = null
        }
    }

    // the handle for the current configuration is made under the write lock, and used under the read lock, so the calls
    // of several threads run together and close() waits for them. The call runs under the read lock on the way that
    // makes the handle too: a call that a server keeps for the whole of its timeout does not hold the write lock and
    // every call of every other thread behind it, which is the one slow answer that the connection is remembered by
    private fun <T> withHandle(call: (MoneroC, Pointer) -> T): T {
        val lib = MoneroNative.lib
        handleLock.read {
            val h = handle
            if (h != null && handleConfiguration == configuration()) return call(lib, h)
        }
        var attempts = 0
        while (true) {
            handleLock.write { ensureHandle(lib) }
            handleLock.read {
                val h = handle
                if (h != null && handleConfiguration == configuration()) return call(lib, h)
            }
            // the configuration changed again while the handle was made, which only a caller that changes it with
            // every call does: after a few of those, run the call the way that cannot miss, under the write lock
            if (++attempts >= 3) return handleLock.write { call(lib, ensureHandle(lib)) }
        }
    }

    // the write lock is held
    private fun ensureHandle(lib: MoneroC): Pointer {
        val configuration = configuration()
        val existing = handle
        if (existing != null) {
            if (handleConfiguration == configuration) return existing
            // the server, or the way to it, is not the one that the state is of
            lib.monero_rpc_connection_free(existing)
            handle = null
            handleConfiguration = null
            isOnline = null
            isAuthenticated = null
            responseTime = null
        }
        val out = PointerByReference()
        MoneroNative.ok(lib.monero_rpc_connection_create(MoneroJson.serialize(this), out))
        handle = out.value
        handleConfiguration = configuration
        return out.value
    }

    private fun serialize(lib: MoneroC, h: Pointer): String {
        val out = PointerByReference()
        return MoneroNative.text(lib.monero_rpc_connection_serialize(h, out), out) ?: throw MoneroError("monero-c returned no JSON")
    }

    // the state of the server is in the handle, and the fields have a copy of it
    private fun readState(lib: MoneroC, h: Pointer) {
        val state = MoneroJson.deserialize(serialize(lib, h), MoneroRpcConnection::class.java)
        isOnline = state.isOnline
        isAuthenticated = state.isAuthenticated
        responseTime = state.responseTime
    }

    private fun configuration() = Configuration(uri, username, password, proxyUri, zmqUri, priority, timeoutMs, sslVerify)

    // a number from 0 to 4294967295 is written to the 32 bits that the C function reads
    private fun timeout(timeoutMs: Long?): IntByReference? {
        if (timeoutMs == null) return null
        if (timeoutMs < 0 || timeoutMs > 0xffffffffL) throw MoneroError("The timeout $timeoutMs is not from 0 to 4294967295 milliseconds")
        return IntByReference(timeoutMs.toInt())
    }

    private data class Configuration(
        val uri: String?,
        val username: String?,
        val password: String?,
        val proxyUri: String?,
        val zmqUri: String?,
        val priority: Int?,
        val timeoutMs: Long?,
        val sslVerify: Boolean?
    )
}
