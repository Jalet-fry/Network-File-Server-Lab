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

        // Запускаем фоновый монитор соединения
        startConnectionMonitor()

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

        while (socket?.isClosed == false) {
            val line = try {
                if (useFallbackScanner || lineReader == null) {
                    print("TCP > ")
                    if (scanner.hasNextLine()) scanner.nextLine() else null
                } else {
                    lineReader.readLine("TCP > ")
                }?.trim()
            } catch (e: Exception) {
                null
            } ?: break

            if (line.isEmpty()) continue
            
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
        closeQuietly()
        println("[INFO] Client session finished.")
    }

    private fun startConnectionMonitor() {
        Thread {
            while (socket != null && !socket!!.isClosed) {
                try {
                    Thread.sleep(2000)
                    // Проверка "на вшивость": посылаем 1 байт Urgent Data.
                    // Если сокет закрыт со стороны сервера, вылетит IOException.
                    socket?.sendUrgentData(0xFF)
                } catch (e: Exception) {
                    if (socket?.isClosed == false) {
                        println("\n[ERROR] Connection lost (Server closed session or network error).")
                        println("Press Enter to exit...")
                        closeQuietly()
                    }
                    break
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (e: Exception) {}
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
                    if (!updateServerFiles()) return true
                    requestFileList()
                    false 
                }
                Command.DOWNLOAD -> { 
                    if (!arg.isNullOrEmpty()) {
                        updateServerFiles()
                        initiateDownload(arg)
                    }
                    false 
                }
                Command.UPLOAD -> { 
                    if (!arg.isNullOrEmpty()) {
                        updateServerFiles()
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
            println("[ERROR] Server response timeout (15s). Connection might be unstable.")
            true
        } catch (e: Exception) {
            println("[ERROR] Connection error: ${e.message}")
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
            socket?.connect(InetSocketAddress(host, port), 5000)
            socket?.keepAlive = true
            socket?.tcpNoDelay = true
            socket?.soTimeout = 15000 // Увеличили до 15 сек для команд
            
            inputStream = BufferedInputStream(socket!!.getInputStream())
            outputStream = socket!!.getOutputStream()
            
            updateServerFiles()
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
            
            val oldTimeout = socket?.soTimeout ?: 0
            socket?.soTimeout = 0 // Без таймаута на время качки
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
