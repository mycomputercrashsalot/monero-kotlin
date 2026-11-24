package monero.wallet

import java.math.BigInteger
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
import monero.common.MoneroRpcError
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroOutputWallet
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The outputs and the key images of a light wallet, against a live monero-lws, like the outputs tests of
 * monero-python (tests/test_monero_wallet_common.py) that freeze and thaw outputs and export and import
 * key images, which BaseMoneroWalletOutputsTest runs for the wallets of a node and of a server. A light
 * wallet has no query of its outputs: they are the outputs that the transactions of
 * [MoneroWalletLight.getTxs] carry when the query asks for them, which is where these tests read them,
 * with the key image that the shape of a received output names. The key images of the outputs that a
 * spend froze are exported with the rest, and the import of a set of them refuses an index that runs
 * past the outputs of the wallet. A wallet that a test makes is funded with the coinbases of its own
 * random seed, which no other test of the shared server has. It needs the monero-lws of
 * src/test/integration/docker-compose.yml, whose lws service accepts the account of a create
 * (--auto-accept-creation and --auto-accept-import), the monero-c with the light wallet surface, and it
 * skips when no server answers. The chain is the one every integration test shares, so the tests assert
 * the amounts of the outputs of their own wallets and never a height that the mining reached.
 */
@Order(9)
@Tag("integration")
class MoneroWalletLightOutputsIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // the chain is the one every integration test shares, and the mining of another test forks it,
    // which refuses the blocks of this one: the mining tries again over the fork that won
    private fun mine(numBlocks: Int, address: String? = null) {
        var tries = 0
        while (true) {
            try {
                if (address == null) RegtestSupport.mineBlocks(numBlocks)
                else RegtestSupport.mineBlocks(numBlocks, address)
                return
            } catch (e: MoneroRpcError) {
                if ("Block not accepted" !in (e.message ?: "") || tries++ >= 5) throw e
                Thread.sleep(2000)
            }
        }
    }

    // a wallet of its own seed at random, which no other test of the shared server has, funded with
    // coinbases that the seventy blocks after them unlock: the wallet syncs until its account on the
    // server scanned the chain, whose logins the syncs drive, the way the funded wallet of the sweep
    // test does. The fork of another test can orphan the coinbases that the mining paid the wallet,
    // which the funding mines again until the wallet holds unlocked money of its own
    private fun fundedWallet(): MoneroWalletLight {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            var rounds = 0
            while ((wallet.getBalance().signum() == 0 || wallet.getUnlockedBalance().signum() == 0) && rounds++ < 5) {
                mine(6, wallet.getPrimaryAddress())
                mine(70)
                val top = MoneroDaemonRpc(RegtestSupport.daemonConnection()).use { it.getHeight() }
                syncUntil(wallet) { wallet.getHeight() > top }
            }
            assertTrue(wallet.getBalance().signum() > 0, "the wallet received the coinbases that the mining paid it")
            assertTrue(wallet.getUnlockedBalance().signum() > 0, "the coinbases of the wallet unlocked")
            return wallet
        } catch (e: Throwable) {
            wallet.close()
            throw e
        }
    }

    // the wallet syncs until the condition holds, which the scans of its account on the server follow
    // behind the mining
    private fun syncUntil(wallet: MoneroWalletLight, condition: () -> Boolean) {
        var tries = 0
        while (tries++ < 30) {
            wallet.sync()
            if (condition()) return
            Thread.sleep(2000)
        }
        wallet.sync()
    }

    // the outputs of the wallet, which a light server hands out on the transactions that carry them
    private fun outputsOf(wallet: MoneroWalletLight): List<MoneroOutputWallet> {
        return wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
            .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
            .filterIsInstance<MoneroOutputWallet>()
            .toList()
    }

    // an output of the wallet that is not spent and not locked, with the key image that its shape names,
    // which the freeze of the model takes
    private fun spendableOutput(wallet: MoneroWalletLight): MoneroOutputWallet {
        val output = outputsOf(wallet).firstOrNull { it.isSpent != true && it.isFrozen != true && it.keyImage?.hex != null && it.getTxWallet()?.isLocked != true }
        assertNotNull(output, "the wallet has an unspent and unlocked output with a key image")
        return output
    }

    // a frozen output stays frozen through the syncs that replace the outputs of the wallet, and the
    // frozen money leaves the balance and the balance that the wallet can spend: the spend path refuses
    // it, so no balance offers money that the wallet cannot send
    @Test
    fun testAFrozenOutputSurvivesTheSyncsOutOfTheBalances() {
        val wallet = fundedWallet()
        try {
            val output = spendableOutput(wallet)
            val keyImage = assertNotNull(output.keyImage?.hex)
            val amount = assertNotNull(output.amount)
            assertEquals(false, wallet.isOutputFrozen(keyImage))
            val balance = wallet.getBalance()
            val unlocked = wallet.getUnlockedBalance()

            wallet.freezeOutput(keyImage)
            assertEquals(true, wallet.isOutputFrozen(keyImage))
            assertEquals(balance - amount, wallet.getBalance())
            assertEquals(unlocked - amount, wallet.getUnlockedBalance())

            // the sync asks the server for the outputs again, which it hands out as fresh objects: the
            // frozen setting lives by the key image of the output and its identity, which the sync
            // cannot lose
            wallet.sync()
            assertEquals(true, wallet.isOutputFrozen(keyImage))
            assertEquals(balance - amount, wallet.getBalance())
            assertEquals(unlocked - amount, wallet.getUnlockedBalance())

            // more blocks pass and the wallet syncs again, which replaces the outputs another time
            mine(5)
            val top = MoneroDaemonRpc(RegtestSupport.daemonConnection()).use { it.getHeight() }
            syncUntil(wallet) { wallet.getHeight() > top }
            assertEquals(true, wallet.isOutputFrozen(keyImage))
            assertEquals(balance - amount, wallet.getBalance())

            // the output that the wallet froze answers frozen in its shape too
            val frozen = outputsOf(wallet).single { it.keyImage?.hex == keyImage }
            assertEquals(true, frozen.isFrozen)

            // the thaw puts the money back
            wallet.thawOutput(keyImage)
            assertEquals(false, wallet.isOutputFrozen(keyImage))
            assertEquals(balance, wallet.getBalance())
            assertEquals(unlocked, wallet.getUnlockedBalance())
        } finally {
            wallet.close()
        }
    }

    // the export names the signed key images of every output of the wallet, the frozen ones with the
    // rest, and the ones since the last export are none once it took all of them
    @Test
    fun testExportKeyImagesOfEveryOutputIncludingTheFrozenOnes() {
        val wallet = fundedWallet()
        try {
            val output = spendableOutput(wallet)
            val keyImage = assertNotNull(output.keyImage?.hex)
            wallet.freezeOutput(keyImage)

            val all = wallet.exportKeyImages(true)
            val images = all.keyImages!!
            assertTrue(images.isNotEmpty(), "the wallet has outputs to export the key images of")
            for (image in images) {
                assertTrue(image.hex!!.matches(Regex("[0-9a-f]{64}")), "the hex of a key image: ${image.hex}")
                assertTrue(image.signature!!.matches(Regex("[0-9a-f]{128}")), "the signature of a key image: ${image.signature}")
            }

            // the key image of the frozen output is among the exported ones
            assertTrue(images.any { it.hex == keyImage }, "the export names the key image of the frozen output")

            // the ones since the last export: the export that took all of them leaves none
            val sinceLast = wallet.exportKeyImages(false)
            assertTrue(images.size > (sinceLast.keyImages?.size ?: 0), "the export since the last one names no more than all of them")
        } finally {
            wallet.close()
        }
    }

    // the import matches the key images to the outputs of the wallet by their index, which a set that
    // runs past them refuses: an offset beyond the outputs, or more key images than the outputs from the
    // offset on, is an error and not a wrong answer
    @Test
    fun testImportKeyImagesRefusesAnIndexThatIsOutOfRange() {
        val wallet = fundedWallet()
        try {
            val exported = wallet.exportKeyImages(true)
            val images = exported.keyImages!!
            assertTrue(images.isNotEmpty(), "the wallet has outputs to export the key images of")

            // the index of the first key image among the outputs of the wallet runs past them
            val offset = assertFailsWith<MoneroError> { wallet.importKeyImages(images, images.size + 1L) }
            assertEquals("Offset larger than known outputs", offset.message)

            // the key images are more than the outputs from the first one of them on
            val count = assertFailsWith<MoneroError> { wallet.importKeyImages(images, 1) }
            assertEquals("The blockchain is out of date compared to the signed key images", count.message)

            // the wallet that answered the refusals still answers its balance, which the refusals left
            // alone
            assertTrue(wallet.getBalance().signum() > 0)
        } finally {
            wallet.close()
        }
    }
}
