package sspoirs.common

import java.io.File

/**
 * Общий интерфейс для выполнения команд. 
 * Реализуется сервером (TCP, UDP или NIO), так как способ отправки ответа везде разный.
 */
interface CommandExecutor {
    fun execute(cmd: Command, args: List<String>): Boolean
}

object CommandProcessor {
    /**
     * Разбирает строку (включая поддержку ';') и по очереди вызывает executor.
     * Возвращает false, если встречена команда завершения (CLOSE).
     */
    fun processLine(line: String, executor: CommandExecutor): Boolean {
        if (line.isBlank()) return true
        
        val chunks = line.split(";")
        for (chunk in chunks) {
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) continue
            
            val parts = trimmed.split(Regex("\\s+"))
            val cmd = Command.fromString(parts[0])
            val args = parts.drop(1)
            
            if (!executor.execute(cmd, args)) {
                return false // Прерываем сессию
            }
        }
        return true
    }

    /**
     * Общая логика получения списка файлов (используется всеми серверами)
     */
    fun getServerFilesList(): String {
        val dir = File(Constants.SERVER_STORAGE)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.filter { it.isFile }
            ?.joinToString(";") { "${it.name}(${it.length()}b)" } ?: "No files"
    }
}
