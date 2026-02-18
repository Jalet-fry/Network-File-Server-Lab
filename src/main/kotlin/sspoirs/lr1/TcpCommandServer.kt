package sspoirs.lr1

import sspoirs.common.*
import java.io.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.time.LocalDateTime
import java.util.*
import kotlin.text.Charsets

class TcpCommandServer(private val port: Int) : CommandExecutor {
    private val selector = Selector.open()
    private val serverChannel = ServerSocketChannel.open()
    
    private var currentSession: ClientSession? = null
    private var currentKey: SelectionKey? = null

    fun start() {
        println("--- Multiplexed Server Starting at ${NetworkUtils.getTimestamp()} ---")
        println("[SERVER] Available local IP addresses:")
        NetworkUtils.getLocalIpAddresses().forEach { println("  - $it") }

        serverChannel.bind(InetSocketAddress(port))
        serverChannel.configureBlocking(false)
        serverChannel.register(selector, SelectionKey.OP_ACCEPT)
        println("[SERVER] TCP Multiplexed Server started on port $port...")
        
        while (true) {
            try {
                if (selector.select(500) == 0) continue
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (key.isValid) {
                        try {
                            handleSelectionKey(key)
                        } catch (e: Exception) {
                            closeClient(key)
                        }
                    }
                }
            } catch (e: Exception) {
                println("[SERVER] Selector loop error: ${e.message}")
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
        client.register(selector, SelectionKey.OP_READ, ClientSession(client.remoteAddress.toString()))
        println("[${NetworkUtils.getTimestamp()}] New connection from ${client.remoteAddress}")
    }

    private fun doRead(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        val channel = key.channel() as SocketChannel
        
        if (session.isUploading) {
            handleUploadChunk(session, channel, key)
            return
        }

        val buffer = ByteBuffer.allocate(Constants.BUFFER_SIZE)
        val read = try { channel.read(buffer) } catch (e: IOException) { -1 }
        
        if (read == -1) return closeClient(key)

        buffer.flip()
        session.addToInput(buffer)
        
        while (!session.isUploading) {
            val line = session.extractLine() ?: break
            println("[${NetworkUtils.getTimestamp()}] Command from ${session.remoteAddr}: $line")
            
            currentSession = session
            currentKey = key
            if (!CommandProcessor.processLine(line, this)) break
        }
    }

    override fun execute(cmd: Command, args: List<String>): Boolean {
        val session = currentSession ?: return false
        val key = currentKey ?: return false

        when (cmd) {
            Command.TIME -> session.queueMsg(NetworkUtils.getTimestamp() + "\n")
            Command.ECHO -> session.queueMsg(args.joinToString(" ") + "\n")
            Command.LIST -> session.queueMsg("FILES ${CommandProcessor.getServerFilesList()}\n")
            Command.DOWNLOAD -> setupDownload(args, session)
            Command.UPLOAD -> setupUpload(args, session)
            Command.CLOSE -> {
                closeClient(key)
                return false
            }
            else -> session.queueMsg("ERROR: Unknown command\n")
        }
        if (key.isValid) key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
        return !session.isUploading
    }

    private fun setupDownload(args: List<String>, session: ClientSession) {
        val fileName = args.getOrNull(0) ?: return
        val file = File(Constants.SERVER_STORAGE, fileName)
        if (!file.exists()) return session.queueMsg("ERROR: Not found\n")
        val offset = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val safeOffset = Math.min(offset, file.length())
        session.fileRaf = RandomAccessFile(file, "r").apply { seek(safeOffset) }
        session.fileRemaining = file.length() - safeOffset
        session.transferStartTime = System.currentTimeMillis()
        session.transferTotalBytes = session.fileRemaining
        session.queueMsg("OK ${session.fileRemaining}\n")
    }

    private fun setupUpload(args: List<String>, session: ClientSession) {
        val name = args.getOrNull(0) ?: return
        val totalSize = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val offset = args.getOrNull(2)?.toLongOrNull() ?: 0L
        val file = File(Constants.SERVER_STORAGE, name)
        val actualOffset = if (file.exists()) Math.min(offset, file.length()) else 0L
        session.fileRaf = RandomAccessFile(file, "rw").apply { seek(actualOffset) }
        session.fileRemaining = totalSize - actualOffset
        session.isUploading = true
        session.transferStartTime = System.currentTimeMillis()
        session.transferTotalBytes = session.fileRemaining
        processRemainingBuffer(session)
        if (session.fileRemaining <= 0) finishUpload(session, null)
    }

    private fun processRemainingBuffer(session: ClientSession) {
        val leftover = session.inputAccumulator.toByteArray()
        if (leftover.isNotEmpty()) {
            val toWrite = Math.min(leftover.size.toLong(), session.fileRemaining).toInt()
            session.fileRaf?.write(leftover, 0, toWrite)
            session.fileRemaining -= toWrite
            session.inputAccumulator.reset()
            if (leftover.size > toWrite) {
                session.inputAccumulator.write(leftover, toWrite, leftover.size - toWrite)
            }
        }
    }

    private fun handleUploadChunk(session: ClientSession, channel: SocketChannel, key: SelectionKey) {
        val bufferSize = Math.min(Constants.BUFFER_SIZE.toLong(), session.fileRemaining).toInt()
        val buffer = ByteBuffer.allocate(bufferSize)
        val read = try { channel.read(buffer) } catch (e: IOException) { -1 }
        if (read > 0) {
            buffer.flip()
            session.fileRaf?.channel?.write(buffer)
            session.fileRemaining -= read
        }
        if (session.fileRemaining <= 0) finishUpload(session, key)
        else if (read == -1) closeClient(key)
    }

    private fun finishUpload(session: ClientSession, key: SelectionKey?) {
        val duration = System.currentTimeMillis() - session.transferStartTime
        val speed = (session.transferTotalBytes / 1024.0) / (Math.max(duration, 1) / 1000.0)
        println("[${NetworkUtils.getTimestamp()}] Upload complete for ${session.remoteAddr}. Speed: ${String.format("%.2f", speed)} KB/s")
        session.fileRaf?.close()
        session.fileRaf = null
        session.isUploading = false
        session.queueMsg("SUCCESS\n")
        key?.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
    }

    private fun doWrite(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        val channel = key.channel() as SocketChannel
        if (session.msgQueue.isNotEmpty()) {
            val buf = session.msgQueue.peek()
            channel.write(buf)
            if (!buf.hasRemaining()) session.msgQueue.poll()
        } else if (session.fileRaf != null && !session.isUploading) {
            sendDownloadChunk(session, channel)
        }
        if (session.msgQueue.isEmpty() && (session.fileRaf == null || session.isUploading)) {
            key.interestOps(SelectionKey.OP_READ)
        }
    }

    private fun sendDownloadChunk(session: ClientSession, channel: SocketChannel) {
        val bytes = ByteArray(Constants.BUFFER_SIZE)
        val toRead = Math.min(bytes.size.toLong(), session.fileRemaining).toInt()
        val read = try { session.fileRaf?.read(bytes, 0, toRead) ?: -1 } catch (e: Exception) { -1 }
        if (read > 0) {
            channel.write(ByteBuffer.wrap(bytes, 0, read))
            session.fileRemaining -= read
        }
        if (session.fileRemaining <= 0) {
            val duration = System.currentTimeMillis() - session.transferStartTime
            val speed = (session.transferTotalBytes / 1024.0) / (Math.max(duration, 1) / 1000.0)
            println("[${NetworkUtils.getTimestamp()}] Download complete for ${session.remoteAddr}. Speed: ${String.format("%.2f", speed)} KB/s")
            session.fileRaf?.close()
            session.fileRaf = null
        }
    }

    private fun closeClient(key: SelectionKey) {
        val session = (key.attachment() as? ClientSession) ?: return
        try {
            session.fileRaf?.close()
            key.channel().close()
            println("[${NetworkUtils.getTimestamp()}] Client disconnected: ${session.remoteAddr}")
        } catch (e: Exception) {}
        key.cancel()
    }

    private class ClientSession(val remoteAddr: String) {
        val inputAccumulator = ByteArrayOutputStream()
        val msgQueue: Queue<ByteBuffer> = LinkedList()
        var fileRaf: RandomAccessFile? = null
        var fileRemaining: Long = 0
        var isUploading: Boolean = false
        var transferStartTime: Long = 0
        var transferTotalBytes: Long = 0
        fun addToInput(buf: ByteBuffer) {
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            inputAccumulator.write(arr)
        }
        fun extractLine(): String? {
            val data = inputAccumulator.toByteArray()
            val idx = data.indexOf('\n'.code.toByte())
            if (idx == -1) return null
            val line = String(data, 0, idx, Charsets.UTF_8).trim()
            val remaining = if (idx < data.size - 1) data.copyOfRange(idx + 1, data.size) else byteArrayOf()
            inputAccumulator.reset()
            inputAccumulator.write(remaining)
            return line
        }
        fun queueMsg(txt: String) {
            msgQueue.add(ByteBuffer.wrap(txt.toByteArray(Charsets.UTF_8)))
        }
    }
}
