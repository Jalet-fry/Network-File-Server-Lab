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

    // Используем LinkedBlockingQueue с лимитом 1, чтобы соответствовать логике "отказ при Nmax"
    // Но при этом позволять системе корректно обрабатывать пики
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
            NetworkUtils.log("--- Server Starting (Lab 4) ---")
            NetworkUtils.log("[CONFIG] Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}")
            
            ServerSocket(port).use { serverSocket ->
                serverSocket.soTimeout = 2000 
                NetworkUtils.log("[SERVER] TCP Pool started on port $port")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val clientSocket = serverSocket.accept()
                        
                        // Согласно требованиям: "Защита accept через synchronized"
                        synchronized(serverSocket) {
                            clientSocket.tcpNoDelay = true 
                            clientSocket.keepAlive = true
                            dispatchClient(clientSocket)
                        }
                    } catch (e: SocketTimeoutException) {
                        continue 
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
                    NetworkUtils.log("[${NetworkUtils.getTimestamp()}] Session with $clientAddr finished. Active: ${activeTasks.get()}")
                }
            }
        } catch (e: RejectedExecutionException) {
            NetworkUtils.log("[POOL FULL] Rejected connection from $clientAddr")
            // Отправляем ошибку в отдельном потоке, чтобы не блокировать accept
            CompletableFuture.runAsync {
                try {
                    val out = socket.getOutputStream()
                    NetworkUtils.writeLine(out, "ERROR: Server busy. Max connections reached.")
                    socket.close()
                } catch (ex: Exception) {}
            }
        }
    }

    private fun shutdownExecutor() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }
}
