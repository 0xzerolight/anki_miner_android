package com.ankiminer.android.service

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ForegroundSessionRegistryTest {
    private val directExecutor = Executor { command -> command.run() }
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun clearRecordingSink() {
        AppLog.install(NoOpSink)
    }

    @Test
    fun `registry logs each successful lifecycle transition once`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val registration = registry.register(identity) { _, _ -> fail("unexpected cancellation") }

        assertTrue(registry.claimStart(identity, SERVICE_TOKEN))
        assertTrue(registry.foregroundStarted(identity, SERVICE_TOKEN))
        assertTrue(registry.beginExpectedClose(identity))
        registry.expectedServiceWasAbsent(identity)

        assertEquals(
            listOf(
                "c=service op=phase from=NONE to=PENDING outcome=ok detail=register " +
                    "runId=run-1 generation=1 leaseId=00000000-0000-4000-8000-000000000001",
                "c=service op=phase from=PENDING to=CLAIMED outcome=ok detail=start_claimed " +
                    "runId=run-1 generation=1 leaseId=00000000-0000-4000-8000-000000000001",
                "c=service op=phase from=CLAIMED to=ACTIVE outcome=ok detail=foreground_started " +
                    "runId=run-1 generation=1 leaseId=00000000-0000-4000-8000-000000000001",
                "c=service op=phase from=ACTIVE to=CLOSING outcome=ok detail=expected_close " +
                    "runId=run-1 generation=1 leaseId=00000000-0000-4000-8000-000000000001",
                "c=service op=phase from=CLOSING to=NONE outcome=ok detail=service_absent " +
                    "runId=run-1 generation=1 leaseId=00000000-0000-4000-8000-000000000001",
            ),
            recorded.records.map { record ->
                record.substringBefore('\n').substring(record.indexOf("c="))
            },
        )
        assertFalse(registration.started.isCompletedExceptionally)
    }

    @Test
    fun `start handshake completes only after foreground promotion`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val registration = registry.register(identity) { _, _ -> fail("unexpected cancellation") }

        assertTrue(registration.accepted)
        assertFalse(registration.started.isDone)
        assertTrue(registry.claimStart(identity, SERVICE_TOKEN))
        assertFalse(registration.started.isDone)

        assertTrue(registry.foregroundStarted(identity, SERVICE_TOKEN))
        assertTrue(registration.started.isDone)
        assertFalse(registration.started.isCompletedExceptionally)
    }

    @Test
    fun `unexpected active service loss requests cancellation exactly once`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        registry.serviceDestroyed(identity, SERVICE_TOKEN)
        registry.serviceDestroyed(identity, SERVICE_TOKEN)

        assertEquals(listOf(MiningForegroundCancellationReason.SERVICE_LOST), reasons)
    }

    @Test
    fun `expected close suppresses service loss cancellation`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertTrue(registry.beginExpectedClose(identity))
        registry.serviceDestroyed(identity, SERVICE_TOKEN)

        assertTrue(reasons.isEmpty())
        assertTrue(registry.register(identity(2)) { _, _ -> }.accepted)
    }

    @Test
    fun `timeout requests cancellation and makes service destruction intentional`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertTrue(
            registry.beginServiceTermination(
                identity,
                SERVICE_TOKEN,
                MiningForegroundCancellationReason.SYSTEM_TIMEOUT,
            ),
        )
        registry.serviceDestroyed(identity, SERVICE_TOKEN)

        assertEquals(listOf(MiningForegroundCancellationReason.SYSTEM_TIMEOUT), reasons)
    }

    @Test
    fun `stale pre-foreground failure cannot mutate a newer generation`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val currentIdentity = identity(2)
        val registration = registry.register(currentIdentity) { _, _ -> fail("unexpected cancellation") }

        assertFalse(
            registry.failBeforeForeground(
                identity(1),
                IllegalStateException("stale start"),
            ),
        )
        assertTrue(registry.claimStart(currentIdentity, SERVICE_TOKEN))
        assertTrue(registry.foregroundStarted(currentIdentity, SERVICE_TOKEN))
        assertTrue(registration.started.isDone)
    }

    @Test
    fun `stale token cannot mutate or cancel an active lease`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertFalse(
            registry.updateProgress(identity(2), MiningForegroundProgress(1, 2)),
        )
        assertFalse(
            registry.requestCancellation(
                identity,
                "stale-token",
                MiningForegroundCancellationReason.USER_REQUESTED,
            ),
        )
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `accepted cancellation remains sticky across later progress`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertTrue(
            registry.requestCancellation(
                identity,
                SERVICE_TOKEN,
                MiningForegroundCancellationReason.USER_REQUESTED,
            ),
        )
        assertTrue(registry.updateProgress(identity, MiningForegroundProgress(1, 2)))

        val snapshot = requireNotNull(registry.snapshotForService(identity, SERVICE_TOKEN))
        assertEquals(MiningForegroundProgress(1, 2), snapshot.progress)
        assertTrue(snapshot.cancelling)
        assertEquals(listOf(MiningForegroundCancellationReason.USER_REQUESTED), reasons)
    }

    @Test
    fun `app cancellation marks active lease without redelivering cancellation`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertTrue(registry.markCancelling(identity))

        assertTrue(requireNotNull(registry.snapshotForService(identity, SERVICE_TOKEN)).cancelling)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `abandoned start closes an already promoted race`() {
        val registry = ForegroundSessionRegistry(directExecutor)
        val identity = identity()
        val reasons = mutableListOf<MiningForegroundCancellationReason>()
        val registration = registry.register(identity) { _, reason -> reasons += reason }
        activate(registry, registration, identity)

        assertTrue(registry.cancelAbandonedStart(identity))
        registry.serviceDestroyed(identity, SERVICE_TOKEN)

        assertEquals(listOf(MiningForegroundCancellationReason.USER_REQUESTED), reasons)
    }

    private fun activate(
        registry: ForegroundSessionRegistry,
        registration: ForegroundSessionRegistry.Registration,
        identity: MiningForegroundSessionIdentity,
    ) {
        assertTrue(registry.claimStart(identity, SERVICE_TOKEN))
        assertTrue(registry.foregroundStarted(identity, SERVICE_TOKEN))
        assertTrue(registration.started.isDone)
    }

    private fun identity(generation: Long = 1): MiningForegroundSessionIdentity =
        MiningForegroundSessionIdentity(
            runId = "run-$generation",
            generation = generation,
            leaseId = "00000000-0000-4000-8000-${generation.toString().padStart(12, '0')}",
        )

    companion object {
        private const val SERVICE_TOKEN = "service-token"
    }
}
