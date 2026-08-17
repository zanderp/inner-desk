package dev.zanderp.innerdesk

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class DexImeAnchor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    init {
        isCursorVisible = false
        showSoftInputOnFocus = false
        isFocusable = true
        isFocusableInTouchMode = true
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) DexInput.injectText(value)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    DexInput.injectKey(event.action, event.keyCode)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) {
                    DexInput.injectKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                    DexInput.injectKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                DexInput.injectKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                DexInput.injectKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }
}
