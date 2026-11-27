package com.cloudwhile.tscc.data.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import java.util.Locale

enum class MdAlertKind {
    NOTE,
    TIP,
    IMPORTANT,
    WARNING,
    CAUTION
}

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Alert(val kind: MdAlertKind, val lines: List<String>) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class BulletItem(val indentLevel: Int, val marker: String, val text: String) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Divider : MdBlock
}

object ReleaseNotesParser {

    const val URL_TAG = "MD_URL"

    private val htmlCommentRegex = Regex("<!--[\\s\\S]*?-->")
    private val enMarkerRegex = Regex(
        "<!--\\s*TSCC_NOTES_EN_START\\s*-->([\\s\\S]*?)<!--\\s*TSCC_NOTES_EN_END\\s*-->",
        RegexOption.IGNORE_CASE
    )
    private val zhMarkerRegex = Regex(
        "<!--\\s*TSCC_NOTES_ZH_START\\s*-->([\\s\\S]*?)<!--\\s*TSCC_NOTES_ZH_END\\s*-->",
        RegexOption.IGNORE_CASE
    )
    private val alertHeaderRegex = Regex(
        "^\\[!\\s*(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\s*]\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    private val dividerRegex = Regex("^\\s*(?:[-]{3,}|[*]{3,}|[_]{3,})\\s*$")
    private val unorderedListRegex = Regex("^(\\s*)[-*+]\\s+(.+)$")
    private val orderedListRegex = Regex("^(\\s*)(\\d+\\.)\\s+(.+)$")
    private val tableSeparatorCellRegex = Regex("^:?-{2,}:?$")

    private val inlineTokenRegex = Regex(
        "`([^`\\n]+)`" +
            "|\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)" +
            "|\\*\\*\\*([^*\\n]+)\\*\\*\\*" +
            "|\\*\\*([^*\\n]+)\\*\\*" +
            "|~~([^~\\n]+)~~" +
            "|(?<![*\\w])\\*([^\\s*][^*\\n]*?[^\\s*]|[^\\s*])\\*(?![*\\w])" +
            "|(https?://[^\\s<>()]+)"
    )

    fun extractEmbeddedNotes(rawBody: String): Pair<String?, String?> {
        val en = enMarkerRegex.find(rawBody)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val zh = zhMarkerRegex.find(rawBody)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return en to zh
    }

    fun cleanMarkdownBody(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(htmlCommentRegex, "")
            .trim()
    }

    fun parseBlocks(raw: String): List<MdBlock> {
        val cleaned = cleanMarkdownBody(raw)
        if (cleaned.isEmpty()) return emptyList()

        val lines = cleaned.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block
            if (trimmed.startsWith("```")) {
                i++
                val codeLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing fence
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n").trimEnd()))
                continue
            }

            // 2. Blockquote or GitHub Alert (> [!WARNING] ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    val content = lines[i].trim().removePrefix(">").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    quoteLines.add(content)
                    i++
                }
                val firstNonEmptyIdx = quoteLines.indexOfFirst { it.isNotBlank() }
                if (firstNonEmptyIdx >= 0) {
                    val alertMatch = alertHeaderRegex.matchEntire(quoteLines[firstNonEmptyIdx].trim())
                    if (alertMatch != null) {
                        val kind = when (alertMatch.groupValues[1].uppercase(Locale.ROOT)) {
                            "TIP" -> MdAlertKind.TIP
                            "IMPORTANT" -> MdAlertKind.IMPORTANT
                            "WARNING" -> MdAlertKind.WARNING
                            "CAUTION" -> MdAlertKind.CAUTION
                            else -> MdAlertKind.NOTE
                        }
                        val inlineRest = alertMatch.groupValues[2].trim()
                        val bodyLines = buildList {
                            if (inlineRest.isNotEmpty()) add(inlineRest)
                            quoteLines.drop(firstNonEmptyIdx + 1)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        }
                        blocks.add(MdBlock.Alert(kind = kind, lines = bodyLines))
                        continue
                    }
                }
                val nonBlankQuotes = quoteLines.map { it.trim() }.filter { it.isNotEmpty() }
                if (nonBlankQuotes.isNotEmpty()) {
                    blocks.add(MdBlock.Quote(nonBlankQuotes))
                }
                continue
            }

