package monero.wallet

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.daemon.model.MoneroVersion
import monero.internal.MoneroC
import monero.internal.MoneroNative
import monero.internal.NativeWorker
import monero.internal.SizeT
import monero.wallet.model.MoneroAccount
import monero.wallet.model.MoneroIntegratedAddress
import monero.wallet.model.MoneroMessageSignatureResult
import monero.wallet.model.MoneroMessageSignatureType
import monero.wallet.model.MoneroSubaddress
import monero.wallet.model.MoneroSyncResult
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxSetsResult
import monero.wallet.model.MoneroTxWallet
import monero.wallet.model.MoneroMultisigInfo
import monero.wallet.model.MoneroMultisigInitResult
import monero.wallet.model.MoneroMultisigSignResult
import monero.wallet.model.MoneroWalletBlocksResult
import monero.wallet.model.MoneroWalletConfig

/**
 * Implements a full Monero wallet: the keys and the addresses of an account, the chain of a daemon, and its own wallet
 * files on disk. It is the full wallet of monero-cpp behind the C ABI of monero-c, created with one of its three
 * constructors or from a config (monero_c_wallet.h:144,159,176,205), and opened from its files
 * (monero_c_wallet.h:216). Close it to release the native handle.
 *
 * The wallet files hold the chain that the wallet has scanned: [save] writes them, [close] with a save writes them
 * before closing, and a wallet that is opened again reads its chain from them, which is why it does not have to scan
 * the chain again. A wallet that a config creates without a path stays in memory. The daemon connection is not held
 * in the files, so it is set on the wallet every time that it is opened, which it can be at any time
 * (monero_c_wallet.h:925).
 *
 * The calls of a wallet run one after the other on a thread of their own, since the C ABI allows one thread per wallet
 * handle (monero_c_wallet.h:49-53).
 */
class MoneroWalletFull private constructor(private val handle: Pointer) : AutoCloseable {

    private val worker = NativeWorker("MoneroWalletFull")
    // volatile: requestShutdown reads it from any thread, and the read/write lock below orders that read against
    // the free of the handle, which the threading contract of the c abi allows no second thread on
    @Volatile
    private var closed = false

    private val shutdownLock = ReentrantReadWriteLock()

    // ------------------------------- KEYS, SEED AND ADDRESSES ----------------------------------

