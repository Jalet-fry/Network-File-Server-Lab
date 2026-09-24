# ==============================================================================
# Apply-Fixes.ps1 — Автоматическое обновление кода и скриптов ЛР 5
# ==============================================================================
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $root) { $root = Get-Location }

Write-Host "Обновление проекта в: $root" -ForegroundColor Cyan

# Хелпер для записи UTF-8 без BOM и с правильными переводами строк
function Write-TextFile([string]$path, [string]$content, [bool]$isLinux = $false) {
    $fullPath = Join-Path $root $path
    $dir = [System.IO.Path]::GetDirectoryName($fullPath)
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    
    if ($isLinux) {
        $content = $content.Replace("`r`n", "`n")
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($fullPath, $content, $encoding)
    Write-Host "  [OK] Записан: $path" -ForegroundColor Green
}

# ------------------------------------------------------------------------------
# 1. Main.kt
# ------------------------------------------------------------------------------
$mainKt = @'
package sspoirs

import sspoirs.common.Constants
import sspoirs.lr1.SimpleClient
import sspoirs.lr1.TcpCommandServer
import sspoirs.lr2.UdpClient
import sspoirs.lr2.UdpServer
import sspoirs.lr4.TcpThreadPoolServer
import sspoirs.lr5.IcmpService
import sspoirs.lr6.NetworkDiscovery
import sspoirs.lr6.P2PChat
import sspoirs.lr7.MatrixMult
import sspoirs.lr8.MatrixMultGroups
import sspoirs.mpi.Mpi
import java.io.RandomAccessFile
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    Constants.initDirs()

    if (args.isEmpty()) {
        printHelp()
        return
    }

    val mode = args.getOrNull(0)
    val protocolStr = args.getOrNull(1) ?: "1"
    val protocol = if (protocolStr == "2") Constants.Protocol.UDP else Constants.Protocol.TCP
    val port = args.getOrNull(2)?.toIntOrNull() ?: Constants.DEFAULT_PORT

    println("=== Network File Server (SSPOiRS) ===")

    when (mode) {
        "1" -> startServer(protocol, port, isThreadPool = false)
        "3" -> startServer(protocol, port, isThreadPool = true)
        "2" -> startClient(protocol, port, args.getOrNull(3) ?: Constants.DEFAULT_HOST)
        "5" -> handleLr5(args.drop(1))
        "6" -> handleLr6(args.drop(1))
        "7" -> handleLr7(args.drop(1))
        "8" -> handleLr8(args.drop(1))
        "mpirun" -> handleLocalMpiRun(args.drop(1))
        "gen" -> generateMatrixFiles(args.drop(1))
        "list-ifaces" -> {
            println("Available interfaces:")
            NetworkDiscovery.listInterfaces().forEachIndexed { idx, p ->
                println("  $idx: ${p.interfaceName} - IP: ${p.ip}, Broadcast: ${p.broadcast}")
            }
        }
        else -> printHelp()
    }
}

private fun printHelp() {
    println("""
        Usage: run.bat <mode> [args...]
        MODES:
          1 <proto> [port]            = Sequential Server (Lab 1/3)
          2 <proto> [port] [host]     = Client (Lab 1/2)
          3 <proto> [port]            = Thread Pool Server (Lab 4)
          5 <submode> [args...]       = ICMP Utils (Lab 5: ping, trace, raw)
          6 [manual_ip]               = P2P Chat (Lab 6)
          7 rank=N hosts=h1,h2...     = MPI Matrix Mult (Lab 7)
          8 rank=N hosts=h1,h2...     = MPI Groups & IO (Lab 8)
          mpirun <np> <mode 7|8> [...] = Auto-launch N local processes for MPI
          gen size=N                  = Generate binary matrix files
          list-ifaces                 = List available network interfaces
    """.trimIndent())
}

