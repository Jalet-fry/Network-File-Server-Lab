package sspoirs.lr5

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Native interface for socket operations.
 * Handles Windows (WinSock2) and Linux (LibC) differences.
 */
interface NativeNet {

    data class SockAddrIn(
        @JvmField var sin_family: Short = 2, // AF_INET
        @JvmField var sin_port: Short = 0,
        @JvmField var sin_addr: ByteArray = ByteArray(4),
        @JvmField var sin_zero: ByteArray = ByteArray(8)
    ) : Structure(), Structure.ByReference {
        override fun getFieldOrder() = listOf("sin_family", "sin_port", "sin_addr", "sin_zero")
    }

    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun sendto(s: Int, buf: Pointer, len: Int, flags: Int, to: Structure, tolen: Int): Int
    fun recvfrom(s: Int, buf: Pointer, len: Int, flags: Int, from: Structure?, fromlen: IntByReference): Int
    fun setsockopt(s: Int, level: Int, optname: Int, optval: Pointer, optlen: Int): Int
    fun close(s: Int): Int
    fun getLastError(): Int
    fun getSolSocket(): Int
    fun getSoRcvTimeo(): Int
    fun getIpHdrIncl(): Int
    fun getIpTtl(): Int
    fun getIpProtoIp(): Int

    companion object {
        const val AF_INET = 2
        const val SOCK_RAW = 3
        const val IPPROTO_IP = 0
        const val IPPROTO_ICMP = 1
        const val IPPROTO_RAW = 255
        const val MSG_PEEK = 0x2

        val instance: NativeNet by lazy {
            if (Platform.isWindows()) WinNet() else LinuxNet()
        }
    }
}

// Windows Implementation
interface WinSock2 : StdCallLibrary {
    fun socket(af: Int, type: Int, protocol: Int): Int
    fun sendto(s: Int, buf: Pointer, len: Int, flags: Int, to: Structure, tolen: Int): Int
    fun recvfrom(s: Int, buf: Pointer, len: Int, flags: Int, from: Structure?, fromlen: IntByReference): Int
    fun setsockopt(s: Int, level: Int, optname: Int, optval: Pointer, optlen: Int): Int
    fun closesocket(s: Int): Int
    fun WSAGetLastError(): Int
    
    class WSAData : Structure() {
        @JvmField var wVersion: Short = 0
        @JvmField var wHighVersion: Short = 0
        @JvmField var szDescription: ByteArray = ByteArray(257)
        @JvmField var szSystemStatus: ByteArray = ByteArray(129)
        @JvmField var iMaxSockets: Short = 0
        @JvmField var iMaxUdpDg: Short = 0
        @JvmField var lpVendorInfo: Pointer? = null
        override fun getFieldOrder() = listOf("wVersion", "wHighVersion", "szDescription", "szSystemStatus", "iMaxSockets", "iMaxUdpDg", "lpVendorInfo")
    }
    fun WSAStartup(wVersionRequested: Short, lpWSAData: WSAData): Int
}

class WinNet : NativeNet {
    private val lib = Native.load("ws2_32", WinSock2::class.java)
    
    init {
        val wsaData = WinSock2.WSAData()
        lib.WSAStartup(0x0202.toShort(), wsaData)
    }

    override fun socket(domain: Int, type: Int, protocol: Int) = lib.socket(domain, type, protocol)
    override fun sendto(s: Int, buf: Pointer, len: Int, flags: Int, to: Structure, tolen: Int) = lib.sendto(s, buf, len, flags, to, tolen)
    override fun recvfrom(s: Int, buf: Pointer, len: Int, flags: Int, from: Structure?, fromlen: IntByReference) = lib.recvfrom(s, buf, len, flags, from, fromlen)
    override fun setsockopt(s: Int, level: Int, optname: Int, optval: Pointer, optlen: Int) = lib.setsockopt(s, level, optname, optval, optlen)
    override fun close(s: Int) = lib.closesocket(s)
    override fun getLastError() = lib.WSAGetLastError()
    override fun getSolSocket() = SOL_SOCKET
    override fun getSoRcvTimeo() = SO_RCVTIMEO
    override fun getIpHdrIncl() = IP_HDRINCL
    override fun getIpTtl() = IP_TTL
    override fun getIpProtoIp() = IPPROTO_IP
    
    companion object {
        const val IPPROTO_IP = 0
        const val SOL_SOCKET = 0xffff
        const val SO_RCVTIMEO = 0x1006
        const val IP_HDRINCL = 2
        const val IP_TTL = 4
    }
}

// Linux Implementation
interface LibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun sendto(s: Int, buf: Pointer, len: Int, flags: Int, to: Structure, tolen: Int): Int
    fun recvfrom(s: Int, buf: Pointer, len: Int, flags: Int, from: Structure?, fromlen: IntByReference): Int
    fun setsockopt(s: Int, level: Int, optname: Int, optval: Pointer, optlen: Int): Int
    fun close(fd: Int): Int
    fun __errno_location(): Pointer
}

class LinuxNet : NativeNet {
    private val lib = Native.load("c", LibC::class.java)

    override fun socket(domain: Int, type: Int, protocol: Int) = lib.socket(domain, type, protocol)
    override fun sendto(s: Int, buf: Pointer, len: Int, flags: Int, to: Structure, tolen: Int) = lib.sendto(s, buf, len, flags, to, tolen)
    override fun recvfrom(s: Int, buf: Pointer, len: Int, flags: Int, from: Structure?, fromlen: IntByReference) = lib.recvfrom(s, buf, len, flags, from, fromlen)
    override fun setsockopt(s: Int, level: Int, optname: Int, optval: Pointer, optlen: Int) = lib.setsockopt(s, level, optname, optval, optlen)
    override fun close(s: Int) = lib.close(s)
    override fun getLastError() = lib.__errno_location().getInt(0)
    override fun getSolSocket() = SOL_SOCKET
    override fun getSoRcvTimeo() = SO_RCVTIMEO
    override fun getIpHdrIncl() = IP_HDRINCL
    override fun getIpTtl() = IP_TTL
    override fun getIpProtoIp() = IPPROTO_IP

    companion object {
        const val IPPROTO_IP = 0
        const val SOL_SOCKET = 1
        const val SO_RCVTIMEO = 20
        const val IP_HDRINCL = 3
        const val IP_TTL = 2
    }
}