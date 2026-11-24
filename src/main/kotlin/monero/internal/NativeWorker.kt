package monero.internal

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import monero.common.MoneroError

/**
 * One thread for the calls of a daemon or a wallet, which run one after the other. A call that reaches the worker after
 * its close fails with the message of the owner, since a wallet and a daemon say that they are closed in their own words.
 *
 * The stack is 8 MB, as the main thread of Linux: the default of a thread of the JVM is 1 MB, and monero-cpp parses
 * JSON by recursion.
 */
internal class NativeWorker(name: String, private val closedMessage: String) : AutoCloseable {

    @Volatile private var thread: Thread? = null

    // the pool is taken raw, since the close needs its queue: the wrapper of newSingleThreadExecutor hides it
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { task ->
        Thread(null, task, name, STACK_SIZE).apply {
            isDaemon = true
            thread = this
        }
    }

    /**
     * Run a call on the thread of the worker, and wait for its result. An error of the call is thrown as it is.
     */
    fun <T> call(task: () -> T): T {

        // the worker runs one call at a time: a call that waits on a call of its own worker waits
        // on itself, which never ends
        if (Thread.currentThread() === thread) {
            throw MoneroError("A call of a worker can not wait on a call of the same worker")
        }
        val future = try {
            executor.submit(Callable { task() })
        } catch (e: RejectedExecutionException) {
            throw MoneroError(closedMessage, e)
        }
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: CancellationException) {
            // a call whose task the close dropped from the queue, which only a lost worker thread leaves
            // behind, reads as the closed message and not as a framework error
            throw MoneroError(closedMessage, e)
        } catch (e: InterruptedException) {
            future.cancel(false)
            Thread.currentThread().interrupt()
            throw MoneroError("Interrupted while waiting for monero-c", e)
        }
    }

    /**
     * Run the last task of the worker and stop the thread after it, waiting for it whatever interrupts come: the
     * task releases what its owner holds, and a queue entry that an interrupt of the caller cancels would leak it.
     */
    fun runFinal(task: () -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                failure[0] = t
            }
        }
        close()
        val error = failure[0]
        if (error != null) throw error
    }

    /**
     * Stop the thread after the calls that are in the queue, and wait until it has run them: the owner frees its handle
     * right after the close, and a call that is still queued would run on a freed handle. The wait is not
     * interruptible, since a close that ends half way leaves the handle freed under a call that is still queued: an
     * interrupt of the closing thread is held and restored when the calls have ended.
     */
    override fun close() {
        executor.shutdown()
        var interrupted = false
        while (!executor.isTerminated) {
            // a pool that lost its only thread with tasks still queued never terminates on its own, and the
            // calls that wait on the queued tasks wait with it: the queue is dropped when that happens, which
            // those calls read as the message of the close
            val worker = thread
            if (worker != null && !worker.isAlive && !executor.queue.isEmpty()) {
                for (task in executor.shutdownNow()) {
                    if (task is Future<*>) task.cancel(false)
                }
            } else {
                try {
                    // bounded, so the state above is looked for while the last calls end
                    executor.awaitTermination(50, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private const val STACK_SIZE = 8L * 1024 * 1024
    }
}
