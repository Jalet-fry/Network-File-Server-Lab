package sspoirs.lr4

import sspoirs.common.*
import sspoirs.lr1.TcpSessionHandler
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class TcpThreadPoolServer(private val port: Int) {
    
    private val activeTasks = AtomicInteger(0)

    private val executor = ThreadPoolExecutor(
        Constants.THREAD_POOL_N_MIN, 
        Constants.THREAD_POOL_N_MAX, 
        Constants.IDLE_TIMEOUT_MS,   
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(), 
        ThreadPoolExecutor.AbortPolicy()
    )

    fun start() {
        try {
            NetworkUtils.log("--- Server Starting ---")
            ServerSocket(port).use { serverSocket ->
                // Таймаут на accept, чтобы поток не висел вечно и мог проверять Interrupted
                serverSocket.soTimeout = 2000 
                NetworkUtils.log("[SERVER] Lab 4 TCP Pool started on port $port")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val clientSocket = serverSocket.accept()
                        clientSocket.tcpNoDelay = true 
                        dispatchClient(clientSocket)
                    } catch (e: SocketTimeoutException) {
                        continue // Просто проверка флага Interrupted
                    } catch (e: Exception) {
                        if (serverSocket.isClosed) break
                        NetworkUtils.log("[ERROR] Accept failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            NetworkUtils.log("[FATAL] Server error: ${e.message}")
        } finally {
            shutdownExecutor()
        }
    }

    private fun dispatchClient(socket: Socket) {
        val clientAddr = socket.remoteSocketAddress
        try {
            executor.execute {
                activeTasks.incrementAndGet()
                NetworkUtils.log("[${NetworkUtils.getTimestamp()}] New connection: $clientAddr. Active: ${activeTasks.get()}")
                try {
                    TcpSessionHandler(socket).run()
                } finally {
                    activeTasks.decrementAndGet()
                    NetworkUtils.log("[${NetworkUtils.getTimestamp()}] Finished: $clientAddr. Active: ${activeTasks.get()}")
                }
            }
        } catch (e: RejectedExecutionException) {
            NetworkUtils.log("[POOL FULL] Rejected: $clientAddr")
            CompletableFuture.runAsync {
                try {
                    val out = socket.getOutputStream()
                    NetworkUtils.writeLine(out, "ERROR: Server busy.")
                    socket.close()
                } catch (ex: Exception) {}
            }
        }
    }

    private fun shutdownExecutor() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }
}
