package monero.wallet

import java.math.BigInteger
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import monero.RegtestSupport
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.MoneroDaemonRpc
import monero.daemon.model.MoneroNetworkType
import monero.wallet.model.MoneroDestination
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroWalletConfig
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Full wallet integration tests. They need monero-c and a regtest node, which integration-tests.yml starts with the
 * docker-compose.yml of monero-c, and they move its chain: they mine to the wallets that they create, and pay with
 * what they mine. What they assert about the chain is relative to the chain that they find.
 */
@Order(3)
@Tag("integration")
class MoneroWalletFullIntegrationTest {

    // the coinbase of a block is locked for 60 blocks, and a block that spends it needs one more
    private val blocksToUnlock = 61

    @TempDir
    lateinit var tempDir: Path

    @BeforeTest
    fun requireLibraryAndDaemon() {
        RegtestSupport.assumeAvailable()
    }

    // A random wallet scans a regtest chain from the genesis block, and reaches the height of the daemon
    @Test
    fun testSyncFromGenesis() {
        MoneroDaemonRpc(RegtestSupport.daemonUri).use { daemon ->
            createWallet().use { wallet ->
                assertTrue(wallet.isConnectedToDaemon())
                assertFalse(wallet.isSynced())
                assertEquals(1L, wallet.getHeight())

                wallet.sync()

                val daemonHeight = daemon.getHeight()
                assertTrue(wallet.isSynced())
                assertEquals(daemonHeight, wallet.getDaemonHeight())
                assertEquals(daemonHeight, wallet.getHeight())
                assertEquals(BigInteger.ZERO, wallet.getBalance())
            }
        }
    }

    // The blocks that a wallet gets mined are its balance once it syncs, and unlocked once they have matured
    @Test
    fun testBalanceOfMinedBlocks() {
        createWallet().use { wallet ->
            RegtestSupport.mineBlocks(2, wallet.getPrimaryAddress())
            wallet.sync()
            assertTrue(wallet.getBalance().signum() > 0, "the wallet got the blocks that were mined for it")
            assertEquals(BigInteger.ZERO, wallet.getUnlockedBalance())

            RegtestSupport.mineBlocks(blocksToUnlock)
            wallet.sync()
            assertTrue(wallet.getUnlockedBalance().signum() > 0)
            assertEquals(wallet.getBalance(), wallet.getUnlockedBalance())
        }
    }

    // A wallet that syncs in the background gets the blocks that are mined after it started
    @Test
    fun testSyncInTheBackground() {
        createWallet().use { wallet ->
            wallet.startSyncing(500)
            try {
                RegtestSupport.mineBlocks(1, wallet.getPrimaryAddress())
                val deadline = System.nanoTime() + 60_000_000_000L
                while (wallet.getBalance().signum() == 0) {
                    if (System.nanoTime() > deadline) throw MoneroError("the wallet did not sync the block that was mined for it")
                    Thread.sleep(500)
                }
            } finally {
                wallet.stopSyncing()
            }
        }
    }

    // The wait for the next block returns the height of the block that is mined while the wallet syncs
    @Test
    fun testWaitForNextBlock() {
        createWallet().use { wallet ->
            wallet.sync()
            wallet.startSyncing(500)
            try {
                assertNull(wallet.waitForNextBlock(300), "no block is mined while the wallet waits")

                // the wait holds the thread of the wallet, so the block is mined from another one
                val mined = CompletableFuture.runAsync {
                    Thread.sleep(500)
                    RegtestSupport.mineBlocks(1)
                }
                val height = wallet.waitForNextBlock(30_000)
                mined.get(60, TimeUnit.SECONDS)

                assertNotNull(height, "a block was mined while the wallet waited")
                assertTrue(height > 0)
            } finally {
                wallet.stopSyncing()
            }
        }
    }

    // A sync that runs is asked to stop from another thread, and the wallet closes after it
    @Test
    fun testRequestShutdownStopsASync() {
        val config = config()
        val wallet = MoneroWalletFull.createWallet(config)
        try {
            RegtestSupport.mineBlocks(300, wallet.getPrimaryAddress())
            val sync = CompletableFuture.supplyAsync { wallet.sync() }
            Thread.sleep(100)

            wallet.requestShutdown()

            // the sync ends, whether it was stopped or it had finished, and the calls that need the daemon fail from then on
            runCatching { sync.get(60, TimeUnit.SECONDS) }
            assertFailsWith<MoneroError> { wallet.sync() }
        } finally {
            wallet.close()
        }
        assertTrue(wallet.isClosed())
    }