            // 3. ATX Heading (# .. ######)
            val headingMatch = headingRegex.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                val title = headingMatch.groupValues[2].trim()
                blocks.add(MdBlock.Heading(level = level, text = title))
                i++
                continue
            }

            // 4. Horizontal Rule
            if (dividerRegex.matches(trimmed)) {
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            // 5. Markdown Table
            if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparatorRow(lines[i + 1])) {
                val headers = parseTableCells(trimmed)
                i += 2 // skip header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val rowCells = parseTableCells(lines[i].trim())
                    if (rowCells.isNotEmpty()) {
                        rows.add(rowCells)
                    }
                    i++
                }
                if (headers.isNotEmpty()) {
                    blocks.add(MdBlock.TableBlock(headers = headers, rows = rows))
                }
                continue
            }

            // 6. Unordered or Ordered List Item (with wrapped continuation line support)
            val ulMatch = unorderedListRegex.matchEntire(line)
            val olMatch = if (ulMatch == null) orderedListRegex.matchEntire(line) else null
            if (ulMatch != null || olMatch != null) {
                val leadingSpaces = (ulMatch?.groupValues?.get(1) ?: olMatch!!.groupValues[1]).length
                val indentLevel = (leadingSpaces / 2).coerceIn(0, 3)
                val marker = if (ulMatch != null) {
                    if (indentLevel == 0) "•" else "◦"
                } else {
                    olMatch!!.groupValues[2]
                }
                val itemBuilder = StringBuilder(
                    (ulMatch?.groupValues?.get(2) ?: olMatch!!.groupValues[3]).trim()
                )
                i++
                while (i < lines.size) {
                    val nextLine = lines[i]
                    val nextTrim = nextLine.trim()
                    if (nextTrim.isEmpty() ||
                        nextTrim.startsWith("#") ||
                        nextTrim.startsWith(">") ||
                        nextTrim.startsWith("```") ||
                        nextTrim.startsWith("|") ||
                        dividerRegex.matches(nextTrim) ||
                        unorderedListRegex.matches(nextLine) ||
                        orderedListRegex.matches(nextLine)
                    ) {
                        break
                    }
                    if (nextLine.startsWith("  ") || nextLine.startsWith("\t")) {
                        itemBuilder.append(' ').append(nextTrim)
                        i++
                    } else {
                        break
                    }
                }
                blocks.add(
                    MdBlock.BulletItem(
                        indentLevel = indentLevel,
                        marker = marker,
                        text = itemBuilder.toString()
                    )
                )
                continue
            }

            // 7. Normal Paragraph
            val paraLines = mutableListOf(trimmed)
            i++
            while (i < lines.size) {
                val nextLine = lines[i]
                val nextTrim = nextLine.trim()
                if (nextTrim.isEmpty() ||
                    nextTrim.startsWith("#") ||
                    nextTrim.startsWith(">") ||
                    nextTrim.startsWith("```") ||
                    dividerRegex.matches(nextTrim) ||
                    (nextTrim.startsWith("|") && i + 1 < lines.size && isTableSeparatorRow(lines[i + 1])) ||
                    unorderedListRegex.matches(nextLine) ||
                    orderedListRegex.matches(nextLine)
                ) {
                    break
                }
                paraLines.add(nextTrim)
                i++
            }
            blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
        }

        return blocks
    }

    fun parseInline(
        raw: String,
        codeBg: Color = Color(0x266366F1),
        codeFg: Color = Color(0xFF818CF8),
        linkColor: Color = Color(0xFF6366F1)
    ): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        for (match in inlineTokenRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }

            val codeSpan = match.groups[1]?.value
            val linkLabel = match.groups[2]?.value
            val linkHref = match.groups[3]?.value
            val boldItalic = match.groups[4]?.value
            val bold = match.groups[5]?.value
            val strike = match.groups[6]?.value
            val italic = match.groups[7]?.value
            val bareUrl = match.groups[8]?.value

            when {
                codeSpan != null -> {
                    val start = length
                    append(" $codeSpan ")
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            background = codeBg,
                            color = codeFg
                        ),
                        start,
                        length
                    )
                }

                linkLabel != null && linkHref != null -> {
                    val start = length
                    append(linkLabel)
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        ),
                        start,
                        length
                    )
                    addStringAnnotation(URL_TAG, linkHref, start, length)
                }

                boldItalic != null -> {
                    val start = length
                    append(boldItalic)
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                        start,
                        length
                    )
                }

                bold != null -> {
                    val start = length
                    append(bold)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }

                strike != null -> {
                    val start = length
                    append(strike)
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }

                italic != null -> {
                    val start = length
                    append(italic)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }

                bareUrl != null -> {
                    val cleanUrl = bareUrl.trimEnd('.', ',', ';', ')')
                    val trailing = bareUrl.substring(cleanUrl.length)
                    val start = length
                    append(cleanUrl)
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ),
                        start,
                        length
                    )
                    addStringAnnotation(URL_TAG, cleanUrl, start, length)
                    if (trailing.isNotEmpty()) append(trailing)
                }
            }

            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            append(raw.substring(cursor))
        }
    }

    private fun isTableSeparatorRow(line: String): Boolean {
        val cells = parseTableCells(line.trim())
        return cells.isNotEmpty() && cells.all { tableSeparatorCellRegex.matches(it) }
    }

    private fun parseTableCells(line: String): List<String> {
        val stripped = line.trim().removePrefix("|").removeSuffix("|")
        if (stripped.isBlank()) return emptyList()
        return stripped.split('|').map { it.trim() }
    }
}
