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
    
    // Thread Pool (Lab 4) - Согласовано с REQUIREMENTS.md
    const val THREAD_POOL_N_MIN = 2
    const val THREAD_POOL_N_MAX = 5
    const val IDLE_TIMEOUT_MS = 60000L
    
    // Timeouts
    const val READ_TIMEOUT_MS = 30000 // 30 секунд на ожидание команды
    const val SESSION_MAX_IDLE_MS = 300000L // 5 минут макс. простой
    
    const val SERVER_STORAGE = "files-server"
    const val CLIENT_STORAGE = "files-client"

    fun initDirs() {
        File(SERVER_STORAGE).mkdirs()
        File(CLIENT_STORAGE).mkdirs()
    }

    enum class Protocol { TCP, UDP }
}
