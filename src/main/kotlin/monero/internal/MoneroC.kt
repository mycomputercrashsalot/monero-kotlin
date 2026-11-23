package monero.internal

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Declarations of the functions of monero-c. No logic: results, memory and JSON are handled by the layers above.
 *
 * A C bool is one byte, so the outputs and the optional inputs that are bools are bytes, and MoneroNative maps the
 * Boolean parameters and the Boolean returns to that byte with its type mapper. An optional input is null when it is not given.
 */
@Suppress("FunctionName")
internal interface MoneroC : Library {

    // utils

    fun monero_last_error(): Pointer?

    // monero_error_code: 0 none, 1 generic, 2 invalid argument, 3 out of memory, 4 rpc, 5 io
    fun monero_last_error_code(): Int

    // the JSON-RPC code of the last failure when it is an rpc error, or the HTTP status of an answer that is not OK, and -1 when it has no code
    fun monero_last_error_rpc_code(): Int

    fun monero_utils_free(ptr: Pointer?)

    fun monero_utils_free_secret(secret: Pointer)

    fun monero_utils_get_abi_version(major: IntByReference, minor: IntByReference, patch: IntByReference): Int

    fun monero_utils_set_log_level(level: Int)

    fun monero_utils_set_log_categories(categories: String?)

    fun monero_utils_configure_logging(path: String?, console: Boolean)

    fun monero_utils_xmr_to_atomic_units(amountXmr: Double, atomicUnits: LongByReference): Int

    fun monero_utils_atomic_units_to_xmr(atomicUnits: Long): Double

    fun monero_utils_is_valid_address(address: String?, networkType: Int): Boolean

    fun monero_utils_is_valid_private_view_key(privateViewKey: String?): Boolean

    fun monero_utils_is_valid_private_spend_key(privateSpendKey: String?): Boolean

    fun monero_utils_is_valid_public_view_key(publicViewKey: String?): Boolean

    fun monero_utils_is_valid_public_spend_key(publicSpendKey: String?): Boolean

    fun monero_utils_is_valid_payment_id(paymentId: String?): Boolean

    fun monero_utils_is_valid_mnemonic(mnemonic: String?, language: String?): Boolean

    fun monero_utils_is_valid_language(language: String?): Boolean

    fun monero_utils_validate_address(address: String?, networkType: Int): Int

    fun monero_utils_validate_private_view_key(privateViewKey: String?): Int

    fun monero_utils_validate_private_spend_key(privateSpendKey: String?): Int

    fun monero_utils_validate_public_view_key(publicViewKey: String?): Int

    fun monero_utils_validate_public_spend_key(publicSpendKey: String?): Int

    fun monero_utils_validate_payment_id(paymentId: String?): Int

    fun monero_utils_validate_mnemonic(mnemonic: String?, language: String?): Int

    fun monero_utils_get_integrated_address(networkType: Int, standardAddress: String, paymentId: String?, json: PointerByReference): Int

    fun monero_utils_get_payment_uri(txConfigJson: String, networkType: Int, uri: PointerByReference): Int

    fun monero_utils_parse_payment_uri(uri: String, networkType: Int, json: PointerByReference): Int

    fun monero_utils_json_to_binary(json: String, data: PointerByReference, count: Pointer): Int

    fun monero_utils_binary_to_json(data: ByteArray, length: SizeT, json: PointerByReference): Int

    fun monero_utils_binary_blocks_to_json(data: ByteArray, length: SizeT, json: PointerByReference): Int

    fun monero_utils_binary_blocks_fast_to_json(data: ByteArray, length: SizeT, json: PointerByReference): Int

    fun monero_utils_is_valid_payment_id_long(paymentId: String?): Boolean

    fun monero_utils_is_valid_payment_id_short(paymentId: String?): Boolean

    fun monero_utils_validate_payment_id_long(paymentId: String?): Int

    fun monero_utils_validate_payment_id_short(paymentId: String?): Int

    fun monero_utils_get_ring_size(): Int

    // rpc connection

    fun monero_rpc_connection_create(connectionJson: String, out: PointerByReference): Int

    fun monero_rpc_connection_free(connection: Pointer?)

    fun monero_rpc_connection_serialize(connection: Pointer, json: PointerByReference): Int

    fun monero_rpc_connection_set_credentials(connection: Pointer, username: String, password: String): Int

    fun monero_rpc_connection_set_attribute(connection: Pointer, key: String, value: String): Int

