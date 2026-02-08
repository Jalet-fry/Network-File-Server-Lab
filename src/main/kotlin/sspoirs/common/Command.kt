package sspoirs.common

enum class Command {
    LIST, DOWNLOAD, UPLOAD, CLOSE, TIME, ECHO, SIZE, UNKNOWN;

    companion object {
        fun fromString(s: String): Command {
            val upper = s.uppercase()
            return when {
                upper == "LS" || upper == "LIST" -> LIST
                upper == "EXIT" || upper == "QUIT" || upper == "CLOSE" -> CLOSE
                else -> try {
                    valueOf(upper)
                } catch (e: Exception) {
                    UNKNOWN
                }
            }
        }

        fun allCommands() = values().filter { it != UNKNOWN }.map { it.name }
    }
}
