package sspoirs.common

import java.io.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NetworkUtils {
    
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun getTimestamp(): String = LocalDateTime.now().format(timeFormatter)

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
        } catch (e: Exception) {
            return listOf("Error detecting IP: ${e.message}")
        }

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
        var lastPrintTime = 0L
        var localLastOobTime = 0L
        val actualFullSize = if (fullSize > 0) fullSize else length
        val clientTag = socket?.remoteSocketAddress?.toString()?.takeLast(5) ?: "???"

        while (total < length) {
            val toRead = Math.min(buffer.size.toLong(), length - total).toInt()
            val read = try { input.read(buffer, 0, toRead) } catch (e: Exception) { -1 }
            if (read <= 0) break
            
            output.write(buffer, 0, read)
            total += read
            
            val now = System.currentTimeMillis()
            if (now - lastPrintTime > 1000) { 
                val currentTotal = offset + total
                val pct = if (actualFullSize > 0) (currentTotal * 100 / actualFullSize) else 0
                println("[Progress $clientTag] $currentTotal / $actualFullSize bytes ($pct%)")
                lastPrintTime = now
            }

            if (socket != null && actualFullSize > 0 && now - localLastOobTime > 1500) {
                val pct = (((offset + total) * 100) / actualFullSize).toInt()
                try { 
                    socket.sendUrgentData(pct) 
                } catch (e: Exception) {}
                localLastOobTime = now
            }
        }
        output.flush()
        val duration = Math.max(System.currentTimeMillis() - start, 1)
        val speed = (total / 1024.0) / (duration / 1000.0)
        println("[Transfer $clientTag] Complete at ${getTimestamp()}: $total bytes in ${duration}ms (${String.format("%.2f", speed)} KB/s)")
        return total
    }
}