private fun handleLocalMpiRun(args: List<String>) {
    val np = args.getOrNull(0)?.toIntOrNull() ?: 3
    val targetMode = args.getOrNull(1) ?: "7"
    val restArgs = args.drop(2)

    val hosts = (0 until np).joinToString(",") { "127.0.0.1" }
    val javaBin = System.getProperty("java.home") + "/bin/java"
    val classpath = System.getProperty("java.class.path")

    println("=== [MPIRUN] Launching $np local processes on 127.0.0.1 for Lab $targetMode ===")

    val processes = mutableListOf<Process>()
    for (rank in 0 until np) {
        val cmd = mutableListOf(
            javaBin, "-cp", classpath, "sspoirs.MainKt",
            targetMode, "rank=$rank", "hosts=$hosts"
        )
        cmd.addAll(restArgs)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        processes.add(proc)

        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> println("[Proc-$rank] $line") }
            }
        }.start()
    }

    processes.forEach { it.waitFor() }
    println("=== [MPIRUN] All processes finished. ===")
}

private fun handleLr7(args: List<String>) {
    Mpi.init(args)
    MatrixMult().run(args)
    Mpi.finalize()
}

private fun handleLr8(args: List<String>) {
    Mpi.init(args)
    MatrixMultGroups().run(args)
    Mpi.finalize()
}

private fun generateMatrixFiles(args: List<String>) {
    val size = args.find { it.startsWith("size=") }?.substringAfter("=")?.toIntOrNull() ?: 600
    println("Generating $size x $size matrix files (matrixA.bin, matrixB.bin)...")

    fun writeMat(path: String) {
        val file = RandomAccessFile(path, "rw")
        val buffer = ByteBuffer.allocate(size * 8)
        for (i in 0 until size) {
            buffer.clear()
            repeat(size) { buffer.putDouble(Math.random()) }
            file.write(buffer.array())
        }
        file.close()
    }

    writeMat("matrixA.bin")
    writeMat("matrixB.bin")
    println("Done.")
}

private fun handleLr5(args: List<String>) {
    val submode = args.getOrNull(0)
    if (submode == null) {
        println("LR5 Submodes: ping, trace, raw")
        return
    }

    try {
        val icmp = IcmpService()
        when (submode) {
            "ping" -> icmp.parallelPing(args.drop(1))
            "trace" -> icmp.traceroute(args.getOrNull(1) ?: "8.8.8.8")
            "raw", "smurf" -> {
                val src = args.getOrNull(1) ?: return println("Source IP required")
                val dst = args.getOrNull(2) ?: return println("Destination IP required")
                val count = args.getOrNull(3)?.toIntOrNull() ?: 10
                icmp.sendRawIpPacket(src, dst, count)
            }
            else -> println("Unknown submode: $submode")
        }
        icmp.close()
    } catch (e: Exception) {
        println("[ERROR] ICMP Service: ${e.message}")
    }
}

private fun startServer(protocol: Constants.Protocol, port: Int, isThreadPool: Boolean) {
    try {
        if (isThreadPool) {
            TcpThreadPoolServer(port).start()
        } else {
            if (protocol == Constants.Protocol.TCP) TcpCommandServer(port).start()
            else UdpServer(port).start()
        }
    } catch (e: Exception) {
        println("[ERROR] Server failed: ${e.message}")
    }
}

private fun startClient(protocol: Constants.Protocol, port: Int, host: String) {
    try {
        if (protocol == Constants.Protocol.TCP) {
            SimpleClient(host, port).start()
        } else {
            UdpClient(host, port).start()
        }
    } catch (e: Exception) {
        println("[ERROR] Client failed: ${e.message}")
    }
}

private fun handleLr6(args: List<String>) {
    var params = NetworkDiscovery.discover(args.getOrNull(0))

    if (params == null || params.broadcast == "N/A" || params.broadcast == "0.0.0.0") {
        println("Auto-discovery failed or found virtual interface.")
        println("Available interfaces:")
        val list = NetworkDiscovery.listInterfaces()
        list.forEachIndexed { idx, p ->
            println("  $idx: ${p.interfaceName} - IP: ${p.ip}, Broadcast: ${p.broadcast}")
        }
        print("Enter interface number (or IP manually): ")

        val input = readLine()?.trim() ?: return
        val selected = input.toIntOrNull()
        if (selected != null && selected in list.indices) {
            params = list[selected]
        } else {
            params = NetworkDiscovery.discover(input)
        }

        if (params == null) {
            println("Could not determine network parameters. Exiting.")
            return
        }
    }

    println("Network: IP=${params.ip}, Mask=${params.mask}, Broadcast=${params.broadcast} (${params.interfaceName})")

    val chat = P2PChat(params)
    println("P2P Chat Started. Commands: /mode <b|m>, /list, /ignore <ip>, /leave, /exit")

    while (true) {
        print("> ")
        val line = readLine() ?: break
        if (line == "/exit") break
        if (line.startsWith("/")) {
            processChatCommand(line, chat)
        } else {
            chat.sendMessage(line)
        }
    }
    chat.close()
}

