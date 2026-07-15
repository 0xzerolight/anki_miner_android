package com.ankiminer.android.anki.provider

import android.content.Context
import com.ankiminer.android.anki.journal.SqliteAnkiMutationStore
import java.io.Closeable

/** Journal-backed composition root; registration cannot open until startup recovery is green. */
internal class AnkiProviderRuntime(
    context: Context,
    workerThreadGuard: WorkerThreadGuard = AndroidWorkerThreadGuard,
) : Closeable {
    private val store = SqliteAnkiMutationStore(context)
    private val gateway = ContentResolverAnkiGateway(context, workerThreadGuard)
    private val recoveryGate =
        JournalBackedTargetRecoveryGate(
            store = store,
            gateway = gateway,
            workerThreadGuard = workerThreadGuard,
        )
    private val registry =
        AnkiRunStateRegistry(
            cleanup = JournalAnkiRunCleanup(store),
            startupAdmission = recoveryGate,
        )
    private val reads = AnkiProviderReadService(gateway, registry)
    private val verifier =
        DurableTargetVerifier(
            gateway = gateway,
            registry = registry,
            journal = AnkiMutationTargetVerificationJournal(store),
        )

    val callbacks =
        AnkiProviderCallbacks(
            registry = registry,
            reads = reads,
            targetVerifier = verifier,
            workerThreadGuard = workerThreadGuard,
            startupRecoveryGate = recoveryGate,
        )

    override fun close() {
        store.close()
    }
}
