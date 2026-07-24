package com.ankiminer.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FaultDiagnosticsTest {
    @Test
    fun `digest carries the class name and first in-app frame but never the message`() {
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

        assertEquals("IllegalStateException @ BridgeReadingMiningRepository.runReading:431", digest)
        assertFalse(digest.contains("secret"))
        assertFalse(digest.contains("user-file"))
    }

    @Test
    fun `digest without in-app frames falls back to the class name alone`() {
        val failure = RuntimeException("boom")
        failure.stackTrace =
            arrayOf(
                StackTraceElement("java.util.zip.ZipFile", "open", "ZipFile.java", 5),
            )

        assertEquals("RuntimeException", exceptionDigest(failure))
    }
}
