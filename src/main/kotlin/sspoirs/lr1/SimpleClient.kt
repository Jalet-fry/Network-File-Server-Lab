package sspoirs.lr1

import org.jline.reader.LineReaderBuilder
import org.jline.reader.impl.completer.AggregateCompleter
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.TerminalBuilder
import sspoirs.common.Command
import sspoirs.common.Constants
import sspoirs.common.NetworkUtils
import java.io.*
import java.net.Socket

class SimpleClient(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var serverFiles = mutableListOf<String>()

    fun start() {
        if (!connect()) return

        val terminal = try { 
            TerminalBuilder.builder().system(true).build() 
        } catch (e: Exception) {
            println("\n[WARN] System terminal not available, using dumb terminal. Autocomplete may not work.")
            TerminalBuilder.builder().dumb(true).build()
        }

        val lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(buildCompleter())
            .build()

        println("\nCommands: LS, DOWNLOAD, UPLOAD, TIME, ECHO, EXIT. Type '?' for help.")

        while (true) {
            val line = try { lineReader.readLine("TCP > ")?.trim() } catch (e: Exception) { null } ?: break
            if (line.isEmpty()) continue
            if (line == "?") {
                printHelp()
                continue
            }
            if (handleCommand(line)) break
        }
        socket?.close()
    }

    private fun printHelp() {
        println("\nAvailable commands:")
        Command.allCommands().forEach { println(" - $it") }
        println("Server files:")
        serverFiles.forEach { println(" - $it") }
    }

    private fun buildCompleter() = AggregateCompleter(
        StringsCompleter(Command.allCommands().map { it.lowercase() } + Command.allCommands()),
        StringsCompleter(serverFiles)
    )

    private fun connect(): Boolean {
        // ... (rest of the code is unchanged)
        return try {
            socket = Socket(host, port).apply { keepAlive = true }
            inputStream = BufferedInputStream(socket!!.getInputStream())
            outputStream = socket!!.getOutputStream()
            println("Connected to $host:$port")
            updateServerFiles()
            true
        } catch (e: Exception) {
            println("Connection failed: ${e.message}")
            false
        }
    }

    private fun handleCommand(line: String): Boolean {
        val parts = line.split(" ")
        val cmd = Command.fromString(parts[0])
        val arg = parts.getOrNull(1)

        return when (cmd) {
            Command.LIST -> { requestFileList(); false }
            Command.DOWNLOAD -> { arg?.let { initiateDownload(it) }; false }
            Command.UPLOAD -> { arg?.let { initiateUpload(it) }; false }
            Command.CLOSE -> true
            else -> { sendBasicCommand(line); false }
        }
    }

    private fun updateServerFiles() {
        try {
            NetworkUtils.writeLine(outputStream!!, "LS")
            val resp = NetworkUtils.readLineBuffered(inputStream!!) ?: return
            if (resp.startsWith("FILES")) {
                serverFiles.clear()
                resp.substringAfter("FILES ").split(";").forEach {
                    if (it.contains("(")) serverFiles.add(it.substringBefore("("))
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun requestFileList() {
        updateServerFiles()
        println("\n--- Server Files ---")
        if (serverFiles.isEmpty()) println("[Empty]") else serverFiles.forEach { println(" - $it") }
    }

    private fun sendBasicCommand(line: String) {
        NetworkUtils.writeLine(outputStream!!, line)
        println("Server: ${NetworkUtils.readLineBuffered(inputStream!!)}")
    }

    private fun initiateDownload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        val offset = if (file.exists()) file.length() else 0L
        NetworkUtils.writeLine(outputStream!!, "DOWNLOAD $name $offset")
        val resp = NetworkUtils.readLineBuffered(inputStream!!) ?: return
        if (resp.startsWith("OK")) {
            val size = resp.split(" ")[1].toLong()
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                NetworkUtils.copyStream(inputStream!!, FileOutputStream(raf.fd), size, socket)
            }
        } else println("Server: $resp")
    }

    private fun initiateUpload(name: String) {
        val file = File(Constants.CLIENT_STORAGE, name)
        if (!file.exists()) return println("Local file not found.")
        NetworkUtils.writeLine(outputStream!!, "UPLOAD $name ${file.length()} 0")
        FileInputStream(file).use { fis ->
            NetworkUtils.copyStream(fis, outputStream!!, file.length(), socket)
        }
        println("Server: ${NetworkUtils.readLineBuffered(inputStream!!)}")
    }
}
