package dev.zanderp.innerdesk

interface PrivilegedShell {
    fun run(command: String): String
    fun runQuiet(command: String)
    fun isAvailable(): Boolean
}
