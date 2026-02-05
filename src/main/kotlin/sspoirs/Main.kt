package sspoirs

import sspoirs.common.Constants
import sspoirs.lr1.SimpleClient
import sspoirs.lr1.TcpCommandServer
import sspoirs.lr2.UdpClient
import sspoirs.lr2.UdpServer
import sspoirs.lr4.TcpThreadPoolServer

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
        "3" -> startServer(protocol, port, isThreadPool = true) // Новый режим для ЛР4
        "2" -> startClient(protocol, port, args.getOrNull(3) ?: Constants.DEFAULT_HOST)
        else -> printHelp()
    }
}

private fun printHelp() {
    println("""
        Usage: run.bat <mode> <protocol> [port] [host]
        MODES:
          1 = Sequential/Multiplexed Server (Lab 1/3)
          2 = Client
          3 = Thread Pool Server (Lab 4)
        PROTOCOLS:
          1 = TCP, 2 = UDP
    """.trimIndent())
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
    if (protocol == Constants.Protocol.TCP) SimpleClient(host, port).start()
    else UdpClient(host, port).start()
}
