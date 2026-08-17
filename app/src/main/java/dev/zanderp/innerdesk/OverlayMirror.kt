package dev.zanderp.innerdesk

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import rikka.shizuku.Shizuku

class OverlayMirror {
    companion object {
        private const val TAG = "OverlayMirror"
    }

    private var mirrorService: IMirrorService? = null
    private var bound = false

    fun getMirrorService(): IMirrorService? = PrivProvider.service() ?: lookupServiceManager() ?: mirrorService

    private fun lookupServiceManager(): IMirrorService? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val raw = sm.getMethod("getService", String::class.java).invoke(null, PrivDaemon.SERVICE_NAME) as? IBinder
            if (raw == null) null else IMirrorService.Stub.asInterface(raw)
        } catch (_: Exception) {
            null
        }
    }

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName("dev.zanderp.innerdesk", MirrorUserService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("mirror")
        .debuggable(false)
        .version(72)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService connected, binder=$binder")
            if (binder != null) {
                try {
                    binder.linkToDeath({
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (PrivProvider.serviceBinder != null) return@post
                            Log.w(TAG, "UserService binder died")
                            AppSession.appendLog("Mirror: binder died")
                            mirrorService = null
                            bound = false
                            AppSession.onMirrorDisconnected?.invoke()
                        }
                    }, 0)
                } catch (_: Exception) {}
                mirrorService = IMirrorService.Stub.asInterface(binder)
                val pending = pendingStart
                Log.d(TAG, "svc connected, pending=${pending?.displayId}")
                AppSession.appendLog("Mirror: svc connected pending=${pending?.displayId}")
                if (pending != null) {
                    val result = doStartMirror(
                        pending.displayId, pending.surface, pending.parent, pending.w, pending.h, pending.dpi
                    )
                    Log.d(TAG, "mirror result: $result")
                    AppSession.appendLog("Mirror: $result")
                    pendingStart = null
                }
            } else {
                Log.w(TAG, "svc connected but binder=null")
                AppSession.appendLog("Mirror: svc connected but binder=null")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (PrivProvider.serviceBinder != null) {
                Log.d(TAG, "UserService disconnected; priv binder still held")
                return
            }
            Log.d(TAG, "UserService disconnected")
            AppSession.appendLog("Mirror: svc DISCONNECTED")
            mirrorService = null
            bound = false
            AppSession.onMirrorDisconnected?.invoke()
        }
    }

    private data class PendingMirror(
        val displayId: Int,
        val surface: Surface,
        val parent: SurfaceControl?,
        val w: Int,
        val h: Int,
        val dpi: Int,
    )
    private var pendingStart: PendingMirror? = null

    fun attachExternal(binder: IBinder) {
        try {
            binder.linkToDeath({
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    PrivProvider.serviceBinder = null
                    mirrorService = null
                    AppSession.appendLog("Priv: binder died")
                    AppSession.onMirrorDisconnected?.invoke()
                }
            }, 0)
        } catch (_: Exception) {}
        mirrorService = IMirrorService.Stub.asInterface(binder)
        bound = true
        AppSession.appendLog("Priv: attached")
        val pending = pendingStart
        if (pending != null) {
            val result = doStartMirror(
                pending.displayId, pending.surface, pending.parent, pending.w, pending.h, pending.dpi
            )
            AppSession.appendLog("Priv mirror: $result")
            pendingStart = null
        }
    }

    fun start(displayId: Int, surfaceView: SurfaceView): String {
        try { mirrorService?.stopMirror() } catch (_: Exception) {}
        pendingStart = null
        val surface = surfaceView.holder.surface
        if (!surface.isValid) {
            AppSession.appendLog("Mirror: surface not valid")
            return "surface not valid"
        }
        val parent = try { surfaceView.surfaceControl } catch (_: Exception) { null }
        val w = surfaceView.width.takeIf { it > 0 } ?: OverlayCanvas.screen(
            surfaceView.context,
        ).width
        val h = surfaceView.height.takeIf { it > 0 } ?: OverlayCanvas.screen(
            surfaceView.context,
        ).height
        val dpi = AppSession.overlayDpi.takeIf { it > 0 } ?: 420

        val existing = PrivProvider.service() ?: lookupServiceManager() ?: mirrorService
        if (existing != null && existing.asBinder().pingBinder()) {
            mirrorService = existing
            return doStartMirror(displayId, surface, parent, w, h, dpi)
        }

        pendingStart = PendingMirror(displayId, surface, parent, w, h, dpi)
        return bindService()
    }

    fun ensureBound(): String {
        val svc = PrivProvider.service() ?: lookupServiceManager() ?: mirrorService
        if (svc != null && svc.asBinder().pingBinder()) {
            return "already bound"
        }
        mirrorService = null
        bound = false
        return bindService()
    }

    fun pingAlive(): Boolean {
        return try {
            val b = PrivProvider.serviceBinder
                ?: lookupServiceManager()?.asBinder()
                ?: mirrorService?.asBinder()
            b?.pingBinder() == true
        } catch (_: Exception) {
            false
        }
    }

    private fun doStartMirror(
        displayId: Int,
        surface: Surface,
        parent: SurfaceControl?,
        w: Int,
        h: Int,
        dpi: Int,
    ): String {
        return try {
            val svc = PrivProvider.service() ?: lookupServiceManager() ?: mirrorService
            if (svc == null) {
                Log.e(TAG, "svc=null when calling startMirror")
                AppSession.appendLog("M[$displayId] svc=null!")
                return "svc null"
            }
            Log.d(TAG, "calling startMirror d=$displayId ${w}x$h parent=${parent != null}")
            val diag: String? = svc.startMirror(displayId, surface, parent, w, h, dpi)
            Log.d(TAG, "startMirror returned: ${diag?.take(300)}")
            if (diag.isNullOrBlank()) {
                AppSession.appendLog("M[$displayId] returned empty/null")
                return "empty response"
            }
            diag.lines().filter { it.isNotBlank() }.forEach { line ->
                Log.d(TAG, "M[$displayId] $line")
                AppSession.appendLog("M[$displayId] $line")
            }
            "done d=$displayId"
        } catch (e: Exception) {
            Log.e(TAG, "startMirror RPC failed", e)
            val cause = e.cause?.message ?: e.message
            AppSession.appendLog("M[$displayId] FAIL: ${e.javaClass.simpleName}: $cause")
            "fail: $cause"
        }
    }

    private fun bindService(): String {
        val priv = PrivProvider.service() ?: lookupServiceManager()
        if (priv != null) {
            try {
                if (priv.asBinder().pingBinder()) {
                    mirrorService = priv
                    bound = true
                    val pending = pendingStart
                    if (pending != null) {
                        pendingStart = null
                        return doStartMirror(pending.displayId, pending.surface, pending.parent, pending.w, pending.h, pending.dpi)
                    }
                    return "priv daemon"
                }
            } catch (_: Exception) {
                PrivProvider.serviceBinder = null
            }
        }
        if (DaemonShell.isAvailable()) {
            AppSession.appendLog("Mirror: waiting for priv binder")
            return "waiting for priv daemon"
        }
        AppSession.appendLog("Mirror: waiting for priv daemon")
        return "waiting for priv daemon"
    }

    fun stop() {
        pendingStart = null
        val svc = mirrorService
        Thread {
            try { svc?.stopMirror() } catch (_: Exception) {}
        }.start()
    }

    fun release() {
        stop()
        try {
            if (bound && PrivProvider.serviceBinder == null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
            }
        } catch (_: Exception) {}
        if (PrivProvider.serviceBinder == null) {
            mirrorService = null
            bound = false
        }
    }
}
