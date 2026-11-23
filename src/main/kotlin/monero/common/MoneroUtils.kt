package monero.common

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.math.BigInteger
import monero.daemon.model.MoneroBlock
import monero.daemon.model.MoneroNetworkType
import monero.internal.MoneroNative
import monero.internal.SizeT
import monero.wallet.model.MoneroIntegratedAddress
import monero.wallet.model.MoneroOutputWallet
import monero.wallet.model.MoneroTransfer
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxWallet

/**
 * Collection of Monero utilities. The ones that need the code of Monero are run by monero-c, which is loaded on the
 * first call; the others only walk the models of this library.
 */
object MoneroUtils {

    private val lib get() = MoneroNative.lib

    // monero-c numbers the networks with the values of the enumerated type
    private fun network(networkType: MoneroNetworkType): Int = networkType.ordinal

    /**
     * Refuse the parts of a transaction configuration that the c abi cannot carry or that no destination can hold,
     * before they cross it: a negative amount crosses as a number that wraps around to almost 2^64, and one at or
     * above 2^64 does not cross at all, which the wallet would answer with an error about its money instead of the
     * amount.
     *
     * @param config is the configuration of the transaction to validate
     * @throws MoneroError if an amount is negative or at or above 2^64, a destination names the amount zero, or an
     * index is negative
     */
    fun validateTxConfig(config: monero.wallet.model.MoneroTxConfig) {
        for (amount in listOfNotNull(config.fee, config.belowAmount) + config.destinations.orEmpty().mapNotNull { it.amount }) {
            if (amount.signum() < 0) throw MoneroError("The amount $amount of the config of the transaction is negative")
            if (amount.bitLength() > 64) throw MoneroError("The amount $amount of the config of the transaction is at or above 2^64")
        }
        for (destination in config.destinations.orEmpty()) {
            val amount = destination.amount ?: continue
            if (amount.signum() == 0) throw MoneroError("The destination of the amount zero is not a transfer")
        }
        for (index in listOfNotNull(config.accountIndex) + config.subaddressIndices.orEmpty() + config.subtractFeeFrom.orEmpty()) {
            if (index < 0) throw MoneroError("The index $index of the config of the transaction is negative")
        }
    }

    /**
     * Convert XMR to atomic units. 1 XMR is 10^12 atomic units, and the amount is rounded to the nearest one.
     *
     * @param amountXmr is the amount in XMR to convert
     * @return the amount in atomic units
     * @throws MoneroError if the amount is negative, not finite, or too big for an unsigned 64-bit number
     */
    fun xmrToAtomicUnits(amountXmr: Double): BigInteger {
        val out = LongByReference()
        MoneroNative.ok(lib.monero_utils_xmr_to_atomic_units(amountXmr, out))
        return MoneroNative.unsigned(out.value)
    }

    /**
     * Convert atomic units to XMR.
     *
     * @param atomicUnits is the amount in atomic units to convert
     * @return the amount in XMR
     * @throws MoneroError if the amount is negative, or too big for an unsigned 64-bit number
     */
    fun atomicUnitsToXmr(atomicUnits: BigInteger): Double {
        if (atomicUnits.signum() < 0 || atomicUnits.bitLength() > 64) {
            throw MoneroError("The amount in atomic units is not an unsigned 64-bit number: $atomicUnits")
        }
        return lib.monero_utils_atomic_units_to_xmr(atomicUnits.toLong())
    }

    /**
     * Indicates if the given address is valid for the given network.
     *
     * @param address is the address to validate
     * @param networkType is the network to validate the address against
     * @return true if the address is valid, false otherwise
     */
    fun isValidAddress(address: String?, networkType: MoneroNetworkType): Boolean {
        return lib.monero_utils_is_valid_address(address, network(networkType))
    }

    /**
     * Validate the given address for the given network.
     *
     * @param address is the address to validate
     * @param networkType is the network to validate the address against
     * @throws MoneroError if the address is not valid for the network
     */
    fun validateAddress(address: String?, networkType: MoneroNetworkType) {
        MoneroNative.ok(lib.monero_utils_validate_address(address, network(networkType)))
    }

