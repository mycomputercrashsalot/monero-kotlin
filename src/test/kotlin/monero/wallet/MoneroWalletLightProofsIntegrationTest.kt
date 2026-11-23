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
import monero.wallet.model.MoneroDestination
import monero.wallet.model.MoneroOutputWallet
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The keys and the proofs of the transactions of a light wallet, against a live monero-lws, like the
 * proofs tests of monero-python (tests/test_monero_wallet_common.py) that check a transaction with its
 * key, which BaseMoneroWalletProofsTest runs for the wallets of a node and of a server. A light wallet
 * server carries no transactions of the chain, so the wallet checks the ones it built itself: a payment
 * that it relayed is proven with the key it kept of the transaction, and a wallet that only reads, which
 * cannot sign a key image itself, learns its spent money from the signed key images that the wallet with
 * the spend key exports, which refines the balances of the history it shares with it. The proofs of a
 * signature, which the base suite also tests, are not the ones of this class: a light wallet builds
 * none, which is the surface to check elsewhere. A wallet that a test makes is funded with the coinbases
 * of its own random seed, which no other test of the shared server has, and pays the mining address of
 * the regtest node, which no wallet of these tests has to be for the check of what it received. It needs
 * the monero-lws of src/test/integration/docker-compose.yml, whose lws service accepts the account of a
 * create (--auto-accept-creation and --auto-accept-import), the monero-c with the light wallet surface,
 * and it skips when no server answers. The chain is the one every integration test shares, so the tests
 * assert the amounts of the history of their own wallets and never a height that the mining reached.
 */
@Order(10)
@Tag("integration")
class MoneroWalletLightProofsIntegrationTest {

    // the address of the python test wallet of the node, which every wallet of these tests can pay: a
    // payment to it receives exactly what the destination names, since no change of another wallet
    // returns to it
    private val payee = RegtestSupport.MINING_ADDRESS

    // an address of the captures that no transaction of these tests pays, which received nothing with
    // them
    private val stranger = "49MNnnrtLzj5vsWsHya28tKFPTcYfEnEYLfCqogA6Dw1TeqvHv5uRPTgjXSyD9pSoPVae3oZe3R5xHsGeC4VapjRV2m6gGj"

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
    private fun fundedWallet(config: MoneroWalletConfig = MoneroWalletConfig().apply { networkType = MoneroNetworkType.MAINNET }): MoneroWalletLight {
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

    // mine blocks, the last ones without the wallet, and let the account of the wallet on the server
    // scan them, which follows the mining behind: the wallet syncs until its height passed the top of
    // the chain that the mining reached
    private fun mineAndSettle(wallet: MoneroWalletLight, toWallet: Int, aging: Int) {
        if (toWallet > 0) mine(toWallet, wallet.getPrimaryAddress())
        if (aging > 0) mine(aging)
        val top = MoneroDaemonRpc(RegtestSupport.daemonConnection()).use { it.getHeight() }
        syncUntil(wallet) { wallet.getHeight() > top }
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

    // a payment that the wallet relayed reached a block once the wallet reads it confirmed, which the
    // scan of its account follows behind the mining: the wallet that another test's fork moved the
    // transaction of reads it when the chain that holds it settles
    private fun settleConfirmed(wallet: MoneroWalletLight, hash: String) {
        syncUntil(wallet) { wallet.getTxs(MoneroTxQuery().apply { this.hash = hash }).firstOrNull()?.isConfirmed == true }
    }

    // a wallet that only reads the account of the wallet on the server, which the server has, since the
    // wallet that these tests fund created it
    private fun viewOnlyWalletOf(wallet: MoneroWalletLight): MoneroWalletLight {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.primaryAddress = wallet.getPrimaryAddress()
        config.privateViewKey = wallet.getPrivateViewKey()
        return MoneroWalletLight.openWallet(config, connection)
    }

    // the outputs of the wallet, which a light server hands out on the transactions that carry them
    private fun outputsOf(wallet: MoneroWalletLight): List<MoneroOutputWallet> {
        return wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
            .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
            .filterIsInstance<MoneroOutputWallet>()
            .toList()
    }

    // what the outputs that the wallet spent and kept are worth, which the wallet with the spend key
    // knows and the wallet that only reads learns from the import
    private fun spentAndUnspentAmounts(wallet: MoneroWalletLight): Pair<BigInteger, BigInteger> {
        val outputs = outputsOf(wallet)
        val spent = outputs.filter { it.isSpent == true }
        val unspent = outputs.filter { it.isSpent != true }
        assertTrue(spent.isNotEmpty(), "the wallet spent outputs")
        assertTrue(unspent.isNotEmpty(), "the wallet kept outputs")
        val spentAmount = spent.fold(BigInteger.ZERO) { sum, output -> sum + output.amount!! }
        val unspentAmount = unspent.fold(BigInteger.ZERO) { sum, output -> sum + output.amount!! }
        return Pair(spentAmount, unspentAmount)
    }

    // a real receive: the wallet relays a transaction that pays an address, and the key that it kept of
    // the transaction proves the payment to the address that received it, and none to an address that
    // received nothing with it. A key that is not the one of the transaction is not an error, and a hash,
    // a key or an address that is not valid is one
    @Test
    fun testCheckTxKeyOfATransactionTheWalletRelayed() {
        val wallet = fundedWallet()
        try {
            val amount = BigInteger.valueOf(3_000_000_000_000L)
            val config = MoneroTxConfig()
            config.accountIndex = 0
            config.destinations = listOf(MoneroDestination(payee, amount))
            config.relay = true
            val tx = wallet.createTxs(config).single()
            val hash = assertNotNull(tx.hash)
            val key = wallet.getTxKey(hash)

            // the key of the transaction, which a payment to a subaddress follows with the additional
            // keys of it
            assertTrue(key.matches(Regex("([0-9a-f]{64})+")), "the key of the transaction: $key")

            // the address that the transaction paid received the amount of its destination
            val check = wallet.checkTxKey(hash, key, payee)
            assertEquals(true, check.isGood)
            assertEquals(amount, check.receivedAmount)
            assertEquals(true, check.inTxPool, "the transaction that the wallet relayed is in the pool until a block holds it")
            assertEquals(0L, check.numConfirmations)

            // an address that received nothing with the transaction: the check says it is not good, and
            // the address received nothing, which is not an error
            val nothing = wallet.checkTxKey(hash, key, stranger)
            assertEquals(false, nothing.isGood)
            assertEquals(BigInteger.ZERO, nothing.receivedAmount)

            // the key of another transaction that the wallet built proves no payment
            val otherConfig = MoneroTxConfig()
            otherConfig.accountIndex = 0
            otherConfig.destinations = listOf(MoneroDestination(payee, BigInteger.valueOf(2_000_000_000_000L)))
            otherConfig.relay = true
            val other = wallet.createTxs(otherConfig).single()
            val keyOfAnother = wallet.getTxKey(assertNotNull(other.hash))
            val notTheOne = wallet.checkTxKey(hash, keyOfAnother, payee)
            assertEquals(false, notTheOne.isGood)
            assertEquals(BigInteger.ZERO, notTheOne.receivedAmount, "the key of another transaction proves no payment")

            // a block holds the transactions, which the wallet reads when it syncs behind the mining
            mineAndSettle(wallet, 0, 12)
            settleConfirmed(wallet, hash)
            val confirmed = wallet.checkTxKey(hash, key, payee)
            assertEquals(true, confirmed.isGood)
            assertEquals(amount, confirmed.receivedAmount)
            assertEquals(false, confirmed.inTxPool)

            // a hash, a key and an address that are not valid
            assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.getTxKey("invalid_tx_id") }.message)
            assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.checkTxKey("invalid_tx_id", key, payee) }.message)
            assertEquals("Tx key has invalid format", assertFailsWith<MoneroError> { wallet.checkTxKey(hash, "invalid_tx_key", payee) }.message)
            assertEquals("Invalid address", assertFailsWith<MoneroError> { wallet.checkTxKey(hash, key, "invalid_tx_address") }.message)

