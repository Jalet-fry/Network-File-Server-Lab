package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.Socket
import java.time.LocalDateTime

class TcpSessionHandler(private val socket: Socket) : Runnable {
    
    override fun run() {
        val clientInfo = socket.remoteSocketAddress
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                
                println("[SERVER] New connection from $clientInfo")
                
                while (!s.isClosed) {
                    val line = NetworkUtils.readLineBuffered(input)
                    if (line == null) {
                        println("[SERVER] Client $clientInfo disconnected (EOF)")
                        break
                    }
                    
                    println("[SERVER] Received from $clientInfo: $line")
                    val parts = line.split(" ")
                    val cmd = Command.fromString(parts[0])
                    val args = parts.drop(1)
                    
                    if (!handleCommand(cmd, args, input, output)) {
                        println("[SERVER] Closing connection with $clientInfo by command")
                        break
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
            Command.TIME -> {
                val time = LocalDateTime.now().toString()
                NetworkUtils.writeLine(output, time)
                println("[SERVER] Replied with TIME: $time")
            }
            Command.ECHO -> {
                val msg = args.joinToString(" ")
                NetworkUtils.writeLine(output, msg)
                println("[SERVER] Replied with ECHO: $msg")
            }
            Command.LIST -> {
                val files = getServerFiles()
                NetworkUtils.writeLine(output, "FILES $files")
                println("[SERVER] Replied with file list")
            }
            Command.DOWNLOAD -> {
                println("[SERVER] Processing DOWNLOAD...")
                doDownload(args, output)
            }
            Command.UPLOAD -> {
                println("[SERVER] Processing UPLOAD...")
                doUpload(args, input, output)
            }
            Command.CLOSE -> return false
            else -> {
                NetworkUtils.writeLine(output, "Error: Unknown command")
                println("[SERVER] Unknown command received")
            }
        }
        return true
    }

    private fun getServerFiles(): String {
        return File(Constants.SERVER_STORAGE).listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
    }

    private fun doDownload(args: List<String>, output: OutputStream) {
        val fileName = args.getOrNull(0) ?: return
        val file = File(Constants.SERVER_STORAGE, fileName)
        if (!file.exists()) {
            NetworkUtils.writeLine(output, "ERROR: Not found")
            return
        }
        
        val offset = args.getOrNull(1)?.toLong() ?: 0L
        NetworkUtils.writeLine(output, "OK ${file.length() - offset}")
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val bytesSent = NetworkUtils.copyStream(FileInputStream(raf.fd), output, file.length() - offset, socket)
            println("[SERVER] Sent $bytesSent bytes of $fileName")
        }
    }

    private fun doUpload(args: List<String>, input: InputStream, output: OutputStream) {
        val name = args.getOrNull(0) ?: return
        val size = args.getOrNull(1)?.toLong() ?: 0L
        val offset = args.getOrNull(2)?.toLong() ?: 0L
        
        println("[SERVER] Expecting $size bytes for $name")
        val file = File(Constants.SERVER_STORAGE, name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val bytesReceived = NetworkUtils.copyStream(input, FileOutputStream(raf.fd), size, socket)
            println("[SERVER] Received $bytesReceived bytes for $name")
        }
        NetworkUtils.writeLine(output, "SUCCESS")
    }
}