    /**
     * Get the mnemonic seed of the wallet.
     *
     * @return the 25 words of the seed
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no seed (monero_c_wallet.h:342)
     */
    fun getSeed(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_seed(h, out) }
    }

    /**
     * Get the language that the seed of the wallet is written in.
     *
     * @return the name of the language
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no seed (monero_c_wallet.h:351)
     */
    fun getSeedLanguage(): String {
        return string { lib, h, out -> lib.monero_wallet_get_seed_language(h, out) }
    }

    /**
     * Get the languages that a seed can be written in. The call needs no wallet, since monero-c has the list of the
     * languages itself (monero_c_wallet.h:232).
     *
     * @return the names of the languages
     * @throws MoneroError if the native library fails
     */
    fun getSeedLanguages(): List<String> {
        checkNotClosed()
        return run { lib, _ ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_get_seed_languages(out), out)
        }
    }

    /**
     * Get the private view key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:369)
     */
    fun getPrivateViewKey(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_private_view_key(h, out) }
    }

    /**
     * Get the private spend key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no spend key (monero_c_wallet.h:378)
     */
    fun getPrivateSpendKey(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_private_spend_key(h, out) }
    }

    /**
     * Get the public view key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:754)
     */
    fun getPublicViewKey(): String {
        return string { lib, h, out -> lib.monero_wallet_get_public_view_key(h, out) }
    }

    /**
     * Get the public spend key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:763)
     */
    fun getPublicSpendKey(): String {
        return string { lib, h, out -> lib.monero_wallet_get_public_spend_key(h, out) }
    }

    /**
     * Get the primary address of the wallet, which is the address of account 0 and subaddress 0.
     *
     * @return the primary address
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:360)
     */
    fun getPrimaryAddress(): String {
        return string { lib, h, out -> lib.monero_wallet_get_primary_address(h, out) }
    }

    /**
     * Get the address of an account and subaddress.
     *
     * @param accountIdx is the account index
     * @param subaddressIdx is the subaddress index within the account
     * @return the address
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:470)
     */
    fun getAddress(accountIdx: Int, subaddressIdx: Int): String {
        return string { lib, h, out -> lib.monero_wallet_get_address(h, accountIdx, subaddressIdx, out) }
    }

    // ------------------------------- ACCOUNTS AND SUBADDRESSES ----------------------------------

    /**
     * Get an account of the wallet, with its subaddresses when they are asked for.
     *
     * @param accountIdx is the account index
     * @param includeSubaddresses is whether to include the subaddresses of the account
     * @return the account
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:502)
     */
    fun getAccount(accountIdx: Int, includeSubaddresses: Boolean = false): MoneroAccount {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_account(h, accountIdx, includeSubaddresses, out) }
    }

    /**
     * Get a subaddress of an account. The C ABI has a function for it that a full wallet does not answer
     * (monero_c_wallet.h:648), so this reads the subaddress through the plural call.
     *
     * @param accountIdx is the account index
     * @param subaddressIdx is the subaddress index within the account
     * @return the subaddress
     * @throws MoneroError if the wallet is closed
     */
    fun getSubaddress(accountIdx: Int, subaddressIdx: Int): MoneroSubaddress {
        return getSubaddresses(accountIdx, listOf(subaddressIdx))[0]
    }

    /**
     * Get the subaddresses of an account at the given indices, or all of them when no indices are given.
     *
     * @param accountIdx is the account index
     * @param subaddressIndices are the subaddress indices within the account, or null for all of them
     * @return the subaddresses, in the order of the indices
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:515)
     */
    fun getSubaddresses(accountIdx: Int, subaddressIndices: List<Int>?): List<MoneroSubaddress> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val indices = subaddressIndices?.toIntArray()
            MoneroNative.json(lib.monero_wallet_get_subaddresses(h, accountIdx, indices, SizeT((indices?.size ?: 0).toLong()), out), out, object : TypeReference<List<MoneroSubaddress>>() {})
        }
    }

    // ------------------------------- INTEGRATED ADDRESSES ----------------------------------

    /**
     * Get the integrated address of a standard address and a payment ID.
     *
     * @param standardAddress is the standard address, or null or "" for the primary address of the wallet
     * @param paymentId is the payment ID in hexadecimal, or null or "" for a random one
     * @return the integrated address
     * @throws MoneroError if the wallet is closed, the address is not a standard one of the network, or the payment
     * ID is not a payment ID (monero_c_wallet.h:481)
     */
    fun getIntegratedAddress(standardAddress: String? = null, paymentId: String? = null): MoneroIntegratedAddress {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_integrated_address(h, standardAddress, paymentId, out) }
    }

    /**
     * Decode an integrated address into its standard address and its payment ID.
     *
     * @param integratedAddress is the integrated address
     * @return the integrated address with its standard address and its payment ID
     * @throws MoneroError if the wallet is closed, or the address is not an integrated address of the network
     * (monero_c_wallet.h:491)
     */
    fun decodeIntegratedAddress(integratedAddress: String): MoneroIntegratedAddress {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_decode_integrated_address(h, integratedAddress, out) }
    }

    // ------------------------------- MESSAGES AND PAYMENT URIS ----------------------------------

    /**
     * Sign a message with the spend key or the view key of an account and subaddress.
     *
     * @param message is the message to sign
     * @param signatureType is the key that signs the message
     * @param accountIdx is the account index
     * @param subaddressIdx is the subaddress index within the account
     * @return the signature
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:528)
     */
    fun signMessage(message: String, signatureType: MoneroMessageSignatureType, accountIdx: Int, subaddressIdx: Int): String {
        checkNotClosed()
        return string { lib, h, out -> lib.monero_wallet_sign_message(h, message, signatureType.ordinal, accountIdx, subaddressIdx, out) }
    }

    /**
     * Verify a signature that [signMessage] made for an address. A signature that does not match is not an error: the
     * result says it is not good (monero_c_wallet.h:541).
     *
     * @param message is the signed message
     * @param address is the address that signed the message
     * @param signature is the signature
     * @return the result of the verification
     * @throws MoneroError if the wallet is closed, or the address is not an address of the network
     */
    fun verifyMessage(message: String, address: String, signature: String): MoneroMessageSignatureResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_verify_message(h, message, address, signature, out) }
    }

    /**
     * Convert a transaction config to a payment URI.
     *
     * @param config is the transaction config, whose first destination holds the address and the amount
     * @return the payment URI
     * @throws MoneroError if the wallet is closed, or the config has no address to build the URI from
     * (monero_c_wallet.h:1326)
     */
    fun getPaymentUri(config: MoneroTxConfig): String {
        checkNotClosed()
        return string { lib, h, out -> lib.monero_wallet_get_payment_uri(h, MoneroJson.serialize(config), out) }
    }

    /**
     * Parse a payment URI into a transaction config.
     *
     * @param uri is the payment URI
     * @return the transaction config that the URI holds
     * @throws MoneroError if the wallet is closed, or the URI is not a payment URI (monero_c_wallet.h:1336)
     */
    fun parsePaymentUri(uri: String): MoneroTxConfig {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_parse_payment_uri(h, uri, out) }
    }

    // ------------------------------- SYNC AND THE CHAIN ----------------------------------

    /**
     * Synchronize the wallet with its daemon in the calling thread (monero_c_wallet.h:1002).
     *
     * @param startHeight is the height to sync from, ignored when it is less than the height that the wallet has
     * already scanned
     * @return the result of the sync
     * @throws MoneroError if the wallet is closed, or it is not connected to the daemon
     */
    fun sync(startHeight: Long? = null): MoneroSyncResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_sync(h, startHeight?.let { LongByReference(it) }, null, out) }
    }

    /**
     * Start a thread of the wallet that syncs it with its daemon, over and over. Stop it with [stopSyncing]
     * (monero_c_wallet.h:1011).
     *
     * @param syncPeriodMs is the most time that the wallet waits between two syncs, in milliseconds
     * @throws MoneroError if the wallet is closed
     */
    fun startSyncing(syncPeriodMs: Long) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_start_syncing(h, syncPeriodMs)) }
    }

    /**
     * Stop the thread of the wallet that syncs it (monero_c_wallet.h:1019).
     *
     * @throws MoneroError if the wallet is closed
     */
    /**
     * Wait until the next block is added to the chain, for at most a number of milliseconds (monero_c_wallet.h:1104).
     * The wallet must be syncing in the background, and a caller that waits in a loop can be cancelled between the
     * waits, which the wait without a timeout can not.
     *
     * @param timeoutMs is the longest time to wait, from 1 to 2147483647
     * @return the height of the block, or null if no block arrived in the time
     * @throws MoneroError if the wallet is closed, or it can not be listened to
     */
    fun waitForNextBlock(timeoutMs: Int): Long? {
        checkNotClosed()
        return run { lib, h ->
            val height = LongByReference()
            val timedOut = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_wait_for_next_block_timeout(h, timeoutMs, height, timedOut))
            if (timedOut.value.toInt() != 0) null else height.value
        }
    }

    fun stopSyncing() {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_stop_syncing(h)) }
    }

    /**
     * Check if the wallet is connected to a daemon.
     *
     * @return true if the wallet is connected, false otherwise
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:1028)
     */
    fun isConnectedToDaemon(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val connected = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_connected_to_daemon(h, connected))
            connected.value.toInt() != 0
        }
    }

    /**
     * Check if the daemon of the wallet is synced with the network.
     *
     * @return true if the daemon is synced, false otherwise
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:1037)
     */
    fun isDaemonSynced(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val synced = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_daemon_synced(h, synced))
            synced.value.toInt() != 0
        }
    }

    /**
     * Check if the wallet is synced with its daemon.
     *
     * @return true if the wallet is synced, false otherwise
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:1046)
     */
    fun isSynced(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val synced = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_synced(h, synced))
            synced.value.toInt() != 0
        }
    }

    /**
     * Get the height that the wallet has scanned to, which is the next block to scan (monero_c_wallet.h:387).
     *
     * @return the height
     * @throws MoneroError if the wallet is closed
     */
    fun getHeight(): Long {
        checkNotClosed()
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_height(h, height))
            height.value
        }
    }

    /**
     * Get the height that the daemon of the wallet is synced to (monero_c_wallet.h:1055).
     *
     * @return the height
     * @throws MoneroError if the wallet is closed, or it is not connected to the daemon
     */
    fun getDaemonHeight(): Long {
        checkNotClosed()
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_daemon_height(h, height))
            height.value
        }
    }

    /**
     * Get the balance of the wallet, including the funds that are still locked (monero_c_wallet.h:397).
     *
     * @return the balance in atomic units
     * @throws MoneroError if the wallet is closed
     */
    fun getBalance(): BigInteger {
        checkNotClosed()
        return run { lib, h ->
            val balance = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_balance(h, balance))
            MoneroNative.unsigned(balance.value)
        }
    }

    /**
     * Get the unlocked balance of the wallet, which is the balance without the funds that are still locked
     * (monero_c_wallet.h:405).
     *
     * @return the unlocked balance in atomic units
     * @throws MoneroError if the wallet is closed
     */
    fun getUnlockedBalance(): BigInteger {
        checkNotClosed()
        return run { lib, h ->
            val balance = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_unlocked_balance(h, balance))
            MoneroNative.unsigned(balance.value)
        }
    }

    /**
     * Get the transactions of the wallet (monero_c_wallet.h:1162). A block of the answer holds its miner
     * transaction twice, in its transactions and as its miner transaction, which is the block model of monero-cpp:
     * this flattens the transactions of the blocks.
     *
     * @param query is the query that the transactions have to match, or null for all of them
     * @return the transactions of the wallet
     * @throws MoneroError if the wallet is closed
     */
    fun getTxs(query: MoneroTxQuery? = null): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.json(lib.monero_wallet_get_txs(h, query?.let { MoneroJson.serialize(it) }, out), out, MoneroWalletBlocksResult::class.java)
            result.getTxs()
        }
    }

    // ------------------------------- SEND ----------------------------------

    /**
     * Create one or more transactions that transfer funds from the wallet, through the RingCT construction of
     * monero-cpp. The transactions are relayed only when the config says so: a transaction that is not relayed
     * carries the metadata that [relayTx] takes (monero_c_wallet.h:1203).
     *
     * @param config is the configuration of the transfer, which names the account to send from and the
     * destinations, and says whether to relay
     * @return the transactions that the config makes, with their fee and their metadata
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, the configuration is invalid,
     * or the wallet has less unlocked money than the transfer and its fee need
     */
    fun createTxs(config: MoneroTxConfig): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.json(lib.monero_wallet_create_txs(h, MoneroJson.serialize(config), out), out, MoneroTxSetsResult::class.java)
            result.getTxs()
        }
    }

    /**
     * Relay a transaction that [createTxs] made without relaying, from its metadata (monero_c_wallet.h:1213).
     *
     * @param txMetadata is the metadata of the transaction, which a transaction of [createTxs] carries
     * @return the hash of the relayed transaction
     * @throws MoneroError if the wallet is closed, the metadata is not the metadata of a transaction, or the daemon
     * refuses the transaction
     */
    fun relayTx(txMetadata: String): String {
        return string { lib, h, out -> lib.monero_wallet_relay_tx(h, txMetadata, out) }
    }

    /**
     * Relay a transaction that [createTxs] made without relaying, from the transaction itself: the C ABI reads the
     * metadata of the JSON of the transaction (monero_c_wallet.h:1224).
     *
     * @param tx is the transaction to relay, which [createTxs] returned
     * @return the hash of the relayed transaction
     * @throws MoneroError if the wallet is closed, the transaction has no metadata, or the daemon refuses the
     * transaction
     */
    fun relayTx(tx: MoneroTxWallet): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_wallet_relay_tx_json(h, MoneroJson.serialize(tx), out), out) ?: throw MoneroError("monero-c returned no hash")
        }
    }

    // ------------------------------- THE DAEMON ----------------------------------

    /**
     * Set the connection of the daemon of the wallet, which it can be at any time. The connection is not held in the
     * wallet files, so it is set again on a wallet that is opened. A connection with no URI, or null, disconnects
     * the wallet, and a connection that names no trust is trusted when its address is local, which the others are
     * not (monero_c_wallet.h:925).
     *
     * @param connection is the connection to the daemon, or null to disconnect it
     * @param isTrusted is whether the daemon is trusted for spend results, or null to trust a daemon on a local
     * address only, which is the default of wallet2
     * @throws MoneroError if the wallet is closed
     */
    fun setDaemonConnection(connection: MoneroRpcConnection?, isTrusted: Boolean? = null) {
        checkNotClosed()
        run { lib, h ->
            val uri = connection?.uri ?: ""
            val username = connection?.username ?: ""
            val password = connection?.password ?: ""
            val proxyUri = connection?.proxyUri ?: ""
            MoneroNative.ok(lib.monero_wallet_set_daemon_connection(h, uri, username, password, proxyUri, isTrusted?.let { ByteByReference().apply { value = if (it) 1 else 0 } }, connection?.sslVerify ?: true))
        }
    }

    /**
     * Get the connection of the daemon of the wallet (monero_c_wallet.h:934).
     *
     * @return the connection, or null if the wallet is not connected to a daemon
     * @throws MoneroError if the wallet is closed
     */
    fun getDaemonConnection(): MoneroRpcConnection? {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val json = MoneroNative.text(lib.monero_wallet_get_daemon_connection(h, out), out)
                ?: return@run null
            MoneroJson.deserialize(json, MoneroRpcConnection::class.java)
        }
    }

    /**
     * Check if the daemon of the wallet is trusted.
     *
     * @return true if the daemon is trusted, false otherwise
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:943)
     */
    fun isDaemonTrusted(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val trusted = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_daemon_trusted(h, trusted))
            trusted.value.toInt() != 0
        }
    }

    // ------------------------------- STATE ----------------------------------

    /**
     * Get the network of the wallet.
     *
     * @return the network type
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:314)
     */
    fun getNetworkType(): MoneroNetworkType {
        checkNotClosed()
        return run { lib, h ->
            val networkType = IntByReference()
            MoneroNative.ok(lib.monero_wallet_get_network_type(h, networkType))
            MoneroNetworkType.values()[networkType.value]
        }
    }

    /**
     * Check if the wallet is view-only, which means it has no private spend key.
     *
     * @return true if the wallet is view-only, false otherwise
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:323)
     */
    fun isViewOnly(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val viewOnly = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_view_only(h, viewOnly))
            viewOnly.value.toInt() != 0
        }
    }

    /**
     * Get the version of the wallet library.
     *
     * @return the version
     * @throws MoneroError if the wallet is closed (monero_c_wallet.h:772)
     */
    fun getVersion(): MoneroVersion {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_version(h, out) }
    }

    /**
     * Get the path of the wallet file, which the wallet was created with, or "" when it stays in memory
     * (monero_c_wallet.h:746).
     *
     * @return the path
     * @throws MoneroError if the wallet is closed
     */
    fun getPath(): String {
        return string { lib, h, out -> lib.monero_wallet_get_path(h, out) }
    }

    /**
     * Get the height that the wallet scans from when it is created or restored, which is the first block that it
     * scans (monero_c_wallet.h:782).
     *
     * @return the restore height
     * @throws MoneroError if the wallet is closed
     */
    fun getRestoreHeight(): Long {
        checkNotClosed()
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_restore_height(h, height))
            height.value
        }
    }

    /**
     * Set the height that the wallet scans from, which it does before its next scan: a height that it has already
     * scanned makes it scan the chain from it again (monero_c_wallet.h:791).
     *
     * @param restoreHeight is the height to scan from
     * @throws MoneroError if the wallet is closed
     */
    fun setRestoreHeight(restoreHeight: Long) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_restore_height(h, restoreHeight)) }
    }

    /**
     * Change the password of the wallet files, which are written with it, and which the wallet opens with from then
     * on (monero_c_wallet.h:604).
     *
     * @param oldPassword is the password of the files, which has to be the one that they are written with
     * @param newPassword is the password to write the files with
     * @throws MoneroError if the wallet is closed, or the old password is not the one that the files are written with
     */
    fun changePassword(oldPassword: String, newPassword: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_change_password(h, oldPassword, newPassword)) }
    }

    /**
     * Write the wallet to its files, which a wallet that stays in memory has none of (monero_c_wallet.h:277).
     *
     * @throws MoneroError if the wallet is closed, or it has no path to write to
     */
    fun save() {
        checkNotClosed()
        requirePath()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_save(h)) }
    }

    /**
     * Check if the wallet is closed.
     *
     * @return true if the wallet is closed, false otherwise
     */
    fun isClosed(): Boolean {
        return closed
    }

    /**
     * Close the wallet, writing it to its files first when a save is asked for. Every call of a closed wallet fails
     * with "Wallet is closed", and closing it again does nothing (monero_c_wallet.h:286).
     *
     * @param save is whether to save the wallet before closing it
     * @throws MoneroError if the wallet is closed, or a save is asked for and the wallet has no path to write to
     */
    @Synchronized
    fun close(save: Boolean) {
        if (closed) return
        if (save) requirePath()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_close(h, save)) }
        closed = true

        // the write lock holds requestShutdown out while the handle dies: its read lock around the native call is
        // what keeps the one any-thread call off a freed handle, since the worker can not order it (a sync that is
        // stuck on the worker is what it exists to stop)
        shutdownLock.writeLock().lock()
        try {
            worker.close()
            MoneroNative.lib.monero_wallet_free(handle)
        } finally {
            shutdownLock.writeLock().unlock()
        }
    }

    override fun close() {
        close(false)
    }

    // ------------------------------- MULTISIG ------------------------------------

    /**
     * Check if the wallet is a multisig wallet (monero_c_wallet.h:334).
     *
     * @return true if the wallet is multisig, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    fun isMultisig(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val multisig = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_multisig(h, multisig))
            multisig.value.toInt() != 0
        }
    }

    /**
     * Check if the wallet needs the multisig hexes of its participants to show the outputs that it received after the
     * last import (monero_c_wallet.h:1453).
     *
     * @return true if an import is needed, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    fun isMultisigImportNeeded(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val needed = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_multisig_import_needed(h, needed))
            needed.value.toInt() != 0
        }
    }

    /**
     * Get the multisig information of the wallet, which a wallet that is not multisig carries without its threshold
     * and its number of participants (monero_c_wallet.h:1462).
     *
     * @return the multisig information
     * @throws MoneroError if the wallet is closed
     */
    fun getMultisigInfo(): MoneroMultisigInfo {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(lib.monero_wallet_get_multisig_info(h, out), out, MoneroMultisigInfo::class.java)
        }
    }

    /**
     * Start a multisig wallet from this wallet, which returns the hex that the other participants make their wallets
     * multisig with. The wallet stays multisig-in-progress until enough key exchanges finalize it
     * (monero_c_wallet.h:1471).
     *
     * @return the multisig hex of this wallet
     * @throws MoneroError if the wallet is closed, or it is not a wallet that a multisig can start from
     */
    fun prepareMultisig(): String {
        checkNotClosed()
        return string { lib, h, out -> lib.monero_wallet_prepare_multisig(h, out) }
    }

    /**
     * Make this wallet a part of a multisig wallet from the prepared hexes of the other participants, which returns
     * the hex that the key exchanges of a threshold below the number of participants carry (monero_c_wallet.h:1484).
     *
     * @param multisigHexes are the prepared hexes of the other participants
     * @param threshold is the number of participants that a transfer needs the signature of
     * @param password encrypts the wallet files
     * @return the multisig hex of this wallet for the key exchanges
     * @throws MoneroError if the wallet is closed, or a hex is not a prepared multisig hex
     */
    fun makeMultisig(multisigHexes: List<String>, threshold: Int, password: String): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_wallet_make_multisig(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), threshold, password, out), out)
                ?: throw MoneroError("monero-c returned no string")
        }
    }

    /**
     * Exchange the multisig keys of the participants, which a threshold below the number of participants needs one or
     * more rounds of before the wallet is final (monero_c_wallet.h:1497). The last round returns the address of the
     * multisig wallet.
     *
     * @param multisigHexes are the hexes of the other participants
     * @param password encrypts the wallet files
     * @return the state of the wallet after the exchange, which carries the address once the wallet is final
     * @throws MoneroError if the wallet is closed, or a hex is not a multisig hex of this round
     */
    fun exchangeMultisigKeys(multisigHexes: List<String>, password: String): MoneroMultisigInitResult {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(lib.monero_wallet_exchange_multisig_keys(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), password, out), out, MoneroMultisigInitResult::class.java)
        }
    }

    /**
     * Export the multisig hex of this wallet, which the other participants import to see the outputs that it knows
     * (monero_c_wallet.h:1506).
     *
     * @return the multisig hex of this wallet
     * @throws MoneroError if the wallet is closed, or the wallet is not a final multisig wallet
     */
    fun exportMultisigHex(): String {
        checkNotClosed()
        return string { lib, h, out -> lib.monero_wallet_export_multisig_hex(h, out) }
    }

    /**
     * Import the multisig hexes of the other participants, which shows this wallet the outputs that they know
     * (monero_c_wallet.h:1518).
     *
     * @param multisigHexes are the hexes of the other participants
     * @param refreshAfterImport is whether to sync with the daemon after the import
     * @return the number of outputs that the import showed
     * @throws MoneroError if the wallet is closed, or a hex is not a multisig hex
     */
    fun importMultisigHex(multisigHexes: List<String>, refreshAfterImport: Boolean): Int {
        checkNotClosed()
        return run { lib, h ->
            val numImported = IntByReference()
            MoneroNative.ok(lib.monero_wallet_import_multisig_hex(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), refreshAfterImport, numImported))
            numImported.value
        }
    }

    /**
     * Sign a multisig transaction from its hex, which a transfer between the participants of a threshold wallet makes
     * (monero_c_wallet.h:1528). The signatures of enough participants turn the hex into a signed one.
     *
     * @param multisigTxHex is the hex of the transaction to sign
     * @return the signed hex once enough participants signed, and the hashes of the transaction
     * @throws MoneroError if the wallet is closed, the hex is not a multisig transaction, or the wallet already
     * signed it
     */
    fun signMultisigTxHex(multisigTxHex: String): MoneroMultisigSignResult {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(lib.monero_wallet_sign_multisig_tx_hex(h, multisigTxHex, out), out, MoneroMultisigSignResult::class.java)
        }
    }

    /**
     * Submit a signed multisig transaction to the network (monero_c_wallet.h:1538).
     *
     * @param signedMultisigTxHex is the hex of the signed transaction
     * @return the hashes of the transaction
     * @throws MoneroError if the wallet is closed, the hex is not a signed multisig transaction, or the network
     * refuses it
     */
    fun submitMultisigTxHex(signedMultisigTxHex: String): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_submit_multisig_tx_hex(h, signedMultisigTxHex, out), out)
        }
    }

    /**
     * Ask a running sync or a background sync of the wallet to stop, which is the one call that any thread can make
     * on a wallet: the others run one after the other on the thread of the wallet, and a sync that is running on it
     * would never let them (monero_c_wallet.h:298).
     *
     * @throws MoneroError if the wallet is closed
     */
    fun requestShutdown() {
        checkNotClosed()
        shutdownLock.readLock().lock()
        try {
            if (!closed) MoneroNative.ok(MoneroNative.lib.monero_wallet_request_shutdown(handle))
        } finally {
            shutdownLock.readLock().unlock()
        }
    }

    // ------------------------------- PRIVATE ------------------------------------

    private fun checkNotClosed() {
        if (closed) throw MoneroError("Wallet is closed")
    }

    // wallet2 silently skips the save of a wallet that stays in memory, which loses the wallet when it closes
    private fun requirePath() {
        if (getPath().isEmpty()) throw MoneroError("The wallet has no path to write to")
    }

    private fun <T> run(call: (MoneroC, Pointer) -> T): T {
        return worker.call { call(MoneroNative.lib, handle) }
    }

    private inline fun <reified T> json(crossinline call: (MoneroC, Pointer, PointerByReference) -> Int): T {
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(call(lib, h, out), out, object : TypeReference<T>() {})
        }
    }

    private fun string(call: (MoneroC, Pointer, PointerByReference) -> Int): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(call(lib, h, out), out) ?: throw MoneroError("monero-c returned no string")
        }
    }

    /**
     * Call the c abi for a secret of the wallet, which it wipes when it frees the string: the seed and the private
     * keys.
     */
    private fun secret(call: (MoneroC, Pointer, PointerByReference) -> Int): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.secret(call(lib, h, out), out) ?: throw MoneroError("monero-c returned no string")
        }
    }

    companion object {

        /**
         * The language that a wallet writes its seed in when none is given.
         */
        const val DEFAULT_LANGUAGE = "English"

        /**
         * Create a wallet with a new random seed, and write it to its files (monero_c_wallet.h:144). The keys are
         * written to path + ".keys".
         *
         * @param config is the configuration of the wallet, which needs its path and its network type, and can name
         * the password of the files and the language of the seed
         * @return the new wallet
         * @throws MoneroError if the configuration has no path or no network type, or its language is not a language
         * of a seed
         */
        fun createWalletRandom(config: MoneroWalletConfig): MoneroWalletFull {
            val path = config.path ?: throw MoneroError("Must provide wallet path")
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")
            return wallet { lib, out -> lib.monero_wallet_create_random(path, config.password, networkType.ordinal, config.language, out) }
        }

        /**
         * Restore a wallet from its mnemonic seed, and write it to its files (monero_c_wallet.h:159).
         *
         * @param config is the configuration of the wallet, which needs its path, its network type and its seed, and
         * can give the seed an offset, which is a passphrase that extends the seed, name the password of the files
         * and the language of the seed, and give the restore height, which is the first block to scan
         * @return the restored wallet
         * @throws MoneroError if the configuration has no path, no network type or no seed, or its seed is not a
         * mnemonic, or its language is not a language of a seed
         */
        fun createWalletFromSeed(config: MoneroWalletConfig): MoneroWalletFull {
            val path = config.path ?: throw MoneroError("Must provide wallet path")
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")
            val seed = config.seed ?: throw MoneroError("Must provide wallet seed")
            return wallet { lib, out -> lib.monero_wallet_create_from_seed(path, config.password, networkType.ordinal, seed, config.seedOffset, config.restoreHeight ?: 0, config.language, out) }
        }

        /**
         * Restore a wallet from its keys, and write it to its files (monero_c_wallet.h:176). Without a private spend
         * key the wallet is view-only.
         *
         * @param config is the configuration of the wallet, which needs its path, its network type and its keys,
         * and can name the password of the files and the language of the seed, and give the restore height, which is
         * the first block to scan
         * @return the restored wallet
         * @throws MoneroError if the configuration has no path or no network type, no key to restore from (which the
         * wrapper answers as a random wallet with a restore height, "Cannot specify restore height when creating
         * random wallet"), a private view key without the address, or keys that do not match the address
         */
        fun createWalletFromKeys(config: MoneroWalletConfig): MoneroWalletFull {
            val path = config.path ?: throw MoneroError("Must provide wallet path")
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")

            // the C ABI takes the address and the keys as strings that are never null, empty when they are unused,
            // which the full wallet of monero-cpp reads as absent (monero_c_wallet.cpp:143,611-619)
            val address = config.primaryAddress ?: ""
            val privateViewKey = config.privateViewKey ?: ""
            val privateSpendKey = config.privateSpendKey ?: ""
            return wallet { lib, out -> lib.monero_wallet_create_from_keys(path, config.password, networkType.ordinal, address, privateViewKey, privateSpendKey, config.restoreHeight ?: 0, config.language, out) }
        }

        /**
         * Create a wallet from a config, which the other constructors cannot: it can connect the wallet to a daemon
         * when it is created, set the trust in the daemon, scan more accounts and subaddresses than the 50 and 200 of
         * wallet2, restore a multisig wallet, and leave the wallet in memory, which it is without a path
         * (monero_c_wallet.h:205).
         *
         * @param config is the configuration of the wallet, which needs its network type
         * @return the new wallet
         * @throws MoneroError if the configuration is invalid: a seed and keys together fail, and so does a restore
         * height with neither of them, since a random wallet starts from the current height
         */
        fun createWallet(config: MoneroWalletConfig): MoneroWalletFull {
            requireNetworkType(config)
            return wallet { lib, out -> lib.monero_wallet_create(MoneroJson.serialize(config), out) }
        }

        /**
         * Open a wallet from its files on disk (monero_c_wallet.h:216).
         *
         * @param config is the configuration of the wallet, which needs its path and its network type, and can name
         * the password of the files
         * @return the opened wallet
         * @throws MoneroError if the configuration has no path or no network type, or no wallet is at the path
         */
        fun openWallet(config: MoneroWalletConfig): MoneroWalletFull {
            val path = config.path ?: throw MoneroError("Must provide wallet path")
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")
            return wallet { lib, out -> lib.monero_wallet_open(path, config.password, networkType.ordinal, out) }
        }

        /**
         * Check if a wallet is at a path. It looks for the keys file, path + ".keys" (monero_c_wallet.h:225).
         *
         * @param path is the path of the wallet file
         * @return true if the wallet exists, false otherwise
         * @throws MoneroError if the native library fails
         */
        fun walletExists(path: String): Boolean {
            val lib = MoneroNative.lib
            val exists = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_exists(path, exists))
            return exists.value.toInt() != 0
        }

        private fun requireNetworkType(config: MoneroWalletConfig) {
            if (config.networkType == null) throw MoneroError("Must provide wallet network type")
        }

        private fun wallet(create: (MoneroC, PointerByReference) -> Int): MoneroWalletFull {
            val lib = MoneroNative.lib
            val out = PointerByReference()
            MoneroNative.ok(create(lib, out))
            return MoneroWalletFull(out.value)
        }
    }
}
