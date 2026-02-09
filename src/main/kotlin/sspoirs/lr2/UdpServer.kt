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
        receiveBufferSize = 4 * 1024 * 1024 
        sendBufferSize = 4 * 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)

    fun start() {
        println("[UDP SERVER v3.4] Listening on port $port...")
        while (true) {
            try {
                // Ждем команду (тип 2)
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
            val chunkSize = 100 // Шлем по 100 пакетов за раз
            var sentBytes = 0L

            while (sentBytes < remaining) {
                val chunkStartPos = fc.position()
                val chunkStartSeq = reliableUdp.getSeqNum()
                
                // 1. Отправляем пачку пакетов
                for (i in 0 until chunkSize) {
                    buffer.clear()
                    val read = fc.read(buffer)
                    if (read <= 0) break
                    buffer.flip()
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    reliableUdp.sendFast(0, data, address, port)
                    sentBytes += data.size
                    if (sentBytes >= remaining) break
                }

                // 2. Ждем подтверждение за ПОСЛЕДНИЙ пакет пачки
                val lastSeq = reliableUdp.getSeqNum() - 1
                if (!reliableUdp.waitForAck(lastSeq, 1500)) {
                    println("[UDP] Chunk loss, rewinding to $chunkStartPos")
                    fc.position(chunkStartPos)
                    sentBytes -= (fc.position() - chunkStartPos) // На самом деле просто сброс
                    sentBytes = fc.position() - offset
                    // Ограничение попыток для защиты сервера
                    if (!reliableUdp.waitForAck(-1, 500)) { 
                        println("[UDP] Client vanish. Aborting.")
                        return 
                    }
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        println("[UDP] Download complete. Speed: ${String.format("%.2f", (remaining / 1024.0) / (duration / 1000.0))} KB/s")
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
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (totalReceived < toReceive) {
                val p = reliableUdp.receive(5000) ?: break 
                if (p.type == 0.toByte()) {
                    // Кумулятивный ACK раз в 50 пакетов
                    if (p.seq % 50 == 0 || totalReceived + p.payload.size >= toReceive) {
                        reliableUdp.sendAck(p.seq, p.address, p.port)
                    }
                    raf.write(p.payload)
                    totalReceived += p.payload.size
                }
            }
        }
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        reliableUdp.sendReliable(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload finished for $name. Speed: ${String.format("%.2f", (totalReceived / 1024.0) / (duration / 1000.0))} KB/s")
    }
}
