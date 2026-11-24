package monero.wallet

import java.math.BigInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroIncomingTransfer
import monero.wallet.model.MoneroOutputWallet
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag

/**
 * The wallet that a light wallet server has, read again after its handle is closed: the wallet lives in
 * memory, so the reopen makes a new handle that syncs with the server from the restore height of the
 * account, and the history that the server has reads the same through both handles. The test mines five
 * regtest blocks to the wallet of the seed of the shared test wallet the way the captures of
 * MoneroWalletLightIntegrationTest were taken, and asserts the exact integer that the emission curve
 * pays the blocks of its history: the python light suite pins 175921021585277, which is the sum of the
 * rewards of the first five heights of a fresh chain, and the chain of these tests is one that other
 * tests mine, so the test reads the rewards of the heights that its own blocks land on from the node and
 * asserts their exact sum against the amounts of its transactions. It needs the monero-lws of
 * src/test/integration/docker-compose.yml and the node that the compose mines on, and it skips when no
 * server answers, like the tests of MoneroWalletLightIntegrationTest, which this class runs after.
 */
@Order(5)
@Tag("integration")
class MoneroWalletLightReopenIntegrationTest {

    // the seed of the shared test wallet of BaseMoneroWalletTest, whose account the shared chain has paid
    private val seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // a wallet that the server has reads the same through two handles: the test mines five blocks to it,
    // reads its balances, its transactions with their transfers and its outputs, and reads them again
    // after a close and a reopen
    @Test
    fun testReopenReadsTheSameWallet() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = seed
        val wallet = openOrCreate(config)
        try {
            // the wallet syncs to the chain before the test mines to it, so that the blocks of the
            // mining are the ones that change what it reads
            syncToHeight(wallet, daemon.getHeight())

            // five blocks to the wallet the way the captures were taken, whose rewards the emission
            // curve pays at the heights that they land on
            val height = daemon.getHeight()
            RegtestSupport.mineBlocks(5, wallet.getPrimaryAddress())

            // the wallet reads the mined blocks when the scan of its account on the server reaches
            // them, which the height of the chain does not say: the coinbase of the last of the five
            // blocks is in the wallet when the scan has them all
            syncUntil(wallet) { wallet.getTxs().any { tx -> tx.height == height + 4 && tx.isMinerTx == true } }

            // the exact integers of the history: the rewards of the five heights that the mining made,
            // which the node answers for the blocks that it holds, are the amounts of the transactions
            // that the wallet reads for them. A transaction that another test relayed to the shared
            // account may reach a block of the range too, so the coinbases are the miner transactions
            val rewards = daemon.getBlockHeadersByRange(height, height + 4).map { header -> checkNotNull(header.reward) }
            val mined = wallet.getTxs().filter { tx -> tx.height in height..height + 4 && tx.isMinerTx == true }
            assertEquals(5, mined.size, "the mining makes five transactions for the wallet")
            assertEquals((height..height + 4).toList(), mined.mapNotNull { tx -> tx.height }.sorted(), "the transactions are the coinbases of the five mined blocks")
            for (tx in mined) {
                assertEquals(true, tx.isMinerTx, "a transaction of a mined block is the miner transaction of it")
                assertEquals(rewards[(tx.height!! - height).toInt()], tx.getIncomingAmount(), "the miner transaction of a block pays the reward of the block")
            }
            val sum = daemon.getMinerTxSum(height, 5)
            assertEquals(rewards.fold(BigInteger.ZERO, BigInteger::add), checkNotNull(sum.emissionSum).add(checkNotNull(sum.feeSum)), "the sum of the rewards is the coinbase sum of the node")

            // the shapes that the reopen reads again: the balances, the transactions with their
            // transfers, and the outputs with their key images
            val balance = wallet.getBalance()
            val unlocked = wallet.getUnlockedBalance()
            val shapes = txShapes(wallet)
            val transfers = transferShapes(wallet)
            val outputs = outputShapes(wallet)
            wallet.close()

            val reopened = MoneroWalletLight.openWallet(config, connection)
            try {
                syncToHeight(reopened, daemon.getHeight())
                assertEquals(balance, reopened.getBalance(), "the reopened wallet reads the same balance")
                assertEquals(unlocked, reopened.getUnlockedBalance(), "the reopened wallet reads the same unlocked balance")
                assertEquals(shapes, txShapes(reopened), "the reopened wallet reads the same transactions")
                assertEquals(transfers, transferShapes(reopened), "the reopened wallet reads the same transfers")
                assertEquals(outputs, outputShapes(reopened), "the reopened wallet reads the same outputs")
            } finally {
                reopened.close()
            }
        } finally {
            if (!wallet.isClosed()) wallet.close()
        }
    }

    // the shared server has the account of the seed after the first run of a test that uses it, and a
    // wallet that the server has is not created again
    private fun openOrCreate(config: MoneroWalletConfig): MoneroWalletLight {
        return if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
    }

    // the shape of a transaction of the history of the wallet: its hash with its height, its flags, its
    // fee and the amounts of its transfers
    private fun txShapes(wallet: MoneroWalletLight): Map<String, List<String?>> {
        return wallet.getTxs().associate { tx ->
            checkNotNull(tx.hash) to listOf(tx.height?.toString(), tx.isMinerTx?.toString(), tx.isIncoming?.toString(), tx.isOutgoing?.toString(), tx.fee?.toString(), tx.getIncomingAmount().toString(), tx.getOutgoingAmount().toString())
        }
    }

    // the shape of the transfers of a transaction: the amount of each one with the account and the
    // subaddress that it pays
    private fun transferShapes(wallet: MoneroWalletLight): Map<String, List<String>> {
        return wallet.getTxs().associate { tx ->
            checkNotNull(tx.hash) to tx.getTransfers().map { transfer -> "${transfer.amount}:${transfer.accountIndex}:${(transfer as? MoneroIncomingTransfer)?.subaddressIndex}" }
        }
    }

    // the shape of the outputs of the wallet: the key image of each one with its amount, its flags and
    // its indices
    private fun outputShapes(wallet: MoneroWalletLight): List<String> {
        return wallet.getTxs(MoneroTxQuery().apply { includeOutputs = true }).asSequence()
            .flatMap { tx -> tx.outputs.orEmpty().asSequence() }
            .filterIsInstance<MoneroOutputWallet>()
            .map { output -> "${output.keyImage?.hex}:${output.amount}:${output.isSpent}:${output.isFrozen}:${output.accountIndex}:${output.subaddressIndex}" }
            .sorted()
            .toList()
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
}
