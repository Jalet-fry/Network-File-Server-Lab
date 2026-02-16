package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.Socket
import java.time.LocalDateTime

class TcpSessionHandler(private val socket: Socket) : Runnable, CommandExecutor {
    
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    override fun run() {
        val clientInfo = socket.remoteSocketAddress
        try {
            socket.soTimeout = 60000
            socket.use { s ->
                input = BufferedInputStream(s.getInputStream())
                output = s.getOutputStream()
                
                while (!s.isClosed) {
                    val line = try { NetworkUtils.readLineBuffered(input) } catch (e: Exception) { null }
                    if (line == null) break
                    
                    if (!CommandProcessor.processLine(line, this)) break
                }
            }
        } catch (e: Exception) {
            println("[SERVER] Error with client $clientInfo: ${e.message}")
        }
    }

    override fun execute(cmd: Command, args: List<String>): Boolean {
        when (cmd) {
            Command.TIME -> NetworkUtils.writeLine(output, NetworkUtils.getTimestamp())
            Command.ECHO -> NetworkUtils.writeLine(output, args.joinToString(" "))
            Command.LIST -> NetworkUtils.writeLine(output, "FILES ${CommandProcessor.getServerFilesList()}")
            Command.DOWNLOAD -> doDownload(args, output)
            Command.UPLOAD -> doUpload(args, input, output)
            Command.CLOSE -> return false
            else -> NetworkUtils.writeLine(output, "Error: Unknown command")
        }
        return true
    }

    private fun doDownload(args: List<String>, output: OutputStream) {
        val fileName = args.getOrNull(0) ?: return
        val file = File(Constants.SERVER_STORAGE, fileName)
        if (!file.exists()) {
            NetworkUtils.writeLine(output, "ERROR: Not found")
            return
        }
        val offset = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val safeOffset = Math.min(offset, file.length())
        val remaining = file.length() - safeOffset
        
        NetworkUtils.writeLine(output, "OK $remaining")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(safeOffset)
            NetworkUtils.copyStream(FileInputStream(raf.fd), output, remaining, socket)
        }
    }

    private fun doUpload(args: List<String>, input: InputStream, output: OutputStream) {
        val name = args.getOrNull(0) ?: return
        val totalSize = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val offset = args.getOrNull(2)?.toLongOrNull() ?: 0L
        val file = File(Constants.SERVER_STORAGE, name)
        val actualOffset = if (file.exists()) Math.min(offset, file.length()) else 0L
        val remaining = totalSize - actualOffset

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(actualOffset)
            NetworkUtils.copyStream(input, FileOutputStream(raf.fd), remaining, socket)
        }
        NetworkUtils.writeLine(output, "SUCCESS")
    }
}
