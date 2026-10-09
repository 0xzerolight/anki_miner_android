package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnkiProviderReadinessTest {
    private val worker = WorkerThreadGuard { }
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
    fun `local recovery waits until the provider probe is ready`() {
        val statuses =
            listOf(
                ProviderAccessStatus.Absent to AnkiProviderReadiness.NotInstalled,
                ProviderAccessStatus.ApiDisabled to AnkiProviderReadiness.Incompatible(null),
                ProviderAccessStatus.Incompatible(1) to AnkiProviderReadiness.Incompatible(1),
                ProviderAccessStatus.PermissionRequired to AnkiProviderReadiness.PermissionDenied,
            )
        statuses.forEach { (status, expected) ->
            var operationalCalls = 0
            var recoveryCalls = 0
            val actual =
                AnkiProviderReadinessProbe(
                    workerThreadGuard = worker,
                    accessStatus = { status },
                    proveCollectionOperational = { operationalCalls += 1 },
                    recoverLocalState = { recoveryCalls += 1 },
                ).probe()
            assertEquals(expected, actual.provider)
            assertEquals(AnkiRecoveryReadiness.NotChecked, actual.recovery)
            assertEquals(0, operationalCalls)
            assertEquals(0, recoveryCalls)
        }
    }

    @Test
    fun `recovery runs and reports its own failure only behind a ready provider`() {
        val available = ProviderAccessStatus.Available("com.ichi2.anki", 2, 42L)
        val uninitialized =
            probe(available, operational = { throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED) })
        assertEquals(AnkiProviderReadiness.Uninitialized, uninitialized.provider)
        assertEquals(AnkiRecoveryReadiness.NotChecked, uninitialized.recovery)

        val blocked = probe(available, recovery = { error("pending recovery") })
        assertEquals(AnkiProviderReadiness.Ready(2, 42L), blocked.provider)
        assertEquals(AnkiRecoveryReadiness.Blocked, blocked.recovery)

        val both =
            probe(
                ProviderAccessStatus.Absent,
                recovery = { error("pending recovery") },
            )
        assertEquals(AnkiProviderReadiness.NotInstalled, both.provider)
        assertEquals(AnkiRecoveryReadiness.NotChecked, both.recovery)

        val ready = probe(available)
        assertEquals(AnkiProviderReadiness.Ready(2, 42L), ready.provider)
        assertEquals(AnkiRecoveryReadiness.Ready, ready.recovery)
    }

    @Test
    fun `recovery that loses AnkiDroid access behind a ready probe stays retryable`() {
        val available = ProviderAccessStatus.Available("com.ichi2.anki", 2, 42L)
        listOf(AnkiErrorCode.TIMEOUT, AnkiErrorCode.PROVIDER_UNAVAILABLE, AnkiErrorCode.PERMISSION_REQUIRED).forEach { code ->
            val result =
                probe(available, recovery = { throw AnkiReadFailure(code, retryable = true, stableMessage = "lost") })

            assertEquals("$code", AnkiProviderReadiness.Ready(2, 42L), result.provider)
            assertEquals("$code", AnkiRecoveryReadiness.NotChecked, result.recovery)
        }
        val queryFailed =
            probe(available, recovery = { throw AnkiReadFailure(AnkiErrorCode.QUERY_FAILED, false, "bad row") })
        assertEquals(AnkiRecoveryReadiness.Blocked, queryFailed.recovery)
    }

    @Test
    fun `cancelled probe leaves local recovery not checked`() {
        var recoveryCalls = 0
        val cancellation = MutableAnkiCancellation().apply { cancel() }

        val result =
            AnkiProviderReadinessProbe(
                workerThreadGuard = worker,
                accessStatus = { ProviderAccessStatus.Absent },
                proveCollectionOperational = {},
                recoverLocalState = { recoveryCalls += 1 },
            ).probe(cancellation)

        assertEquals(AnkiProviderReadiness.NotInstalled, result.provider)
        assertEquals(AnkiRecoveryReadiness.NotChecked, result.recovery)
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun `unexpected access and collection faults are logged before readiness degrades`() {
        val accessFailure = IllegalStateException("access fault")
        val checkFailed =
            AnkiProviderReadinessProbe(
                workerThreadGuard = worker,
                accessStatus = { throw accessFailure },
                proveCollectionOperational = {},
                recoverLocalState = {},
            ).probe()
        val collectionFailure = IllegalArgumentException("collection fault")
        val uninitialized =
            probe(
                ProviderAccessStatus.Available("com.ichi2.anki", 2, 42L),
                operational = { throw collectionFailure },
            )

        assertEquals(AnkiProviderReadiness.NotChecked, checkFailed.provider)
        assertEquals(AnkiProviderReadiness.Uninitialized, uninitialized.provider)
        assertEquals(2, recorded.records.size)
        assertTrue(
            recorded.records[0],
            recorded.records[0].contains(" E run=- c=anki op=readiness.access outcome=fail"),
        )
        assertTrue(recorded.records[0], recorded.records[0].contains("java.lang.IllegalStateException: access fault"))
        assertTrue(
            recorded.records[1],
            recorded.records[1].contains(" E run=- c=anki op=readiness.collection outcome=fail"),
        )
        assertTrue(recorded.records[1], recorded.records[1].contains("java.lang.IllegalArgumentException: collection fault"))
    }

    private fun probe(
        status: ProviderAccessStatus,
        operational: (AnkiCancellation) -> Unit = {},
        recovery: () -> Unit = {},
    ): AnkiReadinessSnapshot =
        AnkiProviderReadinessProbe(
            workerThreadGuard = worker,
            accessStatus = { status },
            proveCollectionOperational = operational,
            recoverLocalState = recovery,
        ).probe()
}
