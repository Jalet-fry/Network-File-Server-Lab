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
    private val socket = DatagramSocket().apply {
        receiveBufferSize = 4 * 1024 * 1024
        sendBufferSize = 4 * 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)
    private val serverAddress = InetAddress.getByName(host)
    private var serverFiles = mutableMapOf<String, Long>()
    private var useFallbackScanner = false

    fun start() {
        println("[DEBUG] UDP Client started (v3.4 Reliable Mode). Host: $host:$port")
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
            reliableUdp.sendReliable(2, "LS".toByteArray(), serverAddress, port)
            val resp = reliableUdp.receive(2000) ?: return
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
        try {
            reliableUdp.sendReliable(2, line.toByteArray(), serverAddress, port)
            reliableUdp.receive(3000)?.let { println("Server: ${String(it.payload)}") }
        } catch (e: Exception) { println("[ERROR] Command failed: ${e.message}") }
    }

    private fun initiateDownload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        val offset = if (file.exists()) file.length() else 0L
        updateServerFiles()
        val fullSize = serverFiles[name] ?: -1L
        
        if (fullSize != -1L && offset >= fullSize) {
            println("[INFO] Already fully downloaded.")
            return
        }

        try {
            reliableUdp.sendReliable(2, "DOWNLOAD $name $offset".toByteArray(), serverAddress, port)
            val resp = reliableUdp.receive(3000) ?: return
            val status = String(resp.payload)
            
            if (status.startsWith("OK")) {
                val remainingSize = status.split(" ")[1].toLong()
                println("[INFO] Downloading $remainingSize bytes...")
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(offset)
                    receiveFileData(raf, remainingSize, offset, fullSize)
                }
            } else println("Error: $status")
        } catch (e: Exception) { println("[ERROR] Download failed: ${e.message}") }
    }

    private fun receiveFileData(raf: RandomAccessFile, length: Long, offset: Long, fullSize: Long) {
        var received = 0L
        val start = System.currentTimeMillis()
        var lastPrint = 0L
        var lastAckedSeq = -1

        while (received < length) {
            val p = reliableUdp.receive(5000) ?: break 
            if (p.type == 0.toByte()) {
                // Если получили дубликат - игнорируем
                if (p.seq <= lastAckedSeq && lastAckedSeq != -1) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                    continue
                }
                
                // Кумулятивный ACK раз в 50 пакетов или для последнего
                if (p.seq % 50 == 0 || received + p.payload.size >= length) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                }
                raf.write(p.payload)
                received += p.payload.size
                lastAckedSeq = p.seq
                
                val now = System.currentTimeMillis()
                if (now - lastPrint > 300) {
                    val currentTotal = offset + received
                    val pct = if (fullSize > 0) (currentTotal * 100 / fullSize) else 0
                    print("\r[Progress] $currentTotal / $fullSize bytes ($pct%)")
                    lastPrint = now
                }
            }
        }
        
        // Посылаем финальный ACK 3 раза для уверенности
        if (received >= length) repeat(3) { reliableUdp.sendAck(lastAckedSeq, serverAddress, port) }

        val duration = Math.max(System.currentTimeMillis() - start, 1)
        if (received >= length) {
            val speed = (received / 1024.0) / (duration / 1000.0)
            println("\n[SUCCESS] Complete: $received bytes in ${duration}ms (${String.format("%.2f", speed)} KB/s)")
        } else {
            println("\n[ERROR] Download interrupted. Received $received / $length bytes.")
        }
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        updateServerFiles()
        val offset = serverFiles[name] ?: 0L
        val totalSize = file.length()
        if (offset >= totalSize) return println("[INFO] Already uploaded.")

        try {
            reliableUdp.sendReliable(2, "UPLOAD $name $totalSize $offset".toByteArray(), serverAddress, port)
            val start = System.currentTimeMillis()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(Constants.UDP_PACKET_SIZE)
                var sent = 0L
                val toSend = totalSize - offset
                val windowSize = 100
                
                while (sent < toSend) {
                    val windowStartPos = raf.filePointer
                    val startSeq = reliableUdp.getSeqNum()
                    
                    var windowBytes = 0L
                    for (i in 0 until windowSize) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        reliableUdp.sendFast(0, buffer.copyOfRange(0, read), serverAddress, port)
                        windowBytes += read
                        if (sent + windowBytes >= toSend) break
                    }
                    
                    if (!reliableUdp.waitForAck(startSeq + windowSize - 1, 1500)) {
                        raf.seek(windowStartPos)
                        continue
                    }
                    sent += windowBytes
                    print("\r[Progress] ${offset + sent} / $totalSize bytes")
                }
            }
            val duration = Math.max(System.currentTimeMillis() - start, 1)
            println("\n[SUCCESS] Complete: ${totalSize - offset} bytes in ${duration}ms")
            reliableUdp.receive(3000)
        } catch (e: Exception) { println("[ERROR] Upload failed: ${e.message}") }
    }
}