    /**
     * Indicates if the given private view key is valid.
     *
     * @param privateViewKey is the private view key to validate
     * @return true if the private view key is valid, false otherwise
     */
    fun isValidPrivateViewKey(privateViewKey: String?): Boolean {
        return lib.monero_utils_is_valid_private_view_key(privateViewKey)
    }

    /**
     * Validate the given private view key.
     *
     * @param privateViewKey is the private view key to validate
     * @throws MoneroError if the private view key is not valid
     */
    fun validatePrivateViewKey(privateViewKey: String?) {
        MoneroNative.ok(lib.monero_utils_validate_private_view_key(privateViewKey))
    }

    /**
     * Indicates if the given private spend key is valid.
     *
     * @param privateSpendKey is the private spend key to validate
     * @return true if the private spend key is valid, false otherwise
     */
    fun isValidPrivateSpendKey(privateSpendKey: String?): Boolean {
        return lib.monero_utils_is_valid_private_spend_key(privateSpendKey)
    }

    /**
     * Validate the given private spend key.
     *
     * @param privateSpendKey is the private spend key to validate
     * @throws MoneroError if the private spend key is not valid
     */
    fun validatePrivateSpendKey(privateSpendKey: String?) {
        MoneroNative.ok(lib.monero_utils_validate_private_spend_key(privateSpendKey))
    }

    /**
     * Indicates if the given public view key is valid.
     *
     * @param publicViewKey is the public view key to validate
     * @return true if the public view key is valid, false otherwise
     */
    fun isValidPublicViewKey(publicViewKey: String?): Boolean {
        return lib.monero_utils_is_valid_public_view_key(publicViewKey)
    }

    /**
     * Validate the given public view key.
     *
     * @param publicViewKey is the public view key to validate
     * @throws MoneroError if the public view key is not valid
     */
    fun validatePublicViewKey(publicViewKey: String?) {
        MoneroNative.ok(lib.monero_utils_validate_public_view_key(publicViewKey))
    }

    /**
     * Indicates if the given public spend key is valid.
     *
     * @param publicSpendKey is the public spend key to validate
     * @return true if the public spend key is valid, false otherwise
     */
    fun isValidPublicSpendKey(publicSpendKey: String?): Boolean {
        return lib.monero_utils_is_valid_public_spend_key(publicSpendKey)
    }

    /**
     * Validate the given public spend key.
     *
     * @param publicSpendKey is the public spend key to validate
     * @throws MoneroError if the public spend key is not valid
     */
    fun validatePublicSpendKey(publicSpendKey: String?) {
        MoneroNative.ok(lib.monero_utils_validate_public_spend_key(publicSpendKey))
    }

    /**
     * Indicates if the given mnemonic is valid.
     *
     * @param mnemonic is the mnemonic to validate
     * @param language is the expected language of the mnemonic, or null to accept any language
     * @return true if the mnemonic is valid, false otherwise
     */
    fun isValidMnemonic(mnemonic: String?, language: String? = null): Boolean {
        return lib.monero_utils_is_valid_mnemonic(mnemonic, language)
    }

    /**
     * Validate the given mnemonic.
     *
     * @param mnemonic is the mnemonic to validate
     * @param language is the expected language of the mnemonic, or null to accept any language
     * @throws MoneroError if the mnemonic is not valid
     */
    fun validateMnemonic(mnemonic: String?, language: String? = null) {
        MoneroNative.ok(lib.monero_utils_validate_mnemonic(mnemonic, language))
    }

    /**
     * Indicates if the given language is a language of a mnemonic.
     *
     * @param language is the language to validate
     * @return true if the language is valid, false otherwise
     */
    fun isValidLanguage(language: String?): Boolean {
        return lib.monero_utils_is_valid_language(language)
    }

    /**
     * Indicates if the given payment id is valid.
     *
     * @param paymentId is the payment id to validate, as 16 or 64 hex characters
     * @return true if the payment id is valid, false otherwise
     */
    fun isValidPaymentId(paymentId: String?): Boolean {
        return lib.monero_utils_is_valid_payment_id(paymentId)
    }

    /**
     * Validate the given payment id.
     *
     * @param paymentId is the payment id to validate, as 16 or 64 hex characters
     * @throws MoneroError if the payment id is not valid
     */
    fun validatePaymentId(paymentId: String?) {
        MoneroNative.ok(lib.monero_utils_validate_payment_id(paymentId))
    }

