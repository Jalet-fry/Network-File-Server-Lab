package sspoirs.common

import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NetworkUtils {
    
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val activeTransfers = ConcurrentHashMap<String, String>()
    private val lock = Any() 

    // Отдельный поток для обновления строки состояния, чтобы не тормозить передачу данных
    private val statusUpdater = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "StatusUpdater").apply { isDaemon = true }
    }.apply {
        scheduleAtFixedRate({
            if (activeTransfers.isNotEmpty()) {
                synchronized(lock) {
                    renderStatusLineInternal()
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun getTimestamp(): String = LocalDateTime.now().format(timeFormatter)

    fun log(message: String) {
        synchronized(lock) {
            // Очищаем текущую строку статуса перед выводом лога
            print("\r" + " ".repeat(120) + "\r")
            println(message)
            if (activeTransfers.isNotEmpty()) renderStatusLineInternal()
        }
    }

    private fun renderStatusLineInternal() {
        val status = activeTransfers.entries.joinToString(" ") { "[${it.key}: ${it.value}]" }
        // Обрезаем, если слишком длинная для консоли
        val limitedStatus = if (status.length > 115) status.take(112) + "..." else status
        print("\r$limitedStatus")
    }

    fun getLocalIpAddresses(): List<String> {
        val addresses = mutableListOf<Triple<Int, String, String>>()
        var primaryIp: String? = null
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                primaryIp = socket.localAddress.hostAddress
            }
        } catch (e: Exception) { }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val iers = iface.inetAddresses
                while (iers.hasMoreElements()) {
                    val addr = iers.nextElement()
                    val ip = addr.hostAddress
                    if (ip.contains(":")) continue 
                    val name = iface.displayName
                    val nameLower = name.lowercase()
                    var priority = 50 
                    if (nameLower.contains("wi-fi") || nameLower.contains("wireless") || nameLower.contains("wlan") || nameLower.contains("rz608")) {
                        priority = 100 
                    } else if (nameLower.contains("ethernet") && !nameLower.contains("virtual")) {
                        priority = 90
                    } else if (nameLower.contains("virtual") || nameLower.contains("vbox") || nameLower.contains("vmware")) {
                        priority = 20
                    } else if (nameLower.contains("tunnel") || nameLower.contains("hide.me") || nameLower.contains("vpn")) {
                        priority = 10
                    }
                    if (ip == primaryIp) priority += 5
                    addresses.add(Triple(priority, name, ip))
                }
            }
        } catch (e: Exception) { return listOf("Error detecting IP: ${e.message}") }
        val sorted = addresses.sortedByDescending { it.first }
        val topPriority = sorted.firstOrNull()?.first ?: 0
        return sorted.map { (priority, name, ip) ->
            if (priority == topPriority && priority > 50) ">>> [RECOMMENDED] $name: $ip"
            else "    $name: $ip"
        }
    }

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
        } catch (e: IOException) {
            return null // При любой ошибке I/O считаем, что связь потеряна
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
                val toRead = Math.min(buffer.size.toLong(), length - total).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) break
                
                output.write(buffer, 0, read)
                total += read
                
                // Только обновляем данные, отрисовку делает statusUpdater
                if (actualFullSize > 0) {
                    val pct = ((offset + total) * 100 / actualFullSize).toInt()
                    if (isServerSide) {
                        activeTransfers[clientTag] = "$pct%"
                    } else {
                        // Для клиента выводим реже прямо здесь, так как он обычно один
                        if (total % (1024 * 1024) == 0L || total == length) {
                             print("\r[Progress] $pct% (${offset + total} / $actualFullSize bytes)")
                        }
                    }
                }
            }
        } finally {
            if (isServerSide) {
                activeTransfers.remove(clientTag)
            }
        }

        output.flush()
        if (!isServerSide) println() 
        
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        val speed = (total / 1024.0) / (duration / 1000.0)
        
        log("[Transfer $clientTag] Finished: ${String.format("%.2f", speed)} KB/s")
        return total
    }
}
