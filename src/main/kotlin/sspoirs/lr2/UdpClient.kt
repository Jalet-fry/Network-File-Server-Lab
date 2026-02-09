package sspoirs.lr2

import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import sspoirs.common.*
import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Scanner
import java.util.regex.Pattern

class UdpClient(private val host: String, private val port: Int) {
    private val socket = DatagramSocket()
    private val reliableUdp = ReliableUdp(socket)
    private val serverAddress = InetAddress.getByName(host)
    private var serverFiles = mutableMapOf<String, Long>()
    private var useFallbackScanner = false

    fun start() {
        println("[DEBUG] UDP Client started (Reliable Mode). Host: $host:$port")
        updateServerFiles()

        var lineReader: LineReader? = null
        try {
            val terminal = TerminalBuilder.builder().system(true).build()
            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(AggregateCompleter(
                    StringsCompleter(Command.allCommands().map { it.lowercase() } + listOf("ls", "exit")),
                    StringsCompleter(serverFiles.keys)
                ))
                .build()
        } catch (e: Exception) {
            println("[WARN] JLine failed, using fallback scanner.")
            useFallbackScanner = true
        }

        println("[SUCCESS] UDP Ready. Commands: LS, DOWNLOAD, UPLOAD, EXIT.")
        val scanner = Scanner(System.`in`)

        while (true) {
            val line = try {
                if (useFallbackScanner || lineReader == null) {
                    print("UDP > ")
                    if (scanner.hasNextLine()) scanner.nextLine() else null
                } else {
                    lineReader.readLine("UDP > ")
                }?.trim()
            } catch (e: Exception) {
                useFallbackScanner = true
                null
            } ?: break

            if (line.isEmpty()) continue
            if (handleCommand(line)) break
        }
        socket.close()
    }

    private fun handleCommand(line: String): Boolean {
        val parts = mutableListOf<String>()
        val m = Pattern.compile("([^\"\\s]\\S*|\".+?\")\\s*").matcher(line)
        while (m.find()) {
            parts.add(m.group(1).replace("\"", ""))
        }

        if (parts.isEmpty()) return false
        val cmd = Command.fromString(parts[0])
        val arg = parts.getOrNull(1)

        return when (cmd) {
            Command.LIST -> { requestFileList(); false }
            Command.DOWNLOAD -> { if (arg != null) initiateDownload(arg); false }
            Command.UPLOAD -> { if (arg != null) initiateUpload(arg); false }
            Command.CLOSE -> true
            else -> { sendBasicCommand(line); false }
        }
    }

    private fun updateServerFiles() {
        try {
            reliableUdp.send(2, "LS".toByteArray(), serverAddress, port)
            val resp = reliableUdp.receive(2000) ?: return
            if (resp.type != 1.toByte()) reliableUdp.sendAck(resp.seq, resp.address, resp.port)
            
            val content = String(resp.payload)
            if (content.startsWith("FILES")) {
                serverFiles.clear()
                val data = content.substringAfter("FILES ")
                if (data != "No files") {
                    data.split(";").forEach {
                        val name = it.substringBefore("(")
                        val size = it.substringAfter("(").substringBefore("b)").toLongOrNull() ?: 0L
                        serverFiles[name] = size
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun requestFileList() {
        updateServerFiles()
        println("\n--- Server Files ---")
        if (serverFiles.isEmpty()) println("[Empty]") else {
            serverFiles.forEach { (name, size) -> println(" - $name ($size bytes)") }
        }
    }

    private fun sendBasicCommand(line: String) {
        reliableUdp.send(2, line.toByteArray(), serverAddress, port)
        reliableUdp.receive(3000)?.let { 
            if (it.type != 1.toByte()) reliableUdp.sendAck(it.seq, it.address, it.port)
            println("Server: ${String(it.payload)}") 
        }
    }

    private fun initiateDownload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        val offset = if (file.exists()) file.length() else 0L
        updateServerFiles()
        val fullSize = serverFiles[name] ?: -1L
        
        reliableUdp.send(2, "DOWNLOAD $name $offset".toByteArray(), serverAddress, port)
        val resp = reliableUdp.receive(3000) ?: return
        if (resp.type != 1.toByte()) reliableUdp.sendAck(resp.seq, resp.address, resp.port)
        
        val status = String(resp.payload)
        if (status.startsWith("OK")) {
            val remainingSize = status.split(" ")[1].toLong()
            println("[INFO] Downloading ${if (offset > 0) "remaining " else ""}$remainingSize bytes...")
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                receiveFileData(raf, remainingSize, offset, fullSize)
            }
            println("\n[SUCCESS] Download finished.")
        } else println("Error: $status")
    }

    private fun receiveFileData(raf: RandomAccessFile, length: Long, offset: Long, fullSize: Long) {
        var received = 0L
        val start = System.currentTimeMillis()
        var lastPrint = 0L
        var packetCount = 0

        while (received < length) {
            val p = reliableUdp.receive(5000) ?: break
            if (p.type == 0.toByte()) {
                packetCount++
                // КУМУЛЯТИВНЫЙ ACK: шлем раз в 50 пакетов для скорости (ЛР 2)
                if (packetCount % 50 == 0 || received + p.payload.size >= length) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                }
                raf.write(p.payload)
                received += p.payload.size
                
                val now = System.currentTimeMillis()
                if (now - lastPrint > 300) {
                    val currentTotal = offset + received
                    val pct = if (fullSize > 0) (currentTotal * 100 / fullSize) else 0
                    print("\r[Progress] $currentTotal / $fullSize bytes ($pct%)")
                    lastPrint = now
                }
            }
        }
        val duration = System.currentTimeMillis() - start
        if (received > 0) {
            val speed = (received / 1024.0) / (Math.max(duration, 1) / 1000.0)
            println("\n[Transfer] Complete: $received bytes in ${duration}ms (${String.format("%.2f", speed)} KB/s)")
        }
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        updateServerFiles()
        val offset = serverFiles[name] ?: 0L
        val totalSize = file.length()
        if (offset >= totalSize) return println("[INFO] Already uploaded.")

        reliableUdp.send(2, "UPLOAD $name $totalSize $offset".toByteArray(), serverAddress, port)
        
        val start = System.currentTimeMillis()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
            var sent = 0L
            val toSend = totalSize - offset
            val windowSize = 50
            
            while (sent < toSend) {
                var lastSeq = 0
                for (i in 0 until windowSize) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    lastSeq = reliableUdp.getSeqNum()
                    reliableUdp.send(0, buffer.copyOfRange(0, read), serverAddress, port, requireAck = false)
                    sent += read
                    if (sent >= toSend) break
                }
                reliableUdp.waitForAck(lastSeq, 500)
                if (sent % (1024 * 100) == 0L) print("\r[Progress] ${offset + sent} / $totalSize bytes")
            }
        }
        val duration = System.currentTimeMillis() - start
        val speed = ((totalSize - offset) / 1024.0) / (Math.max(duration, 1) / 1000.0)
        println("\n[Transfer] Upload speed: ${String.format("%.2f", speed)} KB/s")
        
        val fin = reliableUdp.receive(5000)
        if (fin != null) {
            reliableUdp.sendAck(fin.seq, fin.address, fin.port)
            println("Server: ${String(fin.payload)}")
        }
    }
}
