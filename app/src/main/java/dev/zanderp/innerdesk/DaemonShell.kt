package dev.zanderp.innerdesk

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock

object DaemonShell : PrivilegedShell {
    @Volatile private var lastOk = 0L
    @Volatile var lastError: String? = null
        private set

    override fun isAvailable(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastOk in 1 until 1500) return true
        return try {
            transact("echo ok")
            lastOk = now
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message
            lastOk = 0
            false
        }
    }

    override fun run(command: String): String {
        return try {
            val raw = transact(command)
            "ok ${raw.replace('\n', ' ').trim().take(180)} exit=0"
        } catch (e: Exception) {
            "fail ${e.message}"
        }
    }

    override fun runQuiet(command: String) {
        try { transact(command) } catch (_: Exception) {}
    }

    private fun transact(command: String): String {
        val sock = LocalSocket()
        sock.connect(LocalSocketAddress(PrivDaemon.SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
        sock.soTimeout = if (command == "echo ok") 2000 else 8000
        sock.outputStream.write((command + "\n").toByteArray())
        sock.outputStream.flush()
        sock.shutdownOutput()
        val out = sock.inputStream.readBytes().toString(Charsets.UTF_8)
        sock.close()
        return out
    }
}
