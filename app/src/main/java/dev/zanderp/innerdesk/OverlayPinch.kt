package dev.zanderp.innerdesk

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin

object OverlayPinch {
    @JvmStatic
    fun main(args: Array<String>) {
        val cx = args.getOrNull(0)?.toFloatOrNull() ?: 400f
        val cy = args.getOrNull(1)?.toFloatOrNull() ?: 400f
        val startR = args.getOrNull(2)?.toFloatOrNull() ?: 80f
        val endR = args.getOrNull(3)?.toFloatOrNull() ?: 600f
        val im = InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
        println(pinchOut(im, cx, cy, startR, endR))
    }

    fun pinchOut(app: android.content.Context, cx: Float, cy: Float, startR: Float, endR: Float): String {
        val im = app.getSystemService(InputManager::class.java)
        return pinchOut(im, cx, cy, startR, endR)
    }

    fun pinchOut(im: InputManager, cx: Float, cy: Float, startR: Float, endR: Float): String {
        val inject = try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
        } catch (e: Exception) {
            return "no inject ${e.message}"
        }
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, r: Float, pointers: Int, t: Long): Boolean {
            val coords = Array(pointers) { i ->
                val a = if (i == 0) 0.0 else Math.PI
                MotionEvent.PointerCoords().apply {
                    x = (cx + r * cos(a)).toFloat()
                    y = (cy + r * sin(a)).toFloat()
                    pressure = 1f
                    size = 1f
                }
            }
            val usedProps = if (pointers == 1) arrayOf(props[0]) else props
            val ev = MotionEvent.obtain(
                downTime, t, action, pointers, usedProps, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(ev, 0)
            } catch (_: Exception) {
            }
            return try {
                inject.invoke(im, ev, 0) == true
            } catch (e: Exception) {
                false
            } finally {
                ev.recycle()
            }
        }
        var t = downTime
        if (!send(MotionEvent.ACTION_DOWN, startR, 1, t)) return "pinch down failed"
        t += 16
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        if (!send(pointerDown, startR, 2, t)) return "pinch pointer-down failed"
        val steps = 16
        for (i in 1..steps) {
            t += 16
            val r = startR + (endR - startR) * i / steps
            send(MotionEvent.ACTION_MOVE, r, 2, t)
        }
        t += 16
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(pointerUp, endR, 2, t)
        t += 16
        send(MotionEvent.ACTION_UP, endR, 1, t)
        return "pinch $startR->$endR at $cx,$cy"
    }
}
