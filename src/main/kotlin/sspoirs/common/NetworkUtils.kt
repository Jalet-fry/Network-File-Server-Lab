package sspoirs.common

import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object NetworkUtils {
    
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val activeTransfers = ConcurrentHashMap<String, String>()
    private val lock = Any() // Объект для синхронизации вывода в консоль

    fun getTimestamp(): String = LocalDateTime.now().format(timeFormatter)

    fun log(message: String) {
        synchronized(lock) {
            // Очищаем текущую строку статуса перед выводом лога (100 пробелов)
            print("\r" + " ".repeat(100) + "\r")
            println(message)
            if (activeTransfers.isNotEmpty()) renderStatusLineInternal()
        }
    }

    private fun renderStatusLineInternal() {
        val status = activeTransfers.entries.joinToString(" ") { "[${it.key}: ${it.value}]" }
        print("\r$status")
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
        while (true) {
            val byte = try { inputStream.read() } catch (e: IOException) { -1 }
            if (byte == -1) break
            hasData = true
            if (byte == '\n'.code) break
            if (byte == '\r'.code) continue
            out.write(byte)
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
        var lastPrintTime = start 
        var lastOobTime = start
        val actualFullSize = if (fullSize > 0) fullSize else length
        
        val clientTag = socket?.remoteSocketAddress?.toString()?.split(":")?.lastOrNull() ?: "???"
        val isServerSide = socket != null && socket.localPort == 8888 

        try {
            while (total < length) {
                val toRead = Math.min(buffer.size.toLong(), length - total).toInt()
                val read = try { input.read(buffer, 0, toRead) } catch (e: Exception) { -1 }
                if (read <= 0) break
                
                output.write(buffer, 0, read)
                total += read
                
                val now = System.currentTimeMillis()
                val pct = if (actualFullSize > 0) ((offset + total) * 100 / actualFullSize).toInt() else 0

                if (now - lastPrintTime > 500) { 
                    synchronized(lock) {
                        if (isServerSide) {
                            activeTransfers[clientTag] = "$pct%"
                            renderStatusLineInternal()
                        } else {
                            print("\r[Progress] $pct% (${offset + total} / $actualFullSize bytes)")
                        }
                    }
                    lastPrintTime = now
                }

                // Отправляем Urgent Data реже - раз в 5 секунд, чтобы не перегружать стек TCP
                if (socket != null && now - lastOobTime > 5000) {
                    try { socket.sendUrgentData(pct) } catch (e: Exception) {}
                    lastOobTime = now
                }
            }
        } finally {
            if (isServerSide) {
                activeTransfers.remove(clientTag)
                synchronized(lock) {
                    print("\r" + " ".repeat(100) + "\r")
                    if (activeTransfers.isNotEmpty()) renderStatusLineInternal()
                }
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
