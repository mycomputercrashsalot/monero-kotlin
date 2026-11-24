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
 * The coinbases that a light wallet receives from the mining of the shared chain, which the wallet never
 * takes part in: it has no call that mines, since the C ABI refuses the mining call on a light wallet
 * underneath it, and the blocks come through the daemon that the compose runs, the way the captures of
 * MoneroWalletLightIntegrationTest were taken. A coinbase of a regtest block stays locked until the
 * chain holds sixty blocks on top of it, so five blocks that the test mines to a wallet of its own leave
 * its balance locked, and sixty blocks more to the address that the mining calls pay by default, which
 * the wallet does not own, unlock the money without paying the wallet anything new. It needs the
 * monero-lws of src/test/integration/docker-compose.yml and the node that the compose mines on, and it
 * skips when no server answers, like the tests of MoneroWalletLightIntegrationTest, which this class
 * runs after.
 */
@Order(6)
@Tag("integration")
class MoneroWalletLightMiningIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // the wallet never mines: the chain grows through the daemon, the coinbases that the blocks pay the
    // wallet lock until the chain holds sixty blocks on top of them, and the money unlocks at maturity
    // without the wallet gaining anything from the blocks that pay another address
    @Test
    fun testACoinbaseLocksUntilMaturity_AndUnlocksAfterIt() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val wallet = MoneroWalletLight.createWallet(config(), connection)
        try {
            val address = wallet.getPrimaryAddress()
            // the server reads a new account into its scan threads only after it processes a
            // block, so the wallet syncs first to register its account before the mining pays it:
            // blocks that arrive before the registration never scan for the account, and no block
            // follows them in this test to pick it up
            wallet.sync()
            val height = daemon.getHeight()
            RegtestSupport.mineBlocks(5, address)
            syncUntilTxs(wallet, 5)

            // the balance of the wallet is the exact sum of the rewards of the five heights that the
            // mining made, which the node answers for the blocks that it holds
            val rewards = daemon.getBlockHeadersByRange(height, height + 4).map { header -> checkNotNull(header.reward) }
            val expected = rewards.fold(BigInteger.ZERO, BigInteger::add)
            val txs = wallet.getTxs()
            assertEquals((height..height + 4).toList(), txs.mapNotNull { tx -> tx.height }.sorted(), "the mining makes the five coinbases of the wallet")
            for (tx in txs) {
                assertEquals(true, tx.isMinerTx, "a transaction of a mined block is the miner transaction of it")
                assertEquals(true, tx.isLocked, "a coinbase of a block that the chain holds less than sixty blocks on stays locked")
            }
            assertEquals(expected, wallet.getBalance(), "the balance is the sum of the rewards of the mined blocks")
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance(), "a locked coinbase leaves nothing unlocked")
            assertTrue(wallet.getUnlockedBalance() < wallet.getBalance(), "the coinbase locks the money of the wallet")

            // sixty blocks more through the daemon, to the address that the mining calls pay by
            // default: the coinbases of the wallet reach maturity and unlock, and the blocks pay the
            // wallet nothing, since it does not own that address
            RegtestSupport.mineBlocks(60)
            syncToHeight(wallet, daemon.getHeight())
            assertEquals(expected, wallet.getBalance(), "the maturity blocks pay an address that the wallet does not own")
            assertEquals(expected, wallet.getUnlockedBalance(), "the coinbases unlock when the chain holds sixty blocks on top of them")
            assertEquals(5, wallet.getTxs().size, "the wallet never mines itself")
        } finally {
            wallet.close()
        }
    }

    private fun config(): MoneroWalletConfig {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        return config
    }

    // the server scans the blocks of an account behind the mining: the wallet syncs until its
    // transactions appear, which the locked phase of the coinbases has to read before the shared chain
    // grows past their maturity
    private fun syncUntilTxs(wallet: MoneroWalletLight, count: Int) {
        var tries = 0
        var stalled = 0
        while (wallet.getTxs().size < count && tries++ < 720) {
            wallet.sync()
            Thread.sleep(250)
            // the server loses the wake of the blocks of a quiet chain for minutes at times, and
            // it takes the account up only when a block arrives that it does see: a block every
            // twenty stalled seconds pokes it again, the way the mining tries again over a fork
            if (++stalled % 80 == 0) RegtestSupport.mineBlocks(1)
        }
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
