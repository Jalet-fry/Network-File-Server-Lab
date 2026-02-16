package sspoirs.lr4

import sspoirs.common.*
import sspoirs.lr1.TcpSessionHandler
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class TcpThreadPoolServer(private val port: Int) {
    
    private val activeTasks = AtomicInteger(0)

    // Вариант 5: Пул потоков с динамическим расширением (Nmin -> Nmax)
    private val executor = ThreadPoolExecutor(
        Constants.THREAD_POOL_N_MIN, // Начальное кол-во потоков (Nmin)
        Constants.THREAD_POOL_N_MAX, // Максимальное кол-во потоков (Nmax)
        Constants.IDLE_TIMEOUT_MS,   // Время жизни лишних потоков
        TimeUnit.MILLISECONDS,
        // Использование SynchronousQueue заставляет пул создавать новые потоки немедленно,
        // пока не достигнет Nmax, вместо того чтобы копить их в очереди.
        SynchronousQueue<Runnable>(), 
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        prestartAllCoreThreads() // Инициализируем Nmin потоков сразу
    }

    fun start() {
        try {
            ServerSocket(port).use { serverSocket ->
                println("[SERVER] Lab 4 TCP Pool started on port $port")
                println("[CONFIG] Nmin=${Constants.THREAD_POOL_N_MIN}, Nmax=${Constants.THREAD_POOL_N_MAX}")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        // Механизм защиты accept (согласно варианту 5)
                        // В данном случае используется блокировка на объекте сокета
                        val clientSocket = synchronized(serverSocket) {
                            serverSocket.accept()
                        }
                        
                        // Настройка согласно п. 2.a REQUIREMENTS.md
                        clientSocket.keepAlive = true 
                        // Таймаут на чтение (чтобы не висеть вечно, если клиент "умер")
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
                println("[POOL] Handling client $clientAddr. Active: ${activeTasks.get()}, Pool: ${executor.poolSize}")
                
                try {
                    // Используем обработчик сессии из ЛР1
                    TcpSessionHandler(socket).run()
                } catch (e: Exception) {
                    println("[SESSION ERROR] $clientAddr: ${e.message}")
                } finally {
                    activeTasks.decrementAndGet()
                    println("[POOL] Finished $clientAddr. Active: ${activeTasks.get()}")
                    // Сокет закрывается внутри TcpSessionHandler (через use)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Если достигли Nmax и очередь полна
            println("[POOL FULL] Rejected connection from $clientAddr")
            try { 
                NetworkUtils.writeLine(socket.getOutputStream(), "ERROR: Server busy")
                socket.close() 
            } catch (ex: Exception) {}
        }
    }
}
