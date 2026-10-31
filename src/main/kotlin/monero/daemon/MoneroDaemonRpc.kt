package monero.daemon

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroAddAuxiliaryPowResult
import monero.daemon.model.MoneroAltChain
import monero.daemon.model.MoneroAuxiliaryPow
import monero.daemon.model.MoneroBan
import monero.daemon.model.MoneroBlock
import monero.daemon.model.MoneroBlockHeader
import monero.daemon.model.MoneroBlockTemplate
import monero.daemon.model.MoneroBlocksResult
import monero.daemon.model.MoneroDaemonInfo
import monero.daemon.model.MoneroDaemonListener
import monero.daemon.model.MoneroDaemonNetworkStats
import monero.daemon.model.MoneroDaemonSyncInfo
import monero.daemon.model.MoneroDaemonUpdateCheckResult
import monero.daemon.model.MoneroDaemonUpdateDownloadResult
import monero.daemon.model.MoneroFeeEstimate
import monero.daemon.model.MoneroGenerateBlocksResult
import monero.daemon.model.MoneroGetBlockHashesResult
import monero.daemon.model.MoneroGetBlocksByHashResult
import monero.daemon.model.MoneroHardForkInfo
import monero.daemon.model.MoneroKeyImageSpentStatus
import monero.daemon.model.MoneroMinerData
import monero.daemon.model.MoneroMinerTxSum
import monero.daemon.model.MoneroMiningStatus
import monero.daemon.model.MoneroOutput
import monero.daemon.model.MoneroOutputDistributionEntry
import monero.daemon.model.MoneroOutputHistogramEntry
import monero.daemon.model.MoneroPeer
import monero.daemon.model.MoneroPruneResult
import monero.daemon.model.MoneroSubmitTxResult
import monero.daemon.model.MoneroTx
import monero.daemon.model.MoneroTxBacklogEntry
import monero.daemon.model.MoneroTxPoolStats
import monero.daemon.model.MoneroVersion
import monero.internal.DaemonEvents
import monero.internal.MoneroC
import monero.internal.MoneroNative
import monero.internal.NativeWorker
import monero.internal.SizeT

/**
 * Implements a [MoneroDaemon] using [monerod-rpc](https://docs.getmonero.org/rpc-library/monerod-rpc/), through
 * monero-c. Close it to release the native handle.
 *
 * The calls of a daemon run one after the other on a thread of their own.
 *
 * @param rpc is the RPC connection of the daemon
 * @throws MoneroError if the native library is not found, or it can not make the connection
 */
class MoneroDaemonRpc(rpc: MoneroRpcConnection) : MoneroDaemonDefault(), AutoCloseable {

    private val handle: Pointer
    private val worker = NativeWorker("MoneroDaemonRpc")
    private val lock = Any()
    private val listeners = ArrayList<MoneroDaemonListener>()
    private val waiters = ArrayList<CompletableFuture<MoneroBlockHeader>>()
    private var events: DaemonEvents? = null
    private var nativeListenerAdded = false
    private var closed = false

    /**
     * Construct a daemon RPC client that connects to the given URI.
     *
     * @param uri is the URI of the daemon RPC server
     * @param username is the username to authenticate with (optional)
     * @param password is the password to authenticate with (optional)
     */
    constructor(uri: String, username: String? = null, password: String? = null) : this(MoneroRpcConnection(uri, username, password))

    init {
        val lib = MoneroNative.lib
        val connection = PointerByReference()
        MoneroNative.ok(lib.monero_rpc_connection_create(MoneroJson.serialize(rpc), connection))
        val daemon = PointerByReference()
        try {
            MoneroNative.ok(lib.monero_daemon_connect_with(connection.value, daemon))
        } finally {
            lib.monero_rpc_connection_free(connection.value)
        }
        handle = daemon.value
    }

    // ------------------------------- CONNECTION ---------------------------------

