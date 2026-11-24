package monero.wallet

import java.math.BigInteger
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The locked lifecycle of the money of a light wallet, end to end: a coinbase that the mining pays
 * the wallet locks for the sixty blocks that a regtest chain locks a coinbase for, with a balance
 * above an unlocked balance of zero, and the blocks past the maturity unlock the whole of it, which
 * the exact integers of the balances assert. An output that the wallet freezes stays out of the
 * arithmetic of both balances, which the spend path refuses the money of, and a sweep that fails
 * partway recalculates the balance before it reports the failure, so the wallet that does not sync
 * in the background reads the money that the transactions it relayed moved. It needs the monero-lws
 * of src/test/integration/docker-compose.yml and the regtest node behind it, whose chain the other
 * tests of the suite mine onto as well, so every wallet is of a random seed of its own and the
 * blocks that the test does not mean to pay anyone mine to an address that no account of the server
 * watches. It skips when no server answers, like the tests of MoneroWalletLightIntegrationTest,
 * which this class runs after.
 */
@Order(14)
@Tag("integration")
class MoneroWalletLightUpdateLockedIntegrationTest {

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }
    private val daemon: MoneroDaemonRpc by lazy { MoneroDaemonRpc(RegtestSupport.daemonConnection()) }

    // the size of the outputs that the failed sweep needs, in atomic units, and their count: a sweep
    // takes at most the inputs that the weight of a transaction holds, which is one hundred and
    // forty five of them at the mixin of the ring construction, so a wallet of one hundred and
    // forty six equal outputs sweeps one hundred and forty five of them in its first transaction,
    // whose relay the test reads, and the one output that the sweep leaves behind cannot pay the fee
    // of a transaction of a single input, which fails the sweep
    private val dust = BigInteger.valueOf(1_000_000_000L)
    private val dustCount = 146

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
     * An address of mainnet that no account of the server watches, which the blocks that the tests
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

    // a mined coinbase locks for the sixty blocks that a regtest chain locks a coinbase for, which
    // the balance of the wallet counts and the unlocked balance does not, and the blocks past the
    // maturity unlock the whole of it, which the transactions of the coinbases say with their lock.
    // the rounds of the mining are tried again when a reorganization of the shared chain drops the
    // block that a round of them paid the wallet with
    @Test
    fun testAMinedCoinbaseLocksThenMatures() {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            val address = wallet.getPrimaryAddress()
            val viewKey = wallet.getPrivateViewKey()

            // the mining pays the wallet a coinbase, which the server scans
            for (round in 1..10) {
                if (wallet.getBalance() == BigInteger.ZERO) mine(1, address)
                waitScanned(address, viewKey)
                wallet.sync()
                if (wallet.getBalance() > BigInteger.ZERO) break
            }
            val balance = wallet.getBalance()
            assertTrue(balance > BigInteger.ZERO, "the coinbase counts in the balance")
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance(), "the coinbase is locked")
            assertTrue(balance > wallet.getUnlockedBalance(), "the balance is above the locked unlocked balance")
            val coinbases = wallet.getTxs(MoneroTxQuery().apply { isMinerTx = true })
            assertTrue(coinbases.isNotEmpty(), "the wallet reads the transaction of its coinbase")
            for (coinbase in coinbases) assertEquals(true, coinbase.isLocked, "the transaction of a coinbase is locked")
            assertEquals(balance, coinbases.fold(BigInteger.ZERO) { sum, tx -> sum.add(tx.getIncomingAmount()) }, "the coinbases pay the balance")

            // the blocks past the maturity of sixty unlock the whole of the coinbases
            for (round in 1..10) {
                if (wallet.getBalance() == BigInteger.ZERO) mine(1, address)
                mine(61, unwatchedAddress())
                waitScanned(address, viewKey)
                wallet.sync()
                if (wallet.getBalance() > BigInteger.ZERO && wallet.getUnlockedBalance() == wallet.getBalance()) break
            }
            assertEquals(balance, wallet.getUnlockedBalance(), "the matured coinbase is the whole of the unlocked balance")
            for (coinbase in wallet.getTxs(MoneroTxQuery().apply { isMinerTx = true })) {
                assertEquals(false, coinbase.isLocked, "the transaction of the coinbase matured")
            }
        } finally {
            wallet.close()
        }
    }

    // the outputs that the wallet freezes stay out of the arithmetic of both balances, which the
    // documented freeze semantics say: the money of a frozen output leaves the balance and the
    // unlocked balance until the thaw brings it back, and the sweep of the unlocked money takes the
    // unlocked and unfrozen outputs only, which a wallet whose every output is frozen has none of,
    // so it makes no transaction at all
    @Test
    fun testAFrozenOutputStaysOutOfTheBalances() {
        val wallet = fundedWallet(1)
        try {
            val balance = wallet.getBalance()
            val unlocked = wallet.getUnlockedBalance()
            assertEquals(balance, unlocked, "the coinbases of the wallet matured")
            val keyImages = wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
                .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
                .filterIsInstance<monero.wallet.model.MoneroOutputWallet>()
                .filter { out -> out.isSpent != true }
                .mapNotNull { out -> out.keyImage?.hex }
                .toList()
            assertTrue(keyImages.isNotEmpty(), "the wallet has an unspent output to freeze")
            for (keyImage in keyImages) assertEquals(false, wallet.isOutputFrozen(keyImage), "the output is not frozen before the freeze")

            // the freeze takes the money of every output out of the balance and the unlocked balance
            for (keyImage in keyImages) wallet.freezeOutput(keyImage)
            for (keyImage in keyImages) assertEquals(true, wallet.isOutputFrozen(keyImage), "the output is frozen")
            assertEquals(BigInteger.ZERO, wallet.getBalance(), "the frozen outputs leave no balance")
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance(), "the frozen outputs leave no unlocked balance")

            // the sweep of the unlocked money takes the unlocked and unfrozen outputs only, which the
            // wallet has none of while every output of it is frozen
            val sweep = MoneroTxConfig()
            sweep.address = unwatchedAddress()
            sweep.relay = true
            assertEquals(emptyList(), wallet.sweepUnlocked(sweep).map { tx -> tx.hash }, "the sweep of a wallet whose every output is frozen makes no transaction")

            // the thaw brings the money of the outputs back to both balances
            for (keyImage in keyImages) wallet.thawOutput(keyImage)
            for (keyImage in keyImages) assertEquals(false, wallet.isOutputFrozen(keyImage), "the output is not frozen after the thaw")
            assertEquals(balance, wallet.getBalance(), "the thaw brings the balance back")
            assertEquals(unlocked, wallet.getUnlockedBalance(), "the thaw brings the unlocked balance back")
        } finally {
            wallet.close()
        }
    }

    // a sweep that fails partway recalculates the balance before it reports the failure: the
    // transactions that the sweep relayed moved the money whatever the failed ones did, and a wallet
    // that does not sync in the background would keep a stale balance with the error. the wallet of
    // the test holds one hundred and forty six equal outputs, whose sweep relays a transaction of
    // one hundred and forty five of them and fails on the one that the first transaction leaves
    // behind, which cannot pay the fee of a transaction of a single input
    @Test
    fun testAFailedSweepRecalculatesTheBalanceBeforeItReports() {
        val funder = fundedWallet(12)
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        val wallet = MoneroWalletLight.createWallet(config, connection)
        try {
            val walletAddress = wallet.getPrimaryAddress()

            // the funding pays the wallet the outputs that the sweep needs: one transaction per
            // fifteen destinations at the most, since a transaction of the ring construction holds
            // fifteen of them, and one coinbase of the funder per transaction, since a relayed
            // transaction leaves only unconfirmed change, which the next build cannot spend before a
            // block holds it
            var sent = 0
            while (dustCount - sent >= 15) {
                val fundConfig = MoneroTxConfig()
                fundConfig.accountIndex = 0
                fundConfig.relay = true
                repeat(15) { fundConfig.addDestination(walletAddress, dust) }
                funder.createTxs(fundConfig)
                sent += 15
            }
            if (sent < dustCount) {
                val lastConfig = MoneroTxConfig()
                lastConfig.accountIndex = 0
                lastConfig.relay = true
                repeat(dustCount - sent) { lastConfig.addDestination(walletAddress, dust) }
                funder.createTxs(lastConfig)
            }

            // a block holds the funding transactions and ages their outputs past the ten blocks that
            // a regtest chain locks a transaction output for
            for (round in 1..10) {
                mine(12, unwatchedAddress())
                waitScanned(walletAddress, wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getBalance() == dust.multiply(BigInteger.valueOf(dustCount.toLong()))) break
            }
            assertEquals(dust.multiply(BigInteger.valueOf(dustCount.toLong())), wallet.getBalance(), "the wallet holds the outputs that the sweep needs")
            assertEquals(dust.multiply(BigInteger.valueOf(dustCount.toLong())), wallet.getUnlockedBalance(), "the outputs of the funding matured")

            // the sweep relays its first transaction and fails on the one output that it leaves
            // behind, and the balance that the wallet answers right after the error is the one that
            // the relayed transaction moved it to, without a sync in between
            val sweep = MoneroTxConfig()
            sweep.address = unwatchedAddress()
            sweep.relay = true
            val error = assertFailsWith<MoneroError> { wallet.sweepUnlocked(sweep) }
            assertTrue(error.message!!.contains("failed to sweep"), error.message)
            val relayed = MoneroWalletLight.relayedHashes(error)
            assertEquals(1, relayed.size, "the sweep relayed one transaction: ${error.message}")
            assertEquals(dust, wallet.getBalance(), "the balance after the failure is the output that the sweep left behind")
            assertEquals(dust, wallet.getUnlockedBalance(), "the unlocked balance after the failure is the output that the sweep left behind")
            assertEquals(relayed, wallet.getTxs(MoneroTxQuery().apply { inTxPool = true }).map { tx -> tx.hash }, "the relayed transaction of the sweep is the pending one")

            // a block holds the relayed transaction, which the re-read state of the wallet says the
            // same of as the recalculated one: the round waits for the pool to hold the transaction,
            // mines a block, and reads the wallet again until the chain holds it
            for (round in 1..20) {
                if (daemon.getTxPoolHashes().contains(relayed[0])) mine(1, unwatchedAddress())
                waitScanned(walletAddress, wallet.getPrivateViewKey())
                wallet.sync()
                if (wallet.getTxs(MoneroTxQuery().apply { hash = relayed[0] }).single().isConfirmed == true) break
                Thread.sleep(1000)
            }
            assertEquals(true, wallet.getTxs(MoneroTxQuery().apply { hash = relayed[0] }).single().isConfirmed, "a block holds the transaction that the sweep relayed")
            assertEquals(dust, wallet.getBalance(), "the re-read balance is the one that the failure recalculated")
        } finally {
            wallet.close()
            funder.close()
        }
    }
}
