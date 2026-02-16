package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.Socket
import java.time.LocalDateTime

class TcpSessionHandler(private val socket: Socket) : Runnable {
    
    override fun run() {
        val clientInfo = socket.remoteSocketAddress
        try {
            socket.soTimeout = 60000
            
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                
                println("[SERVER] New connection from $clientInfo")
                
                while (!s.isClosed) {
                    val line = try {
                        NetworkUtils.readLineBuffered(input)
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (line == null) {
                        println("[SERVER] Client $clientInfo disconnected")
                        break
                    }
                    
                    if (line.isBlank()) continue
                    
                    println("[SERVER] Received from $clientInfo: $line")

                    // РАЗДЕЛЯЕМ СТРОКУ НА КОМАНДЫ ПО СИМВОЛУ ';'
                    val commandChunks = line.split(";")
                    
                    for (chunk in commandChunks) {
                        val trimmedChunk = chunk.trim()
                        if (trimmedChunk.isEmpty()) continue
                        
                        val parts = trimmedChunk.split(Regex("\\s+"))
                        val cmd = Command.fromString(parts[0])
                        val args = parts.drop(1)
                        
                        println("[SERVER] Executing sub-command: $cmd with args $args")
                        
                        if (!handleCommand(cmd, args, input, output)) {
                            return // Если CLOSE, выходим из цикла и закрываем сессию
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[SERVER] Error with client $clientInfo: ${e.message}")
        } finally {
            println("[SERVER] Session with $clientInfo finished")
        }
    }

    private fun handleCommand(cmd: Command, args: List<String>, input: InputStream, output: OutputStream): Boolean {
        when (cmd) {
            Command.TIME -> NetworkUtils.writeLine(output, LocalDateTime.now().toString())
            Command.ECHO -> NetworkUtils.writeLine(output, args.joinToString(" "))
            Command.LIST -> NetworkUtils.writeLine(output, "FILES ${getServerFiles()}")
            Command.DOWNLOAD -> doDownload(args, output)
            Command.UPLOAD -> doUpload(args, input, output)
            Command.CLOSE -> {
                NetworkUtils.writeLine(output, "BYE")
                return false
            }
            else -> NetworkUtils.writeLine(output, "Error: Unknown command '$cmd'")
        }
        return true
    }

    private fun getServerFiles(): String {
        val dir = File(Constants.SERVER_STORAGE)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
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