    /**
     * Get the daemon's RPC connection, as it is now: a copy, with the last known state of the server.
     *
     * @return the daemon's RPC connection
     */
    fun getRpcConnection(): MoneroRpcConnection {
        return json<MoneroRpcConnection> { lib, h, out -> lib.monero_daemon_get_rpc_connection(h, out) }
    }

    /**
     * Indicates if the client is connected to the daemon via RPC: the server answered the last request, and it accepted
     * the credentials.
     *
     * @return true if the client is connected to the daemon, false otherwise
     */
    fun isConnected(): Boolean {
        val connection = getRpcConnection()
        return connection.isOnline == true && connection.isAuthenticated != false
    }

    /**
     * Set the period between polls of the daemon for block notifications.
     *
     * @param periodMs is the poll period in milliseconds, from 1 to 2147483647
     */
    fun setPollPeriodInMs(periodMs: Long) {
        unit { lib, h -> lib.monero_daemon_set_poll_period(h, periodMs) }
    }

    // ------------------------------- LISTENERS ----------------------------------

    override fun addListener(listener: MoneroDaemonListener) {
        synchronized(lock) {
            checkNotClosed()
            if (listeners.contains(listener)) return
            listeners.add(listener)
            try {
                updateNativeListener()
            } catch (e: Throwable) {
                listeners.remove(listener)
                throw e
            }
        }
    }

    override fun removeListener(listener: MoneroDaemonListener) {
        synchronized(lock) {
            if (closed || !listeners.remove(listener)) return
            updateNativeListener()
        }
    }

    override fun getListeners(): List<MoneroDaemonListener> {
        synchronized(lock) {
            return ArrayList(listeners)
        }
    }

    override fun removeListeners() {
        synchronized(lock) {
            if (closed) return
            listeners.clear()
            updateNativeListener()
        }
    }

    // the daemon polls while a native listener is added, so there is one while a listener or a waiter exists
    private fun updateNativeListener() {
        val needed = listeners.size + waiters.size > 0
        if (needed && !nativeListenerAdded) {
            val current = events ?: DaemonEvents(::onBlockHeader).also { events = it }
            MoneroNative.ok(MoneroNative.lib.monero_daemon_add_listener(handle, current.handle))
            nativeListenerAdded = true
        } else if (!needed && nativeListenerAdded) {
            MoneroNative.ok(MoneroNative.lib.monero_daemon_remove_listener(handle, events!!.handle))
            nativeListenerAdded = false
        }
    }

    // runs on the thread of the events, one header after the other
    private fun onBlockHeader(header: MoneroBlockHeader) {
        val currentListeners: List<MoneroDaemonListener>
        val currentWaiters: List<CompletableFuture<MoneroBlockHeader>>
        synchronized(lock) {
            currentListeners = ArrayList(listeners)
            currentWaiters = ArrayList(waiters)
            waiters.clear()
        }

        for (listener in currentListeners) {
            try {
                listener.onBlockHeader(header)
            } catch (_: Throwable) {
                // a listener that throws does not stop the others
            }
        }

        for (waiter in currentWaiters) waiter.complete(header)

        if (currentWaiters.isNotEmpty()) {
            synchronized(lock) {
                if (!closed) updateNativeListener()
            }
        }
    }

    private fun checkNotClosed() {
        if (closed) throw MoneroError("The daemon is closed")
    }

    // ------------------------------- NODE ---------------------------------------

    override fun getVersion(): MoneroVersion {
        return json { lib, h, out -> lib.monero_daemon_get_version(h, out) }
    }

    override fun isTrusted(): Boolean {
        return run { lib, h ->
            val trusted = ByteByReference()
            MoneroNative.ok(lib.monero_daemon_is_trusted(h, trusted))
            trusted.value.toInt() != 0
        }
    }