    /**
     * Indicates if the given long payment id is valid.
     *
     * @param paymentId is the payment id to validate, as 64 hex characters
     * @return true if the payment id is a valid long payment id, false otherwise
     */
    fun isValidPaymentIdLong(paymentId: String?): Boolean {
        return lib.monero_utils_is_valid_payment_id_long(paymentId)
    }

    /**
     * Validate the given long payment id.
     *
     * @param paymentId is the payment id to validate, as 64 hex characters
     * @throws MoneroError if the payment id is not a valid long payment id
     */
    fun validatePaymentIdLong(paymentId: String?) {
        MoneroNative.ok(lib.monero_utils_validate_payment_id_long(paymentId))
    }

    /**
     * Indicates if the given short payment id is valid.
     *
     * @param paymentId is the payment id to validate, as 16 hex characters
     * @return true if the payment id is a valid short payment id, false otherwise
     */
    fun isValidPaymentIdShort(paymentId: String?): Boolean {
        return lib.monero_utils_is_valid_payment_id_short(paymentId)
    }

    /**
     * Validate the given short payment id.
     *
     * @param paymentId is the payment id to validate, as 16 hex characters
     * @throws MoneroError if the payment id is not a valid short payment id
     */
    fun validatePaymentIdShort(paymentId: String?) {
        MoneroNative.ok(lib.monero_utils_validate_payment_id_short(paymentId))
    }

    /**
     * Get an integrated address from a standard address and a payment id.
     *
     * @param networkType is the network of the address
     * @param standardAddress is the standard address to integrate the payment id into
     * @param paymentId is the payment id to integrate, or null to generate a random one
     * @return the integrated address
     * @throws MoneroError if the address is not valid for the network, or the payment id is not valid
     */
    fun getIntegratedAddress(networkType: MoneroNetworkType, standardAddress: String, paymentId: String? = null): MoneroIntegratedAddress {
        val out = PointerByReference()
        return MoneroNative.json(lib.monero_utils_get_integrated_address(network(networkType), standardAddress, paymentId, out), out, MoneroIntegratedAddress::class.java)
    }

    /**
     * Create a payment URI from a configuration of a transaction, which has one destination.
     *
     * @param txConfig is the configuration of the transaction to make the payment URI for
     * @param networkType is the network to format the address of the URI for
     * @return the payment URI
     * @throws MoneroError if the configuration is not valid, or an amount of it is not an unsigned 64-bit number
     */
    fun getPaymentUri(txConfig: MoneroTxConfig, networkType: MoneroNetworkType = MoneroNetworkType.MAINNET): String {
        txConfig.destinations?.forEach { destination -> validateAmount(destination.amount) }
        validateAmount(txConfig.fee)
        validateAmount(txConfig.belowAmount)
        val out = PointerByReference()
        val uri = MoneroNative.text(lib.monero_utils_get_payment_uri(MoneroJson.serialize(txConfig), network(networkType), out), out)
        return uri ?: throw MoneroError("monero-c returned no payment URI")
    }

    /**
     * Parse a payment URI into a configuration of a transaction.
     *
     * @param uri is the payment URI to parse
     * @param networkType is the network to validate the address of the URI against
     * @return the configuration of the transaction that the URI describes
     * @throws MoneroError if the URI is not valid
     */
    fun parsePaymentUri(uri: String, networkType: MoneroNetworkType = MoneroNetworkType.MAINNET): MoneroTxConfig {
        val out = PointerByReference()
        return MoneroNative.json(lib.monero_utils_parse_payment_uri(uri, network(networkType), out), out, MoneroTxConfig::class.java)
    }

    /**
     * Convert a JSON string to its binary form, which is the portable storage format of the binary RPC of a daemon.
     * JSON that is not valid is not an error: monero-c reads it as an empty object, and this returns the binary form of
     * that.
     *
     * @param json is the JSON to convert
     * @return the binary form of the JSON
     * @throws MoneroError if the JSON nests objects and arrays deeper than 64 levels
     */
    fun jsonToBinary(json: String): ByteArray {
        val out = PointerByReference()
        val count = MoneroNative.newSize()
        return MoneroNative.bytes(lib.monero_utils_json_to_binary(json, out, count), out, count)
    }

