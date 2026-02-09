package sspoirs.lr2

import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime

class UdpServer(private val port: Int) {
    private val socket = DatagramSocket(port)
    private val reliableUdp = ReliableUdp(socket)

    fun start() {
        println("[UDP SERVER v2.8] Listening on port $port...")
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
            var sent = 0L
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            val windowSize = 100 // Окно из 100 пакетов для скорости

            while (sent < remaining) {
                val windowStartSeq = reliableUdp.getSeqNum()
                val windowStartPos = raf.filePointer
                var lastSeqInWindow = 0
                
                // Шлем пачку без остановок
                for (i in 0 until windowSize) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    lastSeqInWindow = reliableUdp.getSeqNum()
                    reliableUdp.send(0, buffer.copyOfRange(0, read), address, port, requireAck = false)
                    sent += read
                    if (sent >= remaining) break
                }

                // Ждем ACK для всей пачки (Кумулятивно)
                // Если пришел ACK >= windowStartSeq, значит мы продвинулись вперед
                if (!reliableUdp.waitForAck(windowStartSeq, 2000)) {
                    println("[UDP] Packet loss, retrying window from seq $windowStartSeq")
                    raf.seek(windowStartPos)
                    sent = windowStartPos - offset
                    // Если даже после ретрая глухо - клиент отвалился
                    if (!reliableUdp.waitForAck(-1, 1000)) {
                        println("[UDP] Client timed out. Ready for new commands.")
                        return 
                    }
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        val speed = (remaining / 1024.0) / (duration / 1000.0)
        println("[UDP] Download complete for $name. Speed: ${String.format("%.2f", speed)} KB/s")
    }

    private fun handleUpload(name: String?, size: Long, offset: Long, addr: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        val toReceive = size - offset
        var totalReceived = 0L
        val start = System.currentTimeMillis()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (totalReceived < toReceive) {
                val p = reliableUdp.receive(5000) ?: break 
                if (p.type == 0.toByte()) {
                    // ACK раз в 50 пакетов для скорости
                    if (p.seq % 50 == 0 || totalReceived + p.payload.size >= toReceive) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                    }
                    raf.write(p.payload)
                    totalReceived += p.payload.size
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        val speed = (totalReceived / 1024.0) / (duration / 1000.0)
        reliableUdp.send(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload finished for $name. Speed: ${String.format("%.2f", speed)} KB/s")
    }
}
