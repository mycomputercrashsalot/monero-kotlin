package monero.wallet

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.math.BigInteger
import monero.common.MoneroError
import monero.common.MoneroJson
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.internal.MoneroC
import monero.internal.MoneroNative
import monero.internal.NativeWorker
import monero.wallet.model.MoneroSyncResult
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxSetsResult
import monero.wallet.model.MoneroTxWallet
import monero.wallet.model.MoneroWalletBlocksResult
import monero.wallet.model.MoneroWalletConfig

/**
 * Implements a light wallet: the keys and the addresses of an account, scanned through a
 * [monero-lws](https://github.com/vtnerd/monero-lws) server, which is why the wallet needs no monerod and no wallet
 * files of its own. It is the light wallet of monero-cpp behind the C ABI of monero-c, created or opened with the
 * connection of the server (monero_c_wallet.h:1764,1778). Close it to release the native handle.
 *
 * The wallet lives in memory: closing it with a save is an error, and a wallet that is opened again supplies the
 * restore height of its first scan, which no file keeps. The getters read the cache of the wallet, so a fresh handle
 * has to sync with the server before its balances.
 *
 * The calls of a wallet run one after the other on a thread of their own, since the C ABI allows one thread per
 * wallet handle (monero_c_wallet.h:49-53).
 */
class MoneroWalletLight private constructor(private val handle: Pointer) : AutoCloseable {

    private val worker = NativeWorker("MoneroWalletLight")
    private var closed = false

    // ------------------------------- KEYS, SEED AND ADDRESSES ----------------------------------

    /**
     * Get the mnemonic seed of the wallet.
     *
     * @return the 25 words of the seed
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no seed (monero_c_wallet.h:342)
     */
    fun getSeed(): String {
        return string { lib, h, out -> lib.monero_wallet_get_seed(h, out) }
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
        return string { lib, h, out -> lib.monero_wallet_get_private_view_key(h, out) }
    }

    /**
     * Get the private spend key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no spend key (monero_c_wallet.h:378)
     */
    fun getPrivateSpendKey(): String {
        return string { lib, h, out -> lib.monero_wallet_get_private_spend_key(h, out) }
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

    // ------------------------------- SYNC AND THE CHAIN ----------------------------------

    /**
     * Synchronize the wallet with its light wallet server in the calling thread (monero_c_wallet.h:1002).
     *
     * @param startHeight is the height to sync from, ignored when it is less than the height that the wallet has
     * already scanned
     * @return the result of the sync
     * @throws MoneroError if the wallet is closed, or it is not connected to the server
     */
    fun sync(startHeight: Long? = null): MoneroSyncResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_sync(h, startHeight?.let { LongByReference(it) }, null, out) }
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

