package monero.wallet

import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxConfig
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

@Tag("integration")
class MoneroWalletLightSweepIntegrationTest {
    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
    }

    // a sweep of the unlocked money takes every unlocked output of the wallet into one transaction
    // without relaying, which leaves the wallet holding the metadata that a relay takes
    @Test fun testSweepUnlockedWithoutRelay() {
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
            val unlockedBefore = wallet.getUnlockedBalance()
            assertTrue(unlockedBefore > BigInteger.ZERO, "the wallet has unlocked money to sweep")

            val unlockedOutputs = wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                .filter { o -> o.isSpent != true && (o.tx as? monero.wallet.model.MoneroTxWallet)?.isLocked != true }
                .count()
            val txs = wallet.sweepUnlocked(MoneroTxConfig().apply { address = wallet.getPrimaryAddress(); relay = false })
            assertTrue(txs.isNotEmpty(), "the sweep makes at least one transaction")
            for (tx in txs) {
                assertTrue(tx.fee!! > BigInteger.ZERO, "the sweep pays a fee")
                assertEquals(false, tx.isRelayed, "the sweep does not relay when the config says not to")
                assertTrue(tx.inputs.orEmpty().isNotEmpty(), "the sweep spends the outputs of the wallet")
            }
            // the answer of the sweep names the key images of the outputs that it spends and not their
            // amounts, which the count of the unlocked outputs proves instead: every one of them is taken
            val sweptCount = txs.sumOf { it.inputs.orEmpty().size }
            assertEquals(unlockedOutputs, sweptCount, "the sweep takes every unlocked output of the wallet")
        } finally { wallet.close(false) }
    }
}
