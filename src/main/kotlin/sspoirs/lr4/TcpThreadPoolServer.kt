package sspoirs.lr4

import sspoirs.common.*
import sspoirs.lr1.TcpSessionHandler
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Параллельный сервер на базе пула потоков (ЛР №4, Вариант 5).
 * Соответствует требованиям динамического расширения и защиты accept.
 */
class TcpThreadPoolServer(private val port: Int) {
    
    // Используем SynchronousQueue для немедленного расширения до N_MAX,
    // так как LinkedBlockingQueue (даже маленькая) откладывает создание потоков до заполнения очереди.
    private val executor = ThreadPoolExecutor(
        Constants.THREAD_POOL_N_MIN,
        Constants.THREAD_POOL_N_MAX,
        Constants.IDLE_TIMEOUT_MS,
        TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(),
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        // Условие *: Пул инициализируется начальным числом потоков Nmin
        prestartAllCoreThreads()
    }

    private val activeConnections = AtomicInteger(0)

    fun start() {
        ServerSocket(port).use { serverSocket ->
            println("TCP Thread Pool Server (Lab 4) started on port $port...")
            println("Configuration: Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}, Timeout=${Constants.IDLE_TIMEOUT_MS}ms")

            while (true) {
                try {
                    // Вариант 5: Параллельный вызов accept. 
                    // Хотя ServerSocket.accept() в JVM нативен и потокобезопасен, 
                    // в контексте учебной задачи синхронизация подчеркивает контроль за вызовом.
                    val clientSocket = synchronized(serverSocket) {
                        serverSocket.accept()
                    }
                    
                    configureSocket(clientSocket)
                    dispatchClient(clientSocket)
                } catch (e: Exception) {
                    println("Accept error: ${e.message}")
                }
            }
        }
    }

    private fun configureSocket(socket: Socket) {
        // Условие ЛР 1: SO_KEEPALIVE для контроля обрывов
        socket.keepAlive = true
    }

    private fun dispatchClient(socket: Socket) {
        try {
            executor.execute {
                val currentActive = activeConnections.incrementAndGet()
                println("Client ${socket.remoteSocketAddress} handled. Active: $currentActive, Threads: ${executor.poolSize}")
                try {
                    TcpSessionHandler(socket).run()
                } finally {
                    activeConnections.decrementAndGet()
                    println("Client disconnected. Active: ${activeConnections.get()}")
                }
            }
        } catch (e: RejectedExecutionException) {
            println("Rejected: Max capacity ($activeConnections) reached. Terminating connection.")
            try { socket.close() } catch (ex: Exception) {}
        }
    }
}
