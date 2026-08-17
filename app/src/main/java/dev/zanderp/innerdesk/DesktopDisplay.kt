package dev.zanderp.innerdesk

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView

class DesktopDisplay(context: android.content.Context) {
    private val app = context.applicationContext
    var virtualDisplay: VirtualDisplay? = null
        private set
    var width = 1920
        private set
    var height = 1200
        private set
    var dpi = 420
        private set
    private var lastTouchX = -1
    private var lastTouchY = -1
    val displayId: Int?
        get() = virtualDisplay?.display?.displayId

    var needsHome = true
        private set
    var flagMode = "none"
        private set

    fun markHomeStarted() {
        needsHome = false
    }

    fun ensure(viewW: Int, viewH: Int, surface: Surface): Boolean {
        if (virtualDisplay != null && viewW == width && viewH == height) {
            attach(surface)
            return true
        }
        if (virtualDisplay != null) release()
        if (viewW < 200 || viewH < 200) return false
        width = viewW
        height = viewH
        dpi = app.resources.displayMetrics.densityDpi.coerceIn(320, 640)
        val dm = app.getSystemService(DisplayManager::class.java)
        flagMode = "none"
        for ((name, flags) in listOf(
            "shared" to FLAGS_SHARED,
            "public" to FLAGS_PUBLIC,
            "own" to FLAGS_TRUSTED,
            "safe" to FLAGS_SAFE,
        )) {
            try {
                virtualDisplay = dm.createVirtualDisplay("InnerDesk", width, height, dpi, surface, flags)
                flagMode = name
                break
            } catch (_: Exception) {
            }
        }
        needsHome = true
        return virtualDisplay != null
    }

    fun attach(surface: Surface) {
        try {
            virtualDisplay?.surface = surface
        } catch (_: Exception) {
        }
    }

    fun launchHome(activity: Activity, shell: PrivilegedShell): String {
        val id = displayId ?: return "no display"
        val resolved = activity.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val component = resolved?.activityInfo?.let { "${it.packageName}/${it.name}" }
            ?: "com.sec.android.app.launcher/.activities.LauncherActivity"
        val out = StringBuilder()
        out.append(shell.run("wm size -d $id ${width}x$height")).append('\n')
        out.append(shell.run("wm density -d $id $dpi")).append('\n')
        out.append(shell.run("am start --display $id -n $component")).append('\n')
        out.append("wm=").append(shell.run("wm size -d $id").replace('\n', ' ').take(80)).append('\n')
        return "home=$component display=$id ${width}x$height dpi=$dpi flags=$flagMode\n${out.toString().trim()}"
    }

    fun stayForeground(activity: Activity) {
        try {
            val self = Intent(activity, activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(self)
        } catch (_: Exception) {
        }
        try {
            val am = activity.getSystemService(android.app.ActivityManager::class.java)
            am.moveTaskToFront(activity.taskId, 0)
        } catch (_: Exception) {
        }
    }

    fun injectTouch(view: SurfaceView, ev: MotionEvent): Boolean {
        val id = displayId ?: return false
        if (view.width <= 0 || view.height <= 0) return false
        val x = ev.x * width / view.width
        val y = ev.y * height / view.height
        val mapped = MotionEvent.obtain(
            ev.downTime,
            ev.eventTime,
            ev.action,
            x,
            y,
            ev.pressure,
            ev.size,
            ev.metaState,
            ev.xPrecision,
            ev.yPrecision,
            ev.deviceId,
            ev.edgeFlags,
        )
        mapped.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(mapped, id)
        } catch (_: Exception) {
            mapped.recycle()
            return false
        }
        return try {
            val im = app.getSystemService(InputManager::class.java)
            val method = InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(im, mapped, 0) == true
        } catch (_: Exception) {
            false
        } finally {
            mapped.recycle()
        }
    }

    fun mapTouch(view: SurfaceView, ev: MotionEvent): String? {
        val id = displayId ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val x = (ev.x * width / view.width).toInt().coerceIn(0, width - 1)
        val y = (ev.y * height / view.height).toInt().coerceIn(0, height - 1)
        return when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> "input -d $id tap $x $y"
            else -> null
        }
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        needsHome = true
    }

    companion object {
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val TRUSTED = 1 shl 10
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private val FLAGS_SAFE =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                SUPPORTS_TOUCH or SHOW_SYSTEM_DECORATIONS
        private val FLAGS_TRUSTED = FLAGS_SAFE or TRUSTED
        private val FLAGS_SHARED =
            PRESENTATION or SUPPORTS_TOUCH or SHOW_SYSTEM_DECORATIONS or TRUSTED
        private val FLAGS_PUBLIC =
            PUBLIC or PRESENTATION or SUPPORTS_TOUCH or SHOW_SYSTEM_DECORATIONS or TRUSTED
    }
}
