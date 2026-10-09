package com.ankiminer.android.ui.attribution

internal const val MAX_NOTICE_BLOCK_CHARS = 2_000

internal sealed interface NoticeBlock {
    val text: String

    data class Heading(
        val level: Int,
        override val text: String,
    ) : NoticeBlock

    data class Paragraph(
        override val text: String,
    ) : NoticeBlock

    data class Bullet(
        override val text: String,
    ) : NoticeBlock

    data class Code(
        override val text: String,
    ) : NoticeBlock
}

/** `.md` notices are Markdown; every other bundled notice is a plain-text licence shown verbatim. */
internal fun noticeBlocksFor(
    name: String,
    source: String,
): List<NoticeBlock> =
    if (name.endsWith(".md")) parseNoticeBlocks(source) else parsePlainTextNotice(source)

/**
 * A plain-text licence (GPL, LGPL, CC, COPYING), kept byte for byte. A leading "7." there is a
 * section number or a wrapped sentence, never a list marker, so nothing is interpreted: blank
 * lines only split it into bounded monospace blocks.
 */
internal fun parsePlainTextNotice(source: String): List<NoticeBlock> {
    val blocks = mutableListOf<NoticeBlock>()
    val paragraph = mutableListOf<String>()

    fun flush() {
        if (paragraph.isEmpty()) return
        splitBoundedLines(paragraph.joinToString("\n")).forEach { blocks += NoticeBlock.Code(it) }
        paragraph.clear()
    }

    source.lineSequence().forEach { line -> if (line.isBlank()) flush() else paragraph += line }
    flush()
    return blocks
}

/**
 * Small, deliberately non-rendering Markdown parser for the bundled `.md` notices.
 *
 * It recognizes only the block structure needed for readable legal text. Unsupported inline
 * syntax is flattened to plain text so Markdown chrome is never announced to accessibility
 * services.
 */
internal fun parseNoticeBlocks(source: String): List<NoticeBlock> {
    val blocks = mutableListOf<NoticeBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false

    fun flushParagraph() {
        val text = cleanInlineMarkdown(paragraph.joinToString(" ")).trim()
        paragraph.clear()
        splitBounded(text).forEach { blocks += NoticeBlock.Paragraph(it) }
    }

    fun flushCode() {
        val text = code.joinToString("\n").trimEnd()
        code.clear()
        splitBounded(text).forEach { blocks += NoticeBlock.Code(it) }
    }

    source.lineSequence().forEach { sourceLine ->
        val line = sourceLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) flushCode() else flushParagraph()
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code += line
            return@forEach
        }

        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            MARKDOWN_HEADING.matches(trimmed) -> {
                flushParagraph()
                val match = MARKDOWN_HEADING.matchEntire(trimmed)!!
                blocks +=
                    NoticeBlock.Heading(
                        level = match.groupValues[1].length,
                        text = cleanInlineMarkdown(match.groupValues[2]).trim(),
                    )
            }
            MARKDOWN_BULLET.matches(trimmed) -> {
                flushParagraph()
                val match = MARKDOWN_BULLET.matchEntire(trimmed)!!
                // An ordered item keeps its own number; the text may cite it ("see 2.").
                val marker = match.groupValues[1].takeIf { it.first().isDigit() } ?: "•"
                val text = "$marker ${cleanInlineMarkdown(match.groupValues[2]).trim()}"
                splitBounded(text).forEach { blocks += NoticeBlock.Bullet(it) }
            }
            isTableDivider(trimmed) -> flushParagraph()
            isTableRow(trimmed) -> {
                flushParagraph()
                val text =
                    trimmed
                        .trim('|')
                        .split('|')
                        .map { cleanInlineMarkdown(it).trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" — ")
                splitBounded(text).forEach { blocks += NoticeBlock.Paragraph(it) }
            }
            MARKDOWN_RULE.matches(trimmed) -> flushParagraph()
            else -> paragraph += trimmed
        }
    }
    if (inCode) flushCode() else flushParagraph()
    return blocks.filter { it.text.isNotBlank() }
}

