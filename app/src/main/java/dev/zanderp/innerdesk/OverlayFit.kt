package dev.zanderp.innerdesk

import kotlin.math.min
import kotlin.math.roundToInt

object OverlayFit {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int, val scale: Float)

    fun contain(srcW: Int, srcH: Int, destW: Int, destH: Int): Box {
        if (srcW <= 0 || srcH <= 0 || destW <= 0 || destH <= 0) {
            return Box(0, 0, destW.coerceAtLeast(1), destH.coerceAtLeast(1), 1f)
        }
        val scale = min(destW.toFloat() / srcW, destH.toFloat() / srcH)
        val w = (srcW * scale).roundToInt().coerceAtLeast(1)
        val h = (srcH * scale).roundToInt().coerceAtLeast(1)
        return Box((destW - w) / 2, (destH - h) / 2, w, h, scale)
    }
}
