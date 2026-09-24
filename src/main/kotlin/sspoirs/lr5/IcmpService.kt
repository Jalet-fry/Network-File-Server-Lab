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
        executor.awaitTermination(1, TimeUnit.MINUTES)
    }

    private fun pingHost(host: String): String {
        val targetAddr = InetAddress.getByName(host)
        val id = (Random.nextInt(0, 0xFFFF)).toShort()
        val seq: Short = 1
        
        val sendTime = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(8).putLong(sendTime).array()
        val request = IcmpPacket.createEchoRequest(id, seq, payload).toByteArray()
        
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = targetAddr.address
        }

        val memReq = Memory(request.size.toLong()).apply { write(0, request, 0, request.size) }
        val sent = net.sendto(socket, memReq, request.size, 0, dest, dest.size())
        if (sent < 0) return "Send failed: ${net.getLastError()}"

        val buffer = Memory(65536)
        
        while (System.currentTimeMillis() - sendTime < 3000) {
            val fromAddr = NativeNet.SockAddrIn()
            val fromLen = IntByReference(fromAddr.size())
            val peekFlags = NativeNet.MSG_PEEK or dontWaitFlag
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), peekFlags, fromAddr, fromLen)
            
            if (bytesRead > 0) {
                val data = buffer.getByteArray(0, bytesRead)
                val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                
                if (data.size >= ipHeaderLen + 8) {
                    val icmp = try { IcmpPacket.parse(data, ipHeaderLen) } catch (e: Exception) { null }
                    
                    if (icmp != null) {
                        if (icmp.type == IcmpPacket.TYPE_ECHO_REPLY && icmp.identifier == id) {
                            net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
                            val rcvTimestamp = if (icmp.data.size >= 8) ByteBuffer.wrap(icmp.data).long else 0L
                            val rtt = if (rcvTimestamp > 0) System.currentTimeMillis() - rcvTimestamp else System.currentTimeMillis() - sendTime
                            val fromIp = InetAddress.getByAddress(fromAddr.sin_addr).hostAddress
                            return "Reply from $fromIp: bytes=${icmp.data.size} RTT=${rtt}ms"
                        }

                        val isStale = if (icmp.data.size >= 8) {
                            val packetTs = ByteBuffer.wrap(icmp.data).long
                            (System.currentTimeMillis() - packetTs) > 5000
                        } else false

                        if (isStale || icmp.type != IcmpPacket.TYPE_ECHO_REPLY) {
                            net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
                            continue
                        }
                    } else {
                        net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
                        continue
                    }
                } else {
                    net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
                    continue
                }
            }
            Thread.sleep(2)
        }
        
        return "Request timed out"
    }

    private fun flushSocket() {
        val buffer = Memory(65536)
        while (true) {
            val read = net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, null, IntByReference(0))
            if (read <= 0) break
        }
    }

    fun traceroute(host: String, maxHops: Int = 30) {
        flushSocket()
        val targetAddr = InetAddress.getByName(host)
        println("Traceroute to $host (${targetAddr.hostAddress}), $maxHops hops max:")

        for (ttl in 1..maxHops) {
            val result = probeHop(targetAddr, ttl)
            println("${ttl.toString().padStart(2)}  $result")
            if (result.contains("Reached") || result.contains("Echo Reply")) break
        }
    }

    private fun probeHop(target: InetAddress, ttl: Int): String {
        val ttlPtr = Memory(4).apply { setInt(0, ttl) }
        net.setsockopt(socket, net.getIpProtoIp(), net.getIpTtl(), ttlPtr, 4)
        
        val id = (ttl + 1000).toShort()
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

        while (System.currentTimeMillis() - startTime < 2000) {
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), dontWaitFlag, fromAddr, fromLen)
            if (bytesRead > 0) {
                val endTime = System.currentTimeMillis()
                val data = buffer.getByteArray(0, bytesRead)
                val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                val icmp = try { IcmpPacket.parse(data, ipHeaderLen) } catch (e: Exception) { null }
                
                if (icmp != null) {
                    val hopIp = InetAddress.getByAddress(fromAddr.sin_addr).hostAddress
                    val rtt = endTime - startTime
                    
                    when (icmp.type) {
                        IcmpPacket.TYPE_ECHO_REPLY -> {
                            if (icmp.identifier == id) return "$hopIp (Reached) - $rtt ms"
                        }
                        IcmpPacket.TYPE_TIME_EXCEEDED -> return "$hopIp - $rtt ms"
                        IcmpPacket.TYPE_DEST_UNREACHABLE -> return "$hopIp (Unreachable) - $rtt ms"
                    }
                }
            }
            Thread.sleep(2)
        }
        return "* * *"
    }

    fun sendRawIpPacket(sourceIp: String, destIp: String, count: Int = 10) {
        val rawSocket = net.socket(NativeNet.AF_INET, NativeNet.SOCK_RAW, NativeNet.IPPROTO_RAW)
        if (rawSocket < 0) {
            val err = net.getLastError()
            println("[ERROR] Failed to open IPPROTO_RAW socket (errno: $err). Must run as root/admin.")
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
            val res = net.sendto(rawSocket, memPacket, packet.size, 0, dest, dest.size())
            if (res < 0) {
                println("[WARN] sendto failed (errno: ${net.getLastError()})")
            }
            Thread.sleep(50)
        }
        
        net.close(rawSocket)
        println("Raw packet transmission finished.")
    }
}