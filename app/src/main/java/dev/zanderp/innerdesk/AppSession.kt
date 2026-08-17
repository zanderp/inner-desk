package dev.zanderp.innerdesk

import android.content.Context

object AppSession {
    var shizuku: ShizukuShell? = null

    val shell: PrivilegedShell?
        get() = when {
            BinderShell.isAvailable() -> BinderShell
            DaemonShell.isAvailable() -> DaemonShell
            shizuku?.isAvailable() == true -> shizuku
            else -> null
        }

    @Volatile
    var dexRunning: Boolean = false

    data class FoldLayout(
        val tabletop: Boolean,
        val topHeight: Int,
        val width: Int,
        val height: Int,
    )

    @Volatile
    var foldLayout: FoldLayout? = null

    /** True after the hinge sensor has reported an angle. WindowLayoutInfo must not override it. */
    @Volatile
    var hingeTracked: Boolean = false

    /** User pinned tabletop from the DeX menu while the fold is flat. */
    @Volatile
    var tabletopForced: Boolean = false

    var desktop: DesktopDisplay? = null

    var overlayBounds: IntArray? = null
    var overlayDisplayW = 0
    var overlayDisplayH = 0
    var overlayDpi = 420
    val overlayMirror = OverlayMirror()

    fun applyOverlaySpec(context: Context, physicalW: Int, physicalH: Int): String =
        OverlayCanvas.spec(context, physicalW, physicalH)

    private val logLock = Any()
    private val sessionLog = StringBuilder()
    private const val MAX_LOG_CHARS = 256 * 1024

    fun appendLog(line: String) {
        val snapshot: String
        synchronized(logLock) {
            sessionLog.append(line.trim()).append('\n')
            if (sessionLog.length > MAX_LOG_CHARS) {
                sessionLog.delete(0, sessionLog.length - MAX_LOG_CHARS)
            }
            snapshot = sessionLog.toString()
        }
        onLog?.invoke(snapshot)
    }

    fun restore(block: String) {
        synchronized(logLock) {
            if (block.isBlank()) return
            sessionLog.insert(0, block)
            if (sessionLog.length > MAX_LOG_CHARS) {
                sessionLog.delete(MAX_LOG_CHARS, sessionLog.length)
            }
        }
        onLog?.invoke(logText())
    }

    fun logText(): String = synchronized(logLock) { sessionLog.toString() }

    @Volatile
    var onLog: ((String) -> Unit)? = null

    fun desktop(context: Context): DesktopDisplay {
        return desktop ?: DesktopDisplay(context.applicationContext).also { desktop = it }
    }

    fun prefs(context: Context) = context.getSharedPreferences("innerdesk", Context.MODE_PRIVATE)

    fun setDexRunning(context: Context, running: Boolean) {
        dexRunning = running
        prefs(context).edit().putBoolean("dex_running", running).apply()
        onDexRunningChanged?.invoke(running)
        DesktopControls.refresh(context)
    }

    @Volatile
    var onDexRunningChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var onMirrorDisconnected: (() -> Unit)? = null

    @Volatile
    var onTabletopChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var onDexTextFocus: ((Boolean) -> Unit)? = null

    @Volatile
    var onShizukuChanged: ((Boolean) -> Unit)? = null

    fun shutdown(context: Context) {
        setDexRunning(context, false)
        val app = context.applicationContext
        try { DexOverlayService.hide() } catch (_: Exception) {}
        Thread {
            try { shell?.let { DexStarter.clearSession(it) } } catch (_: Exception) {}
            android.os.Handler(app.mainLooper).post {
                try { overlayMirror.release() } catch (_: Exception) {}
                try { desktop?.release() } catch (_: Exception) {}
                desktop = null
                overlayBounds = null
                HudService.stopQuiet(app)
            }
        }.start()
    }

    fun shutdownBlocking(context: Context) {
        setDexRunning(context, false)
        try { DexOverlayService.hide() } catch (_: Exception) {}
        try { shell?.let { DexStarter.clearSession(it) } } catch (_: Exception) {}
        try { overlayMirror.release() } catch (_: Exception) {}
        try { desktop?.release() } catch (_: Exception) {}
        desktop = null
        overlayBounds = null
        HudService.stopQuiet(context)
    }
}
