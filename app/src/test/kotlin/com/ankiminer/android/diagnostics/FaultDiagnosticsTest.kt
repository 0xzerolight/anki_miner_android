package com.ankiminer.android.diagnostics

import com.ankiminer.android.engine.BridgeProtocolCategory
import com.ankiminer.android.engine.BridgeProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultDiagnosticsTest {
    @Test
    fun `digest carries the class name and topmost frame but never the message`() {
        val failure = IllegalStateException("secret /storage/emulated/0/user-file.cbz")
        failure.stackTrace =
            arrayOf(
                StackTraceElement("com.chaquo.python.PyObject", "callAttr", "PyObject.java", 10),
                StackTraceElement(
                    "com.ankiminer.android.reading.BridgeReadingMiningRepository",
                    "runReading",
                    "BridgeReadingMiningRepository.kt",
                    431,
                ),
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 833),
            )

        val digest = exceptionDigest(failure)

        assertEquals("IllegalStateException @ PyObject.callAttr:10", digest)
        assertFalse(digest.contains("secret"))
        assertFalse(digest.contains("user-file"))
    }

    @Test
    fun `digest names the rejected protocol invariant by category`() {
        val failure =
            BridgeProtocolException(
                BridgeProtocolCategory.INVALID_VALUE,
                "sourcePath must be inside cacheDir",
            )
        failure.stackTrace =
            arrayOf(
                StackTraceElement(
                    "com.ankiminer.android.engine.BridgeJsonCodec",
                    "fail",
                    "BridgeJsonCodec.kt",
                    1437,
                ),
            )

        val digest = exceptionDigest(failure)

        assertEquals("BridgeProtocolException/INVALID_VALUE @ BridgeJsonCodec.fail:1437", digest)
        assertFalse(digest.contains("cacheDir"))
    }

    @Test
    fun `digest without any frame falls back to the class name alone`() {
        val failure = RuntimeException("boom")
        failure.stackTrace = emptyArray()

        assertEquals("RuntimeException", exceptionDigest(failure))
    }

    @Test
    fun `fault token keeps the digest but folds every character an encoder could mishandle`() {
        val failure = IllegalStateException("secret /storage/emulated/0/user-file.cbz")
        failure.stackTrace =
            arrayOf(
                StackTraceElement(
                    "com.ankiminer.android.anki.journal.Sqlite\"Store\\bad",
                    "reserve\nMedia",
                    "SqliteAnkiMutationStore.kt",
                    1609,
                ),
            )

        val token = compactFaultToken(failure)

        assertEquals("IllegalStateException @ Sqlite_Store_bad.reserve_Media:1609", token)
        assertFalse(token.contains('"'))
        assertFalse(token.contains('\\'))
        assertFalse(token.contains('\n'))
        assertFalse(token.contains("secret"))
    }

    @Test
    fun `fault token is bounded so it cannot inflate an unbounded error message`() {
        val failure = IllegalStateException("boom")
        failure.stackTrace =
            arrayOf(StackTraceElement("a".repeat(500), "b".repeat(500), "Long.kt", 1))

        val token = compactFaultToken(failure)

        assertEquals(120, token.length)
    }

    @Test
    fun `fault token survives a synthetic class with no simple name`() {
        val failure = object : RuntimeException("boom") {}
        failure.stackTrace =
            arrayOf(StackTraceElement("Anonymous\$1", "invoke", "Anonymous.kt", 7))

        val token = compactFaultToken(failure)

        assertTrue(token.isNotBlank())
        assertTrue(token.endsWith("Anonymous\$1.invoke:7"))
        assertFalse(token.contains("boom"))
    }
}