    fun monero_rpc_connection_get_attribute(connection: Pointer, key: String, value: PointerByReference): Int

    fun monero_rpc_connection_is_onion(connection: Pointer, isOnion: ByteByReference): Int

    fun monero_rpc_connection_is_i2p(connection: Pointer, isI2p: ByteByReference): Int

    // monero_optional_bool: -1 is unset, 0 false and 1 true
    fun monero_rpc_connection_is_connected(connection: Pointer, isConnected: IntByReference): Int

    fun monero_rpc_connection_check_connection(connection: Pointer, timeoutMs: IntByReference?, changed: ByteByReference): Int

    fun monero_rpc_connection_send_json_request(connection: Pointer, method: String, paramsJson: String?, timeoutMs: IntByReference?, json: PointerByReference): Int

    fun monero_rpc_connection_send_path_request(connection: Pointer, path: String, paramsJson: String?, timeoutMs: IntByReference?, json: PointerByReference): Int

    fun monero_rpc_connection_send_binary_request(connection: Pointer, path: String, paramsJson: String?, timeoutMs: IntByReference?, data: PointerByReference, count: Pointer): Int

    // daemon: connection and listeners

    fun monero_daemon_connect_with(connection: Pointer, out: PointerByReference): Int

    fun monero_daemon_free(daemon: Pointer?)

