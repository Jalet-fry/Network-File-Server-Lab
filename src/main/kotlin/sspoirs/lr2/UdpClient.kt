package sspoirs.lr2

import org.jline.reader.LineReaderBuilder
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress

class UdpClient(private val host: String, private val port: Int) {
    private val socket = DatagramSocket()
    private val reliableUdp = ReliableUdp(socket)
    private val serverAddress = InetAddress.getByName(host)
    private var serverFiles = mutableListOf<String>()

    fun start() {
        println("UDP Client started. Connected to $host:$port (Reliable Mode)")
        updateServerFiles()

        val terminal = TerminalBuilder.builder().system(true).build()
        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(AggregateCompleter(
                StringsCompleter(Command.allCommands() + listOf("ls", "exit", "quit")),
                StringsCompleter { serverFiles }
            ))
            .build()

        while (true) {
            val line = try { lineReader.readLine("UDP > ")?.trim() } catch (e: Exception) { null } ?: break
            if (line.isEmpty()) continue
            if (handleCommand(line)) break
        }
    }

    private fun handleCommand(line: String): Boolean {
        val parts = line.split(" ")
        val cmd = Command.fromString(parts[0])
        return when (cmd) {
            Command.LIST -> { requestFileList(); false }
            Command.DOWNLOAD -> { parts.getOrNull(1)?.let { initiateDownload(it) }; false }
            Command.UPLOAD -> { parts.getOrNull(1)?.let { initiateUpload(it) }; false }
            Command.CLOSE -> true
            else -> { sendBasicCommand(line); false }
        }
    }

    private fun updateServerFiles() {
        reliableUdp.send(2, "LS".toByteArray(), serverAddress, port)
        val resp = reliableUdp.receive() ?: return
        if (resp.type == 2.toByte()) {
            val content = String(resp.payload)
            if (content.startsWith("FILES")) {
                serverFiles.clear()
                content.substringAfter("FILES ").split(";").forEach {
                    if (it.contains("(")) serverFiles.add(it.substringBefore("("))
                }
            }
        }
    }

    private fun requestFileList() {
        updateServerFiles()
        println("\n--- Server Files ---")
        if (serverFiles.isEmpty()) println("[Empty]") else serverFiles.forEach { println(" - $it") }
    }

    private fun sendBasicCommand(line: String) {
        reliableUdp.send(2, line.toByteArray(), serverAddress, port)
        reliableUdp.receive()?.let { println("Server: ${String(it.payload)}") }
    }

    private fun initiateDownload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, "udp_$name")
        val offset = if (file.exists()) file.length() else 0L
        reliableUdp.send(2, "DOWNLOAD $name $offset".toByteArray(), serverAddress, port)
        val resp = reliableUdp.receive() ?: return
        val status = String(resp.payload)
        if (status.startsWith("OK")) {
            val size = status.split(" ")[1].toLong()
            receiveFile(file, size)
        } else println("Error: $status")
    }

    private fun receiveFile(file: File, size: Long) {
        var received = 0L
        val start = System.currentTimeMillis()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(file.length())
            while (received < size) {
                val p = reliableUdp.receive() ?: break
                if (p.type == 0.toByte()) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                    raf.write(p.payload)
                    received += p.payload.size
                }
            }
        }
        val duration = System.currentTimeMillis() - start
        println("\nUDP Download complete. Speed: ${calculateSpeed(received, duration)} KB/s")
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        reliableUdp.send(2, "UPLOAD $name ${file.length()} 0".toByteArray(), serverAddress, port)
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            var sent = 0L
            while (sent < file.length()) {
                val read = raf.read(buffer)
                if (read <= 0) break
                reliableUdp.send(0, buffer.copyOfRange(0, read), serverAddress, port)
                sent += read
            }
        }
        reliableUdp.receive()?.let { println("Server: ${String(it.payload)}") }
    }

    private fun calculateSpeed(bytes: Long, ms: Long): String {
        val sec = Math.max(ms / 1000.0, 0.001)
        return String.format("%.2f", (bytes / 1024.0) / sec)
    }
}
