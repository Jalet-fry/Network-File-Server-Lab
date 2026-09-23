package sspoirs.mpi

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        // Если hosts не передан через аргументы, читаем из файла hosts.txt!
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
        val latch = CountDownLatch(hosts.size - 1)

        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(basePort + myRank))
        listener.soTimeout = 60000 // 60 секунд на ожидание подключения

        println("[MPI Rank $myRank] Server listening on port ${basePort + myRank}...")

        val executor = Executors.newFixedThreadPool(hosts.size)

        // 1. Подключаемся к узлам с меньшим рангом
        for (i in 0 until myRank) {
            val targetRank = i
            val targetHost = hosts[targetRank]
            val targetPort = basePort + targetRank
            executor.submit {
                var connected = false
                var attempts = 0
                while (!connected && attempts < 60) {
                    try {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()
                        socket.connect(InetSocketAddress(targetHost, targetPort), 2000)

                        socket.getOutputStream().write(myRank)
                        socket.getOutputStream().flush()

                        connections[targetRank] = socket
                        connected = true
                        println("[MPI Rank $myRank] -> CONNECTED to Rank $targetRank ($targetHost:$targetPort)!")
                        latch.countDown()
                    } catch (e: Exception) {
                        attempts++
                        if (attempts % 5 == 0) {
                            println("[MPI Rank $myRank] Still trying to connect to Rank $targetRank ($targetHost:$targetPort): ${e.message}")
                        }
                        Thread.sleep(1000)
                    }
                }
                if (!connected) {
                    println("[MPI Rank $myRank] FAILED to connect to Rank $targetRank!")
                }
            }
        }

        // 2. Принимаем подключения от узлов с большим рангом
        val acceptThread = Thread {
            for (i in (myRank + 1) until hosts.size) {
                try {
                    println("[MPI Rank $myRank] Waiting for Rank $i to connect...")
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()

                    val remoteRank = socket.getInputStream().read()
                    if (remoteRank != -1) {
                        connections[remoteRank] = socket
                        println("[MPI Rank $myRank] -> ACCEPTED connection from Rank $remoteRank!")
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    println("[MPI Rank $myRank] Accept error: ${e.message}")
                }
            }
        }
        acceptThread.start()

        val success = latch.await(65, TimeUnit.SECONDS)
        if (!success) {
            println("[MPI Rank $myRank] TIMEOUT: Not all ranks connected in 60s! Active: ${connections.keys}")
        }

        executor.shutdown()
        listener.close()
        return connections
    }

    fun finalize() {
        if (!isInitialized) return
        worldComm?.close()
        isInitialized = false
        println("[MPI] Finalized.")
    }
}