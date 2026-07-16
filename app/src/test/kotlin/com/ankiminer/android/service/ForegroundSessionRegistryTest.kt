package com.ankiminer.android.service

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ForegroundSessionRegistryTest {
    private val directExecutor = Executor { command -> command.run() }

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
