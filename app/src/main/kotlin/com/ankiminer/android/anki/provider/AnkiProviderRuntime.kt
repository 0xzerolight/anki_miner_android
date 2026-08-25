package com.ankiminer.android.anki.provider

import android.content.Context
import com.ankiminer.android.anki.journal.SqliteAnkiMutationStore
import com.ankiminer.android.localization.AndroidStringResourceResolver
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
            canNameFilesFor = ::platformCanNameFilesFor,
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
        cardTypeMarkerField: String? = null,
    ): NoteTypeSetupStatus =
        reads.verifyUserNoteType(
            noteType,
            fieldMap,
            cancellation,
            cardTypeMarkerField,
        )

    fun remediationInventory(
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory = remediation.inventory(cancellation)

    /** Non-run-scoped delete loop for a later undo manager, called by method reference. */
    fun deleteNotes(
        noteIds: List<Long>,
        cancellation: AnkiCancellation,
    ): Int = deleteNotesLoop(gateway, noteIds, cancellation)

    override fun close() {
        store.close()
    }
}

/**
 * Loops the raw delete boundary one note at a time, counting affected rows and checking
 * cancellation between notes. Extracted from [AnkiProviderRuntime] so it is JVM-testable against
 * a faked [AnkiProviderGateway]; the runtime itself needs a real `android.content.Context` and
 * cannot be constructed in a JVM unit test.
 */
internal fun deleteNotesLoop(
    gateway: AnkiProviderGateway,
    noteIds: List<Long>,
    cancellation: AnkiCancellation,
): Int {
    var deletedCount = 0
    for (noteId in noteIds) {
        if (cancellation.isCancelled()) break
        val affected = gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(noteId))
        if (affected >= 1) deletedCount += 1
    }
    return deletedCount
}
