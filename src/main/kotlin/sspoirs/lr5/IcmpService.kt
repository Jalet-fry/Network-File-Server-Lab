package sspoirs.lr5

import com.sun.jna.Memory
import com.sun.jna.Platform
import com.sun.jna.ptr.IntByReference
import sspoirs.common.NetworkUtils
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class IcmpService : AutoCloseable {
    private val net = NativeNet.instance
    private val socket = net.socket(NativeNet.AF_INET, NativeNet.SOCK_RAW, NativeNet.IPPROTO_ICMP)
    private val dontWaitFlag = if (Platform.isWindows()) 0 else 0x40

    init {
        if (socket < 0) {
            val err = net.getLastError()
            throw RuntimeException("Failed to create raw socket. Error code: $err. Must run as Admin/Root.")
        }
        setSocketTimeout(800)
    }

    private fun setSocketTimeout(ms: Int) {
        val tv = if (Platform.isWindows()) {
            Memory(4).apply { setInt(0, ms) }
        } else {
            Memory(16).apply {
                setLong(0, (ms / 1000).toLong())
                setLong(8, ((ms % 1000) * 1000).toLong())
            }
        }
        net.setsockopt(socket, net.getSolSocket(), net.getSoRcvTimeo(), tv, tv.size().toInt())
    }

    override fun close() {
        net.close(socket)
    }

    fun parallelPing(hosts: List<String>) {
        flushSocket()
        val executor = Executors.newFixedThreadPool(hosts.size)
        println("Starting parallel ping for ${hosts.size} hosts...")

        hosts.forEach { host ->
            executor.execute {
                try {
                    val result = pingHost(host)
                    NetworkUtils.log("[PING] $host: $result")
                } catch (e: Exception) {
                    NetworkUtils.log("[PING] $host: Error - ${e.message}")
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun pingHost(host: String): String {
        val targetAddr = InetAddress.getByName(host)
        val id = (Random.nextInt(0, 0xFFFF)).toShort()
        val sendTime = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(8).putLong(sendTime).array()
        val request = IcmpPacket.createEchoRequest(id, 1, payload).toByteArray()
        
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = targetAddr.address
        }

        val memReq = Memory(request.size.toLong()).apply { write(0, request, 0, request.size) }
        net.sendto(socket, memReq, request.size, 0, dest, dest.size())

        val buffer = Memory(65536)
        val fromAddr = NativeNet.SockAddrIn()
        val fromLen = IntByReference(fromAddr.size())
        
        while (System.currentTimeMillis() - sendTime < 1500) {
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), NativeNet.MSG_PEEK or dontWaitFlag, fromAddr, fromLen)
            if (bytesRead > 0) {
                val data = buffer.getByteArray(0, bytesRead)
                val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                if (data.size >= ipHeaderLen + 8) {
                    val icmp = try { IcmpPacket.parse(data, ipHeaderLen) } catch (e: Exception) { null }
                    if (icmp != null && icmp.type == IcmpPacket.TYPE_ECHO_REPLY && icmp.identifier == id) {
                        net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
                        val rtt = System.currentTimeMillis() - sendTime
                        val fromIp = InetAddress.getByAddress(fromAddr.sin_addr).hostAddress
                        return "Reply from $fromIp: bytes=${icmp.data.size} RTT=${rtt}ms"
                    }
                }
            }
            Thread.sleep(5)
        }
        return "Request timed out"
    }

    fun traceroute(host: String, maxHops: Int = 30) {
        flushSocket()
        val targetAddr = InetAddress.getByName(host)
        println("Traceroute to $host (${targetAddr.hostAddress}), $maxHops hops max:")

        for (ttl in 1..maxHops) {
            val result = probeHop(targetAddr, ttl)
            println("${ttl.toString().padStart(2)}  $result")
            if (result.contains("Reached")) break
        }
    }

    private fun probeHop(target: InetAddress, ttl: Int): String {
        val ttlPtr = Memory(4).apply { setInt(0, ttl) }
        net.setsockopt(socket, net.getIpProtoIp(), net.getIpTtl(), ttlPtr, 4)
        
        val id = (ttl + 2000).toShort()
        val startTime = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(8).putLong(startTime).array()
        val request = IcmpPacket.createEchoRequest(id, 1, payload).toByteArray()
        
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = target.address
        }

        val memReq = Memory(request.size.toLong()).apply { write(0, request, 0, request.size) }
        net.sendto(socket, memReq, request.size, 0, dest, dest.size())
        
        val buffer = Memory(65536)
        val fromAddr = NativeNet.SockAddrIn()
        val fromLen = IntByReference(fromAddr.size())

        while (System.currentTimeMillis() - startTime < 1000) {
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, fromAddr, fromLen)
            if (bytesRead > 0) {
                val endTime = System.currentTimeMillis()
                val data = buffer.getByteArray(0, bytesRead)
                val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                val icmp = try { IcmpPacket.parse(data, ipHeaderLen) } catch (e: Exception) { null }
                
                if (icmp != null) {
                    val hopIp = InetAddress.getByAddress(fromAddr.sin_addr).hostAddress
                    val rtt = endTime - startTime
                    if (icmp.type == IcmpPacket.TYPE_ECHO_REPLY && icmp.identifier == id) {
                        return "$hopIp (Reached) - $rtt ms"
                    }
                    if (icmp.type == IcmpPacket.TYPE_TIME_EXCEEDED) {
                        return "$hopIp - $rtt ms"
                    }
                }
            }
            Thread.sleep(5)
        }
        return "* * *"
    }

    private fun flushSocket() {
        val buffer = Memory(65536)
        while (true) {
            val read = net.recvfrom(socket, buffer, buffer.size().toInt(), 0x40, null, IntByReference(0))
            if (read <= 0) break
        }
    }

    fun sendRawIpPacket(sourceIp: String, destIp: String, count: Int = 10) {
        val rawSocket = net.socket(NativeNet.AF_INET, NativeNet.SOCK_RAW, NativeNet.IPPROTO_RAW)
        if (rawSocket < 0) {
            println("[ERROR] Failed to open IPPROTO_RAW, errno: ${net.getLastError()}")
            return
        }

        val one = Memory(4).apply { setInt(0, 1) }
        net.setsockopt(rawSocket, net.getIpProtoIp(), net.getIpHdrIncl(), one, 4)
        
        println("Sending $count raw packets: Source=$sourceIp -> Dest=$destIp")
        
        val icmpReq = IcmpPacket.createEchoRequest(0x1337, 1, "PROBE".toByteArray()).toByteArray()
        val ipHeader = IpHeader(sourceIp, destIp).toByteArray(icmpReq.size)
        val packet = ipHeader + icmpReq
        
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = InetAddress.getByName(destIp).address
        }

        val memPacket = Memory(packet.size.toLong()).apply { write(0, packet, 0, packet.size) }

        repeat(count) {
            net.sendto(rawSocket, memPacket, packet.size, 0, dest, dest.size())
            Thread.sleep(50)
        }
        
        net.close(rawSocket)
        println("Transmission finished.")
    }
}