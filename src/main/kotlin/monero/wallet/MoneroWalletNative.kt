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
import monero.internal.WalletEvents
import monero.wallet.model.MoneroAccount
import monero.wallet.model.MoneroAddressBookEntry
import monero.wallet.model.MoneroCheckReserve
import monero.wallet.model.MoneroCheckTx
import monero.daemon.model.MoneroKeyImage
import monero.wallet.model.MoneroKeyImageExportResult
import monero.wallet.model.MoneroKeyImageImportResult
import monero.wallet.model.MoneroOutputQuery
import monero.wallet.model.MoneroOutputWallet
import monero.wallet.model.MoneroIntegratedAddress
import monero.wallet.model.MoneroMultisigInfo
import monero.wallet.model.MoneroMultisigInitResult
import monero.wallet.model.MoneroMultisigSignResult
import monero.wallet.model.MoneroMessageSignatureResult
import monero.wallet.model.MoneroMessageSignatureType
import monero.wallet.model.MoneroSubaddress
import monero.wallet.model.MoneroSyncResult
import monero.wallet.model.MoneroWalletListener
import monero.wallet.model.MoneroTransfer
import monero.wallet.model.MoneroTransferQuery
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxPriority
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxSet
import monero.wallet.model.MoneroTxSetsResult
import monero.wallet.model.MoneroTxWallet
import monero.wallet.model.MoneroWalletBlocksResult

/**
 * What a wallet of monero-c that has a handle shares, the full wallet and the wallet of a monero-wallet-rpc server: the keys and the
 * addresses, the accounts, the transactions, the outputs, the proofs, the address book, the listeners, the sync, the multisig and
 * the rest of the functions of monero-c that take the handle of a wallet. The wallets differ in how they are created and opened, and
 * in a few functions that each of them answers in its own way, which they write.
 *
 * The calls of a wallet run one after the other on a thread of their own, since the C ABI allows one thread per wallet
 * handle. [requestShutdown] is the one call that any thread can make, to stop a sync that is running. Close the wallet to release
 * the native handle.
 *
 * @param handle is the native handle of the wallet
 * @param workerName is the name of the thread of the calls of the wallet
 */
abstract class MoneroWalletNative internal constructor(internal val handle: Pointer, workerName: String) : MoneroWalletDefault(), AutoCloseable {

    private val worker = NativeWorker(workerName, "Wallet is closed")

    // volatile: requestShutdown reads it from any thread, and the read/write lock below orders that read against the
    // free of the handle, which the threading contract of the c abi allows no second thread on
    @Volatile
    private var closed = false

    private val shutdownLock = ReentrantReadWriteLock()

    // the listeners that were added, with the native listener of each: the lock of the map orders the adds and the removes of the
    // callers, and close frees what is left
    private val listeners = LinkedHashMap<MoneroWalletListener, WalletEvents>()

    // ------------------------------- LISTENERS ----------------------------------

    /**
     * Register a listener, which is notified of the sync of the wallet, of its new blocks, of the changes of its balances and of the
     * outputs that it receives and spends, one notification after the other and in order. They are notified on a thread of the listener,
     * which does not hold the other calls of the wallet, so a notification can call the wallet. A notification that does not return
     * holds the next ones, and the close of the wallet and the sync that caused them. A listener that throws is skipped for that
     * notification. A listener that is registered already stays as it is.
     *
     * @param listener is the listener to register
     * @throws MoneroError if the wallet is closed
     */
    override fun addListener(listener: MoneroWalletListener) {
        // the check is inside the lock, since the close clears the listeners under it: a check that passes outside
        // races a close that clears the map and frees the handle under the lock this path is about to take
        synchronized(listeners) {
            checkNotClosed()
            if (listener in listeners) return
            val events = WalletEvents(listener)
            try {
                run { lib, h -> MoneroNative.ok(lib.monero_wallet_add_listener(h, events.handle)) }
            } catch (e: Throwable) {
                events.close()
                throw e
            }
            listeners[listener] = events
        }
    }

    /**
     * Unregister a listener. The notifications that were made before are delivered to it before this returns.
     *
     * @param listener is a listener that was registered with the wallet
     * @throws MoneroError if the wallet is closed, or the listener is not registered
     */
    override fun removeListener(listener: MoneroWalletListener) {
        // the same check under the same lock as the one of the add, for the same reason
        synchronized(listeners) {
            checkNotClosed()
            val events = listeners[listener] ?: throw MoneroError("Listener is not registered with wallet")
            run { lib, h -> MoneroNative.ok(lib.monero_wallet_remove_listener(h, events.handle)) }
            listeners.remove(listener)
            events.close()
        }
    }

    // The listeners that are registered are notified on threads of their own, so a call that makes the wallet notify them, such as a sync, waits
    // for them to be notified of all of it before it returns: without that, the last notifications of the sync can come after it
    private fun flushListeners() {
        val events = synchronized(listeners) { listeners.values.toList() }
        events.forEach { it.flush() }
    }

    /**
     * Get the listeners that are registered with the wallet, in the order that they were added.
     *
     * @return the listeners
     * @throws MoneroError if the wallet is closed
     */
    override fun getListeners(): List<MoneroWalletListener> {
        checkNotClosed()
        return synchronized(listeners) { listeners.keys.toList() }
    }

    /**
     * Unregister all the listeners of the wallet.
     *
     * @throws MoneroError if the wallet is closed
     */
    override fun removeListeners() {
        checkNotClosed()
        synchronized(listeners) {
            run { lib, h -> MoneroNative.ok(lib.monero_wallet_remove_listeners(h)) }
            listeners.values.forEach { it.close() }
            listeners.clear()
        }
    }

    // ------------------------------- SEED, KEYS AND ADDRESSES ----------------------------------

