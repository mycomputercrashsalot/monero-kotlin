package monero.wallet

import java.math.BigInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The scans of a light wallet over the history that its server has: a sync of a wallet that is at the
 * height of its server fetches nothing and changes nothing, and a rescan, which a light wallet makes by
 * opening its wallet again from a restore height below the one that the account scans from, asks the
 * server to import the account from that height again and re-reads the same history. The start height of
 * a sync changes nothing on this surface, since the wallet scans from wherever its server is, so the
 * rescan goes through the restore height of the open. The wallet knows no address that its server has
 * not seen: the transfers of its history name the account and the subaddress of the primary address
 * only, which is the one address that the surface reads. It needs the monero-lws of
 * src/test/integration/docker-compose.yml and the node that the compose mines on, and it skips when no
 * server answers, like the tests of MoneroWalletLightIntegrationTest, which this class runs after.
 */
@Order(7)
@Tag("integration")
class MoneroWalletLightScanIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // a wallet that is at the height of its server syncs cleanly: the sync fetches nothing, says that
    // no money arrived, and the wallet reads the same height, balance and transactions after it
    @Test
    fun testANoOpSync_EndsCleanWithNoChange() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val wallet = openOrCreate()
        try {
            syncToHeight(wallet, daemon.getHeight())
            var height = wallet.getHeight()
            var balance = wallet.getBalance()
            var hashes = wallet.getTxs().map { tx -> tx.hash }
            var sync = wallet.sync()
            var tries = 0
            // the shared chain moves while other tests mine, so the wallet syncs until a sync of it
            // fetches nothing, which is the no-op that the test reads
            while (sync.numBlocksFetched != 0L && tries++ < 30) {
                height = wallet.getHeight()
                balance = wallet.getBalance()
                hashes = wallet.getTxs().map { tx -> tx.hash }
                Thread.sleep(1000)
                sync = wallet.sync()
            }
            assertEquals(0L, sync.numBlocksFetched, "a sync of a wallet at the height of its server fetches nothing")
            assertEquals(false, sync.receivedMoney, "a sync that fetches nothing says that no money arrived")
            assertEquals(height, wallet.getHeight(), "the no-op sync changes no height")
            assertEquals(balance, wallet.getBalance(), "the no-op sync changes no balance")
            assertEquals(hashes, wallet.getTxs().map { tx -> tx.hash }, "the no-op sync changes no transaction")
        } finally {
            wallet.close()
        }
    }

    // a rescan asks the server to import the account from a height again, which the server of the
    // compose accepts, and the history re-reads the same: the wallet of five mined blocks, opened again
    // from the height zero, reads the same transactions with the same amounts and the same balance
    @Test
    fun testARescanFromAHeight_RereadsTheSameHistory() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            val address = wallet.getPrimaryAddress()
            // the server reads a new account into its scan threads only after it processes a
            // block, so the wallet syncs first to register its account before the mining pays it
            wallet.sync()
            val height = daemon.getHeight()
            RegtestSupport.mineBlocks(5, address)
            // the wallet reports the height that it was made at before the server scans the first
            // blocks of its account, so the height says nothing about the coinbases: the wallet
            // syncs until the transactions of the mined blocks are there
            var tries = 0
            var stalled = 0
            while (wallet.getTxs().size < 5 && tries++ < 720) {
                wallet.sync()
                Thread.sleep(250)
                // the server loses the wake of the blocks of a quiet chain for minutes at times:
                // a block every twenty stalled seconds pokes it again
                if (++stalled % 80 == 0) RegtestSupport.mineBlocks(1)
            }
            syncToHeight(wallet, height + 5)
            val balance = wallet.getBalance()
            val txs = wallet.getTxs()
            val seed = wallet.getSeed()
            assertEquals(5, txs.size, "the mining makes five transactions for the wallet")
            assertTrue(balance > BigInteger.ZERO, "the wallet has the money of its mined blocks")
            wallet.close()

            // the reopen from the height zero asks the server to import the account from it again,
            // which makes the server scan the account from the genesis block
            val rescanned = MoneroWalletLight.openWallet(configOf(seed), connection)
            try {
                // the server reads the import request of the account into its scan threads only
                // after it processes a block, and the rescan of the account runs on that pass: two
                // blocks make the server take the account from the genesis block again
                RegtestSupport.mineBlocks(2)
                syncToHeight(rescanned, daemon.getHeight())
                assertEquals(address, rescanned.getPrimaryAddress(), "the rescan reads the wallet of the same keys")
                assertEquals(balance, rescanned.getBalance(), "the rescan reads the same balance")
                assertEquals(txs.map { tx -> tx.hash }, rescanned.getTxs().map { tx -> tx.hash }, "the rescan reads the same transactions")
                assertEquals(txs.map { tx -> tx.height }, rescanned.getTxs().map { tx -> tx.height }, "the rescan reads the same heights")
                assertEquals(txs.map { tx -> tx.getIncomingAmount() }, rescanned.getTxs().map { tx -> tx.getIncomingAmount() }, "the rescan reads the same amounts")

                // the wallet names no address that the server has not seen: the transfers of its
                // history pay the account and the subaddress of the primary address only
                for (tx in rescanned.getTxs()) {
                    for (transfer in tx.incomingTransfers.orEmpty()) {
                        assertEquals(0, transfer.accountIndex, "a transfer of the history pays the primary account")
                        assertEquals(0, transfer.subaddressIndex, "a transfer of the history pays the subaddress of the primary address")
                    }
                }
            } finally {
                rescanned.close()
            }
        } finally {
            if (!wallet.isClosed()) wallet.close()
        }
    }

    // the shared server has the account of the seed after the first run of a test that uses it, and a
    // wallet that the server has is not created again
    private fun openOrCreate(): MoneroWalletLight {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"
        return if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
    }

    private fun configOf(seed: String): MoneroWalletConfig {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = seed
        config.restoreHeight = 0
        return config
    }

    // the server scans behind the chain, which the shared node moves while other tests mine: the wallet
    // syncs until it has the height that the chain had
    private fun syncToHeight(wallet: MoneroWalletLight, target: Long) {
        var tries = 0
        while (wallet.getHeight() < target && tries++ < 60) {
            wallet.sync()
            Thread.sleep(2000)
        }
    }
}
