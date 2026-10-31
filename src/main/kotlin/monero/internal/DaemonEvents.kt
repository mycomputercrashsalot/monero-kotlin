package monero.internal

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.LinkedBlockingQueue
import monero.common.MoneroJson
import monero.daemon.model.MoneroBlockHeader

/**
 * The native listener of a daemon. The callback only queues what it gets, since it runs on a thread of monero-cpp while
 * the daemon holds its lock; another thread parses the JSON of the header and calls onBlockHeader, one header after the
 * other.
 */
internal class DaemonEvents(private val onBlockHeader: (MoneroBlockHeader) -> Unit) : AutoCloseable {

    private val queue = LinkedBlockingQueue<String>()

    // the native code holds the function that the callback becomes, so the callback has to stay reachable
    private val callback = object : DaemonBlockHeaderCallback {
        override fun invoke(userData: Pointer?, headerJson: Pointer?) {
            try {
                headerJson?.getString(0, "UTF-8")?.let { queue.offer(it) }
            } catch (_: Throwable) {
                // an error must not go back to the native code
            }
        }
    }

    /**
     * The native listener, to add to the daemon.
     */
    val handle: Pointer

    private val dispatcher: Thread

    init {
        val callbacks = DaemonListenerCallbacks()
        callbacks.structSize = callbacks.size()
        callbacks.onBlockHeader = callback
        val out = PointerByReference()
        MoneroNative.ok(MoneroNative.lib.monero_daemon_listener_create(callbacks, out))
        handle = out.value
        dispatcher = Thread(::dispatch, "MoneroDaemonRpc-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun dispatch() {
        while (true) {
            val json = queue.take()
            if (json === STOP) return
            try {
                onBlockHeader(MoneroJson.deserialize(json, MoneroBlockHeader::class.java))
            } catch (_: Throwable) {
                // a header that can not be read does not stop the next ones
            }
        }
    }

    /**
     * Stop the dispatch of the headers and free the native listener. The listener has to be removed from the daemon
     * first.
     */
    override fun close() {
        queue.offer(STOP)
        if (Thread.currentThread() !== dispatcher) dispatcher.join(JOIN_TIMEOUT_MS)
        MoneroNative.lib.monero_daemon_listener_free(handle)
    }

    companion object {
        private val STOP = String()
        private const val JOIN_TIMEOUT_MS = 5000L
    }
}