    override fun getHeight(): Long {
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_daemon_get_height(h, height))
            height.value
        }
    }

    override fun getInfo(): MoneroDaemonInfo {
        return json { lib, h, out -> lib.monero_daemon_get_info(h, out) }
    }

    override fun getSyncInfo(): MoneroDaemonSyncInfo {
        return json { lib, h, out -> lib.monero_daemon_get_sync_info(h, out) }
    }

    override fun getNetworkStats(): MoneroDaemonNetworkStats {
        return json { lib, h, out -> lib.monero_daemon_get_network_stats(h, out) }
    }

    override fun getHardForkInfo(): MoneroHardForkInfo {
        return json { lib, h, out -> lib.monero_daemon_get_hard_fork_info(h, out) }
    }

    override fun getAltChains(): List<MoneroAltChain> {
        return json { lib, h, out -> lib.monero_daemon_get_alt_chains(h, out) }
    }

    override fun getAltBlockHashes(): List<String> {
        return json { lib, h, out -> lib.monero_daemon_get_alt_block_hashes(h, out) }
    }

    override fun waitForNextBlockHeader(): MoneroBlockHeader {
        val waiter = CompletableFuture<MoneroBlockHeader>()
        synchronized(lock) {
            checkNotClosed()
            waiters.add(waiter)
            try {
                updateNativeListener()
            } catch (e: Throwable) {
                waiters.remove(waiter)
                throw e
            }
        }

        try {
            return waiter.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? MoneroError) ?: MoneroError(e.cause?.message, e.cause)
        } catch (e: InterruptedException) {
            synchronized(lock) {
                if (waiters.remove(waiter) && !closed) updateNativeListener()
            }
            Thread.currentThread().interrupt()
            throw MoneroError("Interrupted while waiting for the next block header", e)
        }
    }

    // ------------------------------- BLOCKS -------------------------------------

    override fun getBlockHash(height: Long): String {
        return text { lib, h, out -> lib.monero_daemon_get_block_hash(h, height, out) } ?: throw MoneroError("The daemon returned no hash")
    }

    override fun getLastBlockHeader(): MoneroBlockHeader {
        return json { lib, h, out -> lib.monero_daemon_get_last_block_header(h, out) }
    }

    override fun getBlockHeaderByHash(blockHash: String): MoneroBlockHeader {
        return json { lib, h, out -> lib.monero_daemon_get_block_header_by_hash(h, blockHash, out) }
    }

    override fun getBlockHeaderByHeight(height: Long): MoneroBlockHeader {
        return json { lib, h, out -> lib.monero_daemon_get_block_header_by_height(h, height, out) }
    }

    /**
     * Wait until the next block is added to the chain, for at most a number of milliseconds (monero_c_daemon.h:552).
     * A wallet that waits in a loop can be cancelled between the waits, which the wait without a timeout can not.
     *
     * @param timeoutMs is the longest time to wait, from 1 to 2147483647
     * @return the header of the block, or null if no block arrived in the time
     * @throws MoneroError if the daemon is closed
     */
    fun waitForNextBlockHeader(timeoutMs: Int): MoneroBlockHeader? {
        val out = PointerByReference()
        val timedOut = ByteByReference()
        MoneroNative.ok(MoneroNative.lib.monero_daemon_wait_for_next_block_header_timeout(handle, timeoutMs, out, timedOut))
        if (timedOut.value.toInt() != 0) return null
        return MoneroNative.json(0, out, MoneroBlockHeader::class.java)
    }

    override fun getBlockHeadersByRange(startHeight: Long, endHeight: Long): List<MoneroBlockHeader> {
        return json { lib, h, out -> lib.monero_daemon_get_block_headers_by_range(h, startHeight, endHeight, out) }
    }

    override fun getBlockByHash(blockHash: String): MoneroBlock {
        return json { lib, h, out -> lib.monero_daemon_get_block_by_hash(h, blockHash, out) }
    }

    override fun getBlocksByHash(blockHashes: List<String>, startHeight: Long, prune: Boolean, maxBlockCount: Long): MoneroGetBlocksByHashResult {
        val hashes = blockHashes.toTypedArray()
        return json { lib, h, out ->
            lib.monero_daemon_get_blocks_by_hash(h, hashes, SizeT(hashes.size.toLong()), startHeight, prune, maxBlockCount, out)
        }
    }

    override fun getBlockByHeight(height: Long): MoneroBlock {
        return json { lib, h, out -> lib.monero_daemon_get_block_by_height(h, height, out) }
    }

    override fun getBlocksByHeight(heights: List<Long>): List<MoneroBlock> {
        val array = heights.toLongArray()
        return json { lib, h, out -> lib.monero_daemon_get_blocks_by_height(h, array, SizeT(array.size.toLong()), out) }
    }

    override fun getBlocksByRange(startHeight: Long?, endHeight: Long?): List<MoneroBlock> {
        return json { lib, h, out -> lib.monero_daemon_get_blocks_by_range(h, optional(startHeight), optional(endHeight), out) }
    }

    override fun getBlocksByRangeChunked(startHeight: Long?, endHeight: Long?, maxChunkSize: Long?): List<MoneroBlock> {
        return json { lib, h, out ->
            lib.monero_daemon_get_blocks_by_range_chunked(h, optional(startHeight), optional(endHeight), optional(maxChunkSize), out)
        }
    }

    override fun getBlockHashes(blockHashes: List<String>): MoneroGetBlockHashesResult {
        val hashes = blockHashes.toTypedArray()
        return json { lib, h, out -> lib.monero_daemon_get_block_hashes(h, hashes, SizeT(hashes.size.toLong()), out) }
    }

    // ------------------------------- TRANSACTIONS -------------------------------

    override fun getTxs(txHashes: Collection<String>, prune: Boolean): List<MoneroTx> {
        val hashes = txHashes.toTypedArray()
        val result = json<MoneroBlocksResult> { lib, h, out -> lib.monero_daemon_get_txs(h, hashes, SizeT(hashes.size.toLong()), prune, out) }
        return sortByHashes(result.getTxs(), txHashes)
    }

    override fun getTxHexes(txHashes: Collection<String>, prune: Boolean): List<String> {
        val hashes = txHashes.toTypedArray()
        return json { lib, h, out -> lib.monero_daemon_get_tx_hexes(h, hashes, SizeT(hashes.size.toLong()), prune, out) }
    }

    override fun getMinerTxSum(height: Long, numBlocks: Long): MoneroMinerTxSum {
        return json { lib, h, out -> lib.monero_daemon_get_miner_tx_sum(h, height, numBlocks, out) }
    }

    override fun getFeeEstimate(graceBlocks: Long): MoneroFeeEstimate {
        return json { lib, h, out -> lib.monero_daemon_get_fee_estimate(h, graceBlocks, out) }
    }

    override fun submitTxHex(txHex: String, doNotRelay: Boolean): MoneroSubmitTxResult {
        return json { lib, h, out -> lib.monero_daemon_submit_tx_hex(h, txHex, doNotRelay, out) }
    }

    override fun relayTxByHash(txHash: String) {
        unit { lib, h -> lib.monero_daemon_relay_tx_by_hash(h, txHash) }
    }

    override fun relayTxsByHash(txHashes: Collection<String>) {
        val hashes = txHashes.toTypedArray()
        unit { lib, h -> lib.monero_daemon_relay_txs_by_hash(h, hashes, SizeT(hashes.size.toLong())) }
    }

    override fun getTxPool(): List<MoneroTx> {
        return json<MoneroBlocksResult> { lib, h, out -> lib.monero_daemon_get_tx_pool(h, out) }.getTxs()
    }

    override fun getTxPoolHashes(): List<String> {
        return json { lib, h, out -> lib.monero_daemon_get_tx_pool_hashes(h, out) }
    }

    override fun getTxPoolBacklog(): List<MoneroTxBacklogEntry> {
        return json { lib, h, out -> lib.monero_daemon_get_tx_pool_backlog(h, out) }
    }

    override fun getTxPoolStats(): MoneroTxPoolStats {
        return json { lib, h, out -> lib.monero_daemon_get_tx_pool_stats(h, out) }
    }

    override fun flushTxPool() {
        unit { lib, h -> lib.monero_daemon_flush_tx_pool(h) }
    }

    override fun flushTxPool(hash: String) {
        unit { lib, h -> lib.monero_daemon_flush_tx_pool_hash(h, hash) }
    }

    override fun flushTxPool(hashes: Collection<String>) {
        val array = hashes.toTypedArray()
        unit { lib, h -> lib.monero_daemon_flush_tx_pool_hashes(h, array, SizeT(array.size.toLong())) }
    }

    // ------------------------------- KEY IMAGES AND OUTPUTS ---------------------

    override fun getKeyImageSpentStatuses(keyImages: List<String>): List<MoneroKeyImageSpentStatus> {
        val images = keyImages.toTypedArray()
        val statuses = run { lib, h ->
            val out = PointerByReference()
            val count = MoneroNative.newSize()
            MoneroNative.ints(lib.monero_daemon_get_key_image_spent_statuses(h, images, SizeT(images.size.toLong()), out, count), out, count)
        }
        return statuses.map { status ->
            MoneroKeyImageSpentStatus.entries.getOrNull(status) ?: throw MoneroError("Unknown key image spent status: $status")
        }
    }

    override fun getOutputIndices(txHash: String): List<Long> {
        return run { lib, h ->
            val out = PointerByReference()
            val count = MoneroNative.newSize()
            MoneroNative.longs(lib.monero_daemon_get_output_indices(h, txHash, out, count), out, count)
        }
    }

    override fun getOutputs(outputs: Collection<MoneroOutput>): List<MoneroOutput> {
        val request = MoneroJson.serialize(outputs)
        return json { lib, h, out -> lib.monero_daemon_get_outputs(h, request, out) }
    }

    override fun getOutputHistogram(amounts: Collection<BigInteger>, minCount: Int?, maxCount: Int?, isUnlocked: Boolean?, recentCutoff: Int?): List<MoneroOutputHistogramEntry> {
        val array = amounts.map { it.toLong() }.toLongArray()
        return json { lib, h, out ->
            lib.monero_daemon_get_output_histogram(h, array, SizeT(array.size.toLong()), optional(minCount), optional(maxCount), optional(isUnlocked), optional(recentCutoff), out)
        }
    }

    override fun getOutputDistribution(amounts: Collection<BigInteger>, isCumulative: Boolean?, startHeight: Long?, endHeight: Long?): List<MoneroOutputDistributionEntry> {
        val array = amounts.map { it.toLong() }.toLongArray()
        return json { lib, h, out ->
            lib.monero_daemon_get_output_distribution(h, array, SizeT(array.size.toLong()), optional(isCumulative), optional(startHeight), optional(endHeight), out)
        }
    }

    // ------------------------------- MINING -------------------------------------

    override fun getBlockTemplate(walletAddress: String, reserveSize: Int?): MoneroBlockTemplate {
        return json { lib, h, out -> lib.monero_daemon_get_block_template(h, walletAddress, optional(reserveSize), out) }
    }

    override fun getMinerData(): MoneroMinerData {
        return json { lib, h, out -> lib.monero_daemon_get_miner_data(h, out) }
    }

    override fun calculatePow(majorVersion: Int, height: Long, blockBlob: String, seedHash: String): String {
        return text { lib, h, out -> lib.monero_daemon_calculate_pow(h, majorVersion, height, blockBlob, seedHash, out) } ?: throw MoneroError("The daemon returned no hash")
    }

    override fun addAuxiliaryPow(blockTemplateBlob: String, auxPow: List<MoneroAuxiliaryPow>): MoneroAddAuxiliaryPowResult {
        val request = MoneroJson.serialize(auxPow)
        return json { lib, h, out -> lib.monero_daemon_add_auxiliary_pow(h, blockTemplateBlob, request, out) }
    }

    override fun startMining(address: String, numThreads: Long?, isBackground: Boolean?, ignoreBattery: Boolean?) {
        unit { lib, h -> lib.monero_daemon_start_mining(h, address, optional(numThreads), optional(isBackground), optional(ignoreBattery)) }
    }

    override fun stopMining() {
        unit { lib, h -> lib.monero_daemon_stop_mining(h) }
    }

    override fun getMiningStatus(): MoneroMiningStatus {
        return json { lib, h, out -> lib.monero_daemon_get_mining_status(h, out) }
    }

    override fun submitBlock(blockBlob: String) {
        unit { lib, h -> lib.monero_daemon_submit_block(h, blockBlob) }
    }

    override fun submitBlocks(blockBlobs: Collection<String>) {
        val blobs = blockBlobs.toTypedArray()
        unit { lib, h -> lib.monero_daemon_submit_blocks(h, blobs, SizeT(blobs.size.toLong())) }
    }

    override fun generateBlocks(address: String, numBlocks: Long, prevBlockHash: String?, startingNonce: Long?): MoneroGenerateBlocksResult {
        val nonce = startingNonce?.let { IntByReference(it.toInt()) }
        return json { lib, h, out -> lib.monero_daemon_generate_blocks(h, address, numBlocks, prevBlockHash, nonce, out) }
    }

    // ------------------------------- PEERS AND BANDWIDTH ------------------------

    override fun getPeers(): List<MoneroPeer> {
        return json { lib, h, out -> lib.monero_daemon_get_peers(h, out) }
    }

    override fun getKnownPeers(): List<MoneroPeer> {
        return json { lib, h, out -> lib.monero_daemon_get_known_peers(h, out) }
    }

    override fun getPublicPeers(includeOffline: Boolean): List<MoneroPeer> {
        return json { lib, h, out -> lib.monero_daemon_get_public_peers(h, includeOffline, out) }
    }

    override fun setOutgoingPeerLimit(limit: Int) {
        unit { lib, h -> lib.monero_daemon_set_outgoing_peer_limit(h, limit) }
    }

    override fun setIncomingPeerLimit(limit: Int) {
        unit { lib, h -> lib.monero_daemon_set_incoming_peer_limit(h, limit) }
    }

    override fun getPeerBans(): List<MoneroBan> {
        return json { lib, h, out -> lib.monero_daemon_get_peer_bans(h, out) }
    }

    override fun getPeerBan(address: String): MoneroBan {
        return json { lib, h, out -> lib.monero_daemon_get_peer_ban(h, address, out) }
    }

    override fun setPeerBan(ban: MoneroBan) {
        val request = MoneroJson.serialize(ban)
        unit { lib, h -> lib.monero_daemon_set_peer_ban(h, request) }
    }

    override fun setPeerBans(bans: List<MoneroBan>) {
        val request = MoneroJson.serialize(bans)
        unit { lib, h -> lib.monero_daemon_set_peer_bans(h, request) }
    }

    override fun getDownloadLimit(): Int = limit { lib, h, out -> lib.monero_daemon_get_download_limit(h, out) }

    override fun setDownloadLimit(limit: Int): Int = limit { lib, h, out -> lib.monero_daemon_set_download_limit(h, limit, out) }

    override fun resetDownloadLimit(): Int = limit { lib, h, out -> lib.monero_daemon_reset_download_limit(h, out) }

    override fun getUploadLimit(): Int = limit { lib, h, out -> lib.monero_daemon_get_upload_limit(h, out) }

    override fun setUploadLimit(limit: Int): Int = limit { lib, h, out -> lib.monero_daemon_set_upload_limit(h, limit, out) }

    override fun resetUploadLimit(): Int = limit { lib, h, out -> lib.monero_daemon_reset_upload_limit(h, out) }

    // ------------------------------- BLOCKCHAIN, CONFIGURATION, LOGS AND UPDATES -

    override fun pruneBlockchain(check: Boolean): MoneroPruneResult {
        return json { lib, h, out -> lib.monero_daemon_prune_blockchain(h, check, out) }
    }

    override fun saveBlockchain() {
        unit { lib, h -> lib.monero_daemon_save_blockchain(h) }
    }

    override fun popBlocks(numBlocks: Long): Long {
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_daemon_pop_blocks(h, numBlocks, height))
            height.value
        }
    }

    override fun flushCache(badBlocks: Boolean) {
        unit { lib, h -> lib.monero_daemon_flush_cache(h, badBlocks) }
    }

    override fun setBootstrapDaemon(address: String, username: String, password: String, proxy: String) {
        unit { lib, h -> lib.monero_daemon_set_bootstrap_daemon(h, address, username, password, proxy) }
    }

    override fun removeBootstrapDaemon() {
        unit { lib, h -> lib.monero_daemon_remove_bootstrap_daemon(h) }
    }

    override fun setLogHashRate(isVisible: Boolean) {
        unit { lib, h -> lib.monero_daemon_set_log_hash_rate(h, isVisible) }
    }

    override fun setLogLevel(level: Int) {
        unit { lib, h -> lib.monero_daemon_set_log_level(h, level) }
    }

    override fun setLogCategories(categories: String): String {
        return text { lib, h, out -> lib.monero_daemon_set_log_categories(h, categories, out) } ?: ""
    }

    override fun checkForUpdate(): MoneroDaemonUpdateCheckResult {
        return json { lib, h, out -> lib.monero_daemon_check_for_update(h, out) }
    }

    override fun downloadUpdate(path: String): MoneroDaemonUpdateDownloadResult {
        return json { lib, h, out -> lib.monero_daemon_download_update(h, path, out) }
    }

    override fun stop() {
        unit { lib, h -> lib.monero_daemon_stop(h) }
    }

    // ------------------------------- CLOSING ------------------------------------

    /**
     * Release the native handle. The calls that are waiting run first, and the threads that wait for a block header are
     * released with an error. It is closed once.
     */
    override fun close() {
        val released: List<CompletableFuture<MoneroBlockHeader>>
        synchronized(lock) {
            if (closed) return
            closed = true
            released = ArrayList(waiters)
            waiters.clear()
            listeners.clear()
        }
        for (waiter in released) waiter.completeExceptionally(MoneroError("The daemon is closed"))

        // the calls that were in the queue run first, then the listener goes, then the handle
        worker.call {
            if (nativeListenerAdded) {
                try {
                    MoneroNative.lib.monero_daemon_remove_listener(handle, events!!.handle)
                } catch (_: Throwable) {
                    // the daemon is going away anyway
                }
            }
        }
        events?.close()
        worker.close()
        MoneroNative.lib.monero_daemon_free(handle)
    }

    // ------------------------------- PRIVATE ------------------------------------

    private fun <T> run(call: (MoneroC, Pointer) -> T): T {
        return worker.call { call(MoneroNative.lib, handle) }
    }

    private fun unit(call: (MoneroC, Pointer) -> Int) {
        run { lib, h -> MoneroNative.ok(call(lib, h)) }
    }

    private inline fun <reified T> json(crossinline call: (MoneroC, Pointer, PointerByReference) -> Int): T {
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(call(lib, h, out), out, object : TypeReference<T>() {})
        }
    }

    private fun text(call: (MoneroC, Pointer, PointerByReference) -> Int): String? {
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(call(lib, h, out), out)
        }
    }

    private fun limit(call: (MoneroC, Pointer, IntByReference) -> Int): Int {
        return run { lib, h ->
            val out = IntByReference()
            MoneroNative.ok(call(lib, h, out))
            out.value
        }
    }

    // the txs of a query by hashes come in the order of the blocks: sort them by the hashes that were asked for
    private fun sortByHashes(txs: List<MoneroTx>, hashes: Collection<String>): List<MoneroTx> {
        val position = HashMap<String, Int>()
        hashes.forEachIndexed { i, hash -> position.putIfAbsent(hash, i) }
        return txs.sortedBy { tx -> tx.hash?.let { position[it] } ?: Int.MAX_VALUE }
    }

    private fun optional(value: Long?): LongByReference? = value?.let { LongByReference(it) }

    private fun optional(value: Int?): IntByReference? = value?.let { IntByReference(it) }

    private fun optional(value: Boolean?): ByteByReference? = value?.let { ByteByReference(if (it) 1 else 0) }
}
