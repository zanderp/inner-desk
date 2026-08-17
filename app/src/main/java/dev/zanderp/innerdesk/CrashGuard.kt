package dev.zanderp.innerdesk

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Survives fatal crashes: writes stack + session log to filesDir, reloads them on next launch.
 * Still tears down overlay_display_devices so a crash does not leave Overlay # on screen.
 */
object CrashGuard {
    private const val CRASH_FILE = "last_crash.txt"
    private const val SESSION_FILE = "last_session.log"
    private const val RESTORE_BANNER = "--- restored session log (saved before last exit/crash) ---"
    private const val PREV_CRASH_BANNER = "--- previous fatal crash ---"
    private const val END_CRASH_BANNER = "--- end previous crash — use Share to send this ---"
    const val MAX_SESSION_BYTES = 256 * 1024

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var installed = false
    @Volatile private var hydrated = false
    @Volatile private var lastPersistMs = 0L

    fun install(appContext: Context) {
        if (installed) return
        installed = true
        val app = appContext.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordFatal(app, thread, error)
            } catch (_: Throwable) {
            }
            try {
                teardownOverlay()
            } catch (_: Throwable) {
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
                exitProcess(10)
            }
        }
    }

    fun persistSession(appContext: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < 4_000L) return
        lastPersistMs = now
        try {
            val text = AppSession.logText()
            if (text.isBlank()) return
            val clipped = if (text.length <= MAX_SESSION_BYTES) text else text.takeLast(MAX_SESSION_BYTES)
            File(appContext.applicationContext.filesDir, SESSION_FILE).writeText(clipped)
        } catch (_: Exception) {
        }
    }

    /** Returns true when a crash report was waiting. */
    fun hydrate(appContext: Context): Boolean {
        if (hydrated) return pendingCrashText(appContext) != null
        hydrated = true
        val app = appContext.applicationContext
        val hadCrash = crashFile(app).exists() && crashFile(app).length() > 0L
        try {
            val session = File(app.filesDir, SESSION_FILE)
            if (session.exists() && session.length() > 0L) {
                val body = session.readText().trimEnd()
                try { session.delete() } catch (_: Exception) {}
                if (body.isNotBlank()) {
                    val already = body.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.contains(RESTORE_BANNER) == true
                    AppSession.restore(if (already) "$body\n" else "$RESTORE_BANNER\n$body\n")
                }
            }
            val crash = pendingCrashText(app)
            if (crash != null) {
                AppSession.restore("$PREV_CRASH_BANNER\n$crash\n$END_CRASH_BANNER\n")
            }
        } catch (_: Exception) {
        }
        return hadCrash
    }

    fun pendingCrashText(appContext: Context): String? {
        val f = crashFile(appContext.applicationContext)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            f.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun crashFile(appContext: Context): File =
        File(appContext.applicationContext.filesDir, CRASH_FILE)

    private fun recordFatal(app: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val header = buildString {
            append("=== InnerDesk FATAL ")
            append(stampFmt.format(Date()))
            append(" ===\n")
            append("version=").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("device=").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("thread=").append(thread.name).append('\n')
            append(sw.toString().trimEnd()).append('\n')
        }
        try {
            AppSession.appendLog("[FATAL] ${error.javaClass.name}: ${error.message}")
        } catch (_: Exception) {
        }
        val snapshot = try { AppSession.logText() } catch (_: Exception) { "" }
        val body = header + "\n=== session log ===\n" + snapshot
        File(app.filesDir, CRASH_FILE).writeText(body.take(MAX_SESSION_BYTES * 2))
        if (snapshot.isNotBlank()) {
            File(app.filesDir, SESSION_FILE).writeText(
                if (snapshot.length <= MAX_SESSION_BYTES) snapshot else snapshot.takeLast(MAX_SESSION_BYTES),
            )
        }
        Log.e("InnerDesk", "FATAL on ${thread.name}", error)
    }

    private fun teardownOverlay() {
        try {
            DaemonShell.runQuiet("settings put global overlay_display_devices none")
            DaemonShell.runQuiet("settings delete global overlay_display_devices")
        } catch (_: Exception) {
        }
        try {
            AppSession.shell?.let { DexStarter.clearSession(it) }
        } catch (_: Exception) {
        }
    }
}
