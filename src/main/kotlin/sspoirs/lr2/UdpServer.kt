package sspoirs.lr2

import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

class UdpServer(private val port: Int) {
    private val socket = DatagramSocket(port).apply {
        receiveBufferSize = 8 * 1024 * 1024 
        sendBufferSize = 8 * 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)

    fun start() {
        println("[UDP SERVER v3.7] Listening on port $port...")
        while (true) {
            try {
                val packet = reliableUdp.receive(0) ?: continue 
                if (packet.type == 2.toByte()) {
                    reliableUdp.sendAck(packet.seq, packet.address, packet.port)
                    processCommand(String(packet.payload), packet.address, packet.port)
                }
            } catch (e: Exception) {
                println("[UDP ERROR] ${e.message}")
            }
        }
    }

    private fun processCommand(line: String, address: InetAddress, port: Int) {
        val parts = line.split(Regex("\\s+"))
        val cmd = Command.fromString(parts[0])
        val args = parts.drop(1)

        println("[UDP] Command from $address: $line")

        when (cmd) {
            Command.ECHO -> reliableUdp.sendReliable(2, args.joinToString(" ").toByteArray(), address, port)
            Command.TIME -> reliableUdp.sendReliable(2, LocalDateTime.now().toString().toByteArray(), address, port)
            Command.LIST -> handleList(address, port)
            Command.DOWNLOAD -> handleDownload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, address, port)
            Command.UPLOAD -> handleUpload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, args.getOrNull(2)?.toLongOrNull() ?: 0, address, port)
            else -> reliableUdp.sendReliable(2, "Unknown command".toByteArray(), address, port)
        }
    }

    private fun handleList(address: InetAddress, port: Int) {
        val dir = File(Constants.SERVER_STORAGE)
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
        reliableUdp.sendReliable(2, "FILES $files".toByteArray(), address, port)
    }

    private fun handleDownload(name: String?, offset: Long, address: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        if (!file.exists()) {
            reliableUdp.sendReliable(2, "ERROR: Not found".toByteArray(), address, port)
            return
        }

        val totalSize = file.length()
        val remaining = totalSize - offset
        reliableUdp.sendReliable(2, "OK $remaining".toByteArray(), address, port)
        if (remaining <= 0) return

        val start = System.currentTimeMillis()
        RandomAccessFile(file, "r").use { raf ->
            val fc = raf.channel
            fc.position(offset)
            val buffer = ByteBuffer.allocate(Constants.UDP_PACKET_SIZE)
            val windowSize = 256 // МАКСИМАЛЬНЫЙ РАЗГОН
            var sentBytes = 0L

            while (sentBytes < remaining) {
                val windowStartPos = fc.position()
                val windowStartSeq = reliableUdp.getSeqNum()
                
                var currentWindowBytes = 0L
                for (i in 0 until windowSize) {
                    buffer.clear()
                    val read = fc.read(buffer)
                    if (read <= 0) break
                    buffer.flip()
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    reliableUdp.sendFast(0, data, address, port, forcedSeq = windowStartSeq + i)
                    currentWindowBytes += read
                    if (sentBytes + currentWindowBytes >= remaining) break
                }

                // Ждем ACK окна. Если это финал - мы более терпимы к потере
                if (!reliableUdp.waitForAck(windowStartSeq + (currentWindowBytes/Constants.UDP_PACKET_SIZE).toInt() - 1, 1000)) {
                    if (sentBytes + currentWindowBytes >= remaining) break 
                    println("[UDP] Window loss, retrying...")
                    fc.position(windowStartPos)
                    continue
                }
                sentBytes += currentWindowBytes
                val pkts = (currentWindowBytes + Constants.UDP_PACKET_SIZE - 1) / Constants.UDP_PACKET_SIZE
                reliableUdp.advanceSeq(pkts.toInt())
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        println("[UDP] Download complete. Speed: ${String.format("%.2f", (remaining / 1024.0) / (duration / 1000.0))} KB/s")
        Thread.sleep(200)
        reliableUdp.clearQueue()
    }

    private fun handleUpload(name: String?, size: Long, offset: Long, addr: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        val toReceive = size - offset
        if (toReceive <= 0) {
            reliableUdp.sendReliable(2, "SUCCESS".toByteArray(), addr, port)
            return
        }

        var totalReceived = 0L
        val start = System.currentTimeMillis()
        var lastPSeq = -1

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (totalReceived < toReceive) {
                val p = reliableUdp.receive(5000) ?: break 
                if (p.type == 0.toByte()) {
                    if (p.seq <= lastPSeq && lastPSeq != -1) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                        continue
                    }
                    if (p.seq % 64 == 0 || totalReceived + p.payload.size >= toReceive) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                    }
                    raf.write(p.payload)
                    totalReceived += p.payload.size
                    lastPSeq = p.seq
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        reliableUdp.sendReliable(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload complete. Speed: ${String.format("%.2f", (totalReceived / 1024.0) / (duration / 1000.0))} KB/s")
    }
}
