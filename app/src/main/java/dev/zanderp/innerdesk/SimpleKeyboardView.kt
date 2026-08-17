package dev.zanderp.innerdesk

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class SimpleKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onKey: ((String) -> Unit)? = null

    private val rows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m", "⌫"),
        listOf("space", "enter"),
    )

    init {
        orientation = VERTICAL
        rows.forEach { row ->
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            row.forEach { key ->
                val weight = if (key == "space") 3f else 1f
                line.addView(Button(context).apply {
                    text = key
                    isAllCaps = false
                    textSize = 14f
                    minimumHeight = 0
                    minimumWidth = 0
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                        marginStart = 2
                        marginEnd = 2
                    }
                    setOnClickListener { onKey?.invoke(key) }
                })
            }
            addView(line)
        }
    }
}
