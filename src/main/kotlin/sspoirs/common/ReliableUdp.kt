package sspoirs.common

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class ReliableUdp(private val socket: DatagramSocket) {
    private var seqNum = 0
    private val buffer = ByteArray(Constants.UDP_PACKET_SIZE + 5)

    fun sendWithWindow(payloads: List<ByteArray>, address: InetAddress, port: Int) {
        val window = payloads.mapIndexed { index, data -> 
            preparePacket(seqNum + index, data, address, port) 
        }
        
        window.forEach { socket.send(it) }
        verifyWindow(seqNum, window.size)
        seqNum += payloads.size
    }

    private fun preparePacket(seq: Int, data: ByteArray, addr: InetAddress, port: Int): DatagramPacket {
        val buf = ByteArray(5 + data.size)
        buf[0] = 0 // Data type
        writeInt(buf, 1, seq)
        data.copyInto(buf, 5)
        return DatagramPacket(buf, buf.size, addr, port)
    }

    private fun verifyWindow(startSeq: Int, size: Int) {
        for (i in 0 until size) {
            if (!waitForAck(startSeq + i)) {
                // В реальном окне тут должна быть выборочная переотправка, 
                // для ЛР упростим: если нет ACK, бросаем ошибку
                throw IOException("UDP Window Timeout: Missing ACK for seq ${startSeq + i}")
            }
        }
    }

    fun send(type: Byte, payload: ByteArray, address: InetAddress, port: Int) {
        val data = ByteArray(5 + payload.size)
        data[0] = type
        writeInt(data, 1, seqNum)
        payload.copyInto(data, 5)

        val packet = DatagramPacket(data, data.size, address, port)
        retrySend(packet, seqNum)
        seqNum++
    }

    fun receive(): UdpPacket? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.soTimeout = 0 
            socket.receive(packet)
            val type = packet.data[0]
            val seq = readInt(packet.data, 1)
            val payload = packet.data.copyOfRange(5, packet.length)
            UdpPacket(type, seq, payload, packet.address, packet.port)
        } catch (e: Exception) { null }
    }

    fun sendAck(seq: Int, address: InetAddress, port: Int) {
        val data = ByteArray(5)
        data[0] = 1 
        writeInt(data, 1, seq)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    private fun retrySend(packet: DatagramPacket, seq: Int) {
        var attempts = 0
        while (attempts < Constants.MAX_RETRIES) {
            socket.send(packet)
            if (waitForAck(seq)) return
            attempts++
        }
        throw IOException("UDP failed: No ACK for seq $seq")
    }

    private fun waitForAck(expectedSeq: Int): Boolean {
        val ackBuf = ByteArray(5)
        val ackPacket = DatagramPacket(ackBuf, ackBuf.size)
        return try {
            socket.soTimeout = Constants.UDP_TIMEOUT.toInt()
            socket.receive(ackPacket)
            ackBuf[0] == 1.toByte() && readInt(ackBuf, 1) == expectedSeq
        } catch (e: SocketTimeoutException) { false }
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