    /**
     * Convert a binary form, which is the portable storage format of the binary RPC of a daemon, to its JSON. A binary
     * form that is not valid is not an error either: monero-c reads it as an empty object, and this returns the JSON of
     * that.
     *
     * @param binary is the binary form to convert
     * @return the JSON of the binary form
     */
    fun binaryToJson(binary: ByteArray): String {
        val out = PointerByReference()
        val json = MoneroNative.text(lib.monero_utils_binary_to_json(binary, SizeT(binary.size.toLong()), out), out)
        return json ?: throw MoneroError("monero-c returned no JSON")
    }

    // monero-c describes these two functions the other way round in its header. monero-cpp parses a get_blocks_by_height.bin response
    // with the first and a get_blocks.bin response with the second, which the messages of their errors say too, and so does monero-python

    /**
     * Convert the response of the binary RPC `get_blocks_by_height.bin` of a daemon to the JSON of its blocks: `blocks`
     * has each block as a JSON string, `txs` the transactions of each block, `block_hashes` their hashes, and `status`
     * and `untrusted` come from the response.
     *
     * @param binary is the response, as the daemon sent it
     * @return the JSON of the blocks of the response
     * @throws MoneroError if the response is not valid
     */
    fun binaryBlocksToJson(binary: ByteArray): String {
        val out = PointerByReference()
        val json = MoneroNative.text(lib.monero_utils_binary_blocks_to_json(binary, SizeT(binary.size.toLong()), out), out)
        return json ?: throw MoneroError("monero-c returned no JSON")
    }

    /**
     * Convert the response of the binary RPC `get_blocks.bin` of a daemon to the JSON of its blocks, which has what
     * [binaryBlocksToJson] has, and `start_height` and `current_height` too.
     *
     * @param binary is the response, as the daemon sent it
     * @return the JSON of the blocks of the response
     * @throws MoneroError if the response is not valid
     */
    fun binaryBlocksFastToJson(binary: ByteArray): String {
        val out = PointerByReference()
        val json = MoneroNative.text(lib.monero_utils_binary_blocks_fast_to_json(binary, SizeT(binary.size.toLong()), out), out)
        return json ?: throw MoneroError("monero-c returned no JSON")
    }

    /**
     * Convert a map to its binary form, which is the one of the JSON of the map.
     *
     * @param jsonMap is the map to convert
     * @return the binary form of the map
     * @throws MoneroError if the map can not be written as JSON
     */
    fun dictToBinary(jsonMap: Map<String, Any>): ByteArray {
        return jsonToBinary(MoneroJson.serialize(jsonMap))
    }

    /**
     * Convert a binary form to the map of its JSON. A binary form that is not valid reads as an empty object, as in
     * binaryToJson. A whole number of the map comes back as the Int, the Long or the BigInteger that fits it, and a
     * double that is a whole number comes back as a whole number too, so a map is equal to the one that it round trips
     * to only when its numbers are Ints.
     *
     * @param binary is the binary form to convert
     * @return the map of the JSON of the binary form
     * @throws MoneroError if the JSON of the binary form can not be read as a map
     */
    fun binaryToDict(binary: ByteArray): Map<String, Any> {
        return MoneroJson.deserialize(binaryToJson(binary), object : TypeReference<Map<String, Any>>() {})
    }

    /**
     * Get the version of this library.
     *
     * @return the version of this library
     */
    fun getVersion(): String {
        return VERSION
    }

    /**
     * Set the log level of monero-c, which picks the categories that are logged: 0 is warnings and errors only, 1 adds
     * info, 2 debug, and 3 and 4 trace. The level is of the whole process, and [setLogCategories] sets the categories
     * one by one.
     *
     * @param level is the log level, from 0 (least verbose) to 4 (most verbose)
     * @throws MoneroError if the level is not from 0 to 4
     */
    fun setLogLevel(level: Int) {
        if (level !in 0..4) throw MoneroError("The log level must be from 0 to 4")
        lib.monero_utils_set_log_level(level)
    }

