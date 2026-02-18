package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

class TcpSessionHandler(private val socket: Socket) : Runnable, CommandExecutor {
    
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val lastActivity = AtomicLong(System.currentTimeMillis())

    override fun run() {
        val clientInfo = socket.remoteSocketAddress
        try {
            // Устанавливаем таймаут на ожидание данных (30 секунд)
            socket.soTimeout = Constants.READ_TIMEOUT_MS
            socket.keepAlive = true
            
            input = BufferedInputStream(socket.getInputStream())
            output = socket.getOutputStream()
            
            while (!socket.isClosed) {
                val line = try { 
                    NetworkUtils.readLineBuffered(input) 
                } catch (e: SocketTimeoutException) {
                    // Если данных нет 30 секунд, проверяем общий простой
                    val idleTime = System.currentTimeMillis() - lastActivity.get()
                    if (idleTime > Constants.SESSION_MAX_IDLE_MS) {
                        NetworkUtils.log("[SERVER] Client $clientInfo timed out after ${idleTime / 1000}s")
                        break
                    }
                    continue // Просто пробуем читать дальше
                } catch (e: Exception) { 
                    null 
                }
                
                if (line == null) break
                if (line.isEmpty()) continue
                
                lastActivity.set(System.currentTimeMillis())
                if (!CommandProcessor.processLine(line, this)) break
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (!msg.contains("Socket closed") && !msg.contains("Connection reset")) {
                NetworkUtils.log("[SERVER] Error with client $clientInfo: $msg")
            }
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    override fun execute(cmd: Command, args: List<String>): Boolean {
        lastActivity.set(System.currentTimeMillis())
        return try {
            when (cmd) {
                Command.TIME -> NetworkUtils.writeLine(output, NetworkUtils.getTimestamp())
                Command.ECHO -> NetworkUtils.writeLine(output, args.joinToString(" "))
                Command.LIST -> NetworkUtils.writeLine(output, "FILES ${CommandProcessor.getServerFilesList()}")
                Command.DOWNLOAD -> doDownload(args, output)
                Command.UPLOAD -> doUpload(args, input, output)
                Command.CLOSE -> return false
                else -> NetworkUtils.writeLine(output, "Error: Unknown command")
            }
            true
        } catch (e: Exception) {
            false 
        }
    }

    private fun doDownload(args: List<String>, output: OutputStream) {
        val fileName = args.getOrNull(0) ?: return
        val file = File(Constants.SERVER_STORAGE, fileName)
        if (!file.exists() || !file.isFile) {
            NetworkUtils.writeLine(output, "ERROR: Not found")
            return
        }
        val offset = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val safeOffset = Math.min(offset, file.length())
        val remaining = file.length() - safeOffset
        
        NetworkUtils.writeLine(output, "OK $remaining")
        if (remaining <= 0) return

        // Для файлов отключаем таймаут или делаем его очень большим
        socket.soTimeout = 0 
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(safeOffset)
                val fis = object : InputStream() {
                    override fun read() = raf.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = raf.read(b, off, len)
                    override fun read(b: ByteArray) = raf.read(b)
                }
                NetworkUtils.copyStream(fis, output, remaining, socket, safeOffset, file.length())
            }
        } finally {
            socket.soTimeout = Constants.READ_TIMEOUT_MS
            lastActivity.set(System.currentTimeMillis())
        }
    }

    private fun doUpload(args: List<String>, input: InputStream, output: OutputStream) {
        val name = args.getOrNull(0) ?: return
        val totalSize = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val offset = args.getOrNull(2)?.toLongOrNull() ?: 0L
        
        val file = File(Constants.SERVER_STORAGE, name)
        val actualOffset = if (file.exists()) Math.min(offset, file.length()) else 0L
        val remaining = totalSize - actualOffset

        if (remaining > 0) {
            socket.soTimeout = 0 
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(actualOffset)
                    val fos = object : OutputStream() {
                        override fun write(b: Int) = raf.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                        override fun write(b: ByteArray) = raf.write(b)
                    }
                    NetworkUtils.copyStream(input, fos, remaining, socket, actualOffset, totalSize)
                }
            } finally {
                socket.soTimeout = Constants.READ_TIMEOUT_MS
                lastActivity.set(System.currentTimeMillis())
            }
        }
        NetworkUtils.writeLine(output, "SUCCESS")
    }
}
