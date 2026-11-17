package monero.wallet

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import monero.wallet.model.MoneroCheckReserve
import monero.wallet.model.MoneroCheckTx
import monero.wallet.model.MoneroIncomingTransfer
import monero.wallet.model.MoneroOutgoingTransfer
import monero.wallet.model.MoneroTransfer
import monero.wallet.model.MoneroTxWallet

/**
 * What the integration tests check in the transactions that a wallet makes, like the tests of the transactions of monero-python.
 */
object TxAssertions {

    // 7500000 * 10000 atomic units: a fee that no transaction of the regtest chain goes over, as TxWalletUtils.MAX_FEE of monero-python
    val MAX_FEE: BigInteger = BigInteger.valueOf(75_000_000_000L)

    /**
     * Check a transaction that the wallet made: it has a hash, a fee, an outgoing transfer with its destinations and the metadata to
     * relay it later, it is not confirmed, and it is in the pool if it was relayed.
     */
    fun validateSent(tx: MoneroTxWallet, relayed: Boolean) {
        assertTrue(tx.hash!!.matches(Regex("[0-9a-f]{64}")), "hash of the transaction: ${tx.hash}")
        assertTrue(tx.fee!! > BigInteger.ZERO)
        assertEquals(true, tx.isOutgoing)
        assertEquals(false, tx.isConfirmed)
        assertNotNull(tx.outgoingTransfer)
        assertTrue(tx.outgoingTransfer!!.destinations!!.isNotEmpty())
        assertNotNull(tx.metadata, "the metadata of the transaction relays it later")
        if (relayed) {
            assertEquals(true, tx.isRelayed)
            assertEquals(true, tx.inTxPool)
        } else {
            assertEquals(false, tx.isRelayed)
            assertEquals(false, tx.inTxPool)
        }
    }

    /**
     * Check a transfer of a wallet: it is in its transaction, which is in a block or in the pool, it has an account and an amount, and
     * its subaddresses are the ones of an incoming or of an outgoing transfer.
     */
    fun validateTransfer(transfer: MoneroTransfer) {
        val tx = assertNotNull(transfer.tx, "a transfer is in its transaction")
        assertTrue(tx.hash!!.matches(Regex("[0-9a-f]{64}")), "hash of the transaction: ${tx.hash}")
        assertNotNull(transfer.accountIndex)
        assertTrue(transfer.accountIndex!! >= 0)
        assertNotNull(transfer.amount)
        assertTrue(transfer.amount!! >= BigInteger.ZERO)
        assertNotNull(tx.isConfirmed)
        assertNotNull(tx.inTxPool)
        if (tx.isConfirmed == true) {
            assertNotNull(tx.block?.height, "a transaction that is confirmed is in a block")
            assertEquals(false, tx.inTxPool)
        }
        when (transfer) {
            is MoneroIncomingTransfer -> {
                assertTrue(transfer.isIncoming == true)
                assertTrue(transfer.subaddressIndex!! >= 0)
                assertNotNull(transfer.address)
                assertTrue(transfer.address!!.length > 90)
            }
            is MoneroOutgoingTransfer -> {
                assertTrue(transfer.isOutgoing == true)
                assertTrue(transfer.subaddressIndices!!.isNotEmpty())
                assertTrue(transfer.subaddressIndices!!.all { it >= 0 })
                assertNotNull(tx.fee, "a transaction that the wallet sends has a fee")
                assertTrue(tx.isOutgoing == true)
            }
            else -> throw AssertionError("A transfer is incoming or outgoing: $transfer")
        }
    }

    /**
     * Check a transaction that the wallet has, like TxWalletUtils.test_tx_wallet of monero-python: it has a hash, it is in a block or in
     * the pool, it is incoming, outgoing or both with the transfers that say so, and a transaction that the wallet sent has a fee.
     */
    fun validateTx(tx: MoneroTxWallet) {
        assertTrue(tx.hash!!.matches(Regex("[0-9a-f]{64}")), "hash of the transaction: ${tx.hash}")
        assertNotNull(tx.isConfirmed)
        assertNotNull(tx.inTxPool)
        assertNotNull(tx.isLocked)
        if (tx.isConfirmed == true) {
            assertEquals(false, tx.inTxPool)
            assertTrue(tx.block!!.height!! > 0, "a transaction that is confirmed is in a block")
            assertEquals(tx.block!!.height, tx.height)
            assertTrue(tx.numConfirmations!! > 0)
        } else {
            assertNull(tx.height, "a transaction that is not confirmed is at no height")
        }
        if (tx.inTxPool == true) assertEquals(false, tx.isConfirmed)

        val incoming = tx.incomingTransfers ?: emptyList()
        if (tx.isIncoming == true) assertTrue(incoming.isNotEmpty(), "an incoming transaction has incoming transfers")
        else assertTrue(incoming.isEmpty(), "a transaction that is not incoming has no incoming transfers")
        if (tx.isOutgoing == true) {
            assertNotNull(tx.outgoingTransfer, "an outgoing transaction has an outgoing transfer")
            assertNotNull(tx.fee)
        } else {
            assertNull(tx.outgoingTransfer)
        }
        assertTrue(tx.isIncoming == true || tx.isOutgoing == true, "a transaction of the wallet is incoming or outgoing")
        incoming.forEach { validateTransfer(it) }
        tx.outgoingTransfer?.let { validateTransfer(it) }
        incoming.forEach { assertSame(tx, it.tx) }
        tx.outgoingTransfer?.let { assertSame(tx, it.tx) }
    }

    /**
     * Check the result of checking a transaction with its key or with a proof, like TxWalletUtils.test_check_tx of monero-python: a good
     * one says how much was received and where the transaction is. One of a key that is not the one of the transaction says where the
     * transaction is and that the address received nothing, and one of a signature that does not verify says nothing of that.
     */
    fun validateCheckTx(check: MoneroCheckTx) {
        assertNotNull(check.isGood)
        if (check.isGood == true) {
            assertNotNull(check.numConfirmations)
            assertTrue(check.numConfirmations!! >= 0)
            assertNotNull(check.inTxPool)
            assertNotNull(check.receivedAmount)
            assertTrue(check.receivedAmount!!.signum() >= 0)
            if (check.inTxPool == true) assertEquals(0L, check.numConfirmations)
            else assertTrue(check.numConfirmations!! > 0, "a transaction that is not in the pool has confirmations")
        } else {
            // a check of a tx key that is not good still says where the transaction is and how much the
            // address received, which is nothing: only a check of a signature that does not verify says
            // nothing of that
            if (check.inTxPool != null) {
                assertTrue(check.receivedAmount!!.signum() >= 0)
                if (check.inTxPool == true) assertEquals(0L, check.numConfirmations)
            }
        }
    }

    /**
     * Check the result of checking a proof of a reserve, like TxWalletUtils.test_check_reserve of monero-python: a good one says the
     * amount and the part of it that is spent in transactions that are not confirmed, and one that is not good says nothing of that.
     */
    fun validateCheckReserve(check: MoneroCheckReserve) {
        assertNotNull(check.isGood)
        if (check.isGood == true) {
            assertNotNull(check.totalAmount)
            assertTrue(check.totalAmount!!.signum() >= 0)
            assertNotNull(check.unconfirmedSpentAmount)
            assertTrue(check.unconfirmedSpentAmount!!.signum() >= 0)
        } else {
            assertNull(check.totalAmount)
            assertNull(check.unconfirmedSpentAmount)
        }
    }
}
