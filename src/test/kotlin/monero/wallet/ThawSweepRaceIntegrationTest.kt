package monero.wallet

import monero.common.MoneroRpcConnection
import monero.LwsSupport
import monero.RegtestSupport
import monero.NativeTestSupport
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import java.math.BigInteger
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order

@Tag("integration")
@Order(16)
class ThawSweepRaceIntegrationTest {
    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // the interaction of a freeze, a sweep of everything else and a thaw once raced the background sync:
    // the sync replaces the outputs, which lost the frozen setting of the one that the thaw expected, and
    // the sweep of it answered that it was unspendable while it was not. the frozen setting now lives by
    // the identity of the output too, which the sync cannot lose, and this loop holds that true
    @Test fun thawThenSweepLoop() {
        val connection: MoneroRpcConnection = MoneroRpcConnection(LwsSupport.lwsUri)
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"
        val sender = if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
        try {
            // fund the sender and let the coinbases unlock, the way the send test does
            RegtestSupport.mineBlocks(160)
            // the scan of the account on the lws server lags its mining: wait for an output the loop
            // can pick, or the race below runs no iteration and proves nothing
            syncUntil(sender) {
                sender.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                    .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                    .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                    .any { o -> o.isSpent != true && o.keyImage?.hex != null }
            }
            val iterations = Integer.getInteger("RACE_ITERS", 10)
            var failures = 0
            var ran = 0
            for (i in 1..iterations) {
                val picked = sender.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                    .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                    .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                    .filter { o -> o.isSpent != true && o.keyImage?.hex != null }
                    .firstOrNull() ?: break
                ran += 1
                val keyImage = picked.keyImage!!.hex!!
                val dest = sender.getPrimaryAddress()
                if (sender.isOutputFrozen(keyImage)) sender.thawOutput(keyImage)
                sender.freezeOutput(keyImage)
                try {
                    try { sender.sweepUnlocked(MoneroTxConfig().apply { address = dest; relay = false }) } catch (e: Exception) { println("SWEEP-UNLOCKED iteration $i: " + e.message) }
                } finally {
                    sender.thawOutput(keyImage)
                }
                try {
                    sender.sweepOutput(MoneroTxConfig().apply { this.keyImage = keyImage; address = dest; relay = false })
                } catch (e: Exception) {
                    failures += 1
                    println("RACE-FAIL iteration $i: " + e.message)
                    val out = sender.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence().flatMap { tx -> tx.outputs.orEmpty().asSequence() }.filterIsInstance<monero.wallet.model.MoneroOutputWallet>().firstOrNull { it.keyImage?.hex == keyImage }
                    println("  state: spent=" + out?.isSpent + " frozen=" + out?.isFrozen + " locked=" + (out?.tx as? monero.wallet.model.MoneroTxWallet)?.isLocked)
                }
            }
            println("RACE-RESULT: $failures failures in $ran of $iterations iterations")
            if (ran == 0) throw AssertionError("the race ran no iteration: the sender had no output to pick")
            if (failures > 0) throw AssertionError("thaw/sweep race reproduced: $failures/$ran")
        } finally { sender.close(false) }
    }

    private fun syncUntil(wallet: MoneroWalletLight, condition: () -> Boolean) {
        var tries = 0
        while (tries++ < 30) {
            wallet.sync()
            if (condition()) return
            Thread.sleep(2000)
        }
        wallet.sync()
    }
}
