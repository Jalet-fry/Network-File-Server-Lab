package sspoirs.common

import java.io.*
import java.net.Socket

object NetworkUtils {
    private var lastOobTime = 0L

    fun readLineBuffered(inputStream: InputStream): String? {
        val out = ByteArrayOutputStream()
        var hasData = false
        while (true) {
            val byte = try { inputStream.read() } catch (e: IOException) { -1 }
            if (byte == -1) break
            hasData = true
            if (byte == '\n'.toInt()) break
            if (byte == '\r'.toInt()) continue
            out.write(byte)
        }
        return if (!hasData) null else out.toString("UTF-8")
    }

    fun writeLine(outputStream: OutputStream, text: String) {
        outputStream.write((text + Constants.LINE_SEPARATOR).toByteArray())
        outputStream.flush()
    }

    // Добавлены параметры offset и fullSize для честного отображения прогресса
    fun copyStream(input: InputStream, output: OutputStream, length: Long, socket: Socket? = null, offset: Long = 0L, fullSize: Long = -1L): Long {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var total: Long = 0
        val start = System.currentTimeMillis()
        var lastPrintTime = 0L
        val actualFullSize = if (fullSize > 0) fullSize else length

        while (total < length) {
            val toRead = Math.min(buffer.size.toLong(), length - total).toInt()
            val read = try { input.read(buffer, 0, toRead) } catch (e: Exception) { -1 }
            if (read <= 0) break
            
            output.write(buffer, 0, read)
            total += read
            
            val now = System.currentTimeMillis()
            if (now - lastPrintTime > 300) {
                // Считаем прогресс относительно ВСЕГО файла
                val currentTotal = offset + total
                val pct = if (actualFullSize > 0) (currentTotal * 100 / actualFullSize) else 0
                print("\r[Progress] $currentTotal / $actualFullSize bytes ($pct%)")
                lastPrintTime = now
            }
            
            handleOobProgress(socket, offset + total, actualFullSize)
        }
        output.flush()
        val duration = System.currentTimeMillis() - start
        printStats(total, duration)
        return total
    }

    private fun handleOobProgress(socket: Socket?, current: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (socket != null && total > 0 && now - lastOobTime > 1500) {
            val pct = ((current * 100) / total).toInt()
            try { socket.sendUrgentData(pct) } catch (e: Exception) {}
            lastOobTime = now
        }
    }

    private fun printStats(bytes: Long, ms: Long) {
        if (bytes <= 0) return
        val speed = (bytes / 1024.0) / (Math.max(ms, 1) / 1000.0)
        println("\n[Transfer] Part complete: $bytes bytes in ${ms}ms (${String.format("%.2f", speed)} KB/s)")
    }
}