private fun processChatCommand(line: String, chat: P2PChat) {
    val parts = line.split(" ")
    when (parts[0]) {
        "/mode" -> {
            chat.mode = if (parts.getOrNull(1) == "m") P2PChat.ChatMode.MULTICAST else P2PChat.ChatMode.BROADCAST
            println("Mode changed to ${chat.mode}")
        }
        "/list" -> {
            println("Active Peers:")
            chat.peers.keys().toList().forEach { println(" - $it") }
        }
        "/ignore" -> {
            parts.getOrNull(1)?.let {
                chat.ignoredPeers.add(it)
                println("Ignoring $it")
            }
        }
        "/leave" -> chat.leaveMulticast()
        else -> println("Unknown command: ${parts[0]}")
    }
}
'@
Write-TextFile "src\main\kotlin\sspoirs\Main.kt" $mainKt

# ------------------------------------------------------------------------------
# 2. IcmpService.kt
# ------------------------------------------------------------------------------
$icmpServiceKt = @'
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
'@
Write-TextFile "src\main\kotlin\sspoirs\lr5\IcmpService.kt" $icmpServiceKt

# ------------------------------------------------------------------------------
# 3. Test-LR5-Local.ps1
# ------------------------------------------------------------------------------
$testLr5LocalPs1 = @'
Write-Host '=== [1/2] PARALLEL PING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping 8.8.8.8 1.1.1.1 127.0.0.1
Write-Host '`n=== [2/2] TRACEROUTE ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host '`n[!] Raw test launch in WSL via: ./test_lr5_raw_local.sh' -ForegroundColor Yellow
'@
Write-TextFile "Test-LR5-Local.ps1" $testLr5LocalPs1

# ------------------------------------------------------------------------------
# 4. Test-LR5-Net.ps1
# ------------------------------------------------------------------------------
$testLr5NetPs1 = @'
Write-Host '=== [1/2] PARALLEL PING ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 ping 10.220.155.244 8.8.8.8
Write-Host '`n=== [2/2] TRACEROUTE ===' -ForegroundColor Cyan
cmd.exe /c run.bat 5 trace 8.8.8.8
Write-Host '`n[!] Raw test: friend launches on Fedora via: ./test_lr5_raw_net.sh' -ForegroundColor Yellow
'@
Write-TextFile "Test-LR5-Net.ps1" $testLr5NetPs1

# ------------------------------------------------------------------------------
# 5. Linux shell scripts
# ------------------------------------------------------------------------------
$testLr5RawLocalSh = @'
#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Sending raw ICMP packets with Source IP 127.0.0.1...'
./run.sh 5 raw 127.0.0.1 127.0.0.1 10
'@
Write-TextFile "test_lr5_raw_local.sh" $testLr5RawLocalSh $true

$testLr5RawNetSh = @'
#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
echo 'Sending raw ICMP packets: Source=10.220.155.14 -> Target=10.220.155.244...'
./run.sh 5 raw 10.220.155.14 10.220.155.244 10
'@
Write-TextFile "test_lr5_raw_net.sh" $testLr5RawNetSh $true

# Удаляем устаревшие файлы со старыми названиями, чтобы не было путаницы
@("test_lr5_smurf_local.sh", "test_lr5_smurf_net.sh") | ForEach-Object {
    $oldFile = Join-Path $root $_
    if (Test-Path $oldFile) { Remove-Item $oldFile -Force; Write-Host "  [-] Удален устаревший: $_" -ForegroundColor DarkGray }
}

Write-Host "`nВсе файлы успешно обновлены!" -ForegroundColor Green