            // a transaction that the wallet did not build: the server carries no transactions of the
            // chain, so the wallet keeps no key of one and checks none but its own
            val coinbase = wallet.getTxs().first { it.isMinerTx == true }
            assertEquals("No tx secret key is stored for this tx", assertFailsWith<MoneroError> { wallet.getTxKey(coinbase.hash!!) }.message)
            assertEquals("No tx is stored for this tx hash: a light wallet checks a tx key against the raw tx of one it built itself", assertFailsWith<MoneroError> { wallet.checkTxKey(coinbase.hash!!, key, wallet.getPrimaryAddress()) }.message)
        } finally {
            wallet.close()
        }
    }

    // the key-image import path: the wallet that holds the spend key exports the signed key images of
    // the outputs of the account, which the wallet that only reads imports to learn which of them were
    // spent, and the spent and the unspent money of the import come out exact against the history that
    // the two wallets share. Without the import the spent money stays in the balance of the wallet that
    // only reads, whose spends it cannot verify
    @Test
    fun testImportKeyImagesRefineTheSpentMoneyOfAViewOnlyWallet() {
        val wallet = fundedWallet()
        try {
            // a payment out of the wallet spends outputs of it
            val config = MoneroTxConfig()
            config.accountIndex = 0
            config.destinations = listOf(MoneroDestination(payee, BigInteger.valueOf(5_000_000_000_000L)))
            config.relay = true
            val tx = wallet.createTxs(config).single()
            mineAndSettle(wallet, 0, 12)
            settleConfirmed(wallet, tx.hash!!)
            assertTrue(wallet.getTxs(MoneroTxQuery().apply { hash = tx.hash }).firstOrNull()?.isConfirmed == true, "the payment is in a block")

            val (spentAmount, unspentAmount) = spentAndUnspentAmounts(wallet)

            // the export signs every key image of the account, with the index of the first among the
            // outputs of the wallet, which the import takes
            val exported = wallet.exportKeyImages(true)
            val keyImages = exported.keyImages!!
            assertTrue(keyImages.isNotEmpty(), "the wallet has outputs to export the key images of")
            for (image in keyImages) {
                assertTrue(image.hex!!.matches(Regex("[0-9a-f]{64}")), "the hex of a key image: ${image.hex}")
                assertTrue(image.signature!!.matches(Regex("[0-9a-f]{128}")), "the signature of a key image: ${image.signature}")
            }

            val viewOnly = viewOnlyWalletOf(wallet)
            try {
                syncUntil(viewOnly) { viewOnly.getTxs().isNotEmpty() }

                // without the key images the wallet that only reads cannot verify the spend, and its
                // balance says that the money it spent is still there
                assertTrue(viewOnly.getUnrefinedSpendCount() > 0, "the wallet that only reads cannot verify its spends")
                assertEquals(spentAmount + unspentAmount, viewOnly.getBalance())

                val result = viewOnly.importKeyImages(keyImages, 0)
                assertEquals(spentAmount, result.spentAmount, "the key images spent the money of the history")
                assertEquals(unspentAmount, result.unspentAmount, "the key images left the money of the history")
                assertTrue(result.height!! > 0, "the import names the height of the wallet")

                // the spends of the next sync that the server reports verify against the imported key
                // images, which needs a block that the account has not scanned: a sync of the same
                // height reads the answer it kept
                mineAndSettle(viewOnly, 0, 2)
                assertEquals(0L, viewOnly.getUnrefinedSpendCount())
                assertEquals(unspentAmount, viewOnly.getBalance())
                assertEquals(unspentAmount, viewOnly.getUnlockedBalance())
            } finally {
                viewOnly.close()
            }
        } finally {
            wallet.close()
        }
    }

    // the key image of a spend from a subaddress: the lookahead of the wallet's config provisions the
    // subaddresses of its account on the server, which the compose lets it, and the wallet pays its own
    // first subaddress, whose output the light model keeps out of the incoming transfers of a self
    // payment, so the outputs tell it. The wallet spends from the subaddress, and the key image of the
    // spend, which the wallet with the spend key signs, checks against every subaddress of the account,
    // which is what lets the wallet that only reads learn the spent money of the subaddress from the
    // import
    @Test
    fun testAKeyImageOfASpendFromASubaddress() {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.accountLookahead = 0
        config.subaddressLookahead = 2
        val wallet = fundedWallet(config)
        try {
            // the address of the first subaddress, which the keys of the seed derive offline: the
            // light surface reads the primary address only
            val keysConfig = MoneroWalletConfig()
            keysConfig.networkType = MoneroNetworkType.MAINNET
            keysConfig.seed = wallet.getSeed()
            val subaddress = MoneroWalletKeys.createWalletFromSeed(keysConfig).use { it.getSubaddress(0, 1).address!! }

            // the wallet pays its own subaddress, a quarter of what it holds, which the mining confirms
            val payment = wallet.getUnlockedBalance() / BigInteger.valueOf(4)
            val payConfig = MoneroTxConfig()
            payConfig.accountIndex = 0
            payConfig.destinations = listOf(MoneroDestination(subaddress, payment))
            payConfig.relay = true
            wallet.createTxs(payConfig)
            mineAndSettle(wallet, 0, 12)

            // the payment credited the subaddress, which the outputs tell: the incoming transfers of
            // a self payment hide the outputs that the sender's own account received
            val subOutputs = outputsOf(wallet).filter { it.subaddressIndex == 1 }
            assertTrue(subOutputs.isNotEmpty(), "the payment to the subaddress credited it")
            assertTrue(subOutputs.fold(BigInteger.ZERO) { sum, output -> sum + output.amount!! }.signum() > 0, "the subaddress holds the payment")

            // the wallet spends from the subaddress, half of what it holds there
            val spend = subOutputs.fold(BigInteger.ZERO) { sum, output -> sum + output.amount!! } / BigInteger.valueOf(2)
            val spendConfig = MoneroTxConfig()
            spendConfig.accountIndex = 0
            spendConfig.subaddressIndices = listOf(1)
            spendConfig.destinations = listOf(MoneroDestination(payee, spend))
            spendConfig.relay = true
            wallet.createTxs(spendConfig)
            mineAndSettle(wallet, 0, 12)

            // the import refines the spent money of the wallet that only reads, which the key image
            // of the spend from the subaddress carries: it checks against every subaddress of the
            // account, so the spent and the kept money of the whole history come out exact
            val (spentAmount, unspentAmount) = spentAndUnspentAmounts(wallet)
            val exported = wallet.exportKeyImages(true)
            val viewOnly = viewOnlyWalletOf(wallet)
            try {
                syncUntil(viewOnly) { viewOnly.getTxs().isNotEmpty() }
                assertEquals(spentAmount + unspentAmount, viewOnly.getBalance())
                val result = viewOnly.importKeyImages(exported.keyImages!!, 0)
                assertEquals(spentAmount, result.spentAmount, "the key image of the spend from the subaddress spent the money of the history")
                assertEquals(unspentAmount, result.unspentAmount, "the key images left the money of the history")
            } finally {
                viewOnly.close()
            }
        } finally {
            wallet.close()
        }
    }
}
