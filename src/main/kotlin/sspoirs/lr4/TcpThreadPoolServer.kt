package sspoirs.lr4

import sspoirs.common.*
import sspoirs.lr1.TcpSessionHandler
import java.net.ServerSocket
import java.net.Socket
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
    ).apply {
        prestartAllCoreThreads()
    }

    fun start() {
        try {
            println("--- Server Starting at ${NetworkUtils.getTimestamp()} ---")
            println("[SERVER] Available local IP addresses:")
            NetworkUtils.getLocalIpAddresses().forEach { println("  - $it") }

            ServerSocket(port).use { serverSocket ->
                println("[SERVER] Lab 4 TCP Pool started on port $port")
                println("[CONFIG] Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val clientSocket = synchronized(serverSocket) {
                            serverSocket.accept()
                        }
                        
                        clientSocket.keepAlive = true 
                        clientSocket.soTimeout = 60000 
                        
                        dispatchClient(clientSocket)
                    } catch (e: Exception) {
                        if (serverSocket.isClosed) break
                        println("[ERROR] Accept failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[FATAL] Server socket error: ${e.message}")
        } finally {
            executor.shutdown()
        }
    }

    private fun dispatchClient(socket: Socket) {
        val clientAddr = socket.remoteSocketAddress
        try {
            executor.execute {
                activeTasks.incrementAndGet()
                println("[${NetworkUtils.getTimestamp()}] New connection from $clientAddr. Active: ${activeTasks.get()}")
                
                try {
                    TcpSessionHandler(socket).run()
                } catch (e: Exception) {
                    println("[SESSION ERROR] $clientAddr: ${e.message}")
                } finally {
                    activeTasks.decrementAndGet()
                    println("[${NetworkUtils.getTimestamp()}] Session with $clientAddr finished. Active: ${activeTasks.get()}")
                }
            }
        } catch (e: RejectedExecutionException) {
            println("[POOL FULL] Rejected connection from $clientAddr")
            // Даем клиенту шанс прочитать ошибку перед закрытием
            Thread {
                try {
                    val out = socket.getOutputStream()
                    NetworkUtils.writeLine(out, "ERROR: Server busy. Max connections reached.")
                    Thread.sleep(500) // Пауза полсекунды
                    socket.close()
                } catch (ex: Exception) {}
            }.start()
        }
    }
}