    /**
     * Get the mnemonic seed of the wallet.
     *
     * @return the 25 words of the seed
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no seed
     */
    override fun getSeed(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_seed(h, out) }
    }

    /**
     * Get the language that the seed of the wallet is written in.
     *
     * @return the name of the language
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no seed
     */
    override fun getSeedLanguage(): String {
        return string { lib, h, out -> lib.monero_wallet_get_seed_language(h, out) }
    }


    /**
     * Get the private view key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed
     */
    override fun getPrivateViewKey(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_private_view_key(h, out) }
    }

    /**
     * Get the private spend key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed, or it is view-only, which has no spend key
     */
    override fun getPrivateSpendKey(): String {
        return secret { lib, h, out -> lib.monero_wallet_get_private_spend_key(h, out) }
    }

    /**
     * Get the public view key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed
     */
    override fun getPublicViewKey(): String {
        return string { lib, h, out -> lib.monero_wallet_get_public_view_key(h, out) }
    }

    /**
     * Get the public spend key of the wallet.
     *
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed
     */
    override fun getPublicSpendKey(): String {
        return string { lib, h, out -> lib.monero_wallet_get_public_spend_key(h, out) }
    }

    /**
     * Get the primary address of the wallet, which is the address of account 0 and subaddress 0.
     *
     * @return the primary address
     * @throws MoneroError if the wallet is closed
     */
    override fun getPrimaryAddress(): String {
        return string { lib, h, out -> lib.monero_wallet_get_primary_address(h, out) }
    }

    /**
     * Get the address of an account and subaddress.
     *
     * @param accountIdx is the account index
     * @param subaddressIdx is the subaddress index within the account
     * @return the address
     * @throws MoneroError if the wallet is closed
     */
    override fun getAddress(accountIdx: Int, subaddressIdx: Int): String {
        return string { lib, h, out -> lib.monero_wallet_get_address(h, accountIdx, subaddressIdx, out) }
    }

    // ------------------------------- ACCOUNTS AND SUBADDRESSES ----------------------------------

    /**
     * Get an account of the wallet, with its subaddresses when they are asked for.
     *
     * @param accountIdx is the account index
     * @param includeSubaddresses is whether to include the subaddresses of the account
     * @return the account
     * @throws MoneroError if the wallet is closed, or it has no such account
     */
    override fun getAccount(accountIdx: Int, includeSubaddresses: Boolean): MoneroAccount {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_account(h, accountIdx, includeSubaddresses, false, out) }
    }


    /**
     * Create an account, with the next index.
     *
     * @param label is the label of the account, or null or "" for none
     * @return the account that was created
     * @throws MoneroError if the wallet is closed
     */
    override fun createAccount(label: String?): MoneroAccount {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_create_account(h, label, out) }
    }

    /**
     * Create a subaddress in an account.
     *
     * @param accountIdx is the index of the account
     * @param label is the label of the subaddress, or null or "" for none
     * @return the subaddress that was created
     * @throws MoneroError if the wallet is closed, or it has no such account
     */
    override fun createSubaddress(accountIdx: Int, label: String?): MoneroSubaddress {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_create_subaddress(h, accountIdx, label, out) }
    }

    /**
     * Set the label of a subaddress.
     *
     * @param accountIdx is the index of the account of the subaddress
     * @param subaddressIdx is the index of the subaddress within the account
     * @param label is the label to set, or null or "" to clear it
     * @throws MoneroError if the wallet is closed, or it has no such subaddress
     */
    override fun setSubaddressLabel(accountIdx: Int, subaddressIdx: Int, label: String?) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_subaddress_label(h, accountIdx, subaddressIdx, label)) }
    }

    /**
     * Get the subaddress that an address belongs to, with its account and subaddress indices.
     *
     * @param address is the address
     * @return the subaddress of the address
     * @throws MoneroError if the wallet is closed, the address is not valid, or it does not belong to the wallet
     */
    override fun getAddressIndex(address: String): MoneroSubaddress {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_address_index(h, address, out) }
    }

    /**
     * Get a subaddress of an account. The C ABI has a function for it that a full wallet does not answer, so this reads
     * the subaddress through the plural call.
     *
     * @param accountIdx is the account index
     * @param subaddressIdx is the subaddress index within the account
     * @return the subaddress
     * @throws MoneroError if the wallet is closed, or it has no such subaddress
     */
    override fun getSubaddress(accountIdx: Int, subaddressIdx: Int): MoneroSubaddress {
        return getSubaddresses(accountIdx, listOf(subaddressIdx))[0]
    }

    /**
     * Get the subaddresses of an account at the given indices, or all of them when no indices are given.
     *
     * @param accountIdx is the account index
     * @param subaddressIndices are the subaddress indices within the account, or null or empty for all of them
     * @return the subaddresses, in the order of the indices
     * @throws MoneroError if the wallet is closed, or it has no such account or subaddress
     */
    override fun getSubaddresses(accountIdx: Int, subaddressIndices: List<Int>?): List<MoneroSubaddress> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val indices = subaddressIndices?.toIntArray()
            MoneroNative.jsonList(lib.monero_wallet_get_subaddresses(h, accountIdx, indices, SizeT((indices?.size ?: 0).toLong()), false, out), out, "subaddresses", MoneroSubaddress::class.java)
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
     * ID is not a payment ID
     */
    override fun getIntegratedAddress(standardAddress: String?, paymentId: String?): MoneroIntegratedAddress {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_integrated_address(h, standardAddress, paymentId, out) }
    }

    /**
     * Decode an integrated address into its standard address and its payment ID.
     *
     * @param integratedAddress is the integrated address
     * @return the integrated address with its standard address and its payment ID
     * @throws MoneroError if the wallet is closed, or the address is not an integrated address of the network
     */
    override fun decodeIntegratedAddress(integratedAddress: String): MoneroIntegratedAddress {
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
     * @throws MoneroError if the wallet is closed
     */
    override fun signMessage(message: String, signatureType: MoneroMessageSignatureType, accountIdx: Int, subaddressIdx: Int): String {
        return string { lib, h, out -> lib.monero_wallet_sign_message(h, message, signatureType.ordinal, accountIdx, subaddressIdx, out) }
    }

    /**
     * Verify a signature that [signMessage] made for an address. A signature that does not match is not an error: the
     * result says it is not good.
     *
     * @param message is the signed message
     * @param address is the address that signed the message
     * @param signature is the signature
     * @return the result of the verification
     * @throws MoneroError if the wallet is closed, or the address is not an address of the network
     */
    override fun verifyMessage(message: String, address: String, signature: String): MoneroMessageSignatureResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_verify_message(h, message, address, signature, out) }
    }

    /**
     * Convert a transaction config to a payment URI.
     *
     * @param config is the transaction config, whose first destination holds the address and the amount
     * @return the payment URI
     * @throws MoneroError if the wallet is closed, or the config has no address to build the URI from
     */
    override fun getPaymentUri(config: MoneroTxConfig): String {
        return string { lib, h, out -> lib.monero_wallet_get_payment_uri(h, MoneroJson.serialize(config), out) }
    }

    /**
     * Parse a payment URI into a transaction config.
     *
     * @param uri is the payment URI
     * @return the transaction config that the URI holds
     * @throws MoneroError if the wallet is closed, or the URI is not a payment URI
     */
    override fun parsePaymentUri(uri: String): MoneroTxConfig {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_parse_payment_uri(h, uri, out) }
    }

    // ------------------------------- THE FILES ----------------------------------

    /**
     * Get the path of the wallet file, which the wallet was created with, or "" when it stays in memory.
     *
     * @return the path
     * @throws MoneroError if the wallet is closed
     */
    override fun getPath(): String {
        return string { lib, h, out -> lib.monero_wallet_get_path(h, out) }
    }

    /**
     * Write the wallet to its files, which a wallet that stays in memory has none of.
     *
     * @throws MoneroError if the wallet is closed, or it has no path to write to
     */
    override fun save() {
        checkNotClosed()
        requirePath()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_save(h)) }
    }

    /**
     * Get an attribute that the wallet stores with its files.
     *
     * @param key is the attribute to get the value of
     * @return the value of the attribute, or null if the wallet has no such attribute
     * @throws MoneroError if the wallet is closed
     */
    override fun getAttribute(key: String): String? {
        checkNotClosed()
        return run { lib, h ->
            val found = ByteByReference()
            val out = PointerByReference()
            val value = MoneroNative.text(lib.monero_wallet_get_attribute(h, key, found, out), out)
            if (found.value.toInt() != 0) value ?: "" else null
        }
    }

    /**
     * Set an attribute, which the wallet stores with its files: it is written when the wallet is saved.
     *
     * @param key is the attribute key
     * @param value is the attribute value
     * @throws MoneroError if the wallet is closed
     */
    override fun setAttribute(key: String, value: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_attribute(h, key, value)) }
    }

    /**
     * Get the default priority of the fee of the transactions, which the wallet uses when a config has none.
     *
     * @return the default priority
     * @throws MoneroError if the wallet is closed
     */
    override fun getDefaultFeePriority(): MoneroTxPriority {
        checkNotClosed()
        return run { lib, h ->
            val priority = IntByReference()
            MoneroNative.ok(lib.monero_wallet_get_default_fee_priority(h, priority))
            MoneroTxPriority.entries.getOrNull(priority.value) ?: throw MoneroError("monero-c returned a priority that is not known: ${priority.value}")
        }
    }

    /**
     * Move the wallet files to another path, where the wallet is saved with the new password. A wallet that stays in memory is
     * written there. Only the full wallet moves: a wallet of a server does not.
     *
     * @param path is the new path of the wallet
     * @param password is the password of the wallet files at the new path
     * @throws MoneroError if the wallet is closed, or it can not be written at the path
     */
    override fun moveTo(path: String, password: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_move_to(h, path, password)) }
    }

    /**
     * Change the password of the wallet files, which are written with it, and which the wallet opens with from then
     * on.
     *
     * @param oldPassword is the password of the files, which has to be the one that they are written with
     * @param newPassword is the password to write the files with
     * @throws MoneroError if the wallet is closed, or the old password is not the one that the files are written with
     */
    override fun changePassword(oldPassword: String, newPassword: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_change_password(h, oldPassword, newPassword)) }
    }

    // ------------------------------- TRANSACTIONS ----------------------------------

    /**
     * Get the transactions of the wallet. A block of the answer holds its miner transaction twice, in its transactions
     * and as its miner transaction, which is the block model of monero-cpp: this flattens the transactions of the
     * blocks. While a sync runs, the call waits for it.
     *
     * @param query is the query that the transactions have to match, or null for all of them
     * @return the transactions of the wallet
     * @throws MoneroError if the wallet is closed, or it is not connected to a daemon
     */
    override fun getTxs(query: MoneroTxQuery?): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.json(lib.monero_wallet_get_txs(h, query?.let { MoneroJson.serialize(it) }, out), out, MoneroWalletBlocksResult::class.java)
            result.getTxs()
        }
    }

    /**
     * Get the incoming and the outgoing transfers of the wallet. Each transfer is in its transaction, which holds the transfers of
     * the answer and not the other ones. A transaction that the wallet makes to one of its own subaddresses has an outgoing transfer
     * and an incoming one. While a sync runs, the call waits for it.
     *
     * @param query is the query that the transfers have to match, or null for all of them
     * @return the transfers of the wallet that meet the query
     * @throws MoneroError if the wallet is closed, or the query has an account index or a subaddress index that is negative
     */
    override fun getTransfers(query: MoneroTransferQuery?): List<MoneroTransfer> {
        checkNotClosed()
        if (query != null) {
            val indices = listOfNotNull(query.accountIndex, query.subaddressIndex) + (query.subaddressIndices ?: emptyList())
            if (indices.any { it < 0 }) throw MoneroError("The indices of a transfer query can not be negative: $indices")
        }
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.json(lib.monero_wallet_get_transfers(h, query?.let { MoneroJson.serialize(it) }, out), out, MoneroWalletBlocksResult::class.java)
            result.getTransfers()
        }
    }

    // ------------------------------- NOTES OF TRANSACTIONS ----------------------------------

    /**
     * Get the note of a transaction, which the wallet keeps in its file: it is empty if none was set.
     *
     * @param txHash is the hash of the transaction
     * @return the note, which is empty if the transaction has none
     * @throws MoneroError if the wallet is closed, or the hash is not the hash of a transaction
     */
    override fun getTxNote(txHash: String): String {
        return string { lib, h, out -> lib.monero_wallet_get_tx_note(h, txHash, out) }
    }

    /**
     * Get the notes of some transactions.
     *
     * @param txHashes are the hashes of the transactions
     * @return the notes, in the order of the hashes, each empty if the transaction has none
     * @throws MoneroError if the wallet is closed, or a hash is not the hash of a transaction
     */
    override fun getTxNotes(txHashes: List<String>): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_get_tx_notes(h, txHashes.toTypedArray(), SizeT(txHashes.size.toLong()), out), out)
        }
    }

    /**
     * Set the note of a transaction. The note is kept in the file of the wallet when the wallet saves.
     *
     * @param txHash is the hash of the transaction
     * @param note is the note, which can be empty to take the note away
     * @throws MoneroError if the wallet is closed, or the hash is not the hash of a transaction
     */
    override fun setTxNote(txHash: String, note: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_tx_note(h, txHash, note)) }
    }

    /**
     * Set the notes of some transactions.
     *
     * @param txHashes are the hashes of the transactions
     * @param notes are the notes, each for the hash at its position
     * @throws MoneroError if the wallet is closed, the hashes and the notes are not as many, or a hash is not the hash of a transaction,
     * in which case no note is set: monero-c sets them one by one, so the hashes are checked before
     */
    override fun setTxNotes(txHashes: List<String>, notes: List<String>) {
        checkNotClosed()
        if (txHashes.size != notes.size) throw MoneroError("The hashes and the notes of the transactions are not as many: ${txHashes.size} and ${notes.size}")
        txHashes.firstOrNull { !TX_HASH.matches(it) }?.let { throw MoneroError("Invalid transaction hash: $it") }
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_tx_notes(h, txHashes.toTypedArray(), notes.toTypedArray(), SizeT(txHashes.size.toLong()))) }
    }

    // ------------------------------- KEYS AND PROOFS OF TRANSACTIONS ----------------------------------

    /**
     * Get the secret key of a transaction that the wallet sent, which proves a payment to the one who gets the key.
     *
     * @param txHash is the hash of the transaction
     * @return the key in hexadecimal
     * @throws MoneroError if the wallet is closed, the hash is not valid, or the wallet did not send the transaction
     */
    override fun getTxKey(txHash: String): String {
        return secret { lib, h, out -> lib.monero_wallet_get_tx_key(h, txHash, out) }
    }

    /**
     * Check that a transaction paid an address, with the secret key of the transaction.
     *
     * @param txHash is the hash of the transaction
     * @param txKey is the secret key of the transaction
     * @param address is the address that the transaction paid
     * @return the result of the check: how much the address received, and whether the transaction is in the pool or how many
     * confirmations it has. A key that is not the one of the transaction is not an error: the result says it is not good
     * @throws MoneroError if the wallet is closed, or the hash, the key or the address is not valid
     */
    override fun checkTxKey(txHash: String, txKey: String, address: String): MoneroCheckTx {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_check_tx_key(h, txHash, txKey, address, out) }
    }

    /**
     * Get a signature that proves that a transaction paid an address, which the one who has it checks with [checkTxProof].
     *
     * @param txHash is the hash of the transaction
     * @param address is the address that the transaction paid
     * @param message is a message that the signature carries, or null for none
     * @return the signature
     * @throws MoneroError if the wallet is closed, the hash or the address is not valid, or the wallet did not send the transaction
     */
    override fun getTxProof(txHash: String, address: String, message: String?): String {
        return string { lib, h, out -> lib.monero_wallet_get_tx_proof(h, txHash, address, message, out) }
    }

    /**
     * Check the proof that a transaction paid an address.
     *
     * @param txHash is the hash of the transaction
     * @param address is the address that the transaction paid
     * @param message is the message that the signature carries, or null or "" for none
     * @param signature is the signature of [getTxProof]
     * @return the result of the check. A signature that does not prove the payment is not an error: the result says it is not good
     * @throws MoneroError if the wallet is closed, the hash or the address is not valid, or the signature is empty
     */
    override fun checkTxProof(txHash: String, address: String, message: String?, signature: String): MoneroCheckTx {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_check_tx_proof(h, txHash, address, message, signature, out) }
    }

    /**
     * Get a signature that proves that the wallet spent the outputs of a transaction, which does not name the address that the
     * transaction paid.
     *
     * @param txHash is the hash of the transaction
     * @param message is a message that the signature carries, or null for none
     * @return the signature
     * @throws MoneroError if the wallet is closed, the hash is not valid, or the wallet did not send the transaction
     */
    override fun getSpendProof(txHash: String, message: String?): String {
        return string { lib, h, out -> lib.monero_wallet_get_spend_proof(h, txHash, message, out) }
    }

    /**
     * Check the proof that a transaction was spent by a wallet.
     *
     * @param txHash is the hash of the transaction
     * @param message is the message that the signature carries, or null or "" for none
     * @param signature is the signature of [getSpendProof]
     * @return true if the signature proves the spend, false if it does not
     * @throws MoneroError if the wallet is closed, or the hash is not valid
     */
    override fun checkSpendProof(txHash: String, message: String?, signature: String): Boolean {
        return flag { lib, h, good -> lib.monero_wallet_check_spend_proof(h, txHash, message, signature, good) }
    }

    // ------------------------------- PROOFS OF RESERVES ----------------------------------

    /**
     * Get a signature that proves the whole balance of the wallet, which the one who has it checks with [checkReserveProof].
     *
     * @param message is a message that the signature carries, or null for none
     * @return the signature
     * @throws MoneroError if the wallet is closed, or it is view-only, which can not sign
     */
    override fun getReserveProofWallet(message: String?): String {
        return string { lib, h, out -> lib.monero_wallet_get_reserve_proof_wallet(h, message, out) }
    }

    /**
     * Get a signature that proves an amount that an account of the wallet holds.
     *
     * @param accountIdx is the index of the account
     * @param amount is the amount to prove, in atomic units, which is at most what the account has
     * @param message is a message that the signature carries, or null for none
     * @return the signature
     * @throws MoneroError if the wallet is closed, the amount is negative or does not fit 64 bits, the account is not there, or it
     * does not have the amount
     */
    override fun getReserveProofAccount(accountIdx: Int, amount: BigInteger, message: String?): String {
        if (amount.signum() < 0 || amount.bitLength() > 64) throw MoneroError("The amount of a reserve proof is not an amount of atomic units: $amount")
        return string { lib, h, out -> lib.monero_wallet_get_reserve_proof_account(h, accountIdx, amount.toLong(), message, out) }
    }

    /**
     * Check the proof of the reserve of a wallet.
     *
     * @param address is the primary address of the wallet that made the proof
     * @param message is the message that the signature carries, or null or "" for none
     * @param signature is the signature of [getReserveProofWallet] or [getReserveProofAccount]
     * @return the result of the check: the amount that the proof shows, and how much of it the wallet spent in transactions that are not
     * confirmed. A signature that does not prove the reserve is not an error: the result says it is not good
     * @throws MoneroError if the wallet is closed, the address is not valid or is a subaddress, or the signature is not a signature
     */
    override fun checkReserveProof(address: String, message: String?, signature: String): MoneroCheckReserve {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_check_reserve_proof(h, address, message, signature, out) }
    }

    // ------------------------------- ADDRESS BOOK ----------------------------------

    /**
     * Get the entries of the address book. The book is kept in the file of the wallet when the wallet is saved.
     *
     * @param indices are the indices of the entries to get, in the order that they are returned, or null or empty for all of them
     * @return the entries
     * @throws MoneroError if the wallet is closed, or an index is not the one of an entry
     */
    override fun getAddressBookEntries(indices: List<Long>?): List<MoneroAddressBookEntry> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val array = indices?.takeIf { it.isNotEmpty() }?.toLongArray()
            MoneroNative.jsonList(lib.monero_wallet_get_address_book_entries(h, array, SizeT((array?.size ?: 0).toLong()), out), out, "entries", MoneroAddressBookEntry::class.java)
        }
    }

    /**
     * Add an entry to the address book.
     *
     * @param address is the address of the entry, which can be a subaddress or an integrated address
     * @param description is the description of the entry, or null for none
     * @return the index of the added entry
     * @throws MoneroError if the wallet is closed, or the address is not valid
     */
    override fun addAddressBookEntry(address: String, description: String?): Long {
        checkNotClosed()
        return run { lib, h ->
            val index = LongByReference()
            MoneroNative.ok(lib.monero_wallet_add_address_book_entry(h, address, description, index))
            index.value
        }
    }

    /**
     * Edit an entry of the address book.
     *
     * @param index is the index of the entry
     * @param address is the new address, or null to keep the one that the entry has
     * @param description is the new description, or null to keep the one that the entry has
     * @throws MoneroError if the wallet is closed, the index is not the one of an entry, or the address is not valid
     */
    override fun editAddressBookEntry(index: Long, address: String?, description: String?) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_edit_address_book_entry(h, index, address, description)) }
    }

    /**
     * Delete an entry of the address book. The entries after it take the index that is one less.
     *
     * @param index is the index of the entry
     * @throws MoneroError if the wallet is closed, or the index is not the one of an entry
     */
    override fun deleteAddressBookEntry(index: Long) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_delete_address_book_entry(h, index)) }
    }

    // ------------------------------- OUTPUTS AND KEY IMAGES ----------------------------------

    /**
     * Get the outputs of the wallet that it can spend.
     *
     * @param query filters the outputs, or null for all of them
     * @return the outputs that meet the query, each in its transaction
     * @throws MoneroError if the wallet is closed
     */
    override fun getOutputs(query: MoneroOutputQuery?): List<MoneroOutputWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.json(lib.monero_wallet_get_outputs(h, query?.let { MoneroJson.serialize(it) }, out), out, MoneroWalletBlocksResult::class.java)
            result.getOutputs()
        }
    }

    /**
     * Export the outputs of the wallet, in hexadecimal, which a view-only wallet imports to know the outputs of the wallet that has the
     * keys.
     *
     * @param all is true to export all the outputs, and false to export the ones since the last export
     * @return the outputs in hexadecimal
     * @throws MoneroError if the wallet is closed
     */
    override fun exportOutputs(all: Boolean): String {
        return string { lib, h, out -> lib.monero_wallet_export_outputs(h, all, out) }
    }

    /**
     * Import outputs that another wallet exported.
     *
     * @param outputsHex are the outputs in hexadecimal
     * @return the number of outputs that were imported
     * @throws MoneroError if the wallet is closed, or the outputs are not valid
     */
    override fun importOutputs(outputsHex: String): Int {
        checkNotClosed()
        return run { lib, h ->
            val imported = IntByReference()
            MoneroNative.ok(lib.monero_wallet_import_outputs(h, outputsHex, imported))
            imported.value
        }
    }

    /**
     * Export the signed key images of the wallet, which a view-only wallet imports to know which outputs were spent.
     *
     * @param all is true to export all the key images, and false to export the ones since the last export
     * @return the key images, and the index of the first among the outputs of the wallet
     * @throws MoneroError if the wallet is closed
     */
    override fun exportKeyImages(all: Boolean): MoneroKeyImageExportResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_export_key_images(h, all, out) }
    }

    /**
     * Import signed key images and check which of the outputs of the wallet they spend.
     *
     * @param keyImages are the key images to import
     * @param offset is the index of the first key image among the outputs of the wallet
     * @return the height of the wallet, and the amounts that the key images spent and left unspent
     * @throws MoneroError if the wallet is closed, or the key images are not valid
     */
    override fun importKeyImages(keyImages: List<MoneroKeyImage>, offset: Long): MoneroKeyImageImportResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_import_key_images(h, MoneroJson.serialize(keyImages), offset, out) }
    }

    /**
     * Freeze an output, so that the wallet does not spend it.
     *
     * @param keyImage is the key image of the output, in hexadecimal
     * @throws MoneroError if the wallet is closed, or it has no output with that key image
     */
    override fun freezeOutput(keyImage: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_freeze_output(h, keyImage)) }
    }

    /**
     * Thaw a frozen output, so that the wallet can spend it.
     *
     * @param keyImage is the key image of the output, in hexadecimal
     * @throws MoneroError if the wallet is closed, or it has no output with that key image
     */
    override fun thawOutput(keyImage: String) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_thaw_output(h, keyImage)) }
    }

    /**
     * Check if an output is frozen.
     *
     * @param keyImage is the key image of the output, in hexadecimal
     * @return true if the output is frozen
     * @throws MoneroError if the wallet is closed, or it has no output with that key image
     */
    override fun isOutputFrozen(keyImage: String): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val frozen = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_output_frozen(h, keyImage, frozen))
            frozen.value.toInt() != 0
        }
    }

    /**
     * Create a transaction that transfers funds from the wallet. The transaction is relayed only when the config says
     * so: a transaction that is not relayed carries the metadata that [relayTx] takes. The config can not be split into
     * several transactions: [createTxs] does that.
     *
     * @param config is the configuration of the transfer, which names the account to send from and the destinations,
     * and says whether to relay
     * @return the transaction that the config makes, with its fee and its metadata
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, the configuration is invalid,
     * or the wallet has less unlocked money than the transfer and its fee need
     */
    override fun createTx(config: MoneroTxConfig): MoneroTxWallet {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_create_tx(h, MoneroJson.serialize(config), out), out)
        }.getTxs().singleOrNull() ?: throw MoneroError("monero-c did not create one transaction")
    }

    /**
     * Create one or more transactions that transfer funds from the wallet. The transactions are relayed only when the
     * config says so: a transaction that is not relayed carries the metadata that [relayTx] takes.
     *
     * @param config is the configuration of the transfer, which names the account to send from and the
     * destinations, and says whether to relay
     * @return the transactions that the config makes, with their fee and their metadata
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, the configuration is invalid,
     * or the wallet has less unlocked money than the transfer and its fee need
     */
    override fun createTxs(config: MoneroTxConfig): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val result = MoneroNative.txSetsResult(lib.monero_wallet_create_txs(h, MoneroJson.serialize(config), out), out)
            result.getTxs()
        }
    }

    /**
     * Sweep an output into an address, with the key image of the output. The transaction is relayed only when the config says so.
     *
     * @param config is the configuration of the sweep, which has the address to send to and the key image of the output to
     * sweep, and says whether to relay
     * @return the transaction that sweeps the output
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, the configuration is invalid, or the output is
     * not an output of the wallet that it can spend
     */
    override fun sweepOutput(config: MoneroTxConfig): MoneroTxWallet {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_sweep_output(h, MoneroJson.serialize(config), out), out)
        }.getTxs().singleOrNull() ?: throw MoneroError("monero-c did not create one transaction")
    }

    /**
     * Sweep the unlocked funds of the wallet, of an account or of some subaddresses of it into an address. The sweep makes as many
     * transactions as the outputs need. The transactions are relayed only when the config says so.
     *
     * @param config is the configuration of the sweep, which has the address to send to, can name the account and the subaddresses to
     * sweep, and says whether to relay and whether to sweep each subaddress in its own transaction
     * @return the transactions that sweep the funds
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, the configuration is invalid, or there are no
     * unlocked funds to sweep
     */
    override fun sweepUnlocked(config: MoneroTxConfig): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_sweep_unlocked(h, MoneroJson.serialize(config), out), out)
        }.getTxs()
    }

    /**
     * Sweep the dust outputs of the wallet back to itself. There is none in a chain that has the ring signatures of Monero, so the
     * answer is empty.
     *
     * @param relay is true to relay the transactions
     * @return the transactions that sweep the dust, which are none if the wallet has no dust
     * @throws MoneroError if the wallet is closed, or it is not connected to the daemon
     */
    override fun sweepDust(relay: Boolean): List<MoneroTxWallet> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_sweep_dust(h, relay, out), out)
        }.getTxs()
    }

    /**
     * Relay a transaction that [createTxs] made without relaying, from its metadata.
     *
     * @param txMetadata is the metadata of the transaction, which a transaction of [createTxs] carries
     * @return the hash of the relayed transaction
     * @throws MoneroError if the wallet is closed, the metadata is not the metadata of a transaction, or the daemon
     * refuses the transaction
     */
    override fun relayTx(txMetadata: String): String {
        return string { lib, h, out -> lib.monero_wallet_relay_tx(h, txMetadata, out) }
    }

    /**
     * Relay a transaction that [createTxs] made without relaying, from the transaction itself: the C ABI reads the
     * metadata from the JSON of the transaction.
     *
     * @param tx is the transaction to relay, which [createTxs] returned
     * @return the hash of the relayed transaction
     * @throws MoneroError if the wallet is closed, the transaction has no metadata, or the daemon refuses the
     * transaction
     */
    override fun relayTx(tx: MoneroTxWallet): String {
        return string { lib, h, out -> lib.monero_wallet_relay_tx_json(h, MoneroJson.serialize(tx), out) }
    }

    /**
     * Relay transactions that [createTxs] made without relaying, from their metadata.
     *
     * @param txMetadatas are the metadata of the transactions, which a transaction of [createTxs] carries
     * @return the hashes of the relayed transactions
     * @throws MoneroError if the wallet is closed, one of the metadata is not the metadata of a transaction, or the
     * daemon refuses a transaction
     */
    override fun relayTxs(txMetadatas: List<String>): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_relay_txs(h, txMetadatas.toTypedArray(), SizeT(txMetadatas.size.toLong()), out), out)
        }
    }

    /**
     * Relay transactions that [createTxs] made without relaying, from the transactions themselves: the C ABI reads the
     * metadata from the JSON of the transactions.
     *
     * @param txs are the transactions to relay, which [createTxs] returned
     * @return the hashes of the relayed transactions
     * @throws MoneroError if the wallet is closed, a transaction has no metadata, or the daemon refuses a transaction
     */
    override fun relayTxs(txs: Collection<MoneroTxWallet>): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_relay_txs_json(h, MoneroJson.serialize(txs.toList()), out), out)
        }
    }

    /**
     * Describe the unsigned or the multisig hex of a tx set as structured transactions.
     *
     * @param txSet is a tx set with its unsigned tx hex or its multisig tx hex
     * @return the tx set with the structured transactions
     * @throws MoneroError if the wallet is closed, or the set has no hex that the wallet can describe
     */
    override fun describeTxSet(txSet: MoneroTxSet): MoneroTxSet {
        checkNotClosed()
        return txSetOf(run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_describe_tx_set(h, MoneroJson.serialize(txSet), out), out)
        })
    }

    /**
     * Sign the unsigned transactions that a view-only wallet made, with the wallet that has the keys.
     *
     * @param unsignedTxHex is the unsigned tx hex of the set that the view-only wallet made
     * @return the tx set with the signed transactions and their hex
     * @throws MoneroError if the wallet is closed, is view-only, or the hex is not unsigned transactions of this wallet
     */
    override fun signTxs(unsignedTxHex: String): MoneroTxSet {
        checkNotClosed()
        return txSetOf(run { lib, h ->
            val out = PointerByReference()
            MoneroNative.txSetsResult(lib.monero_wallet_sign_txs(h, unsignedTxHex, out), out)
        })
    }

    /**
     * Submit the signed transactions of a view-only wallet to the daemon.
     *
     * @param signedTxHex is the signed tx hex, from [signTxs]
     * @return the hashes of the submitted transactions
     * @throws MoneroError if the wallet is closed, the hex is not signed transactions, or the daemon refuses them
     */
    override fun submitTxs(signedTxHex: String): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_submit_txs(h, signedTxHex, out), out)
        }
    }

    // ------------------------------- THE DAEMON ----------------------------------

    /**
     * Set the connection of the daemon of the wallet, which it can be at any time. The connection is not held in the
     * wallet files, so it is set again on a wallet that is opened. A connection with no URI, or null, disconnects the
     * wallet.
     *
     * @param connection is the connection to the daemon, or null to disconnect it
     * @param isTrusted is whether the daemon is trusted for spend results, or null to trust a daemon on a local
     * address only, which is the default of wallet2
     * @throws MoneroError if the wallet is closed
     */
    override fun setDaemonConnection(connection: MoneroRpcConnection?, isTrusted: Boolean?) {
        checkNotClosed()
        run { lib, h ->
            val uri = connection?.uri ?: ""
            val username = connection?.username ?: ""
            val password = connection?.password ?: ""
            val proxyUri = connection?.proxyUri ?: ""
            val trusted = isTrusted?.let { ByteByReference().apply { value = if (it) 1 else 0 } }
            MoneroNative.ok(lib.monero_wallet_set_daemon_connection(h, uri, username, password, proxyUri, trusted, connection?.sslVerify ?: true))
        }
    }

    /**
     * Get the connection of the daemon of the wallet.
     *
     * @return the connection, or null if the wallet has none
     * @throws MoneroError if the wallet is closed
     */
    override fun getDaemonConnection(): MoneroRpcConnection? {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val json = MoneroNative.text(lib.monero_wallet_get_daemon_connection(h, out), out) ?: return@run null
            MoneroJson.deserialize(json, MoneroRpcConnection::class.java)
        }
    }

    /**
     * Check if the daemon of the wallet is trusted.
     *
     * @return true if the daemon is trusted, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    override fun isDaemonTrusted(): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val trusted = ByteByReference()
            MoneroNative.ok(lib.monero_wallet_is_daemon_trusted(h, trusted))
            trusted.value.toInt() != 0
        }
    }

    // ------------------------------- SYNC AND THE CHAIN ----------------------------------

    /**
     * Synchronize the wallet with its daemon, on the thread of the wallet's calls: the other calls of the wallet wait
     * until the sync is done.
     *
     * @param startHeight is the height to sync from, ignored when it is less than the height that the wallet has
     * already scanned
     * @param listener is notified of the progress of the sync, one notification after the other, or null for none. It has been notified of
     * all of it when this returns, unless it finishes no notification for a minute, which fails the call. It is not registered with the
     * wallet, so it is not notified after the sync. The listeners that are registered with the wallet have been notified of all of it too,
     * on the same terms
     * @return the result of the sync
     * @throws MoneroError if the wallet is closed, or it is not connected to the daemon, or a listener, the one of the call or one that is
     * registered with the wallet, finishes no notification for a minute after the sync, or the thread is interrupted while it waits for
     * them. In the last two the wallet has synced all the same, and an interrupt stays in the flag of the thread
     */
    override fun sync(startHeight: Long?, listener: MoneroWalletListener?): MoneroSyncResult {
        checkNotClosed()
        val events = listener?.let { WalletEvents(it) }
        try {
            val result: MoneroSyncResult = json { lib, h, out -> lib.monero_wallet_sync(h, startHeight?.let { LongByReference(it) }, events?.handle, out) }
            events?.flush()
            flushListeners()
            return result
        } finally {
            events?.close()
        }
    }

    /**
     * Start a thread of the wallet that syncs it with its daemon, over and over. It succeeds while the wallet is not
     * connected, and syncs when a daemon becomes reachable. Stop it with [stopSyncing].
     *
     * @param syncPeriodMs is the most time that the wallet waits between two syncs, in milliseconds, from 1 to
     * 2147483647
     * @throws MoneroError if the wallet is closed, or the period is out of range
     */
    override fun startSyncing(syncPeriodMs: Long) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_start_syncing(h, syncPeriodMs)) }
    }

    /**
     * Stop the thread of the wallet that syncs it.
     *
     * @throws MoneroError if the wallet is closed
     */
    override fun stopSyncing() {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_stop_syncing(h)) }
    }

    /**
     * Wait until the next block is added to the chain, for at most a number of milliseconds. The wallet must be
     * syncing in the background ([startSyncing]), or no block comes. A caller that waits in a loop can be cancelled
     * between the waits, which a wait without a timeout can not.
     *
     * Unlike the wait of a daemon, this one runs on the thread of the wallet's calls, since the C ABI allows no second
     * thread on a wallet handle: every other call of the wallet, and [close], wait for it until a block arrives or the
     * time is out. Pick the timeout with that in mind.
     *
     * @param timeoutMs is the longest time to wait, from 1 to 2147483647
     * @return the height of the block, or null if no block arrived in the time
     * @throws MoneroError if the wallet is closed, or the timeout is out of range
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

    /**
     * Wait for the next block to be added to the chain, which takes as long as it takes. The wallet must be syncing in the background
     * ([startSyncing]), or no block comes.
     *
     * The wait is done in waits of a second, as [waitForNextBlock] with a timeout does, so the other calls of the wallet and [close]
     * get their turn between them, and a wallet that is closed ends it with an error.
     *
     * @return the height of the next block that was added to the chain
     * @throws MoneroError if the wallet is closed
     */
    override fun waitForNextBlock(): Long {
        while (true) {
            waitForNextBlock(1000)?.let { return it }
        }
    }

    // ------------------------------- SCAN AND RESCAN ----------------------------------

    /**
     * Scan transactions of the chain by their hashes, which adds to the wallet the ones that it is a party of, without syncing the blocks
     * between them. The wallet has to be connected to a daemon. The listeners that are registered with the wallet have been notified of
     * what the scan found when this returns.
     *
     * @param txHashes are the hashes of the transactions to scan
     * @throws MoneroError if the wallet is closed, a hash is not a transaction hash, the wallet is not connected to a daemon, or a listener
     * that is registered with the wallet finishes no notification for a minute after the scan, or the thread is interrupted while it waits
     * for it. In the last two the wallet has scanned all the same, and an interrupt stays in the flag of the thread
     */
    override fun scanTxs(txHashes: List<String>) {
        checkNotClosed()
        txHashes.firstOrNull { !TX_HASH.matches(it) }?.let { throw MoneroError("Invalid transaction hash: $it") }
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_scan_txs(h, txHashes.toTypedArray(), SizeT(txHashes.size.toLong()))) }
        flushListeners()
    }

    /**
     * Rescan the chain for the outputs of the wallet that were spent, which needs a daemon that the wallet trusts.
     *
     * @throws MoneroError if the wallet is closed, or it is not connected to a daemon that it trusts
     */
    override fun rescanSpent() {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_rescan_spent(h)) }
    }

    /**
     * Rescan the chain from the start, which loses what the chain does not have: the destinations of the transactions that the wallet
     * sent. Their keys and their notes are kept. The wallet syncs again, so it has its transactions when this returns, and the listeners
     * that are registered with the wallet have been notified of the outputs that it found again, and not of the blocks or of the progress,
     * which a rescan does not notify.
     *
     * @throws MoneroError if the wallet is closed, it is not connected to a daemon, or a listener that is registered with the wallet
     * finishes no notification for a minute after the rescan, or the thread is interrupted while it waits for it. In the last two the
     * wallet has rescanned all the same, and an interrupt stays in the flag of the thread
     */
    override fun rescanBlockchain() {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_rescan_blockchain(h)) }
        flushListeners()
    }

    // ------------------------------- MINING ----------------------------------

    /**
     * Start mining on the daemon of the wallet, to the primary address of the wallet. The daemon has to be one that the wallet trusts.
     *
     * @param numThreads is the number of threads that mine, or null or 0 for one, for the wallet of a server too
     * @param backgroundMining is whether to mine in the background, or null for not
     * @param ignoreBattery is whether to mine on battery power, or null for not
     * @throws MoneroError if the wallet is closed, or it is not connected to a daemon that it trusts, or the daemon is mining already
     */
    override fun startMining(numThreads: Long?, backgroundMining: Boolean?, ignoreBattery: Boolean?) {
        checkNotClosed()
        run { lib, h ->
            val threads = numThreads?.let { LongByReference(it) }
            val background = backgroundMining?.let { ByteByReference(if (it) 1 else 0) }
            val battery = ignoreBattery?.let { ByteByReference(if (it) 1 else 0) }
            MoneroNative.ok(lib.monero_wallet_start_mining(h, threads, background, battery))
        }
    }

    /**
     * Stop mining on the daemon of the wallet.
     *
     * @throws MoneroError if the wallet is closed, or it is not connected to a daemon that it trusts
     */
    override fun stopMining() {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_stop_mining(h)) }
    }

    /**
     * Check if the wallet is connected to a daemon.
     *
     * @return true if the wallet is connected, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    override fun isConnectedToDaemon(): Boolean {
        return flag { lib, h, out -> lib.monero_wallet_is_connected_to_daemon(h, out) }
    }

    /**
     * Check if the daemon of the wallet is synced with the network.
     *
     * @return true if the daemon is synced, false otherwise
     * @throws MoneroError if the wallet is closed, or it can not reach the daemon
     */
    override fun isDaemonSynced(): Boolean {
        return flag { lib, h, out -> lib.monero_wallet_is_daemon_synced(h, out) }
    }

    /**
     * Check if the wallet is synced with its daemon.
     *
     * @return true if the wallet is synced, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    override fun isSynced(): Boolean {
        return flag { lib, h, out -> lib.monero_wallet_is_synced(h, out) }
    }

    /**
     * Get the height that the wallet has scanned to, which is the next block to scan. A wallet that has not scanned
     * starts at 1.
     *
     * @return the height
     * @throws MoneroError if the wallet is closed
     */
    override fun getHeight(): Long {
        return height { lib, h, out -> lib.monero_wallet_get_height(h, out) }
    }

    /**
     * Get the height that the daemon of the wallet is synced to.
     *
     * @return the height
     * @throws MoneroError if the wallet is closed, or it is not connected to the daemon
     */
    override fun getDaemonHeight(): Long {
        return height { lib, h, out -> lib.monero_wallet_get_daemon_height(h, out) }
    }

    /**
     * Get the greatest height of the peers that the daemon of the wallet is connected to. It is the height of the daemon when the
     * daemon does not tell one, which it does not when it is synced. The wallet of a server does not have it.
     *
     * @return the height
     * @throws MoneroError if the wallet is closed, it is not connected to the daemon, or it is the wallet of a server
     */
    override fun getDaemonMaxPeerHeight(): Long {
        return height { lib, h, out -> lib.monero_wallet_get_daemon_max_peer_height(h, out) }
    }

    /**
     * Get the height of the chain at the start of a date, which the wallet finds by the timestamps of the blocks that its daemon has. The
     * wallet of a server does not have it.
     *
     * @param year is the year
     * @param month is the month, from 1 to 12
     * @param day is the day, from 1 to 31
     * @return the height
     * @throws MoneroError if the wallet is closed, the date is not valid, it is after the last block of the chain, the wallet is not
     * connected to the daemon, or it is the wallet of a server
     */
    override fun getHeightByDate(year: Int, month: Int, day: Int): Long {
        if (year !in 0..0xFFFF || month !in 1..12 || day !in 1..31) throw MoneroError("Invalid date: $year-$month-$day")
        return height { lib, h, out -> lib.monero_wallet_get_height_by_date(h, year.toShort(), month.toByte(), day.toByte(), out) }
    }

    /**
     * Get the balance of the wallet, including the funds that are still locked. While a sync runs, the call waits for
     * it.
     *
     * @param accountIdx is the account whose balance to get, or null for the balance of the wallet
     * @param subaddressIdx is the subaddress of that account whose balance to get, or null for the balance of the account
     * @return the balance in atomic units
     * @throws MoneroError if the wallet is closed, a subaddress is given without an account, or the wallet has no such account or
     * subaddress
     */
    override fun getBalance(accountIdx: Int?, subaddressIdx: Int?): BigInteger {
        checkNotClosed()
        if (accountIdx != null) return balanceOf(accountIdx, subaddressIdx) { it.balance }
        if (subaddressIdx != null) throw MoneroError("Cannot provide a subaddress index without an account index")
        return run { lib, h ->
            val balance = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_balance(h, balance))
            MoneroNative.unsigned(balance.value)
        }
    }

    /**
     * Get the unlocked balance of the wallet, which is the balance without the funds that are still locked. While a
     * sync runs, the call waits for it.
     *
     * @param accountIdx is the account whose unlocked balance to get, or null for the unlocked balance of the wallet
     * @param subaddressIdx is the subaddress of that account whose unlocked balance to get, or null for the one of the account
     * @return the unlocked balance in atomic units
     * @throws MoneroError if the wallet is closed, a subaddress is given without an account, or the wallet has no such account or
     * subaddress
     */
    override fun getUnlockedBalance(accountIdx: Int?, subaddressIdx: Int?): BigInteger {
        checkNotClosed()
        if (accountIdx != null) return balanceOf(accountIdx, subaddressIdx) { it.unlockedBalance }
        if (subaddressIdx != null) throw MoneroError("Cannot provide a subaddress index without an account index")
        return run { lib, h ->
            val balance = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_unlocked_balance(h, balance))
            MoneroNative.unsigned(balance.value)
        }
    }

    // the balances of an account and of a subaddress are the ones that the account and the subaddress have. A sync that runs holds the
    // wallet, so the call waits for it, as the balance of the wallet does
    private fun balanceOf(accountIdx: Int, subaddressIdx: Int?, balance: (Balances) -> BigInteger?): BigInteger {
        val balances = if (subaddressIdx == null) {
            val account = getAccount(accountIdx, false)
            Balances(account.balance, account.unlockedBalance)
        } else {
            val subaddress = getSubaddresses(accountIdx, listOf(subaddressIdx))[0]
            Balances(subaddress.balance, subaddress.unlockedBalance)
        }
        return balance(balances) ?: throw MoneroError("The wallet did not return the balance of the account $accountIdx")
    }

    private class Balances(val balance: BigInteger?, val unlockedBalance: BigInteger?)

    // ------------------------------- STATE ----------------------------------

    /**
     * Get the network of the wallet.
     *
     * @return the network type
     * @throws MoneroError if the wallet is closed
     */
    override fun getNetworkType(): MoneroNetworkType {
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
     * @throws MoneroError if the wallet is closed
     */
    override fun isViewOnly(): Boolean {
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
     * @throws MoneroError if the wallet is closed
     */
    override fun getVersion(): MoneroVersion {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_version(h, out) }
    }

    /**
     * Get the height that the wallet scans from when it is created or restored, which is the first block that it
     * scans.
     *
     * @return the restore height
     * @throws MoneroError if the wallet is closed
     */
    override fun getRestoreHeight(): Long {
        checkNotClosed()
        return run { lib, h ->
            val height = LongByReference()
            MoneroNative.ok(lib.monero_wallet_get_restore_height(h, height))
            height.value
        }
    }

    /**
     * Set the height that the wallet scans from, which it does before its next scan: a height that it has already
     * scanned makes it scan the chain from it again.
     *
     * @param restoreHeight is the height to scan from
     * @throws MoneroError if the wallet is closed
     */
    override fun setRestoreHeight(restoreHeight: Long) {
        checkNotClosed()
        run { lib, h -> MoneroNative.ok(lib.monero_wallet_set_restore_height(h, restoreHeight)) }
    }

    // ------------------------------- CLOSING ----------------------------------

    /**
     * Check if the wallet is closed.
     *
     * @return true if the wallet is closed, false otherwise
     */
    override fun isClosed(): Boolean {
        return closed
    }

    /**
     * Close the wallet, writing it to its files first when a save is asked for. Every call of a closed wallet fails
     * with "Wallet is closed", and closing it again does nothing. The close runs after the calls that are in flight, so
     * a sync that runs on the wallet has to be stopped with [requestShutdown] from another thread first.
     *
     * @param save is whether to save the wallet before closing it
     * @throws MoneroError if a save is asked for and the wallet has no path to write to, in which case it stays open, or if the close
     * fails, in which case the wallet stays open too, unless it is one that [onCloseFailed] releases
     */
    @Synchronized
    override fun close(save: Boolean) {
        if (closed) return
        if (save) requirePath()
        var failure: MoneroError? = null
        try {
            run { lib, h -> MoneroNative.ok(lib.monero_wallet_close(h, save)) }
        } catch (e: MoneroError) {
            failure = onCloseFailed(e)
        }
        closed = true

        // the write lock holds requestShutdown out while the handle dies: its read lock around the native call is what
        // keeps the one any-thread call off a freed handle, since the worker can not order it (a sync that is stuck on
        // the worker is what it exists to stop)
        shutdownLock.writeLock().lock()
        try {
            worker.close()
            // the wallet is closed and not freed yet, so freeing a listener removes it from a handle that is there
            synchronized(listeners) {
                listeners.values.forEach { it.close() }
                listeners.clear()
            }
            MoneroNative.lib.monero_wallet_free(handle)
        } finally {
            shutdownLock.writeLock().unlock()
        }
        failure?.let { throw it }
    }

    /**
     * What a wallet does when the close of its native wallet fails: it throws the error, and stays open, or it returns the error to
     * throw after it frees the native handle, or null to throw nothing. A wallet that talks to a server is one that frees the handle,
     * since the network of the server can be down and there is no way to try again.
     *
     * @param error is the error of the close
     * @return the error to throw after the handle is freed, or null for none
     * @throws MoneroError to throw it at once and leave the wallet open
     */
    internal open fun onCloseFailed(error: MoneroError): MoneroError? {
        throw error
    }

    override fun close() {
        close(false)
    }

    // ------------------------------- MULTISIG ----------------------------------

    /**
     * Check if the wallet is a multisig wallet.
     *
     * @return true if the wallet is multisig, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    override fun isMultisig(): Boolean {
        return flag { lib, h, out -> lib.monero_wallet_is_multisig(h, out) }
    }

    /**
     * Check if the wallet needs the multisig hexes of its participants to show the outputs that it received after the
     * last import.
     *
     * @return true if an import is needed, false otherwise
     * @throws MoneroError if the wallet is closed
     */
    override fun isMultisigImportNeeded(): Boolean {
        return flag { lib, h, out -> lib.monero_wallet_is_multisig_import_needed(h, out) }
    }

    /**
     * Get the multisig information of the wallet. A wallet that is not multisig has no threshold and no participants:
     * monero-c answers whatever its memory holds in them, so they are not read and stay null.
     *
     * @return the multisig information
     * @throws MoneroError if the wallet is closed
     */
    override fun getMultisigInfo(): MoneroMultisigInfo {
        checkNotClosed()
        if (!isMultisig()) {
            val info = MoneroMultisigInfo()
            info.isMultisig = false
            info.isReady = false
            return info
        }
        return json { lib, h, out -> lib.monero_wallet_get_multisig_info(h, out) }
    }

    /**
     * Start a multisig wallet from this wallet, which returns the hex that the other participants make their wallets
     * multisig with. The wallet stays multisig-in-progress until enough key exchanges finalize it.
     *
     * @return the multisig hex of this wallet
     * @throws MoneroError if the wallet is closed, or it is not a wallet that a multisig can start from
     */
    override fun prepareMultisig(): String {
        return string { lib, h, out -> lib.monero_wallet_prepare_multisig(h, out) }
    }

    /**
     * Make this wallet a part of a multisig wallet from the prepared hexes of the other participants, which returns
     * the hex that the key exchanges carry.
     *
     * @param multisigHexes are the prepared hexes of the other participants
     * @param threshold is the number of participants that a transfer needs the signature of
     * @param password encrypts the wallet files
     * @return the multisig hex of this wallet for the key exchanges
     * @throws MoneroError if the wallet is closed, or a hex is not a prepared multisig hex
     */
    override fun makeMultisig(multisigHexes: List<String>, threshold: Int, password: String): String {
        return string { lib, h, out -> lib.monero_wallet_make_multisig(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), threshold, password, out) }
    }

    /**
     * Exchange the multisig keys of the participants, which takes as many rounds as the number of participants less
     * the threshold, plus one, before the wallet is final. The last round returns the address of the multisig wallet.
     *
     * @param multisigHexes are the hexes of the other participants
     * @param password encrypts the wallet files
     * @return the state of the wallet after the exchange, which carries the address once the wallet is final
     * @throws MoneroError if the wallet is closed, or a hex is not a multisig hex of this round
     */
    override fun exchangeMultisigKeys(multisigHexes: List<String>, password: String): MoneroMultisigInitResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_exchange_multisig_keys(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), password, out) }
    }

    /**
     * Export the multisig hex of this wallet, which the other participants import to see the outputs that it knows.
     *
     * @return the multisig hex of this wallet
     * @throws MoneroError if the wallet is closed, or the wallet is not a final multisig wallet
     */
    override fun exportMultisigHex(): String {
        return string { lib, h, out -> lib.monero_wallet_export_multisig_hex(h, out) }
    }

    /**
     * Import the multisig hexes of the other participants, which shows this wallet the outputs that they know.
     *
     * @param multisigHexes are the hexes of the other participants
     * @param refreshAfterImport is whether to sync with the daemon after the import
     * @return the number of outputs that the import showed
     * @throws MoneroError if the wallet is closed, or a hex is not a multisig hex
     */
    override fun importMultisigHex(multisigHexes: List<String>, refreshAfterImport: Boolean): Int {
        checkNotClosed()
        return run { lib, h ->
            val numImported = IntByReference()
            MoneroNative.ok(lib.monero_wallet_import_multisig_hex(h, multisigHexes.toTypedArray(), SizeT(multisigHexes.size.toLong()), refreshAfterImport, numImported))
            numImported.value
        }
    }

    /**
     * Sign a multisig transaction from its hex, which a transfer of a threshold wallet makes. The signatures of enough
     * participants turn the hex into a signed one.
     *
     * @param multisigTxHex is the hex of the transaction to sign
     * @return the signed hex once enough participants signed, and the hashes of the transaction
     * @throws MoneroError if the wallet is closed, the hex is not a multisig transaction, or the wallet already
     * signed it
     */
    override fun signMultisigTxHex(multisigTxHex: String): MoneroMultisigSignResult {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_sign_multisig_tx_hex(h, multisigTxHex, out) }
    }

    /**
     * Submit a signed multisig transaction to the network.
     *
     * @param signedMultisigTxHex is the hex of the signed transaction
     * @return the hashes of the transaction
     * @throws MoneroError if the wallet is closed, the hex is not a signed multisig transaction, or the network
     * refuses it
     */
    override fun submitMultisigTxHex(signedMultisigTxHex: String): List<String> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.strings(lib.monero_wallet_submit_multisig_tx_hex(h, signedMultisigTxHex, out), out)
        }
    }

    /**
     * Ask a running sync, or a request to the daemon, to stop, which is the one call that any thread can make on a
     * wallet: the others run one after the other on the thread of the wallet, and a sync that is running on it would
     * never let them. The calls that need the daemon fail from then on, so close the wallet after it.
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

    internal fun txSetOf(result: MoneroTxSetsResult): MoneroTxSet {
        return result.txSets?.singleOrNull() ?: throw MoneroError("monero-c did not return one tx set")
    }

    internal fun checkNotClosed() {
        if (closed) throw MoneroError("Wallet is closed")
    }

    // wallet2 skips the save of a wallet that stays in memory without a word, which loses the wallet when it closes
    internal fun requirePath() {
        if (getPath().isEmpty()) throw MoneroError("The wallet has no path to write to")
    }

    internal fun <T> run(call: (MoneroC, Pointer) -> T): T {
        return worker.call { call(MoneroNative.lib, handle) }
    }

    internal inline fun <reified T> json(crossinline call: (MoneroC, Pointer, PointerByReference) -> Int): T {
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.json(call(lib, h, out), out, object : TypeReference<T>() {})
        }
    }

    internal fun string(call: (MoneroC, Pointer, PointerByReference) -> Int): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.text(call(lib, h, out), out) ?: throw MoneroError("monero-c returned no string")
        }
    }

    internal fun flag(call: (MoneroC, Pointer, ByteByReference) -> Int): Boolean {
        checkNotClosed()
        return run { lib, h ->
            val out = ByteByReference()
            MoneroNative.ok(call(lib, h, out))
            out.value.toInt() != 0
        }
    }

    internal fun height(call: (MoneroC, Pointer, LongByReference) -> Int): Long {
        checkNotClosed()
        return run { lib, h ->
            val out = LongByReference()
            MoneroNative.ok(call(lib, h, out))
            out.value
        }
    }

    /**
     * Call the c abi for a secret of the wallet, which it wipes when it frees the string: the seed and the private
     * keys.
     */
    internal fun secret(call: (MoneroC, Pointer, PointerByReference) -> Int): String {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            MoneroNative.secret(call(lib, h, out), out) ?: throw MoneroError("monero-c returned no string")
        }
    }

    private companion object {
        val TX_HASH = Regex("[0-9a-fA-F]{64}")
    }
}
