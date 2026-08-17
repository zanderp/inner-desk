package dev.zanderp.innerdesk

import android.text.Spanned
import androidx.core.text.HtmlCompat

/**
 * Formats GitHub release-note Markdown for in-app dialogs (headings, bold, bullets, links).
 */
object ReleaseNotes {

    fun toSpanned(markdown: String, maxChars: Int = 4000): Spanned {
        val src = markdown.trim().take(maxChars)
        if (src.isEmpty()) {
            return HtmlCompat.fromHtml(
                "A newer InnerDesk release is available.",
                HtmlCompat.FROM_HTML_MODE_LEGACY,
            )
        }
        return HtmlCompat.fromHtml(markdownToHtml(src), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    internal fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        for (rawLine in md.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() || trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    out.append("<br>")
                }
                trimmed.startsWith("#") -> {
                    val title = trimmed.trimStart('#').trim()
                    if (title.isNotEmpty()) {
                        out.append("<b>").append(formatInline(title)).append("</b><br>")
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    out.append("• ").append(formatInline(trimmed.substring(2).trim())).append("<br>")
                }
                else -> {
                    out.append(formatInline(trimmed)).append("<br>")
                }
            }
        }
        return out.toString().removeSuffix("<br>")
    }

    private fun formatInline(text: String): String {
        val holds = mutableListOf<String>()
        fun hold(html: String): String {
            holds += html
            return "\uE000${holds.lastIndex}\uE001"
        }
        var s = text
        s = LINK_RE.replace(s) { m ->
            hold("<a href=\"${escapeAttr(m.groupValues[2])}\">${escape(m.groupValues[1])}</a>")
        }
        s = BOLD_RE.replace(s) { m ->
            hold("<b>${escape(m.groupValues[1])}</b>")
        }
        s = escape(s)
        holds.forEachIndexed { i, html ->
            s = s.replace("\uE000$i\uE001", html)
        }
        return s
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escape(s).replace("\"", "&quot;")

    private val BOLD_RE = Regex("""\*\*(.+?)\*\*""")
    private val LINK_RE = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
}
