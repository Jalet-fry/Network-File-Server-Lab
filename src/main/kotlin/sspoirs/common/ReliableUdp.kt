package sspoirs.common

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class ReliableUdp(private val socket: DatagramSocket) {
    private var seqNum = 0
    private val buffer = ByteArray(Constants.UDP_PACKET_SIZE + 10)

    fun getSeqNum(): Int = seqNum

    fun send(type: Byte, payload: ByteArray, address: InetAddress, port: Int, requireAck: Boolean = true) {
        val data = ByteArray(5 + payload.size)
        data[0] = type
        writeInt(data, 1, seqNum)
        payload.copyInto(data, 5)

        val packet = DatagramPacket(data, data.size, address, port)
        
        if (requireAck) {
            retrySend(packet, seqNum)
        } else {
            socket.send(packet)
        }
        seqNum++
    }

    fun receive(timeout: Int = 0): UdpPacket? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.soTimeout = timeout
            socket.receive(packet)
            val type = packet.data[0]
            val seq = readInt(packet.data, 1)
            val payload = packet.data.copyOfRange(5, packet.length)
            UdpPacket(type, seq, payload, packet.address, packet.port)
        } catch (e: Exception) {
            null
        }
    }

    fun sendAck(seq: Int, address: InetAddress, port: Int) {
        val data = ByteArray(5)
        data[0] = 1 
        writeInt(data, 1, seq)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    private fun retrySend(packet: DatagramPacket, seq: Int) {
        var attempts = 0
        // Для данных используем очень короткий таймаут, чтобы не тормозить
        val timeout = if (packet.data[0] == 0.toByte()) 100 else 1000
        while (attempts < Constants.MAX_RETRIES) {
            try {
                socket.send(packet)
                if (waitForAck(seq, timeout)) return
            } catch (e: Exception) {}
            attempts++
        }
        // Не бросаем исключение для пакетов данных, чтобы не ронять клиент
        if (packet.data[0] == 2.toByte()) throw IOException("UDP Command Timeout")
    }

    fun waitForAck(expectedSeq: Int, timeout: Int): Boolean {
        val ackBuf = ByteArray(10)
        val ackPacket = DatagramPacket(ackBuf, ackBuf.size)
        val start = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - start < timeout) {
                socket.soTimeout = 50 // Очень быстрый опрос
                socket.receive(ackPacket)
                val receivedSeq = readInt(ackBuf, 1)
                if (ackBuf[0] == 1.toByte() && (expectedSeq == -1 || receivedSeq >= expectedSeq)) {
                    return true
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun writeInt(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v shr 24).toByte()
        buf[offset + 1] = (v shr 16).toByte()
        buf[offset + 2] = (v shr 8).toByte()
        buf[offset + 3] = v.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
               ((buf[offset + 1].toInt() and 0xFF) shl 16) or
               ((buf[offset + 2].toInt() and 0xFF) shl 8) or
               (buf[offset + 3].toInt() and 0xFF)
    }

    data class UdpPacket(val type: Byte, val seq: Int, val payload: ByteArray, val address: InetAddress, val port: Int)
}
