package dev.zanderp.innerdesk

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

object DexInput {
    private var mouseX = 80f
    private var mouseY = 80f
    private var mouseDownAt = 0L
    private var hoverEntered = false
    private var pinchDownAt = 0L
    private var pinching = false
    private var lastPinchX = floatArrayOf(40f, 140f)
    private var lastPinchY = floatArrayOf(80f, 80f)

    fun resetPointer() {
        mouseX = (AppSession.overlayDisplayW / 2f).coerceAtLeast(80f)
        mouseY = (AppSession.overlayDisplayH / 2f).coerceAtLeast(80f)
        hoverEntered = false
        endPinchQuiet()
        DexOverlayService.movePointer(mouseX, mouseY)
    }

    fun move(dx: Float, dy: Float) {
        if (pinching) return
        val w = AppSession.overlayDisplayW.coerceAtLeast(1)
        val h = AppSession.overlayDisplayH.coerceAtLeast(1)
        mouseX = (mouseX + dx).coerceIn(0f, (w - 1).toFloat())
        mouseY = (mouseY + dy).coerceIn(0f, (h - 1).toFloat())
        DexOverlayService.movePointer(mouseX, mouseY)
        val now = SystemClock.uptimeMillis()
        if (!hoverEntered) {
            injectMouse(MotionEvent.ACTION_HOVER_ENTER, now, 0)
            hoverEntered = true
        }
        injectMouse(MotionEvent.ACTION_HOVER_MOVE, now, 0)
    }

    fun scroll(dx: Float, dy: Float) {
        val id = DexOverlayService.activeDisplay() ?: return
        val svc = AppSession.overlayMirror.getMirrorService() ?: return
        val h = (-dx / 42f).coerceIn(-4f, 4f)
        val v = (-dy / 42f).coerceIn(-4f, 4f)
        if (h == 0f && v == 0f) return
        try {
            svc.injectScroll(id, mouseX, mouseY, h, v)
        } catch (_: Exception) {}
    }

    fun pinchBegin(px0: Float, py0: Float, px1: Float, py1: Float) {
        endPinchQuiet()
        if (hoverEntered) {
            injectMouse(MotionEvent.ACTION_HOVER_EXIT, SystemClock.uptimeMillis(), 0)
            hoverEntered = false
        }
        val now = SystemClock.uptimeMillis()
        pinchDownAt = now
        pinching = true
        val (x, y) = mapPadFingers(px0, py0, px1, py1)
        injectFingers(MotionEvent.ACTION_DOWN, 1, floatArrayOf(x[0]), floatArrayOf(y[0]), now, now)
        injectFingers(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            x,
            y,
            now,
            now + 8,
        )
    }

    fun pinch(px0: Float, py0: Float, px1: Float, py1: Float) {
        if (!pinching) pinchBegin(px0, py0, px1, py1)
        val now = SystemClock.uptimeMillis()
        val (x, y) = mapPadFingers(px0, py0, px1, py1)
        injectFingers(MotionEvent.ACTION_MOVE, 2, x, y, pinchDownAt, now)
    }

    fun pinchEnd() {
        if (!pinching) return
        val now = SystemClock.uptimeMillis()
        injectFingers(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            lastPinchX.copyOf(),
            lastPinchY.copyOf(),
            pinchDownAt,
            now,
        )
        injectFingers(
            MotionEvent.ACTION_UP,
            1,
            floatArrayOf(lastPinchX[0]),
            floatArrayOf(lastPinchY[0]),
            pinchDownAt,
            now + 8,
        )
        pinching = false
        hoverEntered = false
    }

    private fun mapPadFingers(px0: Float, py0: Float, px1: Float, py1: Float): Pair<FloatArray, FloatArray> {
        val w = (AppSession.overlayDisplayW - 2).toFloat().coerceAtLeast(2f)
        val h = (AppSession.overlayDisplayH - 2).toFloat().coerceAtLeast(2f)
        val cx = (px0 + px1) / 2f
        val cy = (py0 + py1) / 2f
        val s = 1.15f
        val x0 = (mouseX + (px0 - cx) * s).coerceIn(1f, w)
        val y0 = (mouseY + (py0 - cy) * s).coerceIn(1f, h)
        val x1 = (mouseX + (px1 - cx) * s).coerceIn(1f, w)
        val y1 = (mouseY + (py1 - cy) * s).coerceIn(1f, h)
        lastPinchX = floatArrayOf(x0, x1)
        lastPinchY = floatArrayOf(y0, y1)
        return lastPinchX to lastPinchY
    }

    fun click() {
        val id = DexOverlayService.activeDisplay() ?: return
        val svc = AppSession.overlayMirror.getMirrorService() ?: return
        val now = SystemClock.uptimeMillis()
        mouseDownAt = now
        try {
            svc.injectPointer(id, MotionEvent.ACTION_DOWN, mouseX, mouseY, now, now)
            svc.injectPointer(id, MotionEvent.ACTION_UP, mouseX, mouseY, now, now + 16)
        } catch (_: Exception) {
            injectMouse(MotionEvent.ACTION_DOWN, now, MotionEvent.BUTTON_PRIMARY)
            injectMouse(MotionEvent.ACTION_UP, now + 16, 0)
        }
        DexOverlayService.scheduleImeCheck()
    }

    fun clickRight() {
        val now = SystemClock.uptimeMillis()
        mouseDownAt = now
        injectMouse(MotionEvent.ACTION_DOWN, now, MotionEvent.BUTTON_SECONDARY)
        injectMouse(MotionEvent.ACTION_UP, now + 16, 0)
    }

    fun injectKey(action: Int, keyCode: Int) {
        val id = DexOverlayService.activeDisplay() ?: return
        try {
            AppSession.overlayMirror.getMirrorService()?.injectKey(id, action, keyCode)
        } catch (_: Exception) {}
    }

    fun injectText(text: String) {
        val id = DexOverlayService.activeDisplay() ?: return
        try {
            AppSession.overlayMirror.getMirrorService()?.injectText(id, text)
        } catch (_: Exception) {
            text.forEach { ch ->
                val code = when (ch) {
                    ' ' -> KeyEvent.KEYCODE_SPACE
                    '\n' -> KeyEvent.KEYCODE_ENTER
                    else -> 0
                }
                if (code != 0) {
                    injectKey(KeyEvent.ACTION_DOWN, code)
                    injectKey(KeyEvent.ACTION_UP, code)
                }
            }
        }
    }

    private fun endPinchQuiet() {
        if (!pinching) return
        pinching = false
    }

    private fun injectFingers(action: Int, count: Int, x: FloatArray, y: FloatArray, downTime: Long, eventTime: Long) {
        val id = DexOverlayService.activeDisplay() ?: return
        try {
            AppSession.overlayMirror.getMirrorService()?.injectFingers(id, action, count, x, y, downTime, eventTime)
        } catch (_: Exception) {}
    }

    private fun injectMouse(action: Int, now: Long, buttons: Int) {
        val id = DexOverlayService.activeDisplay() ?: return
        val svc = AppSession.overlayMirror.getMirrorService() ?: return
        try {
            if (buttons == MotionEvent.BUTTON_SECONDARY) {
                svc.injectMouseButtons(id, action, mouseX, mouseY, mouseDownAt, now, buttons)
            } else {
                svc.injectMouse(id, action, mouseX, mouseY, mouseDownAt.takeIf { it > 0 } ?: now, now)
            }
        } catch (_: Exception) {}
    }
}
