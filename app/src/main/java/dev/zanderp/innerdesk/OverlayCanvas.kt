package dev.zanderp.innerdesk

import android.content.Context
import android.view.WindowManager

object OverlayCanvas {
    data class Screen(val width: Int, val height: Int, val dpi: Int)

    /** Keep the system Overlay # window at top-left, under our accessibility canvas. */
    const val OVERLAY_FLAGS = "own_content_only,should_show_system_decorations,gravity_top_left"

    data class Plan(
        val screen: Screen,
        val scalePercent: Int,
        val canvasW: Int,
        val canvasH: Int,
        val dpi: Int,
    ) {
        val spec: String get() = OverlayCanvas.specString(canvasW, canvasH, dpi)
    }

    fun screen(context: Context): Screen {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val dpi = context.resources.displayMetrics.densityDpi.coerceIn(120, 800)
        return Screen(
            width = bounds.width().coerceAtLeast(1),
            height = bounds.height().coerceAtLeast(1),
            dpi = dpi,
        )
    }

    fun scalePercent(context: Context): Int {
        val prefs = AppSession.prefs(context)
        if (prefs.contains("overlay_scale")) {
            return prefs.getInt("overlay_scale", 100).coerceIn(50, 100)
        }
        if (prefs.contains("overlay_scale_percent")) {
            return prefs.getInt("overlay_scale_percent", 50).coerceIn(50, 100)
        }
        return 100
    }

    fun dpiPref(context: Context): Int {
        val prefs = AppSession.prefs(context)
        if (prefs.contains("overlay_dpi")) {
            val stored = prefs.getInt("overlay_dpi", 0)
            if (stored in 160..560) return stored
        }
        return screen(context).dpi.coerceIn(160, 560)
    }

    fun save(context: Context, scalePercent: Int, dpi: Int) {
        AppSession.prefs(context).edit()
            .putInt("overlay_scale", scalePercent.coerceIn(50, 100))
            .putInt("overlay_dpi", dpi.coerceIn(160, 560))
            .apply()
    }

    /** Accessibility overlay window. Tabletop uses the top half of the inner screen. */
    fun overlayWindowSize(context: Context): Pair<Int, Int> {
        val screen = screen(context)
        if (AppSession.foldLayout?.tabletop == true) {
            val half = (screen.height / 2).coerceAtLeast(240)
            return screen.width to (half + 8).coerceAtMost(screen.height - 120)
        }
        return screen.width to screen.height
    }

    fun physicalTarget(context: Context): Pair<Int, Int> = overlayWindowSize(context)

    fun plan(context: Context, physicalW: Int? = null, physicalH: Int? = null): Plan {
        val screen = screen(context)
        val (targetW, targetH) = overlayWindowSize(context)
        val w = physicalW ?: targetW
        val h = physicalH ?: targetH
        val scale = scalePercent(context)
        val dpi = dpiPref(context)
        var canvasW = ((w * scale) / 100 / 2) * 2
        var canvasH = ((h * scale) / 100 / 2) * 2
        canvasW = canvasW.coerceAtLeast(240)
        canvasH = canvasH.coerceAtLeast(240)
        AppSession.overlayDisplayW = canvasW
        AppSession.overlayDisplayH = canvasH
        AppSession.overlayDpi = dpi
        return Plan(screen, scale, canvasW, canvasH, dpi)
    }

    fun spec(context: Context, physicalW: Int, physicalH: Int): String =
        plan(context, physicalW, physicalH).spec

    fun specString(width: Int, height: Int, dpi: Int): String =
        "${width}x${height}/$dpi,$OVERLAY_FLAGS"

    fun summary(context: Context): String {
        val (dw, dh) = overlayWindowSize(context)
        val p = plan(context)
        val posture = if (AppSession.foldLayout?.tabletop == true) {
            "on top half $dw×$dh"
        } else {
            "on $dw×$dh"
        }
        return "Desktop ${p.canvasW}×${p.canvasH}/${p.dpi} · ${p.scalePercent}% $posture"
    }
}
