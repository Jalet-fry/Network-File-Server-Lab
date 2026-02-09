package sspoirs.lr2

import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime

class UdpServer(private val port: Int) {
    private val socket = DatagramSocket(port).apply {
        receiveBufferSize = 1024 * 1024 
        sendBufferSize = 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)

    fun start() {
        println("[UDP SERVER v3.2] Listening on port $port...")
        while (true) {
            try {
                // Вытягиваем команды из ReliableUdp (включая очередь)
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
            Command.ECHO -> reliableUdp.send(2, args.joinToString(" ").toByteArray(), address, port)
            Command.TIME -> reliableUdp.send(2, LocalDateTime.now().toString().toByteArray(), address, port)
            Command.LIST -> handleList(address, port)
            Command.DOWNLOAD -> handleDownload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, address, port)
            Command.UPLOAD -> handleUpload(args.getOrNull(0), args.getOrNull(1)?.toLongOrNull() ?: 0, args.getOrNull(2)?.toLongOrNull() ?: 0, address, port)
            else -> reliableUdp.send(2, "Unknown command".toByteArray(), address, port)
        }
    }

    private fun handleList(address: InetAddress, port: Int) {
        val dir = File(Constants.SERVER_STORAGE)
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
        reliableUdp.send(2, "FILES $files".toByteArray(), address, port)
    }

    private fun handleDownload(name: String?, offset: Long, address: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        if (!file.exists()) {
            reliableUdp.send(2, "ERROR: Not found".toByteArray(), address, port)
            return
        }

        val totalSize = file.length()
        val remaining = totalSize - offset
        reliableUdp.send(2, "OK $remaining".toByteArray(), address, port)
        if (remaining <= 0) return

        val start = System.currentTimeMillis()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var sentInSession = 0L
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            val windowSize = 80

            while (sentInSession < remaining) {
                val windowStartPos = raf.filePointer
                val lastSeqBeforeWindow = reliableUdp.getSeqNum()
                
                // 1. Отправляем окно (пачку)
                var bytesInThisWindow = 0L
                for (i in 0 until windowSize) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    reliableUdp.send(0, buffer.copyOfRange(0, read), address, port, requireAck = false)
                    bytesInThisWindow += read
                    if (windowStartPos + bytesInThisWindow >= totalSize) break
                }

                // 2. Ждем ACK хотя бы за один пакет из этой пачки
                // Если за 1.5 сек нет ACK - перепосылаем окно
                if (!reliableUdp.waitForAck(lastSeqBeforeWindow, 1500)) {
                    println("[UDP] Packet loss at seq $lastSeqBeforeWindow, rewinding to $windowStartPos")
                    raf.seek(windowStartPos)
                    // Мы не увеличиваем sentInSession, поэтому цикл просто повторит попытку
                } else {
                    sentInSession += bytesInThisWindow
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        println("[UDP] Download finished. Speed: ${String.format("%.2f", (remaining / 1024.0) / (duration / 1000.0))} KB/s")
    }

    private fun handleUpload(name: String?, size: Long, offset: Long, addr: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        val toReceive = size - offset
        if (toReceive <= 0) {
            reliableUdp.send(2, "SUCCESS".toByteArray(), addr, port)
            return
        }

        var totalReceived = 0L
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (totalReceived < toReceive) {
                val p = reliableUdp.receive(5000) ?: break 
                if (p.type == 0.toByte()) {
                    if (p.seq % 50 == 0 || totalReceived + p.payload.size >= toReceive) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                    }
                    raf.write(p.payload)
                    totalReceived += p.payload.size
                }
            }
        }
        reliableUdp.send(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload finished for $name.")
    }
}