    // Two transfers move exactly their amounts and their fees between two wallets: the first relays from the metadata
    // of its transaction, and the second from the transaction
    @Test
    fun testSendBetweenWallets() {
        val senderConfig = config()
        val sender = MoneroWalletFull.createWallet(senderConfig)
        val receiver = createWallet()
        try {
            // five coinbases fund the sender. A coinbase of a regtest chain spends after 60 blocks, and every ring
            // that the wallet builds needs sixteen decoys that spend, so the chain grows past that with blocks that no
            // wallet of the test watches
            RegtestSupport.mineBlocks(5, sender.getPrimaryAddress())
            RegtestSupport.mineBlocks(130)
            sender.sync()
            val funds = sender.getBalance()
            assertTrue(funds.signum() > 0, "the sender has no funds")
            assertEquals(funds, sender.getUnlockedBalance(), "the coinbases of the sender are still locked")

            // a destination that is not an address is refused before the daemon is asked anything
            val badAddress = assertFailsWith<MoneroError> { sender.createTxs(txConfig("notanaddress", amount)) }
            assertEquals("Invalid destination address", badAddress.message)

            // the first transfer is created without relaying, and relays from its metadata
            val first = sender.createTxs(txConfig(receiver.getPrimaryAddress(), amount)).single()
            val firstFee = assertNotNull(first.fee)
            assertTrue(firstFee.signum() > 0)
            val firstHash = assertNotNull(first.hash)
            assertEquals(firstHash, sender.relayTx(assertNotNull(first.metadata)))

            // a block holds the transaction, and the receiver reads exactly what was sent
            RegtestSupport.mineBlocks(1)
            receiver.sync()
            assertEquals(amount, receiver.getBalance())
            val received = receiver.getTxs().single { it.hash == firstHash }
            assertEquals(true, received.isIncoming)
            assertEquals(amount, received.incomingTransfers!!.single().amount)

            // the sender is down by the amount and the fee
            sender.sync()
            assertEquals(funds - amount - firstFee, sender.getBalance())
            val sent = sender.getTxs().single { it.hash == firstHash }
            assertEquals(firstFee, sent.fee)
            assertEquals(false, sent.isIncoming)

            // the change of the first transfer spends after 10 blocks, and the second transfer needs it
            RegtestSupport.mineBlocks(10)
            sender.sync()
            val second = sender.createTxs(txConfig(receiver.getPrimaryAddress(), smallAmount)).single()
            val secondFee = assertNotNull(second.fee)
            assertEquals(assertNotNull(second.hash), sender.relayTx(second))
            RegtestSupport.mineBlocks(1)
            receiver.sync()
            sender.sync()
            assertEquals(amount + smallAmount, receiver.getBalance())
            assertEquals(funds - amount - firstFee - smallAmount - secondFee, sender.getBalance())

            // a query reads the transactions by their hashes
            val query = MoneroTxQuery()
            query.hashes = listOf(firstHash)
            assertEquals(listOf(firstHash), sender.getTxs(query).map { it.hash })
        } finally {
            sender.close()
            receiver.close()
        }
    }

    // A wallet that is saved after it spent keeps its balance when it is opened again
    @Test
    fun testSavedWalletKeepsWhatItSpent() {
        val senderConfig = config()
        val sender = MoneroWalletFull.createWallet(senderConfig)
        val receiver = createWallet()
        val expected: BigInteger
        try {
            RegtestSupport.mineBlocks(5, sender.getPrimaryAddress())
            RegtestSupport.mineBlocks(130)
            sender.sync()
            val funds = sender.getBalance()
            val tx = sender.createTxs(txConfig(receiver.getPrimaryAddress(), amount)).single()
            sender.relayTx(assertNotNull(tx.metadata))
            RegtestSupport.mineBlocks(1)
            sender.sync()
            expected = funds - amount - assertNotNull(tx.fee)
            assertEquals(expected, sender.getBalance())
            sender.close(true)
        } finally {
            sender.close()
            receiver.close()
        }

        // the connection is not in the files, so the wallet that is opened connects again
        MoneroWalletFull.openWallet(senderConfig).use { wallet ->
            wallet.setDaemonConnection(MoneroRpcConnection(RegtestSupport.daemonUri))
            wallet.sync()
            assertEquals(expected, wallet.getBalance())
        }
    }

