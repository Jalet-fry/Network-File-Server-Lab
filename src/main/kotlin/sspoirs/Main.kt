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
        val rawLine = readLine() ?: break
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.equals("/exit", ignoreCase = true) || line.equals("exit", ignoreCase = true)) break

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
    when (parts[0].lowercase()) {
        "/mode" -> {
            chat.mode = if (parts.getOrNull(1)?.lowercase() == "m") P2PChat.ChatMode.MULTICAST else P2PChat.ChatMode.BROADCAST
            println("Mode changed to ${chat.mode}")
        }
        "/list" -> {
            println("Active Peers:")
            if (chat.peers.isEmpty()) {
                println("  (No peers discovered yet)")
            } else {
                chat.peers.forEach { (id, peer) -> println(" - IP: ${peer.ip}, ID: $id") }
            }
        }
        "/ignore" -> {
            val target = parts.getOrNull(1)
            if (target != null) {
                chat.ignoredPeers.add(target)
                println("Ignoring messages from $target")
            } else {
                println("Usage: /ignore <ip_or_id>")
            }
        }
        "/leave" -> chat.leaveMulticast()
        else -> println("Unknown command: ${parts[0]}")
    }
}
