package sspoirs.lr6

/**
 * Information about a discovered peer in the network.
 */
data class PeerInfo(
    val ip: String,
    var lastSeen: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerInfo) return false
        return ip == other.ip
    }

    override fun hashCode(): Int = ip.hashCode()
}
