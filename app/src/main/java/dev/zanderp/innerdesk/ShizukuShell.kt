package dev.zanderp.innerdesk

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method

class ShizukuShell : PrivilegedShell {

    private val lock = Any()

    @Volatile
    private var shellProcess: Process? = null
    private var shellOut: OutputStream? = null
    private var shellIn: InputStream? = null
    private var cmdSeq = 0

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override fun run(command: String): String {
        synchronized(lock) {
            return try {
                ensureShell()
                runTagged(command)
            } catch (e: Exception) {
                destroyShell()
                try {
                    ensureShell()
                    runTagged(command)
                } catch (e2: Exception) {
                    Log.e(TAG, "run failed: $command", e2)
                    "fail ${e2.message}"
                }
            }
        }
    }

    override fun runQuiet(command: String) {
        synchronized(lock) {
            try {
                ensureShell()
                writeShell(command)
            } catch (_: Exception) {}
        }
    }

    private fun newShizukuProcess(cmd: Array<String>): Process {
        if (newProcessMethod == null) {
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).also { it.isAccessible = true }
        }
        return newProcessMethod!!.invoke(null, cmd, null, null) as Process
    }

    private fun ensureShell() {
        if (shellProcess != null && shellOut != null) return
        destroyShell()
        val proc = newShizukuProcess(arrayOf("sh"))
        shellProcess = proc
        shellOut = proc.outputStream
        shellIn = proc.inputStream
        drainUntilIdle()
    }

    private fun runTagged(command: String): String {
        cmdSeq++
        val token = "S$cmdSeq"
        drainQuick()
        writeShell(command)
        writeShell("echo $token:\$?")
        val raw = readUntil("$token:")
        val body = raw.substringBefore("$token:")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(180)
        val code = raw.substringAfter("$token:").takeWhile { it.isDigit() || it == '-' }
        return "ok $body exit=$code"
    }

    private fun writeShell(command: String) {
        val out = shellOut ?: throw IllegalStateException("shell not open")
        out.write((command + "\n").toByteArray())
        out.flush()
    }

    private fun drainQuick() {
        val inp = shellIn ?: return
        val n = inp.available()
        if (n <= 0) return
        val buf = ByteArray(n.coerceAtMost(8192))
        inp.read(buf)
    }

    private fun drainUntilIdle() {
        val inp = shellIn ?: return
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) {
            val n = inp.available()
            if (n > 0) {
                val buf = ByteArray(n.coerceAtMost(8192))
                inp.read(buf)
            } else {
                Thread.sleep(30)
            }
        }
    }

    private fun readUntil(marker: String): String {
        val inp = shellIn ?: return ""
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val n = inp.available()
            if (n > 0) {
                val buf = ByteArray(n.coerceAtMost(4096))
                val got = inp.read(buf)
                if (got > 0) sb.append(String(buf, 0, got))
                if (sb.contains(marker)) break
            } else {
                Thread.sleep(20)
            }
        }
        return sb.toString()
    }

    private fun destroyShell() {
        try { shellOut?.close() } catch (_: Exception) {}
        try { shellIn?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellOut = null
        shellIn = null
        shellProcess = null
    }

    fun reset() {
        synchronized(lock) { destroyShell() }
    }

    companion object {
        private const val TAG = "ShizukuShell"
        private var newProcessMethod: Method? = null

        fun isShizukuInstalled(context: android.content.Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun isShizukuRunning(): Boolean {
            return try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            }
        }

        fun hasPermission(): Boolean {
            return try {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }

        fun requestPermission(requestCode: Int) {
            Shizuku.requestPermission(requestCode)
        }
    }
}
