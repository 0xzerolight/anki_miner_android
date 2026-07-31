package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogTailTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `missing file yields an empty result`() {
        assertEquals(
            LogTailResult(text = "", omittedBytes = 0, omittedLines = 0, totalBytes = 0),
            LogTail.of(File(temporary.root, "absent.log"), maxBytes = 16),
        )
    }

    @Test
    fun `file smaller than the cap is returned whole`() {
        val file = temporary.newFile("small.log").apply {
            writeText("WARNING ffmpeg exit code 1: boom\n")
        }

        assertEquals(
            LogTailResult(
                text = "WARNING ffmpeg exit code 1: boom\n",
                omittedBytes = 0,
                omittedLines = 0,
                totalBytes = file.length(),
            ),
            LogTail.of(file, maxBytes = 1024),
        )
    }

    @Test
    fun `file exactly at the cap is returned whole`() {
        val file = temporary.newFile("exact.log").apply { writeText("one\ntwo\n") }

        val result = LogTail.of(file, maxBytes = file.length())

        assertEquals("one\ntwo\n", result.text)
        assertEquals(0, result.omittedBytes)
        assertEquals(0, result.omittedLines)
        assertEquals(file.length(), result.totalBytes)
    }

    @Test
    fun `large file retains its recent complete lines`() {
        val file = temporary.newFile("large.log").apply {
            writeText("old line\nmiddle line\nlast line marker\n")
        }

        val result = LogTail.of(file, maxBytes = 24)

        assertTrue(result.text, result.text.endsWith("last line marker\n"))
        assertFalse(result.text, result.text.contains("old line"))
        assertTrue(result.text.toByteArray(StandardCharsets.UTF_8).size <= 24)
    }

    @Test
    fun `invalid UTF-8 is decoded leniently`() {
        val file = temporary.newFile("invalid.log").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "tail ok\n".toByteArray())
        }

        assertTrue(LogTail.of(file, maxBytes = 1024).text.endsWith("tail ok\n"))
    }

    @Test
    fun `truncation drops a partial first line and reports omitted counts`() {
        val input = "first\nsecond long\nthird\n".toByteArray()

        val result = LogTail.of(ByteArrayInputStream(input), maxBytes = 16)

        assertEquals("third\n", result.text)
        assertEquals(18, result.omittedBytes)
        assertEquals(2, result.omittedLines)
        assertEquals(input.size.toLong(), result.totalBytes)
    }

    @Test
    fun `stream truncation keeps the tail rather than the head`() {
        val input = "head marker\nmiddle\nrecent marker\n".toByteArray()

        val result = LogTail.of(ByteArrayInputStream(input), maxBytes = 19)

        assertEquals("recent marker\n", result.text)
        assertFalse(result.text.contains("head marker"))
    }

    @Test
    fun `zero cap omits the full input`() {
        val input = "first\nsecond\n".toByteArray()

        val result = LogTail.of(ByteArrayInputStream(input), maxBytes = 0)

        assertEquals("", result.text)
        assertEquals(input.size.toLong(), result.omittedBytes)
        assertEquals(2, result.omittedLines)
        assertEquals(input.size.toLong(), result.totalBytes)
    }

    @Test
    fun `split UTF-8 sequence at the tail boundary never emits a replacement token`() {
        val input = "old\nあpartial\nkept\n".toByteArray(StandardCharsets.UTF_8)

        val result = LogTail.of(ByteArrayInputStream(input), maxBytes = 15)

        assertEquals("kept\n", result.text)
        assertFalse(result.text.contains('\uFFFD'))
    }
}
