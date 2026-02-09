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
        println("[UDP SERVER v3.0] Listening on port $port...")
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
            var sentBytes = 0L
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            val windowSize = 100 

            while (sentBytes < remaining) {
                val windowStartPos = raf.filePointer
                val windowStartSeq = reliableUdp.getSeqNum()
                
                // 1. Отправляем окно данных
                var currentWindowBytes = 0L
                for (i in 0 until windowSize) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    //forcedSeq сохраняет порядковый номер при ретраях!
                    val forcedSeq = windowStartSeq + i
                    reliableUdp.send(0, buffer.copyOfRange(0, read), address, port, requireAck = false, forcedSeq = forcedSeq)
                    currentWindowBytes += read
                    if (windowStartPos + currentWindowBytes >= totalSize) break
                }

                // 2. Ждем подтверждение с ретраями
                if (!reliableUdp.waitForAck(windowStartSeq + windowSize - 1, 1500)) {
                    println("[UDP] Window loss at seq $windowStartSeq, retrying same data...")
                    raf.seek(windowStartPos) // Перемотка для повтора
                    continue // Повторяем ТО ЖЕ САМОЕ окно без изменения номеров seq
                }
                sentBytes += currentWindowBytes
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        println("[UDP] Download complete for $name. Speed: ${String.format("%.2f", (remaining / 1024.0) / (duration / 1000.0))} KB/s")
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
                    if (p.seq % 50 == 0 || totalReceived + p.payload.size >= toReceive) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                    }
                    raf.write(p.payload)
                    totalReceived += p.payload.size
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        reliableUdp.send(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload finished for $name. Speed: ${String.format("%.2f", (totalReceived / 1024.0) / (duration / 1000.0))} KB/s")
    }
}
