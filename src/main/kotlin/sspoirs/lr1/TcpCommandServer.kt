package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.time.LocalDateTime
import java.util.*

class TcpCommandServer(private val port: Int) {
    private val selector = Selector.open()
    private val serverChannel = ServerSocketChannel.open()

    fun start() {
        serverChannel.bind(InetSocketAddress(port))
        serverChannel.configureBlocking(false)
        serverChannel.register(selector, SelectionKey.OP_ACCEPT)
        println("TCP Multiplexed Server (Lab 1-3) started on port $port...")
        
        while (true) {
            if (selector.select(500) == 0) continue
            val keys = selector.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                if (key.isValid) handleSelectionKey(key)
            }
        }
    }

    private fun handleSelectionKey(key: SelectionKey) {
        when {
            key.isAcceptable -> acceptClient()
            key.isReadable -> doRead(key)
            key.isWritable -> doWrite(key)
        }
    }

    private fun acceptClient() {
        val client = serverChannel.accept()
        client.configureBlocking(false)
        client.socket().keepAlive = true
        client.register(selector, SelectionKey.OP_READ, ClientSession())
    }

    private fun doRead(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        val channel = key.channel() as SocketChannel
        val buffer = ByteBuffer.allocate(Constants.BUFFER_SIZE)
        
        val read = try { channel.read(buffer) } catch (e: IOException) { -1 }
        if (read == -1) return closeClient(key)

        buffer.flip()
        session.addToInput(buffer)
        processInput(key, session)
    }

    private fun processInput(key: SelectionKey, session: ClientSession) {
        val line = session.extractLine() ?: return
        val parts = line.split(" ")
        val cmd = Command.fromString(parts[0])
        val args = parts.drop(1)
        
        executeCommand(cmd, args, key, session)
    }

    private fun executeCommand(cmd: Command, args: List<String>, key: SelectionKey, session: ClientSession) {
        when (cmd) {
            Command.TIME -> session.queueMsg(LocalDateTime.now().toString() + "\n")
            Command.ECHO -> session.queueMsg(args.joinToString(" ") + "\n")
            Command.LIST -> session.queueMsg("FILES ${getServerFiles()}\n")
            Command.DOWNLOAD -> setupDownload(args, session)
            Command.UPLOAD -> setupUpload(args, session)
            Command.CLOSE -> closeClient(key)
            else -> session.queueMsg("Error: Unknown command\n")
        }
        key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
    }

    private fun getServerFiles(): String {
        return File(Constants.SERVER_STORAGE).listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
    }

    private fun setupDownload(args: List<String>, session: ClientSession) {
        val file = File(Constants.SERVER_STORAGE, args.getOrNull(0) ?: return)
        if (!file.exists()) {
            session.queueMsg("ERROR: Not found\n")
            return
        }
        
        val offset = args.getOrNull(1)?.toLong() ?: 0L
        session.fileRaf = RandomAccessFile(file, "r").apply { seek(offset) }
        session.fileRemaining = file.length() - offset
        session.queueMsg("OK ${session.fileRemaining}\n")
    }

    private fun setupUpload(args: List<String>, session: ClientSession) {
        val name = args.getOrNull(0) ?: return
        val size = args.getOrNull(1)?.toLong() ?: 0L
        val offset = args.getOrNull(2)?.toLong() ?: 0L
        
        session.fileRaf = RandomAccessFile(File(Constants.SERVER_STORAGE, name), "rw").apply { seek(offset) }
        session.fileRemaining = size
        session.isUploading = true
    }

    private fun doWrite(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        val channel = key.channel() as SocketChannel

        if (session.msgQueue.isNotEmpty()) {
            val buf = session.msgQueue.peek()
            channel.write(buf)
            if (!buf.hasRemaining()) session.msgQueue.poll()
        } else if (session.fileRaf != null && !session.isUploading) {
            sendChunk(session, channel)
        }

        if (session.msgQueue.isEmpty() && (session.fileRaf == null || session.isUploading)) {
            key.interestOps(SelectionKey.OP_READ)
        }
    }

    private fun sendChunk(session: ClientSession, channel: SocketChannel) {
        val bytes = ByteArray(4096)
        val toRead = Math.min(bytes.size.toLong(), session.fileRemaining).toInt()
        val read = try { session.fileRaf?.read(bytes, 0, toRead) ?: -1 } catch (e: Exception) { -1 }
        
        if (read > 0) {
            channel.write(ByteBuffer.wrap(bytes, 0, read))
            session.fileRemaining -= read
            session.packetsSent++
            
            if (session.packetsSent % 100 == 0) {
                println("Send progress: ${session.fileRemaining} bytes remaining...")
            }
        }
        if (session.fileRemaining <= 0) {
            println("Transfer finished successfully.")
            session.fileRaf?.close()
            session.fileRaf = null
            session.packetsSent = 0
        }
    }

    private fun closeClient(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        session.fileRaf?.close()
        key.channel().close()
        key.cancel()
    }

    private class ClientSession {
        val inputAccumulator = ByteArrayOutputStream()
        val msgQueue: Queue<ByteBuffer> = LinkedList()
        var fileRaf: RandomAccessFile? = null
        var fileRemaining: Long = 0
        var isUploading: Boolean = false
        var packetsSent: Int = 0

        fun addToInput(buf: ByteBuffer) {
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            inputAccumulator.write(arr)
        }

        fun extractLine(): String? {
            val data = inputAccumulator.toByteArray()
            val str = String(data)
            if (!str.contains("\n")) return null
            
            val line = str.substringBefore("\n")
            val remaining = str.substringAfter("\n").toByteArray()
            inputAccumulator.reset()
            inputAccumulator.write(remaining)
            return line.trim()
        }

        fun queueMsg(txt: String) {
            msgQueue.add(ByteBuffer.wrap(txt.toByteArray()))
        }
    }
}
