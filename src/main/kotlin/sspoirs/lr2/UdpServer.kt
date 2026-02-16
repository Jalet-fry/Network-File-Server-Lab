package sspoirs.lr2

import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

class UdpServer(private val port: Int) : CommandExecutor {
    private val socket = DatagramSocket(port).apply {
        receiveBufferSize = 4 * 1024 * 1024 
        sendBufferSize = 4 * 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)
    
    // Временные переменные для текущего пакета
    private var currentAddr: InetAddress? = null
    private var currentPort: Int = 0

    fun start() {
        println("--- UDP Server Starting at ${NetworkUtils.getTimestamp()} ---")
        println("[SERVER] Available local IP addresses:")
        NetworkUtils.getLocalIpAddresses().forEach { println("  - $it") }
        
        println("[UDP SERVER] Listening on port $port...")
        while (true) {
            try {
                val packet = reliableUdp.receive(0) ?: continue 
                if (packet.type == 2.toByte()) {
                    reliableUdp.sendAck(packet.seq, packet.address, packet.port)
                    
                    val line = String(packet.payload)
                    println("[${NetworkUtils.getTimestamp()}] Received UDP from ${packet.address}: $line")

                    currentAddr = packet.address
                    currentPort = packet.port
                    
                    // Используем общий CommandProcessor
                    CommandProcessor.processLine(line, this)
                }
            } catch (e: Exception) {
                println("[UDP ERROR] ${e.message}")
            }
        }
    }

    override fun execute(cmd: Command, args: List<String>): Boolean {
        val addr = currentAddr ?: return false
        val p = currentPort

        println("[SERVER] Executing sub-command: $cmd")

        when (cmd) {
            Command.ECHO -> reliableUdp.sendReliable(2, args.joinToString(" ").toByteArray(), addr, p)
            Command.TIME -> reliableUdp.sendReliable(2, LocalDateTime.now().toString().toByteArray(), addr, p)
            Command.LIST -> handleList(addr, p)
            Command.DOWNLOAD -> handleDownload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, addr, p)
            Command.UPLOAD -> handleUpload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, args.getOrNull(2)?.toLongOrNull() ?: 0, addr, p)
            Command.CLOSE -> return false
            else -> reliableUdp.sendReliable(2, "Unknown command '$cmd'".toByteArray(), addr, p)
        }
        return true
    }

    private fun handleList(address: InetAddress, port: Int) {
        val files = CommandProcessor.getServerFilesList()
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
            val windowSize = 128 
            var sentBytes = 0L

            while (sentBytes < remaining) {
                val windowStartPos = fc.position()
                val windowStartSeq = reliableUdp.getSeqNum()
                
                var currentWindowBytes = 0L
                for (i in 0 until windowSize) {
                    buffer.clear()
                    if (fc.read(buffer) <= 0) break
                    buffer.flip()
                    val data = ByteArray(buffer.remaining()); buffer.get(data)
                    reliableUdp.sendFast(0, data, address, port, forcedSeq = windowStartSeq + i)
                    currentWindowBytes += data.size
                    if (sentBytes + currentWindowBytes >= remaining) break
                }

                if (!reliableUdp.waitForAck(windowStartSeq + windowSize / 2, 1200)) {
                    if (sentBytes + currentWindowBytes >= remaining) break 
                    println("[UDP] Window lag at ${NetworkUtils.getTimestamp()}, retrying from seq $windowStartSeq")
                    fc.position(windowStartPos)
                    continue
                }
                sentBytes += currentWindowBytes
                val pkts = (currentWindowBytes + Constants.UDP_PACKET_SIZE - 1) / Constants.UDP_PACKET_SIZE
                reliableUdp.advanceSeq(pkts.toInt())
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        println("[${NetworkUtils.getTimestamp()}] UDP Download finished. Speed: ${String.format("%.2f", (remaining / 1024.0) / (duration / 1000.0))} KB/s")
        Thread.sleep(100)
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
        println("[${NetworkUtils.getTimestamp()}] UDP Upload finished for $name. Speed: ${String.format("%.2f", (totalReceived / 1024.0) / (duration / 1000.0))} KB/s")
    }
}
