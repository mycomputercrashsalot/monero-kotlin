package monero.wallet

import java.math.BigInteger
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTransferQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The transactions of a light wallet, read with the filters of [MoneroTxQuery] that the light server
 * answers: by the hash, by the incoming and the outgoing side, by the account of the transfer, and by
 * the height that the blocks of the chain hold the transactions at. The metadata of a transaction
 * that the wallet creates without relaying persists for a relay across a close and a reopen of the
 * wallet, which keeps the key of the transaction at the relay only, and a transaction that the chain
 * never held, whose relay the server refused, reads as failed in the answer of its creation while
 * the history of the wallet holds no transaction of it. It needs the monero-lws of
 * src/test/integration/docker-compose.yml and the regtest node behind it, whose chain the other
 * tests of the suite mine onto as well, so every wallet is of a random seed of its own and the
 * blocks that the test does not mean to pay anyone mine to an address that no account of the server
 * watches. It skips when no server answers, like the tests of MoneroWalletLightIntegrationTest,
 * which this class runs after.
 */
@Order(13)
@Tag("integration")
class MoneroWalletLightTxsIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    // the amount to send, in atomic units: five monero
    private val amount = BigInteger.valueOf(5_000_000_000_000L)

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // the mining of the shared chain races the mining of the other tests of the suite now and then,
    // which makes the node refuse a block that it built on a tip that another one replaced, so a
    // refused block is tried again a few times before it is a failure of the test
    private fun mine(numBlocks: Int, address: String) {
        var tries = 0
        while (true) {
            try {
                RegtestSupport.mineBlocks(numBlocks, address)
                return
            } catch (e: MoneroError) {
                if (++tries >= 5) throw e
                Thread.sleep(1000)
            }
        }
    }

    // the server scans the blocks of an account behind the chain, which the other tests keep mining:
    // this waits longer than the minute of LwsSupport.waitScannedToChain, whose deadline a busy
    // scanner of many accounts passes
    private fun waitScanned(address: String, viewKey: String) {
        val deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos()
        while (true) {
            val info = LwsSupport.post("/get_address_info", """{"address":"$address","view_key":"$viewKey"}""")
            val scanned = Regex("\"scanned_block_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            val chain = Regex("\"blockchain_height\": ?([0-9]+)").find(info)?.groupValues?.get(1)?.toLong()
            if (scanned != null && chain != null && scanned >= chain) return
            if (System.nanoTime() >= deadline) throw MoneroError("the scan never reached the height of the chain: it is at $scanned of $chain")
            Thread.sleep(1000)
        }
    }

    /**
     * An address of mainnet that no account of the server watches, which the blocks that the sends
     * need pay their coinbases to: nobody's balance moves with them. A keys-only wallet has the
     * address without the server.
     *
     * @return an address that the server does not watch
     */
    private fun unwatchedAddress(): String {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        return MoneroWalletKeys.createWalletRandom(config).use { miner -> miner.getPrimaryAddress() }
    }

    // a wallet of a random seed, whose coinbases matured: the funding retries, because a
    // reorganization of the shared chain can drop the blocks that a round of it mined
    private fun fundedWallet(coinbases: Int): MoneroWalletLight {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            for (round in 1..10) {
                mine(coinbases, wallet.getPrimaryAddress())
                mine(63, unwatchedAddress())
                waitScanned(wallet.getPrimaryAddress(), wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getUnlockedBalance() > BigInteger.ZERO) return wallet
            }
            throw AssertionError("the wallet never kept unlocked money: ${wallet.getBalance()}")
        } catch (e: Throwable) {
            wallet.close()
            throw e
        }
    }

    // a wallet of a random seed with a single matured coinbase, and a transaction that it sent and
    // that the pool of the node holds: the coinbase is the incoming and the confirmed transaction of
    // the history, and the sent one is the outgoing and the pending
    private fun walletWithAPendingTx(): Pair<MoneroWalletLight, String> {
        val wallet = fundedWallet(1)
        val config = MoneroTxConfig()
        config.accountIndex = 0
        config.addDestination(unwatchedAddress(), amount)
        val tx = wallet.createTxs(config).single()
        val hash = assertNotNull(wallet.relayTx(assertNotNull(tx.metadata)))
        return wallet to hash
    }

    // the filters of the query that the light server answers: the hash of a single transaction, the
    // incoming and the outgoing side, the account of the transfer, and the height of the block that
    // holds a transaction, which a pending one has none of
    @Test
    fun testGetTxsWithFilters() {
        val (wallet, hash) = walletWithAPendingTx()
        try {
            val txs = wallet.getTxs()
            assertEquals(2, txs.size, "the wallet has its coinbase and the transaction that it sent")
            val coinbase = txs.single { tx -> tx.isMinerTx == true }
            val coinbaseHash = assertNotNull(coinbase.hash)
            val coinbaseHeight = assertNotNull(coinbase.height, "the coinbase is in a block of the chain")

            // by the hash
            assertEquals(listOf(coinbaseHash), wallet.getTxs(MoneroTxQuery().apply { this.hash = coinbaseHash }).map { tx -> tx.hash }, "the query by the hash of the coinbase finds the coinbase")
            assertEquals(listOf(hash), wallet.getTxs(MoneroTxQuery().apply { this.hash = hash }).map { tx -> tx.hash }, "the query by the hash of the sent transaction finds it")

            // by the incoming and the outgoing side
            assertEquals(listOf(coinbaseHash), wallet.getTxs(MoneroTxQuery().apply { isIncoming = true }).map { tx -> tx.hash }, "the coinbase is the incoming transaction")
            assertEquals(listOf(hash), wallet.getTxs(MoneroTxQuery().apply { isOutgoing = true }).map { tx -> tx.hash }, "the sent transaction is the outgoing one")

            // by the account of the transfer
            assertEquals(2, wallet.getTxs(MoneroTxQuery().apply { transferQuery = MoneroTransferQuery().apply { accountIndex = 0 } }).size, "both transactions have a transfer of the first account")

            // by the height of the block: the coinbase is at its height, and the pending transaction,
            // which no block holds, has no height, which a query from the height of the coinbase on
            // leaves out
            assertEquals(listOf(coinbaseHash), wallet.getTxs(MoneroTxQuery().apply { minHeight = coinbaseHeight }).map { tx -> tx.hash }, "the query from the height of the coinbase on leaves the pending transaction out")
            assertEquals(emptyList(), wallet.getTxs(MoneroTxQuery().apply { minHeight = coinbaseHeight + 1000 }).map { tx -> tx.hash }, "no transaction is above the height of the chain")
        } finally {
            wallet.close()
        }
    }

    // the metadata of a transaction that the wallet creates without relaying persists for a relay
    // across a close and a reopen of the wallet, which the wallet lives in memory makes the natural
    // way to read: the handle that reopened relays the metadata and answers the hash that the
    // creation answered, and it keeps the key of the transaction at the relay only, since the
    // construction of the transaction that the first handle made died with it
    @Test
    fun testTheMetadataPersistsForARelayAcrossACloseAndReopen() {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        val seed = wallet.getSeed()
        try {
            mine(1, wallet.getPrimaryAddress())
            mine(63, unwatchedAddress())
            waitScanned(wallet.getPrimaryAddress(), wallet.getPrivateViewKey())
            wallet.sync()
            val before = wallet.getBalance()
            assertTrue(before > BigInteger.ZERO, "the wallet has a coinbase to spend")

            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.addDestination(unwatchedAddress(), amount)
            val tx = wallet.createTxs(txConfig).single()
            val hash = assertNotNull(tx.hash)
            val metadata = assertNotNull(tx.metadata)

            // the chain never held the transaction, which the history of the wallet holds none of
            assertEquals(emptyList(), wallet.getTxs(MoneroTxQuery().apply { this.hash = hash }).map { tx -> tx.hash }, "the history of the wallet holds no unrelayed transaction")
            wallet.close()

            val reopenConfig = MoneroWalletConfig()
            reopenConfig.networkType = MoneroNetworkType.MAINNET
            reopenConfig.seed = seed
            val reopened = MoneroWalletLight.openWallet(reopenConfig, connection)
            try {
                // the reopened handle kept no key of the transaction, which it did not build
                val noKey = assertFailsWith<MoneroError> { reopened.getTxKey(hash) }
                assertEquals("No tx secret key is stored for this tx", noKey.message)

                // the metadata persists for the relay, which answers the hash that the creation
                // answered, and the key of the transaction arrives with the relay
                assertEquals(hash, reopened.relayTx(metadata), "the reopened wallet relays the metadata of the transaction")
                val key = assertNotNull(reopened.getTxKey(hash), "the relay keeps the key of the transaction")
                assertTrue(key.matches(Regex("[0-9a-f]{64}")), "the key of the transaction is a secret key")
            } finally {
                reopened.close()
            }
        } finally {
            if (!wallet.isClosed()) wallet.close()
        }
    }

    // a transaction that the chain never held reads as failed: the wallet builds it with a relay
    // that the server refuses, because a second handle of the same wallet spent the only output that
    // it builds on, which the first handle does not know since the server does not sync the pool.
    // the answer of the creation says that the transaction is failed and not relayed, and the
    // history of the wallet holds no transaction of it, since the chain never held it
    @Test
    fun testATxThatTheChainNeverHeldReadsAsFailed() {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        val seed = wallet.getSeed()
        try {
            mine(1, wallet.getPrimaryAddress())
            mine(63, unwatchedAddress())
            waitScanned(wallet.getPrimaryAddress(), wallet.getPrivateViewKey())
            wallet.sync()
            assertTrue(wallet.getUnlockedBalance() > BigInteger.ZERO, "the wallet has a single coinbase to spend")

            // a second handle of the same wallet relays a spend of the only output, which the pool
            // of the node holds and the server does not tell the first handle of
            val reopenConfig = MoneroWalletConfig()
            reopenConfig.networkType = MoneroNetworkType.MAINNET
            reopenConfig.seed = seed
            val second = MoneroWalletLight.openWallet(reopenConfig, connection)
            try {
                second.sync()
                val spentConfig = MoneroTxConfig()
                spentConfig.accountIndex = 0
                spentConfig.relay = true
                spentConfig.addDestination(unwatchedAddress(), amount)
                val spent = second.createTxs(spentConfig).single()
                assertEquals(true, spent.isRelayed, " the spend of the second handle relayed")
            } finally {
                second.close()
            }

            // the first handle builds on the same output, which the server refuses as a double
            // spend: the transaction was created but its relay never happened, which reads as failed
            val refusedConfig = MoneroTxConfig()
            refusedConfig.accountIndex = 0
            refusedConfig.relay = true
            refusedConfig.addDestination(unwatchedAddress(), BigInteger.valueOf(500_000_000_000L))
            val refused = wallet.createTxs(refusedConfig).single()
            assertEquals(true, refused.isFailed, "a transaction whose relay the server refused reads as failed")
            assertEquals(false, refused.isRelayed, "the refused transaction was not relayed")
            assertEquals(false, refused.inTxPool, "the refused transaction is not in the pool")
            assertEquals(true, refused.relay, "the config of the refused transaction said to relay")

            // the history of the wallet holds no transaction of it, since the chain never held it
            assertEquals(emptyList(), wallet.getTxs(MoneroTxQuery().apply { isFailed = true }).map { tx -> tx.hash }, "the history holds no failed transaction")
            assertEquals(emptyList(), wallet.getTxs(MoneroTxQuery().apply { hash = refused.hash }).map { tx -> tx.hash }, "the history holds no transaction of the chain-never-held")
        } finally {
            wallet.close()
        }
    }
}
