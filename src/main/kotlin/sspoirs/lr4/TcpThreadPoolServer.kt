package sspoirs.lr4

import sspoirs.common.*
import sspoirs.lr1.TcpSessionHandler
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class TcpThreadPoolServer(private val port: Int) {
    
    private val activeTasks = AtomicInteger(0)

    // Вариант 5: Пул потоков с динамическим расширением
    private val executor = ThreadPoolExecutor(
        Constants.THREAD_POOL_N_MIN, // Nmin
        Constants.THREAD_POOL_N_MAX, // Nmax
        Constants.IDLE_TIMEOUT_MS,   // Таймаут завершения лишних потоков
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(), // SynchronousQueue заставляет пул расширяться немедленно
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        // Пул инициализируется начальным числом потоков Nmin
        prestartAllCoreThreads()
    }

    fun start() {
        ServerSocket(port).use { serverSocket ->
            println("[SERVER v2.2] TCP Thread Pool Server (Lab 4) started on port $port")
            println("[CONFIG] Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}, IdleTimeout=${Constants.IDLE_TIMEOUT_MS}ms")

            while (true) {
                try {
                    // Защита accept через синхронизацию (требование ЛР4)
                    val clientSocket = synchronized(serverSocket) {
                        serverSocket.accept()
                    }
                    
                    clientSocket.keepAlive = true
                    // Увеличиваем таймаут, чтобы большие файлы не рвались
                    clientSocket.soTimeout = 60000 
                    
                    dispatchClient(clientSocket)
                } catch (e: Exception) {
                    println("[POOL ERROR] Accept error: ${e.message}")
                }
            }
        }
    }

    private fun dispatchClient(socket: Socket) {
        val addr = socket.remoteSocketAddress
        try {
            executor.execute {
                activeTasks.incrementAndGet()
                println("[POOL] Client $addr connected. Active Tasks: ${activeTasks.get()}, Pool Size: ${executor.poolSize}")
                
                try {
                    TcpSessionHandler(socket).run()
                } finally {
                    activeTasks.decrementAndGet()
                    println("[POOL] Client $addr disconnected. Active Tasks: ${activeTasks.get()}, Pool Size: ${executor.poolSize}")
                }
            }
        } catch (e: RejectedExecutionException) {
            println("[POOL REJECTED] Max capacity reached for $addr")
            try { socket.close() } catch (ex: Exception) {}
        }
    }
}
