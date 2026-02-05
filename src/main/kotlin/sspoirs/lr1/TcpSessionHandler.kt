package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.Socket
import java.time.LocalDateTime

/**
 * Обработчик сессии для параллельного сервера (ЛР №4).
 * Использует блокирующий I/O, так как запускается в отдельном потоке.
 */
class TcpSessionHandler(private val socket: Socket) : Runnable {
    
    override fun run() {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                
                println("New connection from ${s.remoteSocketAddress}")
                
                while (!s.isClosed) {
                    val line = NetworkUtils.readLineBuffered(input) ?: break
                    val parts = line.split(" ")
                    val cmd = Command.fromString(parts[0])
                    val args = parts.drop(1)
                    
                    if (!handleCommand(cmd, args, input, output)) break
                }
            }
        } catch (e: Exception) {
            println("Session error: ${e.message}")
        }
    }

    private fun handleCommand(cmd: Command, args: List<String>, input: InputStream, output: OutputStream): Boolean {
        when (cmd) {
            Command.TIME -> NetworkUtils.writeLine(output, LocalDateTime.now().toString())
            Command.ECHO -> NetworkUtils.writeLine(output, args.joinToString(" "))
            Command.LIST -> NetworkUtils.writeLine(output, "FILES ${getServerFiles()}")
            Command.DOWNLOAD -> doDownload(args, output)
            Command.UPLOAD -> doUpload(args, input, output)
            Command.CLOSE -> return false
            else -> NetworkUtils.writeLine(output, "Error: Unknown command")
        }
        return true
    }

    private fun getServerFiles(): String {
        return File(Constants.SERVER_STORAGE).listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
    }

    private fun doDownload(args: List<String>, output: OutputStream) {
        val file = File(Constants.SERVER_STORAGE, args.getOrNull(0) ?: return)
        if (!file.exists()) {
            NetworkUtils.writeLine(output, "ERROR: Not found")
            return
        }
        
        val offset = args.getOrNull(1)?.toLong() ?: 0L
        NetworkUtils.writeLine(output, "OK ${file.length() - offset}")
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            NetworkUtils.copyStream(FileInputStream(raf.fd), output, file.length() - offset, socket)
        }
    }

    private fun doUpload(args: List<String>, input: InputStream, output: OutputStream) {
        val name = args.getOrNull(0) ?: return
        val size = args.getOrNull(1)?.toLong() ?: 0L
        val offset = args.getOrNull(2)?.toLong() ?: 0L
        
        val file = File(Constants.SERVER_STORAGE, name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            NetworkUtils.copyStream(input, FileOutputStream(raf.fd), size, socket)
        }
        NetworkUtils.writeLine(output, "SUCCESS")
    }
}
