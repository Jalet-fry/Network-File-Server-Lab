package sspoirs.common

enum class Command {
    ECHO, TIME, CLOSE, UPLOAD, DOWNLOAD, LIST, SIZE, UNKNOWN;

    companion object {
        fun fromString(str: String): Command {
            val upper = str.uppercase().trim()
            return when (upper) {
                "LS", "LIST" -> LIST
                "EXIT", "QUIT", "CLOSE" -> CLOSE
                "ECHO" -> ECHO
                "TIME" -> TIME
                "UPLOAD" -> UPLOAD
                "DOWNLOAD" -> DOWNLOAD
                "SIZE" -> SIZE
                else -> UNKNOWN
            }
        }

        fun allCommands(): List<String> = entries.filter { it != UNKNOWN }.map { it.name }
    }
}
