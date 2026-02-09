package sspoirs.common

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.*

class ReliableUdp(private val socket: DatagramSocket) {
    private var seqNum = 0
    private val buffer = ByteArray(Constants.UDP_PACKET_SIZE + 10)
    private val commandQueue: Queue<UdpPacket> = LinkedList()

    fun getSeqNum(): Int = seqNum
    
    fun advanceSeq(delta: Int) {
        seqNum += delta
    }

    fun send(type: Byte, payload: ByteArray, address: InetAddress, port: Int, requireAck: Boolean = true, forcedSeq: Int = -1) {
        val currentSeq = if (forcedSeq != -1) forcedSeq else seqNum
        val data = ByteArray(5 + payload.size)
        data[0] = type
        writeInt(data, 1, currentSeq)
        payload.copyInto(data, 5)

        val packet = DatagramPacket(data, data.size, address, port)
        
        if (requireAck) {
            retrySend(packet, currentSeq)
        } else {
            socket.send(packet)
        }
        if (forcedSeq == -1) seqNum++
    }

    fun receive(timeout: Int = 0): UdpPacket? {
        if (commandQueue.isNotEmpty()) return commandQueue.poll()
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.soTimeout = timeout
            socket.receive(packet)
            parsePacket(packet)
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
        val timeout = if (packet.data[0] == 0.toByte()) 200 else 1000
        while (attempts < Constants.MAX_RETRIES) {
            try {
                socket.send(packet)
                if (waitForAck(seq, timeout)) return
            } catch (e: Exception) {}
            attempts++
        }
        if (packet.data[0] == 2.toByte()) throw IOException("UDP Command Timeout")
    }

    fun waitForAck(expectedSeq: Int, timeout: Int): Boolean {
        val ackBuf = ByteArray(Constants.UDP_PACKET_SIZE + 10)
        val ackPacket = DatagramPacket(ackBuf, ackBuf.size)
        val start = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - start < timeout) {
                socket.soTimeout = 100
                try {
                    socket.receive(ackPacket)
                    val p = parsePacket(ackPacket)
                    if (p.type == 1.toByte() && (expectedSeq == -1 || p.seq >= expectedSeq)) {
                        return true
                    } else if (p.type == 2.toByte()) {
                        commandQueue.add(p)
                    }
                } catch (e: SocketTimeoutException) { continue }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun parsePacket(p: DatagramPacket): UdpPacket {
        val type = p.data[0]
        val seq = readInt(p.data, 1)
        val payload = p.data.copyOfRange(5, p.length)
        return UdpPacket(type, seq, payload, p.address, p.port)
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