private fun cleanInlineMarkdown(value: String): String {
    val linked =
        value.replace(MARKDOWN_LINK) { match ->
            "${match.groupValues[1]} (${match.groupValues[2]})"
        }
    // A code span is literal: `_kiwipiepy.so` and `*_core_news_sm` keep every character.
    val cleaned = StringBuilder()
    var start = 0
    CODE_SPAN.findAll(linked).forEach { span ->
        cleaned.append(stripPairedEmphasis(linked.substring(start, span.range.first)))
        cleaned.append(span.groupValues[1])
        start = span.range.last + 1
    }
    cleaned.append(stripPairedEmphasis(linked.substring(start)))
    return cleaned.toString().replace(WHITESPACE, " ")
}

// Only a pair of markers is emphasis; a lone `_` or `*` is part of the text.
private fun stripPairedEmphasis(value: String): String =
    value
        .replace(STRONG_EMPHASIS) { it.groupValues[2] }
        .replace(STAR_EMPHASIS) { it.groupValues[1] }
        .replace(UNDERSCORE_EMPHASIS) { it.groupValues[1] }
        .replace("`", "")

/** Splits at the last line break within the bound; cuts mid-line only for one overlong line. */
private fun splitBoundedLines(value: String): List<String> {
    val chunks = mutableListOf<String>()
    var remaining = value
    while (remaining.length > MAX_NOTICE_BLOCK_CHARS) {
        val cut =
            remaining.lastIndexOf('\n', startIndex = MAX_NOTICE_BLOCK_CHARS).takeIf { it > 0 }
                ?: MAX_NOTICE_BLOCK_CHARS
        chunks += remaining.substring(0, cut)
        remaining = remaining.substring(cut).removePrefix("\n")
    }
    if (remaining.isNotEmpty()) chunks += remaining
    return chunks
}

private fun splitBounded(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    val remaining = StringBuilder(value.trim())
    val chunks = mutableListOf<String>()
    while (remaining.length > MAX_NOTICE_BLOCK_CHARS) {
        val boundary =
            remaining
                .lastIndexOf(" ", startIndex = MAX_NOTICE_BLOCK_CHARS)
                .takeIf { it > 0 }
                ?: MAX_NOTICE_BLOCK_CHARS
        chunks += remaining.substring(0, boundary).trim()
        remaining.delete(0, boundary)
        while (remaining.isNotEmpty() && remaining.first().isWhitespace()) {
            remaining.deleteCharAt(0)
        }
    }
    if (remaining.isNotEmpty()) chunks += remaining.toString().trim()
    return chunks
}

private fun isTableRow(value: String): Boolean =
    value.startsWith("|") && value.endsWith("|") && value.count { it == '|' } >= 2

private fun isTableDivider(value: String): Boolean =
    isTableRow(value) &&
        value
            .trim('|')
            .split('|')
            .all { cell -> cell.trim().matches(Regex(""":?-{3,}:?""")) }

private val MARKDOWN_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
private val MARKDOWN_BULLET = Regex("""^([-*+]|\d+[.)])\s+(.+)$""")
private val MARKDOWN_RULE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
private val MARKDOWN_LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")
private val CODE_SPAN = Regex("""`([^`]*)`""")
private val STRONG_EMPHASIS = Regex("""(?<!\w)(\*\*|__)(?=\S)(.+?)(?<=\S)\1(?!\w)""")
private val STAR_EMPHASIS = Regex("""(?<![\w*])\*(?=[^\s*])([^*]+?)(?<=[^\s*])\*(?![\w*])""")
private val UNDERSCORE_EMPHASIS = Regex("""(?<!\w)_(?=[^\s_])([^_]+?)(?<=[^\s_])_(?!\w)""")
private val WHITESPACE = Regex("""\s+""")
