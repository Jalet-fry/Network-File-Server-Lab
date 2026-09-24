package sspoirs.mpi

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

object Mpi {
    private var isInitialized = false
    private var worldComm: Communicator? = null

    val COMM_WORLD: Communicator
        get() = worldComm ?: throw IllegalStateException("MPI not initialized. Call Mpi.init() first.")

    fun init(args: List<String>) {
        if (isInitialized) return

        val rank = args.find { it.startsWith("rank=") }?.substringAfter("=")?.toIntOrNull()
            ?: args.getOrNull(0)?.toIntOrNull()
            ?: throw IllegalArgumentException("MPI Init: 'rank' argument missing. Use rank=N or pass number.")

        val hosts = args.find { it.startsWith("hosts=") }?.substringAfter("=")?.split(",")
            ?: loadHostsFromFile()

        println("==================================================")
        println("[MPI] Initializing Rank $rank of ${hosts.size} nodes: $hosts")
        println("==================================================")

        val connections = establishConnections(rank, hosts)
        worldComm = Communicator(rank, hosts, connections)
        isInitialized = true

        println("[MPI Rank $rank] Synchronizing at barrier...")
        worldComm?.barrier()
        println("[MPI Rank $rank] >>> READY TO COMPUTE! <<<")
    }

    private fun loadHostsFromFile(): List<String> {
        val file = File("hosts.txt")
        if (file.exists()) {
            val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.isNotEmpty()) return lines
        }
        return listOf("127.0.0.1", "127.0.0.1")
    }

    private fun establishConnections(myRank: Int, hosts: List<String>): Map<Int, Socket> {
        val connections = ConcurrentHashMap<Int, Socket>()
        val basePort = 10000

        if (myRank == 0) {
            // Rank 0 (Windows) — СЕРВЕР: ждет входящее подключение от Rank 1
            val listener = ServerSocket()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(basePort))
            listener.soTimeout = 60000
            println("[MPI Master (Rank 0)] Server listening on port $basePort, waiting for Worker (Rank 1)...")

            val socket = listener.accept()
            socket.tcpNoDelay = true
            socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()

            val remoteRank = socket.getInputStream().read()
            connections[remoteRank] = socket
            println("[MPI Master (Rank 0)] -> ACCEPTED connection from Rank $remoteRank!")
            listener.close()
        } else {
            // Rank 1 (Fedora) — КЛИЕНТ: упорно подключается к Rank 0 (Windows)
            val masterHost = hosts[0].trim()
            println("[MPI Worker (Rank 1)] Connecting to Master (Rank 0) at $masterHost:$basePort...")

            var connected = false
            var attempts = 0
            while (!connected && attempts < 60) {
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.reuseAddress = true
                    socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()
                    socket.connect(InetSocketAddress(masterHost, basePort), 2000)

                    socket.getOutputStream().write(myRank)
                    socket.getOutputStream().flush()

                    connections[0] = socket
                    connected = true
                    println("[MPI Worker (Rank 1)] -> CONNECTED to Master (Rank 0) successfully!")
                } catch (e: Exception) {
                    attempts++
                    if (attempts % 3 == 0) {
                        println("[MPI Worker (Rank 1)] Still waiting for Master at $masterHost:$basePort (attempt $attempts)...")
                    }
                    Thread.sleep(1000)
                }
            }

            if (!connected) {
                throw RuntimeException("[MPI Worker (Rank 1)] FAILED to connect to Master at $masterHost:$basePort")
            }
        }

        return connections
    }

    fun finalize() {
        if (!isInitialized) return
        worldComm?.close()
        isInitialized = false
        println("[MPI] Finalized.")
    }
}