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
import java.nio.ByteBuffer
import java.util.Scanner
import java.util.regex.Pattern

class UdpClient(private val host: String, private val port: Int) {
    private val socket = DatagramSocket().apply {
        receiveBufferSize = 8 * 1024 * 1024
        sendBufferSize = 8 * 1024 * 1024
    }
    private val reliableUdp = ReliableUdp(socket)
    private val serverAddress = InetAddress.getByName(host)
    private var serverFiles = mutableMapOf<String, Long>()
    private var useFallbackScanner = false

    fun start() {
        println("[DEBUG] UDP Client started. Host: $host:$port")
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
        println("Batch mode supported: time; ls; time")
        
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
            
            // Поддержка пачек команд в UDP
            val chunks = line.split(";")
            var exitRequested = false
            for (chunk in chunks) {
                val trimmed = chunk.trim()
                if (trimmed.isEmpty()) continue
                if (processSingleCommand(trimmed)) {
                    exitRequested = true
                    break
                }
            }
            if (exitRequested) break
        }
        socket.close()
    }

    private fun processSingleCommand(line: String): Boolean {
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

    private fun receiveWithAck(timeout: Int = 5000): ReliableUdp.UdpPacket? {
        val p = reliableUdp.receive(timeout)
        if (p != null && p.type != 1.toByte()) {
            reliableUdp.sendAck(p.seq, p.address, p.port)
        }
        return p
    }

    private fun updateServerFiles() {
        try {
            reliableUdp.clearQueue()
            reliableUdp.sendReliable(2, "LS".toByteArray(), serverAddress, port)
            val resp = receiveWithAck(2000) ?: return
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
            reliableUdp.clearQueue()
            reliableUdp.sendReliable(2, line.toByteArray(), serverAddress, port)
            receiveWithAck(3000)?.let { println("Server: ${String(it.payload)}") }
        } catch (e: Exception) { println("[ERROR] Command failed.") }
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
            reliableUdp.clearQueue()
            reliableUdp.sendReliable(2, "DOWNLOAD $name $offset".toByteArray(), serverAddress, port)
            val resp = receiveWithAck(3000) ?: return
            val status = String(resp.payload)
            
            if (status.startsWith("OK")) {
                val remainingSize = status.split(" ")[1].toLong()
                println("[INFO] Downloading $remainingSize bytes...")
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(offset)
                    receiveFileData(raf, remainingSize, offset, fullSize)
                }
            } else println("Error: $status")
        } catch (e: Exception) { println("[ERROR] Download failed.") }
    }

    private fun receiveFileData(raf: RandomAccessFile, length: Long, offset: Long, fullSize: Long) {
        var received = 0L
        val start = System.currentTimeMillis()
        var lastPrint = 0L
        var lastPSeq = -1
        var timeouts = 0

        while (received < length) {
            val p = reliableUdp.receive(5000)
            if (p == null) {
                timeouts++
                if (timeouts > 5) break
                continue
            }
            timeouts = 0

            if (p.type == 0.toByte()) {
                if (p.seq <= lastPSeq && lastPSeq != -1) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                    continue
                }
                
                val ackFreq = if (received >= length * 0.95) 5 else 50
                if (p.seq % ackFreq == 0 || received + p.payload.size >= length) {
                    reliableUdp.sendAck(p.seq, p.address, p.port)
                }
                raf.write(p.payload)
                received += p.payload.size
                lastPSeq = p.seq
                
                val now = System.currentTimeMillis()
                if (now - lastPrint > 300) {
                    val currentTotal = offset + received
                    val pct = if (fullSize > 0) (currentTotal * 100 / fullSize) else 0
                    print("\r[Progress] $currentTotal / $fullSize bytes ($pct%)")
                    lastPrint = now
                }
            }
        }
        
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        val speed = (received / 1024.0) / (duration / 1000.0)
        println("\n[SUCCESS] Download finished. Speed: ${String.format("%.2f", speed)} KB/s")
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        updateServerFiles()
        val offset = serverFiles[name] ?: 0L
        val totalSize = file.length()
        if (offset >= totalSize) return println("[INFO] Already uploaded.")

        try {
            reliableUdp.clearQueue()
            reliableUdp.sendReliable(2, "UPLOAD $name $totalSize $offset".toByteArray(), serverAddress, port)
            val start = System.currentTimeMillis()
            RandomAccessFile(file, "r").use { raf ->
                val fc = raf.channel
                fc.position(offset)
                val buffer = ByteBuffer.allocate(Constants.UDP_PACKET_SIZE)
                var sent = 0L
                val toSend = totalSize - offset
                val windowSize = 100
                
                while (sent < toSend) {
                    val windowStartSeq = reliableUdp.getSeqNum()
                    val windowStartPos = fc.position()
                    var windowBytes = 0L
                    for (i in 0 until windowSize) {
                        buffer.clear()
                        if (fc.read(buffer) <= 0) break
                        buffer.flip()
                        val payload = ByteArray(buffer.remaining())
                        buffer.get(payload)
                        reliableUdp.sendFast(0, payload, serverAddress, port, forcedSeq = windowStartSeq + i)
                        windowBytes += payload.size
                        if (sent + windowBytes >= toSend) break
                    }
                    if (!reliableUdp.waitForAck(windowStartSeq + windowSize / 2, 1000)) {
                        fc.position(windowStartPos)
                        continue
                    }
                    sent += windowBytes
                    val pkts = (windowBytes + Constants.UDP_PACKET_SIZE - 1) / Constants.UDP_PACKET_SIZE
                    reliableUdp.advanceSeq(pkts.toInt())
                }
            }
            val duration = Math.max(System.currentTimeMillis() - start, 1)
            val speedKb = ((totalSize - offset) / 1024.0) / (duration / 1000.0)
            println("\n[SUCCESS] Upload finished. Speed: ${String.format("%.2f", speedKb)} KB/s")
            receiveWithAck(3000)
        } catch (e: Exception) { println("[ERROR] Upload failed.") }
    }
}
