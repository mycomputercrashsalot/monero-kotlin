package monero.wallet

import java.math.BigInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import monero.RegtestSupport
import monero.common.MoneroError
import monero.daemon.MoneroDaemonRpc
import monero.wallet.model.MoneroTxConfig
import monero.wallet.model.MoneroTransferQuery
import monero.wallet.model.MoneroTxQuery
import monero.wallet.model.MoneroTxWallet

/**
 * The keys and the proofs of the transactions of a wallet, against a regtest node, like the tests of monero-python
 * (tests/test_monero_wallet_common.py) that check a transaction with its key, with a proof of the payment and with a proof of the spend,
 * which every kind of wallet runs: a subclass says which kind. They read the wallet of [TxHistoryWallet], which has transactions to an
 * address that is not its own. They need monero-c and the regtest node of src/test/docker.
 */
abstract class BaseMoneroWalletProofsTest {

    /**
     * The kind of wallet that the tests run for.
     */
    protected abstract val wallets: TestWallets

    /**
     * What the wallet answers when the signature of a reserve proof to check is empty: a full wallet asks for a signature.
     */
    protected open val emptySignatureError = "Must provide signature to check reserve proof"

    // the most transactions that a test proves, as WalletUtils.MAX_TX_PROOFS of monero-python
    private val maxTxProofs = 25

    @BeforeTest
    fun requireLibraryAndNode() {
        wallets.assumeAvailable()
    }

    private fun wallet(): MoneroWalletNative = TxHistoryWallet.get(wallets)

    private fun withDestinations(confirmed: Boolean?): List<MoneroTxWallet> {
        val query = MoneroTxQuery()
        query.isConfirmed = confirmed
        query.transferQuery = MoneroTransferQuery().also { it.hasDestinations = true }
        return wallet().getTxs(query).take(maxTxProofs)
    }

    // What an address received in a transaction is what it was paid, unless the address is one that the change goes to as well: the transaction
    // that pays the primary address of the wallet, or its integrated address, receives the change too
    private fun assertReceived(address: String, paid: BigInteger, received: BigInteger) {
        if (address == RegtestSupport.MINING_ADDRESS) assertEquals(paid, received, "what $address received")
        else assertTrue(received >= paid, "what $address received: $received of $paid")
    }

    // Can prove an unrelayed transaction: the daemon holds it without relaying it, and a wallet that has nothing to do with it checks
    // what each address received with its key. It uses the shared wallet and mines nothing, as the others of the class: a block would put
    // the transaction that the shared wallet has in the pool in a block, which the wallet does not know until it syncs
    @Test
    fun testProveUnrelayedTxs() {
        val max = TxAssertions.MAX_FEE
        val wallet = wallet()
        val address1 = RegtestSupport.MINING_ADDRESS
        val address2 = wallet.getAddress(0, 0)
        val address3 = wallet.getAddress(1, 0)
        val config = MoneroTxConfig()
        config.accountIndex = 0
        config.addDestination(address1, max)
        config.addDestination(address2, max.multiply(BigInteger.TWO))
        config.addDestination(address3, max.multiply(BigInteger.valueOf(3)))
        val tx = wallet.createTx(config)
        val hash = assertNotNull(tx.hash)
        val key = assertNotNull(tx.key)

        // the daemon takes the transaction and does not relay it
        MoneroDaemonRpc(RegtestSupport.daemonConnection()).use { daemon ->
            assertEquals(true, daemon.submitTxHex(assertNotNull(tx.fullHex), true).isGood)
            try {
                wallets.create().use { verifying ->
                    val check1 = verifying.checkTxKey(hash, key, address1)
                    TxAssertions.validateCheckTx(check1)
                    assertEquals(true, check1.isGood)
                    assertEquals(true, check1.inTxPool)
                    assertEquals(0L, check1.numConfirmations)
                    assertEquals(max, check1.receivedAmount)

                    // this one gets the change too
                    val check2 = verifying.checkTxKey(hash, key, address2)
                    assertEquals(true, check2.isGood)
                    assertEquals(true, check2.inTxPool)
                    assertEquals(0L, check2.numConfirmations)
                    assertTrue(check2.receivedAmount!! >= max.multiply(BigInteger.TWO))

                    val check3 = verifying.checkTxKey(hash, key, address3)
                    assertEquals(true, check3.isGood)
                    assertEquals(true, check3.inTxPool)
                    assertEquals(0L, check3.numConfirmations)
                    assertEquals(max.multiply(BigInteger.valueOf(3)), check3.receivedAmount)
                }
            } finally {
                // otherwise the outputs that it spends stay tied up for the other tests
                daemon.flushTxPool(hash)
            }
        }
    }

