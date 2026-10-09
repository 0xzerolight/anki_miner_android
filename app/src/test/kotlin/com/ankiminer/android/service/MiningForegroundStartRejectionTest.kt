package com.ankiminer.android.service

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiningForegroundStartRejectionTest {
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun detachRecordingSink() {
        AppLog.install(NoOpSink)
    }

    @Test
    fun `a rejected start enters the foreground before it stops`() {
        val events = mutableListOf<String>()

        rejectForegroundStart(
            enterForeground = { events += "foreground" },
            stop = { events += "stop" },
        )

        assertEquals(listOf("foreground", "stop"), events)
        assertTrue(recorded.records.isEmpty())
    }

    @Test
    fun `a rejected start still stops when the foreground promotion throws`() {
        val events = mutableListOf<String>()

        rejectForegroundStart(
            enterForeground = { throw IllegalStateException("promotion refused") },
            stop = { events += "stop" },
        )

        assertEquals(listOf("stop"), events)
        val record = recorded.records.single()
        assertTrue(record, record.contains(" W run=- c=service op=start.reject outcome=fail"))
        assertTrue(record, record.contains("java.lang.IllegalStateException: promotion refused"))
    }
}
