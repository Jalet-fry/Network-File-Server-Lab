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
        println("UDP Server listening on port $port...")
        while (true) {
            try {
                handleNextPacket()
            } catch (e: Exception) { println("Error: ${e.message}") }
        }
    }

    private fun handleNextPacket() {
        val packet = reliableUdp.receive() ?: return
        if (packet.type == 2.toByte()) { // Command type
            reliableUdp.sendAck(packet.seq, packet.address, packet.port)
            processCommand(String(packet.payload), packet.address, packet.port)
        }
    }

    private fun processCommand(line: String, address: InetAddress, port: Int) {
        val parts = line.split(" ")
        val cmd = Command.fromString(parts[0])
        val args = parts.drop(1)

        when (cmd) {
            Command.ECHO -> sendResponse(args.joinToString(" "), address, port)
            Command.TIME -> sendResponse(LocalDateTime.now().toString(), address, port)
            Command.LIST -> handleList(address, port)
            Command.DOWNLOAD -> handleDownload(args.getOrNull(0), args.getOrNull(1)?.toLong() ?: 0, address, port)
            Command.UPLOAD -> handleUpload(args.getOrNull(0), args.getOrNull(1)?.toLong() ?: 0, args.getOrNull(2)?.toLong() ?: 0, address, port)
            else -> sendResponse("Unknown command", address, port)
        }
    }

    private fun sendResponse(text: String, address: InetAddress, port: Int) {
        reliableUdp.send(2, text.toByteArray(), address, port)
    }

    private fun handleList(address: InetAddress, port: Int) {
        val files = File(Constants.SERVER_STORAGE).listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
        sendResponse("FILES $files", address, port)
    }

    private fun handleDownload(name: String?, offset: Long, address: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        if (!file.exists()) return sendResponse("ERROR: Not found", address, port)

        val remaining = file.length() - offset
        sendResponse("OK $remaining", address, port)
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            sendFileData(raf, remaining, address, port)
        }
    }

    private fun sendFileData(raf: RandomAccessFile, totalSize: Long, addr: InetAddress, port: Int) {
        var sent = 0L
        val windowSize = 5 
        while (sent < totalSize) {
            val chunk = ByteArray(Constants.UDP_PACKET_SIZE)
            val read = raf.read(chunk)
            if (read <= 0) break
            reliableUdp.send(0, chunk.copyOfRange(0, read), addr, port)
            sent += read
        }
    }

    private fun handleUpload(name: String?, size: Long, offset: Long, addr: InetAddress, port: Int) {
        val file = File(Constants.SERVER_STORAGE, name ?: return)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            receiveFileData(raf, size)
        }
        sendResponse("SUCCESS", addr, port)
    }

    private fun receiveFileData(raf: RandomAccessFile, totalSize: Long) {
        var received = 0L
        while (received < totalSize) {
            val p = reliableUdp.receive() ?: continue
            if (p.type == 0.toByte()) {
                reliableUdp.sendAck(p.seq, p.address, p.port)
                raf.write(p.payload)
                received += p.payload.size
            }
        }
    }
}
