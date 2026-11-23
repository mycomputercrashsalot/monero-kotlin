package monero.wallet

import java.math.BigInteger
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroWalletConfig
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order

/**
 * The sweeps of a light wallet against a live monero-lws: a sweep of the unlocked money takes every
 * unlocked and unfrozen output of the wallet into transactions, which the config says to relay or to
 * leave holding the metadata that a relay takes; a sweep that fails partway reports exactly the
 * transactions that it relayed before the failure, which [MoneroWalletLight.relayedHashes] reads
 * from the error; a wallet that only reads refuses the sweep that would relay; the swept money of a
 * sweep to the wallet itself lands in the balance of the sender; and an output that the wallet froze
 * stays out of the swept amount. It needs the monero-lws of src/test/integration/docker-compose.yml
 * and the regtest node behind it, whose chain the other tests of the suite mine onto as well, so the
 * wallets of the tests that this class adds are of a random seed of their own, and the blocks that
 * they do not mean to pay anyone mine to an address that no account of the server watches. It skips
 * when no server answers, like the tests of MoneroWalletLightIntegrationTest, which this class runs
 * after.
 */
@Order(15)
@Tag("integration")
class MoneroWalletLightSweepIntegrationTest {
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
     * An address of mainnet that no account of the server watches, which the blocks that the sweeps
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

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }
    private val daemon: MoneroDaemonRpc by lazy { MoneroDaemonRpc(RegtestSupport.daemonConnection()) }

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

    // a sweep that fails partway reports exactly the transactions that it relayed: the wallet holds
    // one hundred and forty six equal outputs, whose sweep takes the one hundred and forty five
    // inputs that the weight of a transaction holds into its first transaction and relays it, and
    // fails on the one output that the first transaction leaves behind, which cannot pay the fee of
    // a transaction of a single input. the error of the sweep names the transaction that reached the
    // server, which relayedHashes reads, and the wallet tracks it as the pending one
    @Test fun testAFailedSweepReportsOnlyTheRelayedTxs() {
        val funder = fundedWallet(12)
        val dust = BigInteger.valueOf(1_000_000_000L)
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            val walletAddress = wallet.getPrimaryAddress()

            // the funding pays the wallet one hundred and forty six outputs of an equal amount: one
            // transaction per fifteen destinations at the most, and one coinbase of the funder per
            // transaction, since a relayed transaction leaves only unconfirmed change, which the next
            // build cannot spend before a block holds it
            var sent = 0
            while (146 - sent >= 15) {
                val fundConfig = MoneroTxConfig()
                fundConfig.accountIndex = 0
                fundConfig.relay = true
                repeat(15) { fundConfig.addDestination(walletAddress, dust) }
                funder.createTxs(fundConfig)
                sent += 15
            }
            val lastConfig = MoneroTxConfig()
            lastConfig.accountIndex = 0
            lastConfig.relay = true
            repeat(146 - sent) { lastConfig.addDestination(walletAddress, dust) }
            funder.createTxs(lastConfig)

            // a block holds the funding transactions and ages their outputs past the ten blocks that
            // a regtest chain locks a transaction output for
            for (round in 1..10) {
                mine(12, unwatchedAddress())
                waitScanned(walletAddress, wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getBalance() == dust.multiply(BigInteger.valueOf(146L))) break
            }
            assertEquals(dust.multiply(BigInteger.valueOf(146L)), wallet.getBalance(), "the wallet holds the outputs that the sweep needs")
            assertEquals(dust.multiply(BigInteger.valueOf(146L)), wallet.getUnlockedBalance(), "the outputs of the funding matured")

            // the sweep relays its first transaction and fails on the leftover output, and its error
            // names exactly the transaction that reached the server
            val sweep = MoneroTxConfig()
            sweep.address = unwatchedAddress()
            sweep.relay = true
            val error = assertFailsWith<MoneroError> { wallet.sweepUnlocked(sweep) }
            assertTrue(error.message!!.contains("failed to sweep"), error.message)
            assertTrue(error.message!!.contains("need more money than found"), error.message)
            val relayed = MoneroWalletLight.relayedHashes(error)
            assertEquals(1, relayed.size, "the report names the one transaction that the sweep relayed: ${error.message}")

            // the wallet tracks the relayed transaction as the pending one, which no failed one
            // joins: the sweep that failed never submitted the transaction of the leftover output
            assertEquals(relayed, wallet.getTxs(MoneroTxQuery().apply { inTxPool = true }).map { tx -> tx.hash }, "the relayed transaction of the sweep is the pending one")
            assertEquals(emptyList(), wallet.getTxs(MoneroTxQuery().apply { isFailed = true }).map { tx -> tx.hash }, "the failed sweep tracks no failed transaction")
        } finally {
            wallet.close()
            funder.close()
        }
    }

    // a wallet that only reads has no spend key, so what it would submit is garbage that the server
    // refuses: it refuses the relay of the sweep of its unlocked money and of the sweep of one
    // output, before it builds anything
    @Test fun testAViewOnlyWalletRefusesTheSweepRelay() {
        val funder = fundedWallet(1)
        val viewOnlyConfig = MoneroWalletConfig()
        viewOnlyConfig.networkType = MoneroNetworkType.MAINNET
        viewOnlyConfig.primaryAddress = funder.getPrimaryAddress()
        viewOnlyConfig.privateViewKey = funder.getPrivateViewKey()
        val viewOnly = if (MoneroWalletLight.walletExists(viewOnlyConfig, connection)) MoneroWalletLight.openWallet(viewOnlyConfig, connection)
        else MoneroWalletLight.createWallet(viewOnlyConfig, connection)
        try {
            assertEquals(true, viewOnly.isViewOnly(), "the wallet of the address and the view key only reads")

            val sweep = MoneroTxConfig()
            sweep.address = unwatchedAddress()
            sweep.relay = true
            assertEquals("Cannot relay from a view-only wallet", assertFailsWith<MoneroError> { viewOnly.sweepUnlocked(sweep) }.message, "the wallet that only reads refuses to relay its sweep")

            // the key image of an output of the wallet, which the sweep of one output names: the
            // refusal comes before the build, whatever the output is
            val keyImage = funder.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                .filter { output -> output.isSpent != true }
                .mapNotNull { output -> output.keyImage?.hex }
                .first()
            val oneOutput = MoneroTxConfig()
            oneOutput.keyImage = keyImage
            oneOutput.address = unwatchedAddress()
            oneOutput.relay = true
            assertEquals("Cannot relay from a view-only wallet", assertFailsWith<MoneroError> { viewOnly.sweepOutput(oneOutput) }.message, "the wallet that only reads refuses to relay the sweep of one output")
        } finally {
            viewOnly.close()
            funder.close()
        }
    }

    // the swept money of a sweep to the wallet itself lands in the balance of the sender, which the
    // exact integer of the balance before less the fee of the sweep says: the sweep spends every
    // unlocked output into a transaction that pays the wallet, whose output the balance counts the
    // moment the transaction relays, and which the block holds as the outgoing amount of the sweep
    @Test fun testASweepToItselfCreditsTheSender() {
        val wallet = fundedWallet(1)
        try {
            val walletAddress = wallet.getPrimaryAddress()
            val before = wallet.getBalance()
            assertEquals(before, wallet.getUnlockedBalance(), "the coinbase of the wallet matured")

            val sweep = MoneroTxConfig()
            sweep.address = walletAddress
            sweep.relay = true
            val txs = wallet.sweepUnlocked(sweep)
            assertEquals(1, txs.size, "the sweep of a single output makes one transaction")
            val tx = txs[0]
            assertEquals(true, tx.isRelayed, "the sweep relays when the config says to")
            val fee = assertNotNull(tx.fee)

            // the swept money lands in the balance of the sender the moment the transaction relays,
            // as the pending output of the transaction that pays the wallet
            assertEquals(before.subtract(fee), wallet.getBalance(), "the swept money less the fee is the balance of the sender")

            // a block holds the transaction, whose outgoing amount is the swept money, which stays
            // in the balance of the sender: the round waits for the pool to hold the transaction,
            // mines a block, and reads the wallet again until the chain holds it
            for (round in 1..20) {
                if (daemon.getTxPoolHashes().contains(tx.hash)) mine(1, unwatchedAddress())
                waitScanned(walletAddress, wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getTxs(MoneroTxQuery().apply { hash = tx.hash }).single().isConfirmed == true) break
                Thread.sleep(1000)
            }
            assertEquals(true, wallet.getTxs(MoneroTxQuery().apply { hash = tx.hash }).single().isConfirmed, "a block holds the transaction of the sweep")
            assertEquals(before.subtract(fee), wallet.getBalance(), "the swept money stays in the balance of the sender")
            val swept = wallet.getTxs(MoneroTxQuery().apply { hash = tx.hash }).single()
            assertEquals(true, swept.isOutgoing, "the sweep of the wallet to itself is outgoing")
            assertEquals(before.subtract(fee), swept.outgoingTransfer!!.amount, "the outgoing amount of the sweep is the swept money")
        } finally {
            wallet.close()
        }
    }

    // an output that the wallet froze stays out of the swept amount: the freeze takes the money of
    // the output out of the balances that the wallet answers, and the sweep of the unlocked money
    // takes the unlocked and unfrozen outputs only, which leaves the frozen one in the wallet
    @Test fun testAFrozenOutputIsExcludedFromTheSweptAmount() {
        val wallet = fundedWallet(2)
        try {
            val walletAddress = wallet.getPrimaryAddress()
            val balance = wallet.getBalance()
            val unlocked = wallet.getUnlockedBalance()
            assertEquals(balance, unlocked, "the coinbases of the wallet matured")

            // the outputs of the wallet, of which the test freezes one
            val outputs = wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                .filter { output -> output.isSpent != true }
                .mapNotNull { output -> output.keyImage?.hex?.let { image -> output to image } }
                .toList()
            assertEquals(2, outputs.size, "the wallet holds the two coinbases that the mining paid it")
            val (frozen, keyImage) = outputs[0]
            assertEquals(false, wallet.isOutputFrozen(keyImage), "the output is not frozen before the freeze")

            // the freeze takes the money of the output out of both balances
            wallet.freezeOutput(keyImage)
            assertEquals(true, wallet.isOutputFrozen(keyImage))
            assertEquals(balance.subtract(frozen.amount!!), wallet.getBalance(), "the balance drops by the frozen amount")
            assertEquals(unlocked.subtract(frozen.amount!!), wallet.getUnlockedBalance(), "the unlocked balance drops by the frozen amount")

            // the sweep takes the unlocked and unfrozen outputs only: the frozen one is not among
            // the inputs of the transaction that it makes, and the swept amount is the unlocked
            // money less the frozen one, less the fee
            val sweep = MoneroTxConfig()
            sweep.address = walletAddress
            sweep.relay = false
            val txs = wallet.sweepUnlocked(sweep)
            assertEquals(1, txs.size, "the sweep of the unfrozen output makes one transaction")
            val tx = txs[0]
            val sweptInputs = tx.inputs.orEmpty().filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
            assertEquals(1, sweptInputs.size, "the sweep spends the one unfrozen output")
            assertTrue(sweptInputs.none { input -> input.keyImage?.hex == keyImage }, "the frozen output is not swept")
            assertEquals(unlocked.subtract(frozen.amount!!).subtract(tx.fee!!), tx.getOutgoingAmount(), "the swept amount is the unlocked money less the frozen one, less the fee")

            // the thaw brings the money of the frozen output back, which the sweep left in the wallet
            wallet.thawOutput(keyImage)
            assertEquals(false, wallet.isOutputFrozen(keyImage))
            assertEquals(balance, wallet.getBalance(), "the thaw brings the balance back")
            assertEquals(unlocked, wallet.getUnlockedBalance(), "the thaw brings the unlocked balance back")
        } finally {
            wallet.close()
        }
    }
}
