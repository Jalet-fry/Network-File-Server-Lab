package sspoirs.lr5

import java.nio.ByteBuffer
import java.nio.ByteOrder

class IcmpPacket(
    val type: Byte,
    val code: Byte,
    val identifier: Short,
    val sequenceNumber: Short,
    val data: ByteArray = ByteArray(0)
) {
    companion object {
        const val TYPE_ECHO_REPLY: Byte = 0
        const val TYPE_DEST_UNREACHABLE: Byte = 3
        const val TYPE_ECHO_REQUEST: Byte = 8
        const val TYPE_TIME_EXCEEDED: Byte = 11

        fun createEchoRequest(id: Short, seq: Short, payload: ByteArray): IcmpPacket {
            return IcmpPacket(TYPE_ECHO_REQUEST, 0, id, seq, payload)
        }

        fun calculateChecksum(bytes: ByteArray): Short {
            var sum = 0
            var i = 0
            while (i < bytes.size - 1) {
                val word = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < bytes.size) {
                sum += (bytes[i].toInt() and 0xFF) shl 8
            }
            while ((sum shr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toShort()
        }

        fun parse(packetBytes: ByteArray, offset: Int = 0): IcmpPacket {
            val buffer = ByteBuffer.wrap(packetBytes, offset, packetBytes.size - offset).order(ByteOrder.BIG_ENDIAN)
            val type = buffer.get()
            val code = buffer.get()
            val checksum = buffer.getShort()
            val id = buffer.getShort()
            val seq = buffer.getShort()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return IcmpPacket(type, code, id, seq, data)
        }
    }

    fun toByteArray(): ByteArray {
        val size = 8 + data.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type)
        buffer.put(code)
        buffer.putShort(0) // Checksum placeholder
        buffer.putShort(identifier)
        buffer.putShort(sequenceNumber)
        buffer.put(data)
        
        val bytes = buffer.array()
        val checksum = calculateChecksum(bytes)
        
        // Put checksum back in Big Endian
        bytes[2] = (checksum.toInt() shr 8).toByte()
        bytes[3] = (checksum.toInt() and 0xFF).toByte()
        
        return bytes
    }
}
