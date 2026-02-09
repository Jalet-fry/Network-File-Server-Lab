package sspoirs.common

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.*

class ReliableUdp(private val socket: DatagramSocket) {
    private var seqNum = 0
    private val buffer = ByteArray(Constants.UDP_PACKET_SIZE + 100)
    private val incomingQueue: Queue<UdpPacket> = LinkedList()

    fun getSeqNum(): Int = seqNum
    
    fun advanceSeq(delta: Int) { seqNum += delta }

    fun clearQueue() {
        incomingQueue.clear()
        val oldTimeout = socket.soTimeout
        try {
            socket.soTimeout = 1
            val dummy = DatagramPacket(buffer, buffer.size)
            while (true) { socket.receive(dummy) }
        } catch (e: Exception) {}
        finally { socket.soTimeout = oldTimeout }
    }

    fun sendFast(type: Byte, payload: ByteArray, address: InetAddress, port: Int, forcedSeq: Int = -1) {
        val s = if (forcedSeq != -1) forcedSeq else seqNum++
        val data = buildPacket(type, s, payload)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    fun sendReliable(type: Byte, payload: ByteArray, address: InetAddress, port: Int) {
        val s = seqNum++
        val data = buildPacket(type, s, payload)
        val packet = DatagramPacket(data, data.size, address, port)
        var attempts = 0
        while (attempts < Constants.MAX_RETRIES) {
            socket.send(packet)
            if (waitForAck(s, 1000)) return
            attempts++
        }
        throw IOException("UDP Reliable Send Failed for seq $s")
    }

    fun receive(timeout: Int = 0): UdpPacket? {
        while (true) {
            if (incomingQueue.isNotEmpty()) return incomingQueue.poll()
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.soTimeout = timeout
                socket.receive(packet)
                val p = parsePacket(packet)
                if (p.type == 1.toByte()) continue 
                return p
            } catch (e: Exception) { return null }
        }
    }

    fun sendAck(seq: Int, address: InetAddress, port: Int) {
        val data = buildPacket(1, seq, byteArrayOf())
        try { socket.send(DatagramPacket(data, data.size, address, port)) } catch (e: Exception) {}
    }

    fun waitForAck(expectedSeq: Int, timeout: Int): Boolean {
        val ackBuf = ByteArray(Constants.UDP_PACKET_SIZE + 100)
        val ackPacket = DatagramPacket(ackBuf, ackBuf.size)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            try {
                socket.soTimeout = 100
                socket.receive(ackPacket)
                val p = parsePacket(ackPacket)
                if (p.type == 1.toByte()) {
                    if (expectedSeq == -1 || p.seq >= expectedSeq) return true
                } else {
                    sendAck(p.seq, p.address, p.port)
                    incomingQueue.add(p)
                }
            } catch (e: SocketTimeoutException) { continue }
            catch (e: Exception) { break }
        }
        return false
    }

    private fun parsePacket(p: DatagramPacket): UdpPacket {
        val type = p.data[0]
        val seq = readInt(p.data, 1)
        val payload = p.data.copyOfRange(5, p.length)
        return UdpPacket(type, seq, payload, p.address, p.port)
    }

    private fun buildPacket(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val data = ByteArray(5 + payload.size)
        data[0] = type
        writeInt(data, 1, seq)
        payload.copyInto(data, 5)
        return data
    }

    private fun writeInt(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v shr 24).toByte(); buf[offset + 1] = (v shr 16).toByte()
        buf[offset + 2] = (v shr 8).toByte(); buf[offset + 3] = v.toByte()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or ((buf[offset + 1].toInt() and 0xFF) shl 16) or
               ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
    }

    data class UdpPacket(val type: Byte, val seq: Int, val payload: ByteArray, val address: InetAddress, val port: Int)
}
