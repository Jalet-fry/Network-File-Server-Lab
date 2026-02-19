package sspoirs.common

import java.io.File

object Constants {
    const val DEFAULT_PORT = 8888
    const val DEFAULT_HOST = "localhost"
    const val LINE_SEPARATOR = "\r\n"
    
    const val BUFFER_SIZE = 65536 
    
    // UDP specific
    const val UDP_PACKET_SIZE = 1400
    const val UDP_TIMEOUT = 1000L 
    const val MAX_RETRIES = 5
    
    // Thread Pool (Lab 4)
    const val THREAD_POOL_N_MIN = 2
    const val THREAD_POOL_N_MAX = 4 
    const val IDLE_TIMEOUT_MS = 60000L 
    
    // Timeouts - Установлено 1 минута согласно требованию
    const val READ_TIMEOUT_MS = 60000 // 1 минута на ожидание данных
    const val SESSION_MAX_IDLE_MS = 60000L // 1 минута общего бездействия сессии
    
    const val SERVER_STORAGE = "files-server"
    const val CLIENT_STORAGE = "files-client"

    fun initDirs() {
        File(SERVER_STORAGE).mkdirs()
        File(CLIENT_STORAGE).mkdirs()
    }

    enum class Protocol { TCP, UDP }
}
