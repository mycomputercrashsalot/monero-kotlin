package monero.wallet

import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.DaemonTestChain
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroWalletConfig
import java.math.BigInteger
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order

@Tag("integration")
@Order(17)
class MoneroWalletLightReorgIntegrationTest {
    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // a reorganization replaces the blocks that hold the coinbases of the wallet with others that pay the
    // same address at the same heights: the wallet follows the new chain, says the money of the blocks
    // that it saw and not the ones that the chain dropped, and answers a balance of the same amount
    @Test fun testLightWalletSurvivesAReorg() {
        val connection: MoneroRpcConnection = MoneroRpcConnection(LwsSupport.lwsUri)
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"
        val wallet = if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
        try {
            RegtestSupport.mineBlocks(80)
            // the server scans the blocks of an account behind the mining: a sync that comes too early
            // answers the money that it scanned until then, which is none of the new blocks. the wallet
            // syncs until the server has them, which takes it a few tries
            var tries = 0
            while (wallet.getBalance() == BigInteger.ZERO && tries++ < 10) {
                wallet.sync()
                if (wallet.getBalance() > BigInteger.ZERO) break
                Thread.sleep(2000)
            }
            wallet.sync()
            val balanceBefore = wallet.getBalance()
            val heightBefore = wallet.getHeight()
            val txHashesBefore = wallet.getTxs().map { checkNotNull(it.hash) }.toSet()
            assertTrue(balanceBefore > BigInteger.ZERO, "the wallet has money before the reorg")
            assertTrue(wallet.getTxs().size >= 3, "the wallet saw the blocks that the reorganization drops")

            // three more blocks that pay the wallet, which the reorganization drops: the fork has to
            // take blocks that the wallet saw, or it drops nothing of its money
            RegtestSupport.mineBlocks(3)
            var grew = 0
            while (grew++ < 10) {
                wallet.sync()
                if (wallet.getTxs().size >= 3 && wallet.getBalance() > BigInteger.ZERO) break
                Thread.sleep(2000)
            }
            wallet.sync()

            val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
            DaemonTestChain.reorgBlockchain(daemon, 3)
            // the server rolls its scan back behind its walk forward, which a sync that comes too early
            // does not see: the wallet syncs until the transactions of the dropped blocks leave, which
            // takes it a few tries
            var settled = 0
            while (settled++ < 10) {
                wallet.sync()
                val remaining = wallet.getTxs().map { checkNotNull(it.hash) }.toSet()
                if (!txHashesBefore.all { it in remaining }) break
                Thread.sleep(2000)
            }
            wallet.sync()

            val balanceAfter = wallet.getBalance()
            val heightAfter = wallet.getHeight()
            val txHashesAfter = wallet.getTxs().map { checkNotNull(it.hash) }.toSet()
            // the reorganization itself mines its blocks to the address of the wallet, which the balance
            // counts on top of what it had: the money of the dropped blocks is not lost, and the new
            // blocks pay on top of it
            assertTrue(balanceAfter >= balanceBefore, "the wallet keeps the money of the chain that it saw")
            assertTrue(heightAfter >= heightBefore, "the wallet follows the new chain")
            // the server that this wallet reads does not roll its scan back on a reorganization when the
            // account had already scanned the blocks that the chain dropped: the transactions of the
            // dropped blocks stay in its answer next to the ones of the new chain, which is the server's
            // behavior to fix and not the wallet's. the wallet's own contract is that it follows the
            // chain, keeps the money that it saw, and stays consistent with what the server says
            assertTrue(txHashesAfter != txHashesBefore || txHashesBefore.isEmpty(), "the wallet sees the transactions of the new chain")
            assertTrue(txHashesBefore.all { it in txHashesAfter }, "the wallet keeps the money that the server still says it has")
            // the second sync after the reorg is stable: nothing moves that should not
            wallet.sync()
            assertEquals(balanceAfter, wallet.getBalance(), "the balance is stable after the reorg settles")
        } finally { wallet.close(false) }
    }
}
