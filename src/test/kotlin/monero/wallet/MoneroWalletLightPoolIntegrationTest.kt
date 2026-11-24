package monero.wallet

import java.math.BigInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import monero.LwsSupport
import monero.NativeTestSupport
import monero.RegtestSupport
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
 * The transactions of a light wallet that its server has not taken: a light wallet server does not sync
 * the pool of its daemon, so the wallet has no mempool of its own, and a transaction that the wallet
 * builds without relaying is nowhere until a block holds it. The test builds one without relaying it and
 * asserts its answer: it is not relayed and not failed, it carries the metadata that a relay takes and a
 * fee, the node that the compose runs knows nothing of its hash, and the wallet itself does not list it,
 * since its history is the chain that its server has scanned. It needs the monero-lws of
 * src/test/integration/docker-compose.yml and the node that the compose mines on, and it skips when no
 * server answers, like the tests of MoneroWalletLightIntegrationTest, which this class runs after.
 */
@Order(8)
@Tag("integration")
class MoneroWalletLightPoolIntegrationTest {

    // the seed of the shared test wallet of BaseMoneroWalletTest, whose unlocked money the test spends
    private val seed = "vortex degrees outbreak teeming gimmick school rounded tonic observant injury leech ought problems ahead upcoming ledge textbook cigar atrium trash dunes eavesdrop dullness evolved vortex"

    private val connection: MoneroRpcConnection by lazy { MoneroRpcConnection(LwsSupport.lwsUri) }

    @BeforeTest
    fun requireLibraryAndServer() {
        NativeTestSupport.assumeAvailable()
        NativeTestSupport.assumeLightWalletAvailable()
        assumeTrue(LwsSupport.isUp, "no monero-lws at ${LwsSupport.lwsUri}: start the compose of src/test/integration, or set MONERO_KOTLIN_TEST_LWS_URI")
    }

    // a transaction that the wallet builds without relaying is not relayed and not failed, and it is
    // nowhere: the node does not know it, its pool does not hold it, and the wallet does not list it,
    // since a light wallet server has no pool to read
    @Test
    fun testATxBuiltWithoutRelaying_IsNowhereButInItsMetadata() {
        val daemon = MoneroDaemonRpc(RegtestSupport.daemonConnection())
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.seed = seed
        val wallet = if (MoneroWalletLight.walletExists(config, connection)) MoneroWalletLight.openWallet(config, connection)
        else MoneroWalletLight.createWallet(config, connection)
        try {
            syncToHeight(wallet, daemon.getHeight())
            val amount = BigInteger.valueOf(1_000_000_000_000)
            assertTrue(wallet.getUnlockedBalance() > amount, "the wallet of the seed has unlocked money to build a transaction with")

            val txConfig = MoneroTxConfig()
            txConfig.accountIndex = 0
            txConfig.address = wallet.getPrimaryAddress()
            txConfig.amount = amount
            txConfig.relay = false
            val txs = wallet.createTxs(txConfig)
            assertEquals(1, txs.size, "the transfer makes one transaction")
            val tx = txs[0]
            assertEquals(false, tx.isRelayed, "a transaction that the config says not to relay is not relayed")
            assertEquals(false, tx.isFailed, "a transaction that the wallet built is not failed")
            assertEquals(false, tx.inTxPool, "a transaction that was not relayed is in no pool")
            assertEquals(false, tx.isConfirmed, "a transaction that was not relayed is in no block")
            assertEquals(true, tx.isLocked, "a transaction that was not relayed is not spendable")
            assertNotNull(tx.hash, "the transaction carries its hash")
            assertTrue(tx.fee!! > BigInteger.ZERO, "the transaction pays a fee")
            assertNotNull(tx.metadata, "the transaction carries the metadata that a relay takes")

            // the transaction is nowhere: the node answers no transaction of its hash, its pool holds
            // no hash of it, and the wallet lists none of it, since its history is the chain
            assertEquals(0, daemon.getTxs(listOf(tx.hash!!), true).size, "the node knows no transaction of the hash")
            assertEquals(false, daemon.getTxPoolHashes().contains(tx.hash), "the pool of the node holds no hash of the transaction")
            assertEquals(false, wallet.getTxs().any { it.hash == tx.hash }, "the wallet lists no transaction that no block holds")
            assertEquals(0, wallet.getTxs(MoneroTxQuery().apply { inTxPool = true }).size, "a light wallet has no pool to read")
        } finally {
            wallet.close()
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
