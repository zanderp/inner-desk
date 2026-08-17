package dev.zanderp.innerdesk

import android.view.MotionEvent
import android.view.View

object OverlayTouch {
    fun handle(v: View, ev: MotionEvent, displayId: Int): Boolean {
        if (displayId < 0) return true
        val tw = AppSession.overlayDisplayW.takeIf { it > 0 } ?: v.width
        val th = AppSession.overlayDisplayH.takeIf { it > 0 } ?: v.height
        val vw = v.width.coerceAtLeast(1)
        val vh = v.height.coerceAtLeast(1)
        val n = ev.pointerCount.coerceAtLeast(1)
        val xs = FloatArray(n) { i -> ev.getX(i) * tw / vw }
        val ys = FloatArray(n) { i -> ev.getY(i) * th / vh }
        val svc = AppSession.overlayMirror.getMirrorService() ?: return true
        try {
            svc.injectFingers(displayId, ev.action, n, xs, ys, ev.downTime, ev.eventTime)
        } catch (_: Exception) {
            if (ev.pointerCount == 1) {
                try {
                    svc.injectPointer(
                        displayId,
                        ev.actionMasked,
                        xs[0],
                        ys[0],
                        ev.downTime,
                        ev.eventTime,
                    )
                } catch (_: Exception) {}
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
        return true
    }
}
