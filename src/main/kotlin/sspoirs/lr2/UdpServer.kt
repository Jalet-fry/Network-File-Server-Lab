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
        println("[UDP SERVER v2.2] Listening on port $port...")
        while (true) {
            try {
                val packet = reliableUdp.receive(0) ?: continue // Бесконечное ожидание команд
                if (packet.type == 2.toByte()) { // Тип: Команда
                    reliableUdp.sendAck(packet.seq, packet.address, packet.port)
                    processCommand(String(packet.payload), packet.address, packet.port)
                }
            } catch (e: Exception) {
                println("[UDP ERROR] ${e.message}")
            }
        }
    }

    private fun processCommand(line: String, address: InetAddress, port: Int) {
        val parts = line.split(" ")
        val cmd = Command.fromString(parts[0])
        val args = parts.drop(1)

        println("[UDP] Command from $address: $line")

        when (cmd) {
            Command.ECHO -> reliableUdp.send(2, args.joinToString(" ").toByteArray(), address, port)
            Command.TIME -> reliableUdp.send(2, LocalDateTime.now().toString().toByteArray(), address, port)
            Command.LIST -> handleList(address, port)
            Command.DOWNLOAD -> handleDownload(args.getOrNull(0), args.getOrNull(1)?.toLong() ?: 0, address, port)
            Command.UPLOAD -> handleUpload(args.getOrNull(0), args.getOrNull(1)?.toLong() ?: 0, args.getOrNull(2)?.toLong() ?: 0, address, port)
            else -> reliableUdp.send(2, "Unknown command".toByteArray(), address, port)
        }
    }

    private fun handleList(address: InetAddress, port: Int) {
        val files = File(Constants.SERVER_STORAGE).listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
        reliableUdp.send(2, "FILES $files".toByteArray(), address, port)
    }

    private fun handleDownload(name: String?, offset: Long, address: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        if (!file.exists()) {
            reliableUdp.send(2, "ERROR: Not found".toByteArray(), address, port)
            return
        }

        val remaining = file.length() - offset
        reliableUdp.send(2, "OK $remaining".toByteArray(), address, port)
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var sent = 0L
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            while (sent < remaining) {
                val read = raf.read(buffer)
                if (read <= 0) break
                // Отправляем как данные (тип 0)
                reliableUdp.send(0, buffer.copyOfRange(0, read), address, port)
                sent += read
            }
        }
        println("[UDP] Download finished for $name")
    }

    private fun handleUpload(name: String?, size: Long, offset: Long, addr: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        var received = 0L
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (received < size) {
                val p = reliableUdp.receive(5000) ?: break // Ждем данные 5 сек
                if (p.type == 0.toByte()) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                    raf.write(p.payload)
                    received += p.payload.size
                }
            }
        }
        reliableUdp.send(2, "SUCCESS".toByteArray(), addr, port)
        println("[UDP] Upload complete for $name")
    }
}
