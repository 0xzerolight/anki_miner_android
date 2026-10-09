package com.ankiminer.android.ui.attribution

import com.ankiminer.android.ui.locateFromWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                NoticeBlock.Bullet("• First component"),
                NoticeBlock.Bullet("• Second component"),
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

    @Test
    fun orderedListItemsKeepTheirNumber() {
        assertEquals(
            listOf(NoticeBlock.Bullet("1. First"), NoticeBlock.Bullet("2) Second")),
            parseNoticeBlocks("1. First\n2) Second"),
        )
    }

    @Test
    fun emphasisIsStrippedOnlyInPairs() {
        assertEquals(
            listOf(NoticeBlock.Paragraph("Keep snake _case and *glob alone; strip emphasis, this and bold.")),
            parseNoticeBlocks("Keep snake _case and *glob alone; strip *emphasis*, _this_ and **bold**."),
        )
    }

    @Test
    fun codeSpansInTheBundledIndexKeepUnderscoresAndStars() {
        val text = noticeBlocksFor("NOTICE.md", notice("NOTICE.md")).joinToString("\n") { it.text }

        listOf("_kiwipiepy.so", "fa/_hazm/", "ar/_calima/", "*_core_news_sm").forEach { literal ->
            assertTrue(literal, text.contains(literal))
        }
    }

    @Test
    fun gplSectionNumbersSurvive() {
        val blocks = noticeBlocksFor("LICENSE-GPL-3.0.txt", notice("LICENSE-GPL-3.0.txt"))

        assertTrue(blocks.any { it.text == "  0. Definitions." })
        assertTrue(blocks.any { it.text.startsWith("  17. Interpretation of Sections 15 and 16.") })
    }

    @Test
    fun gplWrappedSectionReferenceStaysInItsParagraph() {
        val blocks = noticeBlocksFor("LICENSE-GPL-3.0.txt", notice("LICENSE-GPL-3.0.txt"))

        assertTrue(
            blocks.any {
                it.text.contains(
                    "added under section\n" +
                        "    7.  This requirement modifies the requirement in section 4 to\n" +
                        "    \"keep intact all notices\".",
                )
            },
        )
    }

    @Test
    fun lgplSectionOpeningStaysOneSentence() {
        mapOf(
            "ffmpeg-COPYING.LGPLv2.1" to
                "  0. This License Agreement applies to any software library or other\n" +
                "program which contains a notice placed by",
            "lame-COPYING" to
                "  0. This License Agreement applies to any software library which\n" +
                "contains a notice placed by",
        ).forEach { (name, opening) ->
            assertTrue(name, noticeBlocksFor(name, notice(name)).any { it.text.startsWith(opening) })
        }
    }

    @Test
    fun ccLicenceKeepsSectionNumbers() {
        val blocks = noticeBlocksFor("LICENSE-CC-BY-NC-SA-3.0.txt", notice("LICENSE-CC-BY-NC-SA-3.0.txt"))

        assertTrue(blocks.any { it.text == "1. Definitions" })
        assertTrue(blocks.any { it.text.startsWith("2. Fair Dealing Rights. Nothing in this License") })
    }

    @Test
    fun everyPlainTextNoticeIsBoundedCodeKeepingEveryLine() {
        val plain = noticesDir().listFiles().orEmpty().filterNot { it.name.endsWith(".md") }
        assertTrue(plain.size > 10)

        plain.forEach { file ->
            val source = file.readText()
            val blocks = noticeBlocksFor(file.name, source)

            assertTrue(file.name, blocks.all { it is NoticeBlock.Code })
            assertTrue(file.name, blocks.all { it.text.length <= MAX_NOTICE_BLOCK_CHARS })
            assertEquals(
                file.name,
                source.lines().filter { it.isNotBlank() },
                blocks.flatMap { it.text.lines() },
            )
        }
    }

    private fun noticesDir(): File = locateFromWorkspace("app/src/main/assets/notices")

    private fun notice(name: String): String = File(noticesDir(), name).readText()
}
