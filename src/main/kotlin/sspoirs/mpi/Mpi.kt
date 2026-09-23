package sspoirs.mpi

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

    /**
     * Initializes MPI. 
     * Expected args: rank=<N> hosts=ip1,ip2,ip3...
     */
    fun init(args: List<String>) {
        if (isInitialized) return
        
        val rank = args.find { it.startsWith("rank=") }?.substringAfter("=")?.toIntOrNull()
            ?: throw IllegalArgumentException("MPI Init: 'rank' argument missing")
        val hosts = args.find { it.startsWith("hosts=") }?.substringAfter("=")?.split(",")
            ?: throw IllegalArgumentException("MPI Init: 'hosts' argument missing")
        
        println("[MPI] Initializing Rank $rank / ${hosts.size} nodes...")
        
        val connections = establishConnections(rank, hosts)
        worldComm = Communicator(rank, hosts, connections)
        isInitialized = true
        
        // Barrier-like wait to ensure everyone is connected
        worldComm?.barrier()
        println("[MPI] Rank $rank ready.")
    }

    private fun establishConnections(myRank: Int, hosts: List<String>): Map<Int, Socket> {
        val connections = ConcurrentHashMap<Int, Socket>()
        val basePort = 10000
        val latch = CountDownLatch(hosts.size - 1)
        
        val listener = ServerSocket(basePort + myRank)
        listener.soTimeout = 30000 // 30s timeout for handshake
        
        val executor = Executors.newFixedThreadPool(hosts.size)
        
        // 1. Connect to nodes with lower rank in parallel
        for (i in 0 until myRank) {
            executor.submit {
                var connected = false
                var attempts = 0
                while (!connected && attempts < 30) {
                    try {
                        val socket = Socket(hosts[i], basePort + i)
                        socket.tcpNoDelay = true
                        socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()
                        
                        // Send our rank as a single byte handshake
                        socket.getOutputStream().write(myRank)
                        socket.getOutputStream().flush()
                        
                        connections[i] = socket
                        connected = true
                        latch.countDown()
                    } catch (e: Exception) {
                        attempts++
                        Thread.sleep(1000)
                    }
                }
            }
        }

        // 2. Accept connections from nodes with higher rank
        val acceptThread = Thread {
            for (i in (myRank + 1) until hosts.size) {
                try {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = Communicator.SOCKET_TIMEOUT_MS.toInt()
                    
                    // Read remote rank from handshake byte
                    val remoteRank = socket.getInputStream().read()
                    if (remoteRank != -1) {
                        connections[remoteRank] = socket
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    println("[MPI] Accept error: ${e.message}")
                }
            }
        }
        acceptThread.start()
        
        // Wait for all connections with a timeout
        val success = latch.await(40, TimeUnit.SECONDS)
        if (!success) {
            println("[MPI] Warning: Not all connections established within timeout.")
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
