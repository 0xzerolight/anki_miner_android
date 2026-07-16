package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiProviderReadinessTest {
    private val worker = WorkerThreadGuard { }

    @Test
    fun `access outcomes remain distinct without touching collection or recovery`() {
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
            assertEquals(expected, actual)
            assertEquals(0, operationalCalls)
            assertEquals(0, recoveryCalls)
        }
    }

    @Test
    fun `installed provider must prove collection then local recovery`() {
        val available = ProviderAccessStatus.Available("com.ichi2.anki", 2, 42L)
        val uninitialized =
            probe(available, operational = { throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED) })
        assertEquals(AnkiProviderReadiness.Uninitialized, uninitialized)

        val blocked = probe(available, recovery = { error("pending recovery") })
        assertEquals(AnkiProviderReadiness.RecoveryBlocked, blocked)

        val ready = probe(available)
        assertEquals(AnkiProviderReadiness.Ready(2, 42L), ready)
    }

    private fun probe(
        status: ProviderAccessStatus,
        operational: (AnkiCancellation) -> Unit = {},
        recovery: () -> Unit = {},
    ): AnkiProviderReadiness =
        AnkiProviderReadinessProbe(
            workerThreadGuard = worker,
            accessStatus = { status },
            proveCollectionOperational = operational,
            recoverLocalState = recovery,
        ).probe()
}
