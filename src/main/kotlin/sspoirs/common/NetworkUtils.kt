package sspoirs.common

import java.io.*
import java.net.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NetworkUtils {
    
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val activeTransfers = ConcurrentHashMap<String, String>()
    private val lock = Any()

    // Единственный фоновый поток для отрисовки прогресса на СЕРВЕРЕ. Не тормозит сеть.
    private val statusUpdater = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "StatusUpdater").apply { isDaemon = true }
    }.apply {
        scheduleAtFixedRate({
            if (activeTransfers.isNotEmpty()) {
                synchronized(lock) { renderStatusLineInternal() }
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun getTimestamp(): String = LocalDateTime.now().format(timeFormatter)

    fun log(message: String) {
        synchronized(lock) {
            print("\r" + " ".repeat(120) + "\r") // Очистка строки прогресса
            println(message)
            if (activeTransfers.isNotEmpty()) renderStatusLineInternal()
        }
    }

    private fun renderStatusLineInternal() {
        val status = activeTransfers.entries.joinToString(" ") { "[${it.key}: ${it.value}]" }
        val limitedStatus = if (status.length > 115) status.take(112) + "..." else status
        print("\r$limitedStatus")
    }

    fun getLocalIpAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val iers = iface.inetAddresses
                while (iers.hasMoreElements()) {
                    val addr = iers.nextElement()
                    if (addr is Inet4Address) addresses.add("    ${iface.displayName}: ${addr.hostAddress}")
                }
            }
        } catch (e: Exception) { addresses.add("Error detecting IP: ${e.message}") }
        return addresses
    }

    // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: пробрасываем таймаут, чтобы сессия могла его обработать
    fun readLineBuffered(inputStream: InputStream): String? {
        val out = ByteArrayOutputStream()
        var hasData = false
        try {
            while (true) {
                val byte = inputStream.read()
                if (byte == -1) break
                hasData = true
                if (byte == '\n'.code) break
                if (byte == '\r'.code) continue
                out.write(byte)
            }
        } catch (e: SocketTimeoutException) {
            throw e // Пробрасываем для логики idle-сессий
        } catch (e: IOException) {
            return null // Остальные ошибки I/O означают разрыв соединения
        }
        return if (!hasData) null else out.toString("UTF-8")
    }

    fun writeLine(outputStream: OutputStream, text: String) {
        outputStream.write((text + Constants.LINE_SEPARATOR).toByteArray())
        outputStream.flush()
    }

    fun copyStream(input: InputStream, output: OutputStream, length: Long, socket: Socket? = null, offset: Long = 0L, fullSize: Long = -1L): Long {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var total: Long = 0
        val start = System.currentTimeMillis()
        val actualFullSize = if (fullSize > 0) fullSize else length
        
        val clientTag = socket?.remoteSocketAddress?.toString()?.split(":")?.lastOrNull() ?: "???"
        val isServerSide = socket != null && socket.localPort == Constants.DEFAULT_PORT

        try {
            while (total < length) {
                val toRead = minOf(buffer.size.toLong(), length - total).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) break
                
                output.write(buffer, 0, read)
                total += read
                
                if (actualFullSize > 0) {
                    val pct = ((offset + total) * 100 / actualFullSize).toInt()
                    if (isServerSide) {
                        // Только обновляем данные, отрисовку делает statusUpdater
                        activeTransfers[clientTag] = "$pct%"
                    } else {
                        // Для клиента выводим прогресс прямо здесь, но не слишком часто
                        if (total % (1024 * 256) == 0L || total == length) {
                            synchronized(lock) {
                                print("\r[Progress] $pct% (${offset + total} / $actualFullSize bytes)")
                            }
                        }
                    }
                }
            }
            output.flush()
        } finally {
            if (isServerSide) activeTransfers.remove(clientTag)
        }
        
        if (!isServerSide) println()
        val duration = maxOf(System.currentTimeMillis() - start, 1)
        val speed = (total / 1024.0) / (duration / 1000.0)
        log("[Transfer $clientTag] Finished: ${String.format("%.2f", speed)} KB/s")
        return total
    }
}
