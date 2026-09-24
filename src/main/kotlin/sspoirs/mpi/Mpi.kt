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
        val targetRank = if (myRank == 0) 1 else 0
        val targetHost = hosts[targetRank].trim()
        val targetPort = basePort + targetRank
        val myPort = basePort + myRank

        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(myPort))
        listener.soTimeout = 45000

        println("[MPI Rank $myRank] Listening on port $myPort, target node: Rank $targetRank ($targetHost:$targetPort)")

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // Поток 1: Постоянно пробуем подключиться к напарнику
        executor.submit {
            var attempts = 0
            while (latch.count > 0 && attempts < 30) {
                try {
                    Thread.sleep(1000)
                    if (connections.containsKey(targetRank)) break
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.reuseAddress = true
                    socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()
                    socket.connect(InetSocketAddress(targetHost, targetPort), 2000)

                    socket.getOutputStream().write(myRank)
                    socket.getOutputStream().flush()

                    if (connections.putIfAbsent(targetRank, socket) == null) {
                        println("[MPI Rank $myRank] -> CONNECTED to Rank $targetRank successfully!")
                        latch.countDown()
                    } else {
                        socket.close()
                    }
                    break
                } catch (e: Exception) {
                    attempts++
                    if (attempts % 3 == 0) {
                        println("[MPI Rank $myRank] Waiting for Rank $targetRank at $targetHost:$targetPort (attempt $attempts)...")
                    }
                }
            }
        }

        // Поток 2: Одновременно ждем входящего подключения от напарника
        executor.submit {
            try {
                while (latch.count > 0) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()

                    val remoteRank = socket.getInputStream().read()
                    if (remoteRank != -1) {
                        if (connections.putIfAbsent(remoteRank, socket) == null) {
                            println("[MPI Rank $myRank] -> ACCEPTED connection from Rank $remoteRank!")
                            latch.countDown()
                        } else {
                            socket.close()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                // Timeout or closed
            }
        }

        val connected = latch.await(45, TimeUnit.SECONDS)
        executor.shutdownNow()
        try { listener.close() } catch (e: Exception) {}

        if (!connected || !connections.containsKey(targetRank)) {
            throw RuntimeException("[MPI Rank $myRank] Connection failed! Could not link with Rank $targetRank ($targetHost:$targetPort)")
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