    fun monero_daemon_get_rpc_connection(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_is_connected(daemon: Pointer, connected: ByteByReference): Int

    fun monero_daemon_set_poll_period(daemon: Pointer, periodMs: Long): Int

    fun monero_daemon_listener_create(callbacks: DaemonListenerCallbacks, out: PointerByReference): Int

    fun monero_daemon_listener_free(listener: Pointer?)

    fun monero_daemon_add_listener(daemon: Pointer, listener: Pointer): Int

    fun monero_daemon_remove_listener(daemon: Pointer, listener: Pointer): Int

    // daemon: node, version and chain

    fun monero_daemon_get_version(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_is_trusted(daemon: Pointer, trusted: ByteByReference): Int

    fun monero_daemon_get_height(daemon: Pointer, height: LongByReference): Int

    fun monero_daemon_get_block_hash(daemon: Pointer, height: Long, hash: PointerByReference): Int

    fun monero_daemon_get_info(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_sync_info(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_network_stats(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_hard_fork_info(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_alt_chains(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_alt_block_hashes(daemon: Pointer, json: PointerByReference): Int

    // daemon: mining

    fun monero_daemon_get_block_template(daemon: Pointer, walletAddress: String, reserveSize: IntByReference?, json: PointerByReference): Int

    fun monero_daemon_get_miner_data(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_calculate_pow(daemon: Pointer, majorVersion: Int, height: Long, blockBlob: String, seedHash: String, powHash: PointerByReference): Int

    fun monero_daemon_add_auxiliary_pow(daemon: Pointer, blockTemplateBlob: String, auxPowJson: String, json: PointerByReference): Int

    fun monero_daemon_get_mining_status(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_start_mining(daemon: Pointer, address: String, numThreads: LongByReference?, isBackground: ByteByReference?, ignoreBattery: ByteByReference?): Int

    fun monero_daemon_stop_mining(daemon: Pointer): Int

    fun monero_daemon_generate_blocks(daemon: Pointer, walletAddress: String, numBlocks: Long, prevBlockHash: String?, startingNonce: IntByReference?, json: PointerByReference): Int

    fun monero_daemon_submit_block(daemon: Pointer, blockBlob: String): Int

    fun monero_daemon_submit_blocks(daemon: Pointer, blockBlobs: Array<String>, numBlockBlobs: SizeT): Int

    // daemon: blocks

    fun monero_daemon_get_last_block_header(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_block_header_by_hash(daemon: Pointer, blockHash: String, json: PointerByReference): Int

    fun monero_daemon_get_block_header_by_height(daemon: Pointer, height: Long, json: PointerByReference): Int

    fun monero_daemon_wait_for_next_block_header_timeout(daemon: Pointer, timeoutMs: Int, outJson: PointerByReference, outTimedOut: ByteByReference): Int

    fun monero_daemon_get_block_headers_by_range(daemon: Pointer, startHeight: Long, endHeight: Long, json: PointerByReference): Int

    fun monero_daemon_get_block_by_hash(daemon: Pointer, blockHash: String, json: PointerByReference): Int

    fun monero_daemon_get_blocks_by_hash(daemon: Pointer, blockHashes: Array<String>, numBlockHashes: SizeT, startHeight: Long, prune: Boolean, maxBlockCount: Long, json: PointerByReference): Int

    fun monero_daemon_get_block_by_height(daemon: Pointer, height: Long, json: PointerByReference): Int

    fun monero_daemon_get_blocks_by_height(daemon: Pointer, heights: LongArray, numHeights: SizeT, json: PointerByReference): Int

    fun monero_daemon_get_blocks_by_range(daemon: Pointer, startHeight: LongByReference?, endHeight: LongByReference?, json: PointerByReference): Int

    fun monero_daemon_get_blocks_by_range_chunked(daemon: Pointer, startHeight: LongByReference?, endHeight: LongByReference?, maxChunkSize: LongByReference?, json: PointerByReference): Int

    fun monero_daemon_get_block_hashes(daemon: Pointer, blockHashes: Array<String>, numBlockHashes: SizeT, json: PointerByReference): Int

    // daemon: transactions

    fun monero_daemon_get_txs(daemon: Pointer, txHashes: Array<String>, numTxHashes: SizeT, prune: Boolean, json: PointerByReference): Int

    fun monero_daemon_get_tx_hexes(daemon: Pointer, txHashes: Array<String>, numTxHashes: SizeT, prune: Boolean, json: PointerByReference): Int

    fun monero_daemon_get_miner_tx_sum(daemon: Pointer, height: Long, numBlocks: Long, json: PointerByReference): Int

    fun monero_daemon_get_fee_estimate(daemon: Pointer, graceBlocks: Long, json: PointerByReference): Int

    fun monero_daemon_submit_tx_hex(daemon: Pointer, txHex: String, doNotRelay: Boolean, json: PointerByReference): Int

    fun monero_daemon_relay_tx_by_hash(daemon: Pointer, txHash: String): Int

    fun monero_daemon_relay_txs_by_hash(daemon: Pointer, txHashes: Array<String>, numTxHashes: SizeT): Int

    fun monero_daemon_get_tx_pool(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_tx_pool_hashes(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_tx_pool_backlog(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_tx_pool_stats(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_flush_tx_pool(daemon: Pointer): Int

    fun monero_daemon_flush_tx_pool_hashes(daemon: Pointer, txHashes: Array<String>, numTxHashes: SizeT): Int

    fun monero_daemon_flush_tx_pool_hash(daemon: Pointer, txHash: String): Int

    // daemon: key images and outputs

    fun monero_daemon_get_key_image_spent_statuses(daemon: Pointer, keyImages: Array<String>, numKeyImages: SizeT, statuses: PointerByReference, count: Pointer): Int

    fun monero_daemon_get_output_indices(daemon: Pointer, txHash: String, indices: PointerByReference, count: Pointer): Int

    fun monero_daemon_get_outputs(daemon: Pointer, outputsJson: String, json: PointerByReference): Int

    fun monero_daemon_get_output_histogram(daemon: Pointer, amounts: LongArray, numAmounts: SizeT, minCount: IntByReference?, maxCount: IntByReference?, isUnlocked: ByteByReference?, recentCutoff: IntByReference?, json: PointerByReference): Int

    fun monero_daemon_get_output_distribution(daemon: Pointer, amounts: LongArray, numAmounts: SizeT, isCumulative: ByteByReference?, startHeight: LongByReference?, endHeight: LongByReference?, json: PointerByReference): Int

    // daemon: peers and bandwidth

    fun monero_daemon_get_download_limit(daemon: Pointer, limit: IntByReference): Int

    fun monero_daemon_set_download_limit(daemon: Pointer, limit: Int, newLimit: IntByReference): Int

    fun monero_daemon_reset_download_limit(daemon: Pointer, limit: IntByReference): Int

    fun monero_daemon_get_upload_limit(daemon: Pointer, limit: IntByReference): Int

    fun monero_daemon_set_upload_limit(daemon: Pointer, limit: Int, newLimit: IntByReference): Int

    fun monero_daemon_reset_upload_limit(daemon: Pointer, limit: IntByReference): Int

    fun monero_daemon_get_peers(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_known_peers(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_get_public_peers(daemon: Pointer, includeOffline: Boolean, json: PointerByReference): Int

    fun monero_daemon_set_outgoing_peer_limit(daemon: Pointer, limit: Int): Int

    fun monero_daemon_set_incoming_peer_limit(daemon: Pointer, limit: Int): Int

    fun monero_daemon_get_peer_bans(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_set_peer_bans(daemon: Pointer, bansJson: String): Int

    fun monero_daemon_set_peer_ban(daemon: Pointer, banJson: String): Int

    fun monero_daemon_get_peer_ban(daemon: Pointer, address: String, json: PointerByReference): Int

    // daemon: blockchain maintenance, configuration, logs and updates

    fun monero_daemon_prune_blockchain(daemon: Pointer, check: Boolean, json: PointerByReference): Int

    fun monero_daemon_save_blockchain(daemon: Pointer): Int

    fun monero_daemon_pop_blocks(daemon: Pointer, numBlocks: Long, height: LongByReference): Int

    fun monero_daemon_flush_cache(daemon: Pointer, badBlocks: Boolean): Int

    fun monero_daemon_set_bootstrap_daemon(daemon: Pointer, address: String, username: String, password: String, proxy: String): Int

    fun monero_daemon_remove_bootstrap_daemon(daemon: Pointer): Int

    fun monero_daemon_set_log_hash_rate(daemon: Pointer, isVisible: Boolean): Int

    fun monero_daemon_set_log_level(daemon: Pointer, level: Int): Int

    fun monero_daemon_set_log_categories(daemon: Pointer, categories: String, result: PointerByReference): Int

    fun monero_daemon_check_for_update(daemon: Pointer, json: PointerByReference): Int

    fun monero_daemon_download_update(daemon: Pointer, path: String, json: PointerByReference): Int

    fun monero_daemon_stop(daemon: Pointer): Int

    // wallet

    fun monero_wallet_keys_create_random(networkType: Int, language: String?, out: PointerByReference): Int

    fun monero_wallet_keys_create_from_seed(networkType: Int, seed: String, seedOffset: String?, language: String?, out: PointerByReference): Int

    fun monero_wallet_keys_create_from_keys(networkType: Int, address: String?, privateViewKey: String?, privateSpendKey: String?, language: String?, out: PointerByReference): Int

    fun monero_wallet_get_seed_languages(json: PointerByReference): Int

    // wallet: full

    fun monero_wallet_create_random(path: String, password: String?, networkType: Int, language: String?, out: PointerByReference): Int

    fun monero_wallet_create_from_seed(path: String, password: String?, networkType: Int, seed: String, seedOffset: String?, restoreHeight: Long, language: String?, out: PointerByReference): Int

    fun monero_wallet_create_from_keys(path: String, password: String?, networkType: Int, address: String, privateViewKey: String, privateSpendKey: String?, restoreHeight: Long, language: String?, out: PointerByReference): Int

    fun monero_wallet_create(configJson: String, out: PointerByReference): Int

    fun monero_wallet_open(path: String, password: String?, networkType: Int, out: PointerByReference): Int

    fun monero_wallet_exists(path: String, exists: ByteByReference): Int

    fun monero_utils_get_default_ringdb_path(networkType: Int, path: PointerByReference): Int

    // the buffers of the wallet files of a full wallet, and the wallet that opens from them; the cache can be null
    fun monero_wallet_get_keys_file_buffer(wallet: Pointer, password: String?, viewOnly: Boolean, data: PointerByReference, length: Pointer): Int

    fun monero_wallet_get_cache_file_buffer(wallet: Pointer, data: PointerByReference, length: Pointer): Int

    fun monero_wallet_open_data(password: String?, networkType: Int, keysData: ByteArray, keysLength: SizeT, cacheData: ByteArray?, cacheLength: SizeT, out: PointerByReference): Int

    fun monero_wallet_save(wallet: Pointer): Int

    fun monero_wallet_get_attribute(wallet: Pointer, key: String, found: ByteByReference, value: PointerByReference): Int

    fun monero_wallet_set_attribute(wallet: Pointer, key: String, value: String): Int

    fun monero_wallet_get_default_fee_priority(wallet: Pointer, priority: IntByReference): Int

    fun monero_wallet_move_to(wallet: Pointer, path: String, password: String?): Int

    fun monero_wallet_get_path(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_restore_height(wallet: Pointer, height: LongByReference): Int

    fun monero_wallet_set_restore_height(wallet: Pointer, restoreHeight: Long): Int

    fun monero_wallet_change_password(wallet: Pointer, oldPassword: String, newPassword: String): Int

    fun monero_wallet_set_daemon_connection(wallet: Pointer, uri: String, username: String, password: String, proxyUri: String, isTrusted: ByteByReference?, sslVerify: Boolean): Int

    fun monero_wallet_get_daemon_connection(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_is_daemon_trusted(wallet: Pointer, trusted: ByteByReference): Int

    fun monero_wallet_get_height(wallet: Pointer, height: LongByReference): Int

    fun monero_wallet_get_balance(wallet: Pointer, balance: LongByReference): Int

    fun monero_wallet_get_unlocked_balance(wallet: Pointer, balance: LongByReference): Int

    fun monero_wallet_sync(wallet: Pointer, startHeight: LongByReference?, listener: Pointer?, json: PointerByReference): Int

    fun monero_wallet_start_syncing(wallet: Pointer, syncPeriodMs: Long): Int

    fun monero_wallet_stop_syncing(wallet: Pointer): Int

    fun monero_wallet_is_connected_to_daemon(wallet: Pointer, connected: ByteByReference): Int

    fun monero_wallet_is_daemon_synced(wallet: Pointer, synced: ByteByReference): Int

    fun monero_wallet_is_synced(wallet: Pointer, synced: ByteByReference): Int

    fun monero_wallet_get_daemon_height(wallet: Pointer, height: LongByReference): Int

    fun monero_wallet_get_daemon_max_peer_height(wallet: Pointer, height: LongByReference): Int

    fun monero_wallet_get_height_by_date(wallet: Pointer, year: Short, month: Byte, day: Byte, height: LongByReference): Int

    fun monero_wallet_get_txs(wallet: Pointer, queryJson: String?, json: PointerByReference): Int

    fun monero_wallet_get_transfers(wallet: Pointer, queryJson: String?, json: PointerByReference): Int

    fun monero_wallet_get_outputs(wallet: Pointer, queryJson: String?, json: PointerByReference): Int

    fun monero_wallet_export_outputs(wallet: Pointer, all: Boolean, hex: PointerByReference): Int

    fun monero_wallet_import_outputs(wallet: Pointer, outputsHex: String, numImported: IntByReference): Int

    fun monero_wallet_freeze_output(wallet: Pointer, keyImage: String): Int

    fun monero_wallet_thaw_output(wallet: Pointer, keyImage: String): Int

    fun monero_wallet_is_output_frozen(wallet: Pointer, keyImage: String, frozen: ByteByReference): Int

    fun monero_wallet_export_key_images(wallet: Pointer, all: Boolean, json: PointerByReference): Int

    fun monero_wallet_import_key_images(wallet: Pointer, keyImagesJson: String, offset: Long, json: PointerByReference): Int

    fun monero_wallet_create_txs(wallet: Pointer, configJson: String, json: PointerByReference): Int

    fun monero_wallet_create_tx(wallet: Pointer, configJson: String, json: PointerByReference): Int

    fun monero_wallet_sweep_output(wallet: Pointer, configJson: String, json: PointerByReference): Int

    fun monero_wallet_sweep_unlocked(wallet: Pointer, configJson: String, json: PointerByReference): Int

    fun monero_wallet_sweep_dust(wallet: Pointer, relay: Boolean, json: PointerByReference): Int

    fun monero_wallet_relay_txs(wallet: Pointer, txMetadatas: Array<String>, numTxMetadatas: SizeT, json: PointerByReference): Int

    fun monero_wallet_relay_txs_json(wallet: Pointer, txsJson: String, json: PointerByReference): Int

    fun monero_wallet_describe_tx_set(wallet: Pointer, txSetJson: String, json: PointerByReference): Int

    fun monero_wallet_sign_txs(wallet: Pointer, unsignedTxHex: String, json: PointerByReference): Int

    fun monero_wallet_submit_txs(wallet: Pointer, signedTxHex: String, json: PointerByReference): Int

    fun monero_wallet_relay_tx(wallet: Pointer, txMetadata: String, out: PointerByReference): Int

    fun monero_wallet_relay_tx_json(wallet: Pointer, txJson: String, out: PointerByReference): Int

    fun monero_wallet_is_multisig(wallet: Pointer, multisig: ByteByReference): Int

    fun monero_wallet_is_multisig_import_needed(wallet: Pointer, needed: ByteByReference): Int

    fun monero_wallet_get_multisig_info(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_prepare_multisig(wallet: Pointer, multisigHex: PointerByReference): Int

    fun monero_wallet_make_multisig(wallet: Pointer, multisigHexes: Array<String>, numMultisigHexes: SizeT, threshold: Int, password: String, multisigHex: PointerByReference): Int

    fun monero_wallet_exchange_multisig_keys(wallet: Pointer, multisigHexes: Array<String>, numMultisigHexes: SizeT, password: String, json: PointerByReference): Int

    fun monero_wallet_export_multisig_hex(wallet: Pointer, multisigHex: PointerByReference): Int

    fun monero_wallet_import_multisig_hex(wallet: Pointer, multisigHexes: Array<String>, numMultisigHexes: SizeT, refreshAfterImport: Boolean, numImported: IntByReference): Int

    fun monero_wallet_sign_multisig_tx_hex(wallet: Pointer, multisigTxHex: String, json: PointerByReference): Int

    fun monero_wallet_submit_multisig_tx_hex(wallet: Pointer, signedMultisigTxHex: String, json: PointerByReference): Int

    fun monero_wallet_wait_for_next_block_timeout(wallet: Pointer, timeoutMs: Int, height: LongByReference, timedOut: ByteByReference): Int

    fun monero_wallet_scan_txs(wallet: Pointer, txHashes: Array<String>, numTxHashes: SizeT): Int

    fun monero_wallet_rescan_blockchain(wallet: Pointer): Int

    fun monero_wallet_rescan_spent(wallet: Pointer): Int

    // the threads and the two flags are optional: null is one thread, and false
    fun monero_wallet_start_mining(wallet: Pointer, numThreads: LongByReference?, backgroundMining: ByteByReference?, ignoreBattery: ByteByReference?): Int

    fun monero_wallet_stop_mining(wallet: Pointer): Int

    // wallet: the tags of the accounts, which a wallet of a monero-wallet-rpc server has and a full wallet has not
    fun monero_wallet_set_account_tag_label(wallet: Pointer, tag: String, label: String): Int

    fun monero_wallet_get_account_tags(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_tag_accounts(wallet: Pointer, tag: String, accountIndices: IntArray, numAccountIndices: SizeT): Int

    fun monero_wallet_untag_accounts(wallet: Pointer, accountIndices: IntArray, numAccountIndices: SizeT): Int

    // wallet: listeners
    fun monero_wallet_listener_create(callbacks: WalletListenerCallbacks, out: PointerByReference): Int

    fun monero_wallet_listener_free(listener: Pointer?)

    fun monero_wallet_add_listener(wallet: Pointer, listener: Pointer): Int

    fun monero_wallet_remove_listener(wallet: Pointer, listener: Pointer): Int

    fun monero_wallet_remove_listeners(wallet: Pointer): Int

    // wallet: the wallet of a monero-wallet-rpc server, which has the handle of the other wallets, so the functions above work on it
    fun monero_wallet_rpc_connect(connection: Pointer, out: PointerByReference): Int

    fun monero_wallet_rpc_open_wallet(wallet: Pointer, name: String, walletPassword: String?): Int

    fun monero_wallet_rpc_create_wallet(wallet: Pointer, configJson: String): Int

    fun monero_wallet_rpc_open(uri: String, username: String?, password: String?, name: String, walletPassword: String?, out: PointerByReference): Int

    fun monero_wallet_rpc_create_random(uri: String, username: String?, password: String?, name: String, walletPassword: String?, language: String?, out: PointerByReference): Int

    fun monero_wallet_rpc_create_from_seed(
        uri: String, username: String?, password: String?, name: String, walletPassword: String?, seed: String, seedOffset: String?, restoreHeight: Long, language: String?, out: PointerByReference
    ): Int

    fun monero_wallet_rpc_create_from_keys(
        uri: String, username: String?, password: String?, name: String, walletPassword: String?, address: String, privateViewKey: String, privateSpendKey: String?, restoreHeight: Long, language: String?, out: PointerByReference
    ): Int

    fun monero_wallet_rpc_stop(wallet: Pointer): Int

    fun monero_wallet_rpc_get_seed_languages(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_rpc_get_connection(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_rpc_set_poll_period(wallet: Pointer, periodMs: Long): Int

    // the account and the subaddress are optional
    fun monero_wallet_rpc_get_balances(wallet: Pointer, accountIdx: IntByReference?, subaddressIdx: IntByReference?, json: PointerByReference): Int

    fun monero_wallet_rpc_set_daemon_connection(wallet: Pointer, connectionJson: String, isTrusted: Boolean, sslOptionsJson: String?): Int

    // the one call that any thread can make on a wallet, which the others can not
    fun monero_wallet_request_shutdown(wallet: Pointer): Int

    // wallet: keys and full

    fun monero_wallet_close(wallet: Pointer, save: Boolean): Int

    fun monero_wallet_free(wallet: Pointer)

    fun monero_wallet_get_network_type(wallet: Pointer, networkType: IntByReference): Int

    fun monero_wallet_is_view_only(wallet: Pointer, viewOnly: ByteByReference): Int

    fun monero_wallet_get_seed(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_seed_language(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_primary_address(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_private_view_key(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_private_spend_key(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_public_view_key(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_public_spend_key(wallet: Pointer, out: PointerByReference): Int

    fun monero_wallet_get_version(wallet: Pointer, json: PointerByReference): Int

    fun monero_wallet_get_address(wallet: Pointer, accountIdx: Int, subaddressIdx: Int, out: PointerByReference): Int

    fun monero_wallet_get_integrated_address(wallet: Pointer, standardAddress: String?, paymentId: String?, json: PointerByReference): Int

    fun monero_wallet_decode_integrated_address(wallet: Pointer, integratedAddress: String, json: PointerByReference): Int

    fun monero_wallet_get_account(wallet: Pointer, accountIdx: Int, includeSubaddresses: Boolean, skipBalances: Boolean, json: PointerByReference): Int

    fun monero_wallet_get_subaddresses(wallet: Pointer, accountIdx: Int, subaddressIndices: IntArray?, numSubaddressIndices: SizeT, skipBalances: Boolean, json: PointerByReference): Int

    fun monero_wallet_get_accounts(wallet: Pointer, includeSubaddresses: Boolean, tag: String?, skipBalances: Boolean, json: PointerByReference): Int

    fun monero_wallet_create_account(wallet: Pointer, label: String?, json: PointerByReference): Int

    fun monero_wallet_create_subaddress(wallet: Pointer, accountIdx: Int, label: String?, json: PointerByReference): Int

    fun monero_wallet_set_subaddress_label(wallet: Pointer, accountIdx: Int, subaddressIdx: Int, label: String?): Int

    fun monero_wallet_get_address_index(wallet: Pointer, address: String, json: PointerByReference): Int

    fun monero_wallet_sign_message(wallet: Pointer, message: String, signatureType: Int, accountIdx: Int, subaddressIdx: Int, out: PointerByReference): Int

    fun monero_wallet_verify_message(wallet: Pointer, message: String, address: String, signature: String, json: PointerByReference): Int

    fun monero_wallet_get_payment_uri(wallet: Pointer, configJson: String, out: PointerByReference): Int

    fun monero_wallet_parse_payment_uri(wallet: Pointer, uri: String, json: PointerByReference): Int

    fun monero_wallet_get_tx_note(wallet: Pointer, txHash: String, out: PointerByReference): Int

    fun monero_wallet_set_tx_note(wallet: Pointer, txHash: String, note: String): Int

    fun monero_wallet_get_tx_notes(wallet: Pointer, txHashes: Array<String>, numTxHashes: SizeT, json: PointerByReference): Int

    fun monero_wallet_set_tx_notes(wallet: Pointer, txHashes: Array<String>, notes: Array<String>, numTxHashes: SizeT): Int

    fun monero_wallet_get_tx_key(wallet: Pointer, txHash: String, out: PointerByReference): Int

    fun monero_wallet_check_tx_key(wallet: Pointer, txHash: String, txKey: String, address: String, json: PointerByReference): Int

    fun monero_wallet_get_tx_proof(wallet: Pointer, txHash: String, address: String, message: String?, out: PointerByReference): Int

    fun monero_wallet_check_tx_proof(wallet: Pointer, txHash: String, address: String, message: String?, signature: String, json: PointerByReference): Int

    fun monero_wallet_get_spend_proof(wallet: Pointer, txHash: String, message: String?, out: PointerByReference): Int

    fun monero_wallet_check_spend_proof(wallet: Pointer, txHash: String, message: String?, signature: String, good: ByteByReference): Int

    fun monero_wallet_get_reserve_proof_wallet(wallet: Pointer, message: String?, out: PointerByReference): Int

    fun monero_wallet_get_reserve_proof_account(wallet: Pointer, accountIdx: Int, amount: Long, message: String?, out: PointerByReference): Int

    fun monero_wallet_check_reserve_proof(wallet: Pointer, address: String, message: String?, signature: String, json: PointerByReference): Int

    fun monero_wallet_add_address_book_entry(wallet: Pointer, address: String, description: String?, index: LongByReference): Int

    fun monero_wallet_edit_address_book_entry(wallet: Pointer, index: Long, address: String?, description: String?): Int

    fun monero_wallet_delete_address_book_entry(wallet: Pointer, index: Long): Int

    fun monero_wallet_get_address_book_entries(wallet: Pointer, indices: LongArray?, numIndices: SizeT, json: PointerByReference): Int
    // wallet: light
    fun monero_wallet_light_create(configJson: String, connection: Pointer, out: PointerByReference): Int

    fun monero_wallet_light_open(configJson: String, connection: Pointer, out: PointerByReference): Int

    fun monero_wallet_light_wallet_exists(configJson: String, connection: Pointer, exists: ByteByReference): Int

    fun monero_wallet_light_get_unrefined_spend_count(wallet: Pointer, count: LongByReference): Int
}

/**
 * A size_t, which has the size of a pointer.
 */
internal class SizeT(value: Long) : com.sun.jna.IntegerType(Native.SIZE_T_SIZE, value, true) {

    constructor() : this(0)

    override fun toByte(): Byte = toLong().toByte()

    override fun toShort(): Short = toLong().toShort()

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Called with the JSON of each new block header. The string is only valid during the call, and the call is made on a
 * thread of monero-cpp.
 */
internal interface DaemonBlockHeaderCallback : Callback {
    fun invoke(userData: Pointer?, headerJson: Pointer?)
}

/**
 * The callbacks of a daemon listener. monero-c copies the struct when the listener is created.
 */
@Structure.FieldOrder("structSize", "userData", "onBlockHeader")
internal class DaemonListenerCallbacks : Structure() {
    /**
     * The size of the struct as this code was built with it, which the program sets before the create call: the C ABI
     * copies only that many bytes, and fails if it does not reach the user data (monero_c_daemon.h, struct_size).
     */
    @JvmField
    var structSize: Int = 0

    @JvmField
    var userData: Pointer? = null

    @JvmField
    var onBlockHeader: DaemonBlockHeaderCallback? = null
}

/**
 * Called with the progress of a sync. The message is only valid during the call, and the call is made on a thread of monero-c that the
 * listener owns, one callback after the other.
 */
internal interface WalletSyncProgressCallback : Callback {
    fun invoke(userData: Pointer?, height: Long, startHeight: Long, endHeight: Long, percentDone: Double, message: Pointer?)
}

/**
 * Called with the height of each new block.
 */
internal interface WalletNewBlockCallback : Callback {
    fun invoke(userData: Pointer?, height: Long)
}

/**
 * Called with the balance and the unlocked balance, in atomic units, when they change.
 */
internal interface WalletBalancesChangedCallback : Callback {
    fun invoke(userData: Pointer?, newBalance: Long, newUnlockedBalance: Long)
}

/**
 * Called with the JSON of the output that the wallet received or spent, in its transaction and its block. The string is only valid
 * during the call.
 */
internal interface WalletOutputCallback : Callback {
    fun invoke(userData: Pointer?, outputJson: Pointer?)
}

/**
 * The callbacks of a wallet listener. monero-c copies the struct when the listener is created, and skips a callback that is null.
 */
@Structure.FieldOrder("structSize", "userData", "onSyncProgress", "onNewBlock", "onBalancesChanged", "onOutputReceived", "onOutputSpent")
internal class WalletListenerCallbacks : Structure() {
    /**
     * The size of the struct as this code was built with it, which the program sets before the create call (monero_c_wallet.h,
     * struct_size).
     */
    @JvmField
    var structSize: Int = 0

    @JvmField
    var userData: Pointer? = null

    @JvmField
    var onSyncProgress: WalletSyncProgressCallback? = null

    @JvmField
    var onNewBlock: WalletNewBlockCallback? = null

    @JvmField
    var onBalancesChanged: WalletBalancesChangedCallback? = null

    @JvmField
    var onOutputReceived: WalletOutputCallback? = null

    @JvmField
    var onOutputSpent: WalletOutputCallback? = null
}