    /**
     * Create one or more transactions that transfer funds from the wallet, through the RingCT construction of
     * monero-cpp. The transactions are relayed only when the config says so: a transaction that is not relayed
     * carries the metadata that [relayTx] takes (monero_c_wallet.h:1203). A light wallet server does not sync the
     * pool of its daemon, so a relayed transaction reaches the wallets when a block holds it.
     *
     * @param config is the configuration of the transfer, which names the account to send from and the
     * destinations, and says whether to relay
     * @return the transactions that the config makes, with their fee and their metadata
     * @throws MoneroError if the wallet is closed, it is not connected to the server, the configuration is invalid,
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
     * @throws MoneroError if the wallet is closed, the metadata is not the metadata of a transaction, or the server
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
     * @throws MoneroError if the wallet is closed, the transaction has no metadata, or the server refuses the
     * transaction
     */
    fun relayTx(tx: MoneroTxWallet): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(lib.monero_wallet_relay_tx_json(h, MoneroJson.serialize(tx), out), out) ?: throw MoneroError("monero-c returned no hash")
        }
    }

    /**
     * Get the number of candidate spends that the last sync could not verify, which is greater than zero only for a
     * view-only wallet without imported key images. While it is not zero, the balances are the ones of the server,
     * which the wallet can not refine (monero_c_wallet.h:1802).
     *
     * @return the number of unrefined spends
     * @throws MoneroError if the wallet is closed
     */
    fun getUnrefinedSpendCount(): Long {
        checkNotClosed()
        return run { lib, h ->
            val count = LongByReference()
            MoneroNative.ok(lib.monero_wallet_light_get_unrefined_spend_count(h, count))
            count.value
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
     * Check if the wallet is closed.
     *
     * @return true if the wallet is closed, false otherwise
     */
    fun isClosed(): Boolean {
        return closed
    }

    /**
     * Close the wallet, without saving it, since a light wallet has no files: closing it with a save is an error, and
     * the wallet stays open. Every call of a closed wallet fails with "Wallet is closed", and closing it again does
     * nothing (monero_c_wallet.h:285).
     *
     * @param save is whether to save the wallet before closing it, which must be false
     * @throws MoneroError if a save is asked for, which a light wallet does not support
     */
    fun close(save: Boolean) {
        if (closed) return
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_close(h, save)) }
        closed = true
        worker.close()
        MoneroNative.lib.monero_wallet_free(handle)
    }

    override fun close() {
        close(false)
    }

    // ------------------------------- PRIVATE ------------------------------------

    private fun checkNotClosed() {
        if (closed) throw MoneroError("Wallet is closed")
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

    companion object {

        /**
         * The language that a wallet writes its seed in when none is given.
         */
        const val DEFAULT_LANGUAGE = "English"

        /**
         * Create a light wallet, which registers the address with the server. A config with a seed restores the
         * wallet from the seed, a config with its keys restores it from them, and a config with neither creates a
         * wallet with a new random seed. A wallet that the server has is not created again
         * (monero_c_wallet.h:1764).
         *
         * @param config is the configuration of the wallet, which needs its network type and the keys or the seed to
         * restore, and can give the restore height and name the language of the seed
         * @param connection is the connection to the light wallet server
         * @return the new wallet
         * @throws MoneroError if the configuration is invalid, or the server already has the wallet
         */
        fun createWallet(config: MoneroWalletConfig, connection: MoneroRpcConnection): MoneroWalletLight {
            requireNetworkType(config)
            return wallet(config, connection) { lib, json, conn, out -> lib.monero_wallet_light_create(json, conn, out) }
        }

        /**
         * Open a light wallet from its light wallet server, which the server has to have: a config with a seed
         * restores the wallet from the seed, a config with its keys restores it from them, and a config with neither
         * fails. The wallet lives in memory, so supply the restore height of its first scan, which no file keeps
         * (monero_c_wallet.h:1778).
         *
         * @param config is the configuration of the wallet, which needs its network type and the keys or the seed to
         * restore
         * @param connection is the connection to the light wallet server
         * @return the opened wallet
         * @throws MoneroError if the configuration is invalid, or the server does not have the wallet
         */
        fun openWallet(config: MoneroWalletConfig, connection: MoneroRpcConnection): MoneroWalletLight {
            requireNetworkType(config)
            return wallet(config, connection) { lib, json, conn, out -> lib.monero_wallet_light_open(json, conn, out) }
        }

        /**
         * Check if a light wallet server has a wallet. The config needs a seed, or the primary address and the
         * private view key, and a seed is read as the address and the view key that it gives. A wallet that the
         * server does not have is not an error (monero_c_wallet.h:1790).
         *
         * @param config is the configuration of the wallet to look for
         * @param connection is the connection to the light wallet server
         * @return true if the server has the wallet, false otherwise
         * @throws MoneroError if the configuration is invalid, or the server does not answer
         */
        fun walletExists(config: MoneroWalletConfig, connection: MoneroRpcConnection): Boolean {
            requireNetworkType(config)
            val lib = MoneroNative.lib
            val conn = PointerByReference()
            MoneroNative.ok(lib.monero_rpc_connection_create(MoneroJson.serialize(connection), conn))
            try {
                val exists = ByteByReference()
                MoneroNative.ok(lib.monero_wallet_light_wallet_exists(MoneroJson.serialize(config), conn.value, exists))
                return exists.value.toInt() != 0
            } finally {
                lib.monero_rpc_connection_free(conn.value)
            }
        }

        private fun requireNetworkType(config: MoneroWalletConfig) {
            if (config.networkType == null) throw MoneroError("Must provide wallet network type")
        }

        // the connection handle is only read by the call, which the C ABI documents, so it is freed right after it
        private fun wallet(config: MoneroWalletConfig, connection: MoneroRpcConnection, call: (MoneroC, String, Pointer, PointerByReference) -> Int): MoneroWalletLight {
            val lib = MoneroNative.lib
            val json = MoneroJson.serialize(config)
            val conn = PointerByReference()
            MoneroNative.ok(lib.monero_rpc_connection_create(MoneroJson.serialize(connection), conn))
            val out = PointerByReference()
            try {
                MoneroNative.ok(call(lib, json, conn.value, out))
            } finally {
                lib.monero_rpc_connection_free(conn.value)
            }
            return MoneroWalletLight(out.value)
        }
    }
}