    // Can check a transfer using the secret key of the transaction and the destination
    @Test
    fun testCheckTxKey() {
        val wallet = wallet()
        val txs = withDestinations(true)
        assertTrue(txs.size > 1, "No transactions found with outgoing destinations")

        for (tx in txs) {
            val key = wallet.getTxKey(tx.hash!!)
            // the key of the transaction, followed by the additional keys that a payment to subaddresses has
            assertTrue(key.matches(Regex("([0-9a-f]{64})+")), "the key of a transaction: $key")
            assertTrue(tx.outgoingTransfer!!.destinations!!.isNotEmpty())
            for (destination in tx.outgoingTransfer!!.destinations!!) {
                val check = wallet.checkTxKey(tx.hash!!, key, destination.address!!)
                TxAssertions.validateCheckTx(check)
                assertEquals(true, check.isGood)
                assertReceived(destination.address!!, destination.amount!!, check.receivedAmount!!)
            }
        }

        // a key, a hash and an address that are not valid
        val tx = txs.first()
        val key = wallet.getTxKey(tx.hash!!)
        val destination = tx.outgoingTransfer!!.destinations!!.first()
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.getTxKey("invalid_tx_id") }.message)
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.checkTxKey("invalid_tx_id", key, destination.address!!) }.message)
        assertEquals("Tx key has invalid format", assertFailsWith<MoneroError> { wallet.checkTxKey(tx.hash!!, "invalid_tx_key", destination.address!!) }.message)
        assertEquals("Invalid address", assertFailsWith<MoneroError> { wallet.checkTxKey(tx.hash!!, key, "invalid_tx_address") }.message)

        // an address of another destination of the wallet: the fixture reuses its addresses, so the
        // transaction may have paid it too, and the check is good exactly when it received something
        val other = wallet.getTxs().flatMap { it.outgoingTransfer?.destinations ?: emptyList() }.map { it.address!! }.first { it != destination.address }
        val different = wallet.checkTxKey(tx.hash!!, key, other)
        TxAssertions.validateCheckTx(different)
        if (tx.outgoingTransfer!!.destinations!!.any { it.address == other }) {
            assertEquals(true, different.isGood)
            assertTrue(different.receivedAmount!!.signum() > 0)
        } else {
            assertEquals(false, different.isGood)
            assertEquals(BigInteger.ZERO, different.receivedAmount)
        }
    }

    // The key of a transaction that the wallet did not send is not one it has, and the key of another transaction proves no payment
    @Test
    fun testCheckTxKeyThatIsNotTheOne() {
        val wallet = wallet()
        val sent = withDestinations(true)
        val received = wallet.getTxs().first { it.isOutgoing != true }
        assertFailsWith<MoneroError> { wallet.getTxKey(received.hash!!) }
        assertFailsWith<MoneroError> { wallet.getTxKey("a".repeat(64)) }

        val tx = sent[0]
        val address = tx.outgoingTransfer!!.destinations!!.first().address!!
        val keyOfAnother = wallet.getTxKey(sent[1].hash!!)
        val check = wallet.checkTxKey(tx.hash!!, keyOfAnother, address)
        TxAssertions.validateCheckTx(check)
        // the key of another transaction proves no payment: the answer is not good, and the address received nothing with it
        assertEquals(false, check.isGood)
        assertEquals(BigInteger.ZERO, check.receivedAmount, "the key of another transaction proves no payment")
    }

    // Can prove a transaction by getting its signature
    @Test
    fun testCheckTxProof() {
        val wallet = wallet()
        val txs = withDestinations(null)
        assertTrue(txs.size > 1, "No transactions found with outgoing destinations")
        val message = "This transaction definitely happened."

        for (tx in txs) {
            for (destination in tx.outgoingTransfer!!.destinations!!) {
                val signature = wallet.getTxProof(tx.hash!!, destination.address!!, message)
                val check = wallet.checkTxProof(tx.hash!!, destination.address!!, message, signature)
                TxAssertions.validateCheckTx(check)
                assertEquals(true, check.isGood)
                assertReceived(destination.address!!, destination.amount!!, check.receivedAmount!!)
            }
        }

        // without a message
        val tx = txs[0]
        val destination = tx.outgoingTransfer!!.destinations!!.first()
        val signature = wallet.getTxProof(tx.hash!!, destination.address!!)
        val check = wallet.checkTxProof(tx.hash!!, destination.address!!, "", signature)
        TxAssertions.validateCheckTx(check)
        assertEquals(true, check.isGood)
        assertEquals(true, wallet.checkTxProof(tx.hash!!, destination.address!!, null, signature).isGood)

        // a hash and an address that are not valid
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.getTxProof("invalid_tx_id", destination.address!!) }.message)
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.checkTxProof("invalid_tx_id", destination.address!!, "", signature) }.message)
        assertEquals("Invalid address", assertFailsWith<MoneroError> { wallet.checkTxProof(tx.hash!!, "invalid_tx_address", "", signature) }.message)

        // the wrong message
        val rightMessage = wallet.getTxProof(tx.hash!!, destination.address!!, "This is the right message")
        val wrongMessage = wallet.checkTxProof(tx.hash!!, destination.address!!, "This is the wrong message", rightMessage)
        assertEquals(false, wrongMessage.isGood)
        TxAssertions.validateCheckTx(wrongMessage)
    }

    // A signature of another transaction, or none, is an error
    @Test
    fun testCheckTxProofWithASignatureThatIsNotTheOne() {
        val wallet = wallet()
        val txs = withDestinations(null)
        val tx = txs[0]
        val destination = tx.outgoingTransfer!!.destinations!!.first()
        val other = txs[1]
        val wrong = wallet.getTxProof(other.hash!!, other.outgoingTransfer!!.destinations!!.first().address!!, "This is the right message")
        val mismatch = assertFailsWith<MoneroError> { wallet.checkTxProof(tx.hash!!, destination.address!!, "This is the right message", wrong) }
        assertEquals("Signature size mismatch with additional tx pubkeys", mismatch.message)

        val empty = assertFailsWith<MoneroError> { wallet.checkTxProof(tx.hash!!, destination.address!!, "This is the right message", "") }
        assertEquals("Must provide signature to check tx proof", empty.message)
    }

    // Can prove a spend using a signature and no destination address
    @Test
    fun testCheckSpendProof() {
        val wallet = wallet()
        val query = MoneroTxQuery()
        query.isIncoming = false
        query.inTxPool = false
        query.isFailed = false
        val txs = wallet.getTxs(query).take(maxTxProofs)
        assertTrue(txs.size > 1, "No confirmed outgoing transactions to test")
        for (tx in txs) {
            assertEquals(true, tx.isConfirmed)
            assertTrue((tx.incomingTransfers ?: emptyList()).isEmpty())
            assertNotNull(tx.outgoingTransfer)
        }

        for (tx in txs) {
            val signature = wallet.getSpendProof(tx.hash!!, "I am a message.")
            assertTrue(wallet.checkSpendProof(tx.hash!!, "I am a message.", signature))
        }

        // without a message
        val tx = txs[0]
        val signature = wallet.getSpendProof(tx.hash!!)
        assertTrue(wallet.checkSpendProof(tx.hash!!, "", signature))
        assertTrue(wallet.checkSpendProof(tx.hash!!, null, signature))

        // a hash that is not valid
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.getSpendProof("invalid_tx_id") }.message)
        assertEquals("TX hash has invalid format", assertFailsWith<MoneroError> { wallet.checkSpendProof("invalid_tx_id", "", signature) }.message)

        // the wrong message, and the signature of another transaction
        val right = wallet.getSpendProof(tx.hash!!, "This is the right message")
        assertFalse(wallet.checkSpendProof(tx.hash!!, "This is the wrong message", right))
        val ofAnother = wallet.getSpendProof(txs[1].hash!!, "This is the right message")
        assertFalse(wallet.checkSpendProof(tx.hash!!, "This is the right message", ofAnother))
    }

    // Can prove the reserves of the wallet, which include what it spent in transactions that are not confirmed
    @Test
    fun testGetReserveProofWallet() {
        val wallet = wallet()
        val primary = wallet.getPrimaryAddress()
        val signature = wallet.getReserveProofWallet("Test message")

        val check = wallet.checkReserveProof(primary, "Test message", signature)
        TxAssertions.validateCheckReserve(check)
        assertEquals(true, check.isGood)
        // the proof shows the outputs that the wallet had before the transaction in the pool spent them
        assertTrue(wallet.getTxs().any { it.inTxPool == true })
        assertTrue(check.unconfirmedSpentAmount!!.signum() > 0, "the transaction in the pool spent outputs")
        assertTrue(check.totalAmount!! >= wallet.getBalance())

        // without a message, and for its integrated address
        assertEquals(true, wallet.checkReserveProof(primary, null, wallet.getReserveProofWallet(null)).isGood)
        assertEquals(true, wallet.checkReserveProof(wallet.getIntegratedAddress(null, TxHistoryWallet.PAYMENT_ID).integratedAddress!!, "Test message", signature).isGood)

        // the wrong message
        val wrong = wallet.checkReserveProof(primary, "Wrong message", signature)
        assertEquals(false, wrong.isGood)
        TxAssertions.validateCheckReserve(wrong)

        // an address that the proof is not of, a subaddress, a signature that is not one, and an address that is not one
        assertEquals("The given address isn't found in the proof", assertFailsWith<MoneroError> { wallet.checkReserveProof(RegtestSupport.MINING_ADDRESS, "Test message", signature) }.message)
        assertEquals("Address must not be a subaddress", assertFailsWith<MoneroError> { wallet.checkReserveProof(wallet.getAddress(0, 1), "Test message", signature) }.message)
        assertEquals("Signature header check error", assertFailsWith<MoneroError> { wallet.checkReserveProof(primary, "Test message", "wrong signature") }.message)
        assertEquals(emptySignatureError, assertFailsWith<MoneroError> { wallet.checkReserveProof(primary, "Test message", "") }.message)
        assertEquals("Invalid address", assertFailsWith<MoneroError> { wallet.checkReserveProof("invalid_address", "Test message", signature) }.message)
    }

    // The proof of a wallet that has nothing in the pool shows its balance, and none of it spent
    @Test
    fun testGetReserveProofWalletWithoutUnconfirmedTxs() {
        wallets.create().use { wallet ->
            RegtestSupport.mineBlocks(5, wallet.getPrimaryAddress())
            RegtestSupport.mineBlocks(130)
            wallet.sync()
            assertTrue(wallet.getTxs().none { it.inTxPool == true })

            val check = wallet.checkReserveProof(wallet.getPrimaryAddress(), "Test message", wallet.getReserveProofWallet("Test message"))
            TxAssertions.validateCheckReserve(check)
            assertEquals(true, check.isGood)
            assertEquals(wallet.getBalance(), check.totalAmount)
            assertEquals(BigInteger.ZERO, check.unconfirmedSpentAmount)
        }
    }

    // Can prove the reserves of the accounts that have funds, and not those that have none or have less than the amount
    @Test
    fun testGetReserveProofAccount() {
        val wallet = wallet()
        val primary = wallet.getPrimaryAddress()
        val accounts = wallet.getAccounts().filter { it.balance!!.signum() > 0 }
        assertTrue(accounts.size > 1, "Must have more than one account with funds")

        var signature = ""
        for (account in accounts) {
            val amount = account.balance!!.divide(BigInteger.TWO)
            signature = wallet.getReserveProofAccount(account.index!!, amount, "Test message")
            val check = wallet.checkReserveProof(primary, "Test message", signature)
            TxAssertions.validateCheckReserve(check)
            assertEquals(true, check.isGood)
            assertTrue(check.totalAmount!! >= amount, "the proof shows at least the amount that was asked")
        }

        // the amounts that can not be proved: none, and more than the balance of the account. An amount over the balance of the first
        // account is proved all the same, as monero-python notes (monero-project/monero#6595), so that one is not asserted
        val account = accounts[1]
        assertEquals("Proved amount must be greater than 0", assertFailsWith<MoneroError> { wallet.getReserveProofAccount(account.index!!, BigInteger.ZERO, "Test message") }.message)
        assertEquals("Not enough balance in this account for the requested minimum reserve amount", assertFailsWith<MoneroError> { wallet.getReserveProofAccount(account.index!!, account.balance!!.add(TxAssertions.MAX_FEE), "Test message") }.message)
        assertEquals("Account index is out of bound", assertFailsWith<MoneroError> { wallet.getReserveProofAccount(99, BigInteger.ONE, "Test message") }.message)
        assertFailsWith<MoneroError> { wallet.getReserveProofAccount(0, BigInteger.ONE.negate(), "Test message") }

        // the proof of the last account, checked for an address that is not its own, a subaddress, the wrong message and a wrong signature
        assertFailsWith<MoneroError> { wallet.checkReserveProof(RegtestSupport.MINING_ADDRESS, "Test message", signature) }
        assertFailsWith<MoneroError> { wallet.checkReserveProof(wallet.getAddress(0, 1), "Test message", signature) }
        val wrong = wallet.checkReserveProof(primary, "Wrong message", signature)
        assertEquals(false, wrong.isGood)
        TxAssertions.validateCheckReserve(wrong)
        assertFailsWith<MoneroError> { wallet.checkReserveProof(primary, "Test message", "wrong signature") }
    }
}
