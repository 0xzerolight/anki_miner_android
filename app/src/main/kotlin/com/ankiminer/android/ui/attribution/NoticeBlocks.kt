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

/**
 * Small, deliberately non-rendering Markdown parser for bundled legal notices.
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
                val text =
                    cleanInlineMarkdown(
                        MARKDOWN_BULLET.matchEntire(trimmed)!!.groupValues[1],
                    ).trim()
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

private fun cleanInlineMarkdown(value: String): String =
    value
        .replace(MARKDOWN_LINK) { match ->
            "${match.groupValues[1]} (${match.groupValues[2]})"
        }.replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""(?<!\w)[*_](?=\S)|(?<=\S)[*_](?!\w)"""), "")
        .replace(Regex("""\s+"""), " ")

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
private val MARKDOWN_BULLET = Regex("""^(?:[-*+]|\d+[.)])\s+(.+)$""")
private val MARKDOWN_RULE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
private val MARKDOWN_LINK = Regex("""\[([^]]+)]\(([^)]+)\)""")