    /**
     * Set the log categories of monero-c, which replace the ones that [setLogLevel] chose. The process has one set of
     * categories.
     *
     * @param categories is a comma-separated list of category:level pairs, e.g. "*:WARNING,net:FATAL". A '+' at the
     * start adds them to the current categories, and a '-' removes them from it
     */
    fun setLogCategories(categories: String) {
        lib.monero_utils_set_log_categories(categories)
    }

    /**
     * Set where monero-c writes its log. The process has one log: it goes to the file, and to the console too if
     * asked, and a new call replaces the previous one.
     *
     * @param path is the base path of the log file
     * @param console is whether to write the log to the console too
     */
    fun configureLogging(path: String, console: Boolean) {
        lib.monero_utils_configure_logging(path, console)
    }

    /**
     * Get the ring size that the network enforces.
     *
     * @return the ring size
     */
    fun getRingSize(): Int {
        return lib.monero_utils_get_ring_size()
    }

    /**
     * Get the distinct blocks of the given transactions, in the order in which they are first seen. A transaction
     * without a block, which is not confirmed, is put in a block without a height, which is shared with the other
     * unconfirmed transactions.
     *
     * @param txs are the transactions to get the blocks of, which are put in the shared block when they have none
     * @return the distinct blocks of the transactions
     */
    fun getBlocksFromTxs(txs: List<MoneroTxWallet>): List<MoneroBlock> {
        val blocks = ArrayList<MoneroBlock>()
        var unconfirmed: MoneroBlock? = null
        for (tx in txs) {
            val block = tx.block
            if (block == null) {
                val shared = unconfirmed ?: MoneroBlock().also { blocks.add(it) }
                unconfirmed = shared
                shared.txs = (shared.txs ?: emptyList()) + tx
            } else if (!blocks.contains(block)) {
                blocks.add(block)
            }
        }
        return blocks
    }

    /**
     * Get the distinct blocks of the transactions of the given transfers, in the order in which the transactions are
     * first seen. A transaction without a block, which is not confirmed, is put in a block without a height, which is
     * shared with the other unconfirmed transactions.
     *
     * @param transfers are the transfers to get the blocks of the transactions of, which are put in the shared block when they have none
     * @return the distinct blocks of the transactions of the transfers
     * @throws MoneroError if a transfer has no transaction
     */
    fun getBlocksFromTransfers(transfers: List<MoneroTransfer>): List<MoneroBlock> {
        val blocks = ArrayList<MoneroBlock>()
        var unconfirmed: MoneroBlock? = null
        for (transfer in transfers) {
            val tx = transfer.tx ?: throw MoneroError("Transfer has no tx")
            val block = tx.block
            if (block == null) {
                val shared = unconfirmed ?: MoneroBlock().also { blocks.add(it) }
                unconfirmed = shared
                shared.txs = (shared.txs ?: emptyList()) + tx
            } else if (!blocks.contains(block)) {
                blocks.add(block)
            }
        }
        return blocks
    }

    /**
     * Get the distinct blocks of the transactions of the given outputs, in the order in which the transactions are
     * first seen. Unlike the transactions and the transfers, the transaction of an output has to have a block: an
     * output that is not confirmed is an error, not a block without a height.
     *
     * @param outputs are the outputs to get the blocks of the transactions of
     * @return the distinct blocks of the transactions of the outputs
     * @throws MoneroError if an output has no transaction, or its transaction has no block
     */
    fun getBlocksFromOutputs(outputs: List<MoneroOutputWallet>): List<MoneroBlock> {
        val blocks = ArrayList<MoneroBlock>()
        for (output in outputs) {
            val tx = output.tx ?: throw MoneroError("Output has no tx")
            val block = tx.block ?: throw MoneroError("Need to handle unconfirmed output")
            if (!blocks.contains(block)) {
                blocks.add(block)
            }
        }
        return blocks
    }

    // the C reads an amount of a configuration as an unsigned 64-bit number, and it rejects one that is bigger with a message that names neither the field nor the value
    private fun validateAmount(amount: BigInteger?) {
        if (amount != null && (amount.signum() < 0 || amount.bitLength() > 64)) {
            throw MoneroError("The amount in atomic units is not an unsigned 64-bit number: $amount")
        }
    }

    // the version of the build, which build.gradle.kts also gives to its artifacts
    private const val VERSION = "0.1.0-SNAPSHOT"
}
