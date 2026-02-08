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
        println("[SERVER] TCP Multiplexed Server (Lab 1-3) started on port $port...")
        
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
        client.register(selector, SelectionKey.OP_READ, ClientSession(client.remoteAddress.toString()))
        println("[SERVER] New connection from ${client.remoteAddress}")
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
        
        if (read == -1) {
            println("[SERVER] Client ${session.remoteAddr} disconnected")
            return closeClient(key)
        }

        buffer.flip()
        session.addToInput(buffer)
        processInput(key, session)
    }

    private fun processInput(key: SelectionKey, session: ClientSession) {
        // Читаем команды в цикле, пока они есть в буфере
        while (true) {
            if (session.isUploading) break
            val line = session.extractLine() ?: break
            println("[SERVER] Command from ${session.remoteAddr}: $line")
            
            val parts = line.split(Regex("\\s+"))
            val cmd = Command.fromString(parts[0])
            val args = parts.drop(1)
            
            executeCommand(cmd, args, key, session)
        }
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
        
        // КРИТИЧЕСКИЙ ФИКС: Если в буфере уже есть данные файла, записываем их
        val remainingData = session.inputAccumulator.toByteArray()
        if (remainingData.isNotEmpty()) {
            val toWrite = Math.min(remainingData.size.toLong(), session.fileRemaining).toInt()
            session.fileRaf?.write(remainingData, 0, toWrite)
            session.fileRemaining -= toWrite
            
            session.inputAccumulator.reset()
            if (remainingData.size > toWrite) {
                session.inputAccumulator.write(remainingData, toWrite, remainingData.size - toWrite)
            }
        }
        
        if (session.fileRemaining <= 0) finishUpload(session, SelectionKey.OP_READ.let { key -> null } ?: return ) // Handle instant small file
    }

    private fun handleUploadChunk(session: ClientSession, channel: SocketChannel, key: SelectionKey) {
        val buffer = ByteBuffer.allocate(Math.min(Constants.BUFFER_SIZE.toLong(), session.fileRemaining).toInt())
        val read = channel.read(buffer)
        if (read > 0) {
            buffer.flip()
            session.fileRaf?.channel?.write(buffer)
            session.fileRemaining -= read
        }
        if (session.fileRemaining <= 0 || read == -1) {
            finishUpload(session, key)
        }
    }

    private fun finishUpload(session: ClientSession, key: SelectionKey) {
        println("[SERVER] Upload complete for ${session.remoteAddr}")
        session.fileRaf?.close()
        session.fileRaf = null
        session.isUploading = false
        session.queueMsg("SUCCESS\n")
        key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
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
        }
        if (session.fileRemaining <= 0) {
            session.fileRaf?.close()
            session.fileRaf = null
        }
    }

    private fun closeClient(key: SelectionKey) {
        val session = key.attachment() as ClientSession
        session.fileRaf?.close()
        key.channel().close()
        key.cancel()
    }

    private class ClientSession(val remoteAddr: String) {
        val inputAccumulator = ByteArrayOutputStream()
        val msgQueue: Queue<ByteBuffer> = LinkedList()
        var fileRaf: RandomAccessFile? = null
        var fileRemaining: Long = 0
        var isUploading: Boolean = false

        fun addToInput(buf: ByteBuffer) {
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            inputAccumulator.write(arr)
        }

        fun extractLine(): String? {
            val data = inputAccumulator.toByteArray()
            var newlineIdx = -1
            for (i in data.indices) {
                if (data[i] == '\n'.toByte()) {
                    newlineIdx = i
                    break
                }
            }
            if (newlineIdx == -1) return null
            
            val line = String(data, 0, newlineIdx).trim()
            val remaining = if (newlineIdx < data.size - 1) data.copyOfRange(newlineIdx + 1, data.size) else byteArrayOf()
            inputAccumulator.reset()
            inputAccumulator.write(remaining)
            return line
        }

        fun queueMsg(txt: String) {
            msgQueue.add(ByteBuffer.wrap(txt.toByteArray()))
        }
    }
}
