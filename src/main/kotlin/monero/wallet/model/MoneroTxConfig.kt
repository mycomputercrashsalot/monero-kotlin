package monero.wallet.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.math.BigInteger

/**
 * Configures a transaction to send, to sweep or to make a payment URI for.
 */
class MoneroTxConfig {
    var destinations: List<MoneroDestination>? = null
    // indices of the destinations that pay the fee, which is split among them
    var subtractFeeFrom: List<Int>? = null
    var paymentId: String? = null
    var priority: MoneroTxPriority? = null
    // monero-cpp ignores it, and the network decides it
    var ringSize: Int? = null
    // the priority of the transaction decides the fee: the c abi reads this one, and the wallet of a light server
    // takes the one of the priority whatever it is
    var fee: BigInteger? = null
    var accountIndex: Int? = null
    var subaddressIndices: List<Int>? = null
    var canSplit: Boolean? = null
    // the transaction is not relayed when it is not set
    var relay: Boolean? = null
    var note: String? = null
    var recipientName: String? = null
    var belowAmount: BigInteger? = null
    var sweepEachSubaddress: Boolean? = null
    var keyImage: String? = null

    constructor()

    constructor(other: MoneroTxConfig) {
        this.destinations = other.destinations?.map { it.copy() }
        this.subtractFeeFrom = other.subtractFeeFrom?.let { ArrayList(it) }
        this.paymentId = other.paymentId
        this.priority = other.priority
        this.ringSize = other.ringSize
        this.fee = other.fee
        this.accountIndex = other.accountIndex
        this.subaddressIndices = other.subaddressIndices?.let { ArrayList(it) }
        this.canSplit = other.canSplit
        this.relay = other.relay
        this.note = other.note
        this.recipientName = other.recipientName
        this.belowAmount = other.belowAmount
        this.sweepEachSubaddress = other.sweepEachSubaddress
        this.keyImage = other.keyImage
    }

    fun copy(): MoneroTxConfig {
        return MoneroTxConfig(this)
    }

    /**
     * The address to send to, which is the address of the only destination. Setting it sets the address of that
     * destination, which is added if there is none.
     */
    @get:JsonIgnore
    var address: String?
        get() = destinations?.singleOrNull()?.address
        set(value) {
            onlyDestination("address").address = value
        }

    /**
     * The amount to send (atomic units), which is the amount of the only destination. Setting it sets the amount of
     * that destination, which is added if there is none.
     */
    @get:JsonIgnore
    var amount: BigInteger?
        get() = destinations?.singleOrNull()?.amount
        set(value) {
            onlyDestination("amount").amount = value
        }

    /**
     * Add a destination to send to.
     *
     * @param address is the address of the destination
     * @param amount is the amount to send to the destination (atomic units)
     * @return this configuration for chaining
     */
    fun addDestination(address: String?, amount: BigInteger? = null): MoneroTxConfig {
        destinations = (destinations ?: emptyList()) + MoneroDestination(address, amount)
        return this
    }

    private fun onlyDestination(name: String): MoneroDestination {
        val current = destinations
        if (current != null && current.size > 1) throw IllegalStateException("The $name can't be set because the config has more than one destination")
        if (current == null || current.isEmpty()) destinations = listOf(MoneroDestination())
        return destinations!![0]
    }
}
