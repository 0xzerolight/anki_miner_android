package com.ankiminer.android.ui.attribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeBlocksTest {
    @Test
    fun markdownChromeBecomesStructuredBlocks() {
        val blocks =
            parseNoticeBlocks(
                """
                # Package notice

                Plain **metadata** with [source](https://example.com).

                - First component
                * Second component

                | Component | License |
                | --- | --- |
                | Parser | BSD-3-Clause |

                ```text
                CONFIG_GPL=0
                ```
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                NoticeBlock.Heading(level = 1, text = "Package notice"),
                NoticeBlock.Paragraph("Plain metadata with source (https://example.com)."),
                NoticeBlock.Bullet("First component"),
                NoticeBlock.Bullet("Second component"),
                NoticeBlock.Paragraph("Component — License"),
                NoticeBlock.Paragraph("Parser — BSD-3-Clause"),
                NoticeBlock.Code("CONFIG_GPL=0"),
            ),
            blocks,
        )
        assertFalse(blocks.any { it.text.contains("```") })
        assertFalse(blocks.any { it.text.startsWith("#") })
        assertFalse(blocks.any { it.text.startsWith("|") || it.text.endsWith("|") })
    }

    @Test
    fun veryLargeParagraphIsSplitIntoBoundedSemanticBlocks() {
        val source = List(2_000) { "license-token-$it" }.joinToString(" ")

        val blocks = parseNoticeBlocks(source)

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length <= MAX_NOTICE_BLOCK_CHARS })
        assertEquals(source, blocks.joinToString(" ") { it.text })
    }
}
