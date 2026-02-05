package sspoirs.common

import java.io.File

object Constants {
    const val DEFAULT_PORT = 9999
    const val DEFAULT_HOST = "localhost"
    const val LINE_SEPARATOR = "\r\n"
    const val BUFFER_SIZE = 8192
    
    // UDP specific
    const val UDP_PACKET_SIZE = 1400
    const val UDP_TIMEOUT = 500L
    const val MAX_RETRIES = 5
    
    // Thread Pool (Lab 4)
    const val THREAD_POOL_N_MIN = 2
    const val THREAD_POOL_N_MAX = 10
    const val IDLE_TIMEOUT_MS = 10000L // 10 секунд
    
    const val SERVER_STORAGE = "files-server"
    const val CLIENT_STORAGE = "files-client"

    fun initDirs() {
        File(SERVER_STORAGE).mkdirs()
        File(CLIENT_STORAGE).mkdirs()
    }

    enum class Protocol { TCP, UDP }
}
