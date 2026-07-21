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
    private val mediaStaging =
        AnkiMediaStaging(
            journal = StoreAnkiMediaStagingJournal(store),
            platform = AndroidAnkiMediaStagingPlatform(context),
        )
    private val recoveryGate =
        JournalBackedTargetRecoveryGate(
            store = store,
            gateway = gateway,
            workerThreadGuard = workerThreadGuard,
            mediaStagingRecovery = MediaStagingRecovery(mediaStaging::recover),
        )
    private val registry =
        AnkiRunStateRegistry(
            cleanup = JournalAnkiRunCleanup(store),
            startupAdmission = recoveryGate,
        )
    private val reads = AnkiProviderReadService(gateway, registry)
    private val mediaMutations =
        JournalBackedMediaMutationService(
            registry = registry,
            journal = AnkiMutationMediaJournal(store),
            staging = PrivateMediaMutationStaging(mediaStaging),
            provider = CheckedMediaMutationProvider(gateway),
        )
    private val noteMutations =
        JournalBackedNoteMutationService(
            registry = registry,
            journal = AnkiMutationNoteJournal(store),
            reads = ExactNoteMutationReads(gateway, reads),
            provider = CheckedNoteMutationProvider(gateway),
        )
    private val verifier =
        DurableTargetVerifier(
            gateway = gateway,
            registry = registry,
            journal = AnkiMutationTargetVerificationJournal(store),
        )
    private val remediation =
        AnkiRemediationService(
            journal = StoreAnkiRemediationJournal(store),
            interruptedWorkRecovery = InterruptedAnkiWorkRecovery(recoveryGate::ensureRecovered),
            stagingRecovery = MediaStagingRecovery(mediaStaging::recover),
            workerThreadGuard = workerThreadGuard,
        )

    private val readiness =
        AnkiProviderReadinessProbe(
            workerThreadGuard = workerThreadGuard,
            accessStatus = gateway::accessStatus,
            proveCollectionOperational = { cancellation ->
                val cursor =
                    gateway.query(
                        ProviderQuery(
                            endpoint = ProviderEndpoint.DECKS,
                            projection = ProviderQueryShapes.DECK_PROJECTION,
                        ),
                        cancellation,
                    ) ?: throw ProviderGatewayException(ProviderFailureKind.PROVIDER_UNAVAILABLE)
                cursor.close()
            },
            recoverLocalState = {
                if (!recoveryGate.isOpen()) recoveryGate.ensureRecovered()
            },
        )

    val callbacks =
        AnkiProviderCallbacks(
            registry = registry,
            reads = reads,
            targetVerifier = verifier,
            mediaMutations = mediaMutations,
            noteMutations = noteMutations,
            workerThreadGuard = workerThreadGuard,
            startupRecoveryGate = recoveryGate,
        )

    fun probeReadiness(cancellation: AnkiCancellation): AnkiReadinessSnapshot =
        readiness.probe(cancellation)

    fun listNoteTypes(cancellation: AnkiCancellation): List<ModelSummary> =
        reads.listNoteTypes(cancellation)

    fun listDeckNames(cancellation: AnkiCancellation): List<String> =
        reads.listDeckNames(cancellation)

    fun verifyUserNoteType(
        noteType: String,
        fieldMap: Map<String, String>,
        cancellation: AnkiCancellation,
    ): NoteTypeSetupStatus = reads.verifyUserNoteType(noteType, fieldMap, cancellation)

    fun remediationInventory(
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory = remediation.inventory(cancellation)

    fun reconcileInterruptedWork(
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory = remediation.reconcileInterruptedWork(cancellation)

    fun performRemediation(
        command: AnkiRemediationCommand,
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory = remediation.perform(command, cancellation).inventory

    override fun close() {
        store.close()
    }
}
