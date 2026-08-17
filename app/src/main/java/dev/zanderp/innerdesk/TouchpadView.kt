package dev.zanderp.innerdesk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onClick: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onScroll: ((dx: Float, dy: Float) -> Unit)? = null
    var onPinchBegin: ((x0: Float, y0: Float, x1: Float, y1: Float) -> Unit)? = null
    var onPinch: ((x0: Float, y0: Float, x1: Float, y1: Float) -> Unit)? = null
    var onPinchEnd: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var maxPointers = 1
    private var twoFinger = false
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 0f
    private var gesture = Gesture.None
    private var scrollSide = 0
    private val dots = FloatArray(8)
    private var dotCount = 0
    private val pad = RectF()
    private val leftWell = RectF()
    private val rightWell = RectF()
    private val grip = RectF()
    private val chevron = Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2C.toInt()
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E3E42.toInt()
        style = Paint.Style.STROKE
        strokeWidth = d
    }
    private val wellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF353538.toInt()
        style = Paint.Style.STROKE
        strokeWidth = d * 0.8f
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d * 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d * 1.15f
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
    }

    private enum class Gesture { None, Scroll, Pinch }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutWells()
    }

    private fun layoutWells() {
        pad.set(0f, 0f, width.toFloat(), height.toFloat())
        val inset = 10f * d
        val zw = (56f * d).coerceIn(48f * d, width * 0.16f)
        leftWell.set(inset, inset, inset + zw, height - inset)
        rightWell.set(width - inset - zw, inset, width - inset, height - inset)
    }

    private fun inLeftWell(x: Float, y: Float) = leftWell.contains(x, y)
    private fun inRightWell(x: Float, y: Float) = rightWell.contains(x, y)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downAt = System.currentTimeMillis()
                moved = false
                maxPointers = 1
                twoFinger = false
                gesture = Gesture.None
                scrollSide = when {
                    inLeftWell(event.x, event.y) -> -1
                    inRightWell(event.x, event.y) -> 1
                    else -> 0
                }
                captureDots(event)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (scrollSide != 0) return true
                maxPointers = maxOf(maxPointers, event.pointerCount)
                twoFinger = true
                val c = centroid(event)
                lastCx = c.first
                lastCy = c.second
                lastSpan = span(event)
                captureDots(event)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (scrollSide != 0) {
                    val dy = event.y - lastY
                    if (abs(event.y - downY) > 6) moved = true
                    if (dy != 0f) onScroll?.invoke(0f, dy)
                    lastX = event.x
                    lastY = event.y
                } else if (event.pointerCount >= 2) {
                    twoFinger = true
                    val c = centroid(event)
                    val s = span(event)
                    val dcx = c.first - lastCx
                    val dcy = c.second - lastCy
                    val ds = s - lastSpan
                    if (abs(dcx) + abs(dcy) > 8 || abs(ds) > 8) moved = true
                    if (gesture == Gesture.None) {
                        gesture = when {
                            abs(ds) > 16 && abs(ds) >= abs(dcx) + abs(dcy) -> {
                                onPinchBegin?.invoke(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                                Gesture.Pinch
                            }
                            abs(dcx) + abs(dcy) > 8 -> Gesture.Scroll
                            else -> Gesture.None
                        }
                    }
                    when (gesture) {
                        Gesture.Scroll -> onScroll?.invoke(dcx, dcy)
                        Gesture.Pinch -> onPinch?.invoke(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                        Gesture.None -> {}
                    }
                    lastCx = c.first
                    lastCy = c.second
                    lastSpan = s
                } else if (!twoFinger && gesture == Gesture.None) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) + abs(event.y - downY) > 12) moved = true
                    onMove?.invoke(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
                captureDots(event)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (scrollSide == 0 && gesture == Gesture.Pinch) {
                    onPinchEnd?.invoke()
                    gesture = Gesture.None
                }
                captureDots(event)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (gesture == Gesture.Pinch) onPinchEnd?.invoke()
                if (scrollSide == 0 && !moved && System.currentTimeMillis() - downAt < 280) {
                    if (maxPointers >= 2) onRightClick?.invoke() else onClick?.invoke()
                }
                gesture = Gesture.None
                twoFinger = false
                scrollSide = 0
                dotCount = 0
                invalidate()
            }
        }
        return true
    }

    private fun centroid(ev: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until ev.pointerCount) {
            x += ev.getX(i)
            y += ev.getY(i)
        }
        val n = ev.pointerCount.coerceAtLeast(1)
        return x / n to y / n
    }

    private fun span(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        return hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
    }

    private fun captureDots(ev: MotionEvent) {
        if (scrollSide != 0) {
            dotCount = 1
            dots[0] = ev.x
            dots[1] = ev.y
            return
        }
        dotCount = ev.pointerCount.coerceAtMost(4)
        for (i in 0 until dotCount) {
            dots[i * 2] = ev.getX(i)
            dots[i * 2 + 1] = ev.getY(i)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (leftWell.isEmpty) layoutWells()
        val r = 28f * d
        canvas.drawRoundRect(pad, r, r, bg)
        canvas.drawRoundRect(pad, r, r, stroke)
        val wellR = 18f * d
        drawScrollWell(canvas, leftWell, wellR, scrollSide < 0)
        drawScrollWell(canvas, rightWell, wellR, scrollSide > 0)
        val radius = 22f * d
        for (i in 0 until dotCount) {
            canvas.drawCircle(dots[i * 2], dots[i * 2 + 1], radius, dot)
        }
    }

    private fun drawScrollWell(canvas: Canvas, well: RectF, radius: Float, pressed: Boolean) {
        wellFill.color = if (pressed) 0xFF3A3A3E.toInt() else 0xFF1C1C1E.toInt()
        canvas.drawRoundRect(well, radius, radius, wellFill)
        canvas.drawRoundRect(well, radius, radius, wellStroke)

        val cx = well.centerX()
        val active = if (pressed) 0x88FFFFFF.toInt() else 0x3DFFFFFF
        gripPaint.color = active
        chevronPaint.color = active
        tickPaint.color = if (pressed) 0x55FFFFFF else 0x28FFFFFF

        val gripW = 4f * d
        val gripH = well.height() * 0.22f
        grip.set(cx - gripW / 2f, well.centerY() - gripH / 2f, cx + gripW / 2f, well.centerY() + gripH / 2f)
        canvas.drawRoundRect(grip, gripW, gripW, gripPaint)

        val tickTop = well.top + 28f * d
        val tickBot = well.bottom - 28f * d
        val step = (tickBot - tickTop) / 10f
        if (step > d) {
            var y = tickTop
            while (y <= tickBot + 0.5f) {
                if (y < grip.top - 4f * d || y > grip.bottom + 4f * d) {
                    canvas.drawLine(cx - 5f * d, y, cx + 5f * d, y, tickPaint)
                }
                y += step
            }
        }

        drawChevron(canvas, cx, well.top + 14f * d, up = true)
        drawChevron(canvas, cx, well.bottom - 14f * d, up = false)
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        val w = 7f * d
        val h = 5f * d
        chevron.reset()
        if (up) {
            chevron.moveTo(cx - w, cy + h / 2f)
            chevron.lineTo(cx, cy - h / 2f)
            chevron.lineTo(cx + w, cy + h / 2f)
        } else {
            chevron.moveTo(cx - w, cy - h / 2f)
            chevron.lineTo(cx, cy + h / 2f)
            chevron.lineTo(cx + w, cy - h / 2f)
        }
        canvas.drawPath(chevron, chevronPaint)
    }
}
