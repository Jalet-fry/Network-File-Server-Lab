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

    /**
     * Настройка для ЖЕСТКОГО ограничения (Лаб 4):
     * 1. SynchronousQueue — заставляет пул расширяться немедленно при появлении 3-го и 4-го клиента.
     * 2. AbortPolicy — при попытке подключить 5-го клиента (когда 4 потока заняты), 
     *    executor выбросит RejectedExecutionException.
     */
    private val executor = ThreadPoolExecutor(
        Constants.THREAD_POOL_N_MIN, 
        Constants.THREAD_POOL_N_MAX, 
        Constants.IDLE_TIMEOUT_MS,   
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(), 
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun start() {
        try {
            NetworkUtils.log("--- Server Starting (Lab 4) ---")
            NetworkUtils.log("[CONFIG] Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}")
            
            ServerSocket(port).use { serverSocket ->
                serverSocket.soTimeout = 2000 
                NetworkUtils.log("[SERVER] TCP Pool started on port $port")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        // Accept всегда свободен, так как мы не используем CallerRunsPolicy
                        val clientSocket = synchronized(serverSocket) {
                            serverSocket.accept()
                        }
                        
                        clientSocket.tcpNoDelay = true 
                        clientSocket.keepAlive = true
                        dispatchClient(clientSocket)
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
                val currentActive = activeTasks.incrementAndGet()
                NetworkUtils.log("[${NetworkUtils.getTimestamp()}] New connection: $clientAddr. Active tasks: $currentActive")
                try {
                    TcpSessionHandler(socket).run()
                } finally {
                    val remaining = activeTasks.decrementAndGet()
                    NetworkUtils.log("[${NetworkUtils.getTimestamp()}] Session with $clientAddr finished. Active tasks: $remaining")
                }
            }
        } catch (e: RejectedExecutionException) {
            // Сюда попадает 5-й клиент
            NetworkUtils.log("[REJECTED] Pool is full (N_MAX=${Constants.THREAD_POOL_N_MAX}). Client $clientAddr rejected.")
            try {
                // Отправляем вежливый отказ перед закрытием
                val out = socket.getOutputStream()
                NetworkUtils.writeLine(out, "ERROR: Server busy. Max connections (${Constants.THREAD_POOL_N_MAX}) reached.")
                socket.close()
            } catch (ex: Exception) {
                try { socket.close() } catch (ex2: Exception) {}
            }
        }
    }

    private fun shutdownExecutor() {
        NetworkUtils.log("[SERVER] Shutting down executor...")
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
