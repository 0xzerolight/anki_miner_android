package com.ankiminer.android.service

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiningForegroundServiceLoggingTest {
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
    fun `malformed recognized start logs action and extra key names`() {
        assertMalformedActionLogged("com.ankiminer.android.service.START")
    }

    @Test
    fun `malformed recognized update logs action and extra key names`() {
        assertMalformedActionLogged("com.ankiminer.android.service.UPDATE")
    }

    @Test
    fun `malformed recognized cancel logs action and extra key names`() {
        assertMalformedActionLogged("com.ankiminer.android.service.CANCEL")
    }

    private fun assertMalformedActionLogged(action: String) {
        val identity =
            decodeMiningForegroundIntentIdentity(
                action = action,
                extraKeys = setOf("run_id", "generation"),
                runId = "private-run-id",
                generation = 1,
                leaseId = null,
            )

        assertNull(identity)
        val record = recorded.records.single()
        assertFalse(record, record.contains("private-run-id"))
        assertFalse(record, record.contains("lease_id"))
        assertTrue(
            record,
            record.contains(
                " W run=- c=service op=intent.decode outcome=ignored action=$action " +
                    "extraKeys=\"[generation, run_id]\"",
            ),
        )
        assertTrue(
            record,
            record.contains(
                "java.lang.IllegalArgumentException: Malformed foreground-service intent",
            ),
        )
    }
}
