package com.cloudwhile.tscc.data.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Converts TeamSpeak BBCode (`[b]`, `[i]`, `[u]`, `[s]`, `[color=#RRGGBB]`, `[url=...]`)
 * and bare HTTP(S) / `tscc://` links into a Compose [AnnotatedString] with `"URL"` annotations.
 */
object BBCodeParser {

    const val URL_TAG = "URL"

    private val tagRegex = Regex(
        """\[(/)?(b|i|u|s|color|url)(?:=([^\]]+))?]""",
        RegexOption.IGNORE_CASE
    )
    private val bareUrlRegex = Regex(
        """(https?://[^\s\["'<>，。！？；：、（）【】「」『』《》\u3000]+|tscc://[^\s\["'<>，。！？；：、（）【】「」『』《》\u3000]+)""",
        RegexOption.IGNORE_CASE
    )

    private sealed interface OpenTag {
        val start: Int
        data class Bold(override val start: Int) : OpenTag
        data class Italic(override val start: Int) : OpenTag
        data class Underline(override val start: Int) : OpenTag
        data class Strike(override val start: Int) : OpenTag
        data class Colored(override val start: Int, val color: Color) : OpenTag
        data class Url(override val start: Int, val explicitHref: String?) : OpenTag
    }

    fun parse(
        raw: String,
        linkColor: Color = Color(0xFF38BDF8)
    ): AnnotatedString = buildAnnotatedString {
        if (raw.isEmpty()) return@buildAnnotatedString

        val stack = ArrayDeque<OpenTag>()
        val urlRanges = mutableListOf<IntRange>()
        val plainBuf = StringBuilder()
        var cursor = 0

        fun appendPlain(chunk: String) {
            append(chunk)
            plainBuf.append(chunk)
        }

        for (match in tagRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                appendPlain(raw.substring(cursor, match.range.first))
            }
            val isClosing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase(java.util.Locale.ROOT)
            val arg = match.groupValues[3].takeIf { it.isNotBlank() }
            val outPos = plainBuf.length

            if (!isClosing) {
                when (name) {
                    "b" -> stack.addLast(OpenTag.Bold(outPos))
                    "i" -> stack.addLast(OpenTag.Italic(outPos))
                    "u" -> stack.addLast(OpenTag.Underline(outPos))
                    "s" -> stack.addLast(OpenTag.Strike(outPos))
                    "color" -> {
                        val parsed = parseColor(arg)
                        if (parsed != null) {
                            stack.addLast(OpenTag.Colored(outPos, parsed))
                        }
                    }
                    "url" -> stack.addLast(OpenTag.Url(outPos, arg))
                }
            } else {
                val idx = stack.indexOfLast { openTagMatches(it, name) }
                if (idx >= 0) {
                    val tag = stack.removeAt(idx)
                    if (outPos > tag.start) {
                        if (tag is OpenTag.Url) {
                            urlRanges += (tag.start until outPos)
                        }
                        applyClosedTag(tag, outPos, plainBuf, linkColor)
                    }
                }
            }

            cursor = match.range.last + 1
        }

        if (cursor < raw.length) {
            appendPlain(raw.substring(cursor))
        }

        // Auto-link any bare URLs not already covered by [url] tags
        val plain = plainBuf.toString()
        for (m in bareUrlRegex.findAll(plain)) {
            val start = m.range.first
            val end = m.range.last + 1
            val overlaps = urlRanges.any { start <= it.last && end > it.first }
            if (!overlaps) {
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start,
                    end
                )
                addStringAnnotation(URL_TAG, m.value, start, end)
            }
        }
    }

    private fun AnnotatedString.Builder.applyClosedTag(
        tag: OpenTag,
        end: Int,
        plainBuf: StringBuilder,
        linkColor: Color
    ) {
        when (tag) {
            is OpenTag.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), tag.start, end)
            is OpenTag.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), tag.start, end)
            is OpenTag.Underline -> addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                tag.start,
                end
            )
            is OpenTag.Strike -> addStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
                tag.start,
                end
            )
            is OpenTag.Colored -> addStyle(SpanStyle(color = tag.color), tag.start, end)
            is OpenTag.Url -> {
                val innerText = plainBuf.substring(tag.start, end)
                val href = tag.explicitHref?.trim() ?: innerText.trim()
                if (href.isNotEmpty()) {
                    addStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        tag.start,
                        end
                    )
                    addStringAnnotation(URL_TAG, href, tag.start, end)
                }
            }
        }
    }

    private fun openTagMatches(tag: OpenTag, name: String): Boolean = when (name) {
        "b" -> tag is OpenTag.Bold
        "i" -> tag is OpenTag.Italic
        "u" -> tag is OpenTag.Underline
        "s" -> tag is OpenTag.Strike
        "color" -> tag is OpenTag.Colored
        "url" -> tag is OpenTag.Url
        else -> false
    }

    private fun parseColor(raw: String?): Color? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().lowercase(java.util.Locale.ROOT)
        val named = mapOf(
            "red" to 0xFFEF4444,
            "green" to 0xFF22C55E,
            "blue" to 0xFF3B82F6,
            "yellow" to 0xFFEAB308,
            "cyan" to 0xFF06B6D4,
            "orange" to 0xFFF97316,
            "white" to 0xFFFFFFFF,
            "gray" to 0xFF94A3B8
        )
        named[clean]?.let { return Color(it) }
        if (clean.startsWith("#")) {
            val hex = clean.removePrefix("#")
            if (hex.length == 6) {
                val rgb = hex.toLongOrNull(16) ?: return null
                return Color(0xFF000000L or rgb)
            }
        }
        return null
    }
}
