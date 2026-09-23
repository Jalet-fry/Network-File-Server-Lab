package sspoirs.lr5

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IpHeader(
    val sourceIp: String,
    val destIp: String,
    val protocol: Byte = 1, // ICMP = 1
    val ttl: Byte = 64,
    val id: Short = 1234
) {
    fun toByteArray(payloadSize: Int): ByteArray {
        val totalLength = 20 + payloadSize
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        
        // Version (4) | IHL (5 words = 20 bytes)
        buffer.put(0x45.toByte())
        // TOS
        buffer.put(0.toByte())
        // Total Length
        buffer.putShort(totalLength.toShort())
        // ID
        buffer.putShort(id)
        // Flags (0) + Fragment Offset (0)
        buffer.putShort(0)
        // TTL
        buffer.put(ttl)
        // Protocol
        buffer.put(protocol)
        // Header Checksum (0 for now)
        buffer.putShort(0)
        
        // Source IP
        buffer.put(InetAddress.getByName(sourceIp).address)
        // Destination IP
        buffer.put(InetAddress.getByName(destIp).address)
        
        val headerBytes = buffer.array()
        val checksum = calculateChecksum(headerBytes)
        
        headerBytes[10] = (checksum.toInt() shr 8).toByte()
        headerBytes[11] = (checksum.toInt() and 0xFF).toByte()
        
        return headerBytes
    }

    private fun calculateChecksum(bytes: ByteArray): Short {
        var sum = 0
        var i = 0
        while (i < bytes.size - 1) {
            val word = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }
}
