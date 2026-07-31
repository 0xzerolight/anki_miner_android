package com.ankiminer.android.diagnostics.log

import java.io.IOException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogGrammarTest {
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
        AppLog.setMinLevel(LogLevel.DEBUG)
    }

    @After
    fun detachRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
    }

    @Test
    fun `every facade level emits the record grammar`() {
        AppLog.d(LogComponent.MINING, "word.scored") { arrayOf("wordIndex" to 3) }
        AppLog.i(LogComponent.MINING, "run.start", "batch" to 2)
        AppLog.w(LogComponent.MEDIA, "extract", null, "outcome" to "degraded")
        AppLog.e(LogComponent.BRIDGE, "dispatch", IOException("broken\nbridge"))

        val firstLine = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z [DIWE] run=\S+ c=[a-z]+ op=\S+(?: [^\s=]+=(?:\S+|"(?:\\.|[^"])*"))*$""")
        assertTrue(recorded.records.isNotEmpty())
        for (record in recorded.records) {
            val lines = record.lines()
            assertTrue(record, firstLine.matches(lines.first()))
            assertTrue(record, lines.drop(1).all { it.startsWith('\t') })
        }
    }

    @Test
    fun `per item path never emits at info`() {
        AppLog.d(LogComponent.MINING, "word.scored") { arrayOf("wordIndex" to 3) }

        val perItem = recorded.records.filter { it.contains(" op=word.scored") }
        assertTrue(perItem.isNotEmpty())
        assertFalse(perItem.any { it.contains(" I ") })
        assertTrue(perItem.all { it.contains(" D ") })
    }
}
