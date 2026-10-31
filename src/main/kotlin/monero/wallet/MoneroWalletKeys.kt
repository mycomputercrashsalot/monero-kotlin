package monero.wallet

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import monero.common.MoneroError
import monero.common.MoneroJson
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
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroWalletConfig

/**
 * Implements a keys-only Monero wallet: the keys, the seed and the addresses of an account, with no files, no chain
 * and no transactions, which is why it needs no daemon. It is the keys wallet of monero-cpp behind the C ABI of
 * monero-c, created with one of its three constructors (monero_c_wallet.h:235-268). Close it to release the native
 * handle.
 *
 * The calls of a wallet run one after the other on a thread of their own, since the C ABI allows one thread per wallet
 * handle (monero_c_wallet.h:49-53).
 */
class MoneroWalletKeys private constructor(private val handle: Pointer) : AutoCloseable {

    private val worker = NativeWorker("MoneroWalletKeys")
    private var closed = false

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
     * Get an account of the wallet, which a keys-only wallet derives from its keys: the account has its index and its
     * primary address, and it never has subaddresses, which a keys-only wallet cannot enumerate
     * (monero_c_wallet.h:502).
     *
     * @param accountIdx is the account index
     * @param includeSubaddresses is whether to include the subaddresses of the account, which must be false
     * @return the account
     * @throws MoneroError if the wallet is closed, or subaddresses are asked for, which a keys-only wallet cannot
     * enumerate
     */
    fun getAccount(accountIdx: Int, includeSubaddresses: Boolean = false): MoneroAccount {
        checkNotClosed()
        return json { lib, h, out -> lib.monero_wallet_get_account(h, accountIdx, includeSubaddresses, out) }
    }

    /**
     * Get a subaddress of an account, which the keys wallet derives from its keys. The C ABI has a function for it
     * that only a full wallet answers, so this reads the subaddress through the plural call, the way the python
     * binding does (monero_c_wallet.h:515).
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
     * Get the subaddresses of an account at the given indices. A keys-only wallet cannot enumerate its subaddresses,
     * so the indices name the ones to derive (monero_c_wallet.h:515).
     *
     * @param accountIdx is the account index
     * @param subaddressIndices are the subaddress indices within the account
     * @return the subaddresses, in the order of the indices
     * @throws MoneroError if the wallet is closed, or no indices are given, which a keys-only wallet cannot enumerate
     */
    fun getSubaddresses(accountIdx: Int, subaddressIndices: List<Int>): List<MoneroSubaddress> {
        checkNotClosed()
        return run { lib, h ->
            val out = PointerByReference()
            val indices = subaddressIndices.toIntArray()
            MoneroNative.json(lib.monero_wallet_get_subaddresses(h, accountIdx, indices, SizeT(indices.size.toLong()), out), out, object : TypeReference<List<MoneroSubaddress>>() {})
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
     * Check if the wallet is closed.
     *
     * @return true if the wallet is closed, false otherwise
     */
    fun isClosed(): Boolean {
        return closed
    }

    /**
     * Close the wallet, without saving it, since a keys-only wallet has no files: closing it with a save is an error,
     * and the wallet stays open. Every call of a closed wallet fails with "Wallet is closed", and closing it again
     * does nothing (monero_c_wallet.h:285).
     *
     * @param save is whether to save the wallet before closing it, which must be false
     * @throws MoneroError if a save is asked for, which a keys-only wallet does not support
     */
    @Synchronized
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
         * Create a keys-only wallet with a new random seed (monero_c_wallet.h:243).
         *
         * @param config is the configuration of the wallet, which needs its network type, and can name the language
         * of the seed
         * @return the new wallet
         * @throws MoneroError if the configuration has no network type, or its language is not a language of a seed
         */
        fun createWalletRandom(config: MoneroWalletConfig): MoneroWalletKeys {
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")
            return wallet { lib, out -> lib.monero_wallet_keys_create_random(networkType.ordinal, config.language, out) }
        }

        /**
         * Restore a keys-only wallet from its mnemonic seed (monero_c_wallet.h:255).
         *
         * @param config is the configuration of the wallet, which needs its network type and its seed, and can give
         * the seed an offset, which is a passphrase that extends the seed, and name the language of the seed
         * @return the restored wallet
         * @throws MoneroError if the configuration has no network type or no seed, or its seed is not a mnemonic, or
         * its language is not a language of a seed
         */
        fun createWalletFromSeed(config: MoneroWalletConfig): MoneroWalletKeys {
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")
            val seed = config.seed ?: throw MoneroError("Must provide wallet seed")
            return wallet { lib, out -> lib.monero_wallet_keys_create_from_seed(networkType.ordinal, seed, config.seedOffset, config.language, out) }
        }

        /**
         * Restore a keys-only wallet from its keys. Without a private spend key the wallet is view-only, and without
         * an address it needs the private spend key, from which it derives the address (monero_c_wallet.h:268).
         *
         * @param config is the configuration of the wallet, which needs its network type and at least one of its
         * private keys, and can give the primary address and name the language of the seed
         * @return the restored wallet
         * @throws MoneroError if the configuration has no network type, no key to restore from, a private view key
         * without the address, or keys that do not match the address
         */
        fun createWalletFromKeys(config: MoneroWalletConfig): MoneroWalletKeys {
            val networkType = config.networkType ?: throw MoneroError("Must provide wallet network type")

            // the C ABI takes the address and the private view key as strings that are never null, empty when they
            // are unused, which the keys wallet of monero-cpp reads as absent (monero_c_wallet.cpp:143,611-619)
            val address = config.primaryAddress ?: ""
            val privateViewKey = config.privateViewKey ?: ""
            val privateSpendKey = config.privateSpendKey ?: ""
            return wallet { lib, out -> lib.monero_wallet_keys_create_from_keys(networkType.ordinal, address, privateViewKey, privateSpendKey, config.language, out) }
        }

        private fun wallet(create: (MoneroC, PointerByReference) -> Int): MoneroWalletKeys {
            val lib = MoneroNative.lib
            val out = PointerByReference()
            MoneroNative.ok(create(lib, out))
            return MoneroWalletKeys(out.value)
        }
    }
}
