package com.ankiminer.android.diagnostics.log

import java.io.IOException
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        AppLog.d(LogComponent.MINING, "word.scored") {
            arrayOf("wordIndex" to 3, "outcome" to "ok")
        }
        AppLog.i(LogComponent.MINING, "run.start", "batch" to 2, "outcome" to "ok")
        AppLog.w(LogComponent.MEDIA, "extract", IOException("degraded"), "outcome" to "fail")
        AppLog.e(
            LogComponent.BRIDGE,
            "dispatch",
            IOException("broken\nbridge"),
            "outcome" to "fail",
        )

        val firstLine = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z [DIWE] run=\S+ c=[a-z]+ op=\S+(?: [^\s=]+=(?:\S+|"(?:\\.|[^"])*"))*$""")
        assertTrue(recorded.records.isNotEmpty())
        for (record in recorded.records) {
            val lines = record.lines()
            assertTrue(record, firstLine.matches(lines.first()))
            val outcomes = Regex("""(?:^| )outcome=([^\s]+)""").findAll(lines.first()).toList()
            assertEquals(record, 1, outcomes.size)
            assertTrue(record, outcomes.single().groupValues[1] in ALLOWED_OUTCOMES)
            if (lines.first()[TIMESTAMP_LENGTH + 1] in "WE") {
                assertTrue(record, lines.size > 1)
            }
            assertTrue(record, lines.drop(1).all { it.startsWith('\t') })
        }
    }

    @Test
    fun `renderer rejects a missing or unsupported outcome`() {
        listOf(
            emptyArray(),
            arrayOf("outcome" to "degraded"),
        ).forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) {
                renderLogRecord(
                    Instant.EPOCH,
                    LogLevel.INFO,
                    null,
                    LogComponent.DIAG,
                    "grammar.probe",
                    fields,
                    null,
                )
            }
        }
    }

    @Test
    fun `renderer rejects warn and error records without a throwable`() {
        listOf(LogLevel.WARN, LogLevel.ERROR).forEach { level ->
            assertThrows(IllegalArgumentException::class.java) {
                renderLogRecord(
                    Instant.EPOCH,
                    level,
                    null,
                    LogComponent.DIAG,
                    "grammar.probe",
                    arrayOf("outcome" to "fail"),
                    null,
                )
            }
        }
    }

    @Test
    fun `per item path never emits at info`() {
        AppLog.d(LogComponent.MINING, "word.scored") {
            arrayOf("wordIndex" to 3, "outcome" to "ok")
        }

        val perItem = recorded.records.filter { it.contains(" op=word.scored") }
        assertTrue(perItem.isNotEmpty())
        assertFalse(perItem.any { it.contains(" I ") })
        assertTrue(perItem.all { it.contains(" D ") })
    }

    private companion object {
        val ALLOWED_OUTCOMES = setOf("ok", "fail", "skip", "ignored")
    }
}
