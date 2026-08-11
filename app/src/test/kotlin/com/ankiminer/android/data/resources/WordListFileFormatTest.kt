package com.ankiminer.android.data.resources

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WordListFileFormatTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun blankLinesAndCommentsDoNotCount() {
        val text =
            """
            # sound effects
            猫

              犬
            # trailing comment
            """.trimIndent()

        assertEquals(2, WordListFileFormat.entryCount(text))
    }

    @Test
    fun aTrimmedHashAnywhereButTheStartIsStillAWord() {
        assertEquals(1, WordListFileFormat.entryCount("猫#1"))
        // The engine strips before testing for the comment marker, so indentation does not save it.
        assertEquals(0, WordListFileFormat.entryCount("   # 猫"))
    }

    @Test
    fun nonUtf8BytesAreRejectedLikeTheEngineWould() {
        val file = temporaryFolder.newFile("shift-jis.txt")
        // 猫 in Shift-JIS: invalid UTF-8, and the engine opens the file with encoding="utf-8".
        file.writeBytes(byteArrayOf(0x94.toByte(), 0x4C.toByte(), 0x0A))

        assertThrows(CharacterCodingException::class.java) { WordListFileFormat.entryCount(file) }
    }

    @Test
    fun utf8FileCountsTheWordsTheEngineWouldLoad() {
        val file = temporaryFolder.newFile("words.txt")
        file.writeText("猫\n# comment\n\n犬\n", Charsets.UTF_8)

        assertEquals(2, WordListFileFormat.entryCount(file))
    }

    @Test
    fun installNormalizationRemovesUtf8BomFromFirstWord() {
        val file = temporaryFolder.newFile("bom-word.txt")
        file.writeBytes(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                "猫\n".toByteArray(),
        )

        val entryCount = WordListFileFormat.normalizeForInstall(file)

        assertEquals(1, entryCount)
        assertEquals("猫\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun installNormalizationMakesBomPrefixedFirstCommentAComment() {
        val file = temporaryFolder.newFile("bom-comment.txt")
        file.writeBytes(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                "# comment\n猫\n".toByteArray(),
        )

        val entryCount = WordListFileFormat.normalizeForInstall(file)

        assertEquals(1, entryCount)
        assertEquals("# comment\n猫\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun installNormalizationComposesDecomposedJapaneseEntriesToNfc() {
        val file = temporaryFolder.newFile("decomposed.txt")
        file.writeText("は\u3099\n", Charsets.UTF_8)

        val entryCount = WordListFileFormat.normalizeForInstall(file)

        assertEquals(1, entryCount)
        assertEquals("ば\n", file.readText(Charsets.UTF_8))
    }
}
