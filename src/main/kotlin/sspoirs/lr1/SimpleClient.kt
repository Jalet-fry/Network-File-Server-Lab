package sspoirs.lr1

import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import sspoirs.common.Command
import sspoirs.common.Constants
import sspoirs.common.NetworkUtils
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Scanner
import java.util.regex.Pattern

class SimpleClient(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var serverFiles = mutableMapOf<String, Long>()
    private var useFallbackScanner = false

    fun start() {
        println("[DEBUG] Connecting to $host:$port...")
        if (!connect()) return

        var lineReader: LineReader? = null
        try {
            val terminal = TerminalBuilder.builder().system(true).build()
            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(buildCompleter())
                .build()
        } catch (e: Exception) {
            useFallbackScanner = true
        }

        println("\n[SUCCESS] Connected! Commands: LS, DOWNLOAD, UPLOAD, EXIT.")
        
        val scanner = Scanner(System.`in`)

        while (true) {
            val line = try {
                if (useFallbackScanner || lineReader == null) {
                    print("TCP > ")
                    if (scanner.hasNextLine()) scanner.nextLine() else null
                } else {
                    lineReader.readLine("TCP > ")
                }?.trim()
            } catch (e: Exception) {
                useFallbackScanner = true
                null
            } ?: break

            if (line.isNullOrEmpty()) continue
            
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
        socket?.close()
        println("[INFO] Client session finished.")
    }

    private fun processSingleCommand(line: String): Boolean {
        if (socket == null || socket!!.isClosed) return true

        val parts = mutableListOf<String>()
        val m = Pattern.compile("([^\"\\s]\\S*|\".+?\")\\s*").matcher(line)
        while (m.find()) {
            parts.add(m.group(1).replace("\"", ""))
        }

        if (parts.isEmpty()) return false
        val cmd = Command.fromString(parts[0])
        val arg = parts.getOrNull(1)

        return try {
            when (cmd) {
                Command.LIST -> { 
                    if (!updateServerFiles()) {
                        println("[ERROR] Connection lost or server timeout.")
                        return true
                    }
                    requestFileList()
                    false 
                }
                Command.DOWNLOAD -> { 
                    if (!arg.isNullOrEmpty()) {
                        if (!updateServerFiles()) return true
                        initiateDownload(arg)
                    }
                    false 
                }
                Command.UPLOAD -> { 
                    if (!arg.isNullOrEmpty()) {
                        if (!updateServerFiles()) return true
                        initiateUpload(arg)
                    }
                    false 
                }
                Command.CLOSE -> true
                else -> { 
                    NetworkUtils.writeLine(outputStream!!, line)
                    val resp = NetworkUtils.readLineBuffered(inputStream!!)
                    if (resp == null) {
                        println("[ERROR] Server disconnected.")
                        return true
                    }
                    println("Server: $resp")
                    false 
                }
            }
        } catch (e: SocketTimeoutException) {
            println("[ERROR] Server non-responsive (Timeout).")
            true
        } catch (e: Exception) {
            println("[ERROR] Connection lost: ${e.message}")
            true
        }
    }

    private fun buildCompleter() = AggregateCompleter(
        StringsCompleter(Command.allCommands().map { it.lowercase() } + listOf("ls", "exit")),
        StringsCompleter(serverFiles.keys)
    )

    private fun connect(): Boolean {
        return try {
            socket = Socket()
            // Тайм-аут на установку соединения 5 сек
            socket?.connect(InetSocketAddress(host, port), 5000)
            socket?.keepAlive = true
            socket?.tcpNoDelay = true
            // Клиент ждет ответа на команду не более 5 секунд
            socket?.soTimeout = 5000
            
            inputStream = BufferedInputStream(socket!!.getInputStream())
            outputStream = socket!!.getOutputStream()
            
            if (!updateServerFiles()) {
                println("[FAILED] Server did not respond to initial request.")
                return false
            }
            true
        } catch (e: Exception) {
            println("[FAILED] Connection error: ${e.message}")
            false
        }
    }

    private fun updateServerFiles(): Boolean {
        return try {
            NetworkUtils.writeLine(outputStream!!, "LS")
            val resp = NetworkUtils.readLineBuffered(inputStream!!)
            if (resp != null) {
                if (resp.startsWith("FILES")) {
                    serverFiles.clear()
                    val data = resp.substringAfter("FILES ")
                    if (data != "No files" && data.isNotEmpty()) {
                        data.split(";").forEach {
                            val name = it.substringBefore("(")
                            val size = it.substringAfter("(").substringBefore("b)").toLongOrNull() ?: 0L
                            serverFiles[name] = size
                        }
                    }
                }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun requestFileList() {
        println("\n--- Server Files ---")
        if (serverFiles.isEmpty()) {
            println(" (No files)")
        } else {
            serverFiles.forEach { (name, size) -> println(" - $name ($size bytes)") }
        }
    }

    private fun initiateDownload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        val offset = if (file.exists()) file.length() else 0L
        val fullSize = serverFiles[name] ?: -1L
        
        if (fullSize != -1L && offset >= fullSize) {
            println("[INFO] Already fully downloaded.")
            return
        }

        NetworkUtils.writeLine(outputStream!!, "DOWNLOAD $name $offset")
        val resp = NetworkUtils.readLineBuffered(inputStream!!) ?: return
        
        if (resp.startsWith("OK")) {
            val remainingSize = resp.split(" ")[1].toLong()
            println("[INFO] Downloading $remainingSize bytes...")
            
            // Во время загрузки отключаем таймаут
            val oldTimeout = socket?.soTimeout ?: 0
            socket?.soTimeout = 0
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(offset)
                    val fos = object : OutputStream() {
                        override fun write(b: Int) = raf.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                        override fun write(b: ByteArray) = raf.write(b)
                    }
                    NetworkUtils.copyStream(inputStream!!, fos, remainingSize, socket, offset, fullSize)
                }
                println("\n[SUCCESS] Download finished.")
            } finally {
                socket?.soTimeout = oldTimeout
            }
        } else {
            println("Server: $resp")
        }
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        val offset = serverFiles[name] ?: 0L
        val totalSize = file.length()
        
        if (offset >= totalSize) return println("[INFO] Already uploaded.")

        NetworkUtils.writeLine(outputStream!!, "UPLOAD $name $totalSize $offset")
        
        val oldTimeout = socket?.soTimeout ?: 0
        socket?.soTimeout = 0
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val fis = object : InputStream() {
                    override fun read() = raf.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = raf.read(b, off, len)
                    override fun read(b: ByteArray) = raf.read(b)
                }
                NetworkUtils.copyStream(fis, outputStream!!, totalSize - offset, socket, offset, totalSize)
            }
            val finalResp = NetworkUtils.readLineBuffered(inputStream!!)
            println("Server: $finalResp")
        } finally {
            socket?.soTimeout = oldTimeout
        }
        updateServerFiles()
    }
}