    // A wallet that is reopened keeps what it scanned, so it does not scan the chain again
    @Test
    fun testReopenKeepsTheChain() {
        val config = config()
        val created = MoneroWalletFull.createWallet(config)
        val height: Long
        val balance: BigInteger
        val seed: String
        try {
            RegtestSupport.mineBlocks(2, created.getPrimaryAddress())
            created.sync()
            height = created.getHeight()
            balance = created.getBalance()
            seed = created.getSeed()
            assertTrue(balance.signum() > 0)
        } finally {
            created.close(true)
        }

        MoneroWalletFull.openWallet(config).use { wallet ->
            assertEquals(seed, wallet.getSeed())
            assertEquals(height, wallet.getHeight())
            assertEquals(balance, wallet.getBalance())
        }
    }

    // A 2 of 2 multisig wallet is funded, and pays a wallet that is not one of its participants with the signatures of
    // both of them
    @Test
    fun testSendFromMultisig2Of2() {
        testSendFromMultisig(2, 2)
    }

    // A 2 of 3 multisig wallet pays with the signatures of two of its three participants, and the third one sees it
    @Test
    fun testSendFromMultisig2Of3() {
        testSendFromMultisig(2, 3)
    }

    // ------------------------------- PRIVATE ------------------------------------

    private val password = "supersecretpassword123"
    private val amount = BigInteger.valueOf(5_000_000_000_000L)
    private val smallAmount = BigInteger.valueOf(3_000_000_000_000L)

    private fun testSendFromMultisig(m: Int, n: Int) {
        val receiver = createWallet()
        try {
            MultisigSupport.create(m, n, ::config, password).use { setup ->
                val participants = setup.participants
                val creator = participants.first()

                // five coinbases fund the multisig wallet, which spends them after the same blocks as any other
                RegtestSupport.mineBlocks(5, setup.address)
                RegtestSupport.mineBlocks(130)
                for (participant in participants) participant.sync()
                val funds = creator.getBalance()
                assertTrue(funds.signum() > 0, "the multisig wallet has no funds")
                for (participant in participants) assertEquals(funds, participant.getBalance())

                // a participant can not spend what it has not exchanged the key images of with the others
                assertTrue(creator.isMultisigImportNeeded())
                assertFailsWith<MoneroError> { creator.createTxs(txConfig(receiver.getPrimaryAddress(), amount)) }
                exchangeKeyImages(participants)
                assertFalse(creator.isMultisigImportNeeded())

                // the creator makes the transaction, which already has its signature, and the others up to the threshold
                // sign it one after the other
                val tx = creator.createTxs(txConfig(receiver.getPrimaryAddress(), amount)).single()
                val fee = assertNotNull(tx.fee)
                var signedHex = assertNotNull(tx.txSet?.multisigTxHex, "the multisig transaction has no hex to sign")
                var txHashes: List<String>? = null
                for (signer in participants.drop(1).take(m - 1)) {
                    val result = signer.signMultisigTxHex(signedHex)
                    signedHex = assertNotNull(result.signedMultisigTxHex)
                    txHashes = result.txHashes
                }

                // the signatures of the threshold are in the hex, so any participant can submit it
                val submitted = participants.last().submitMultisigTxHex(signedHex)
                assertEquals(1, submitted.size)
                assertEquals(txHashes, submitted)
                RegtestSupport.mineBlocks(1)

                receiver.sync()
                assertEquals(amount, receiver.getBalance())
                assertEquals(submitted.single(), receiver.getTxs().single().hash)

                // every participant sees what the transaction spent, once it has the key images of the others
                for (participant in participants) participant.sync()
                exchangeKeyImages(participants)
                for (participant in participants) {
                    assertEquals(funds - amount - fee, participant.getBalance())
                    assertEquals(submitted.single(), participant.getTxs().single { it.isOutgoing == true }.hash)
                }
            }
        } finally {
            receiver.close()
        }
    }

    // every participant imports what the others export, which tells it which outputs they spent
    private fun exchangeKeyImages(participants: List<MoneroWalletFull>) {
        val hexes = participants.map { it.exportMultisigHex() }
        for ((i, participant) in participants.withIndex()) {
            participant.importMultisigHex(hexes.filterIndexed { j, _ -> j != i }, true)
        }
    }

    private fun txConfig(address: String, amount: BigInteger): MoneroTxConfig {
        val config = MoneroTxConfig()
        config.accountIndex = 0
        config.destinations = listOf(MoneroDestination(address, amount))
        config.relay = false
        return config
    }

    private fun config(): MoneroWalletConfig {
        val config = MoneroWalletConfig()
        config.path = tempDir.resolve("wallet-${System.nanoTime()}").toString()
        config.password = password
        config.networkType = MoneroNetworkType.MAINNET
        config.server = MoneroRpcConnection(RegtestSupport.daemonUri)
        config.regtest = true
        return config
    }

    private fun createWallet(): MoneroWalletFull {
        return MoneroWalletFull.createWallet(config())
    }
}
