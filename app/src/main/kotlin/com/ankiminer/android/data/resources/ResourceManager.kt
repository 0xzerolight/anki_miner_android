package com.ankiminer.android.data.resources

import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.media.CancellableProviderIo
import com.ankiminer.android.media.ProviderIoCancelledException
import com.ankiminer.android.media.SafAccessException
import com.ankiminer.android.media.SafAccessFailureKind
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionRecord
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.TransientSafSelectionInventory
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface ResourceManager {
    val state: StateFlow<ResourceManagerState>

    suspend fun recoverAndRefresh()

    suspend fun installUniDic()

    suspend fun installCatalogDictionary(resourceId: String, replace: Boolean)

    /** Inspect a retained Yomitan archive and return its desktop-derived base slot. */
    suspend fun preflightCustomDictionary(uri: String): String? =
        error("Custom dictionary preflight is unavailable")

    suspend fun importCustomDictionary(
        uri: String,
        slotId: String,
        replace: Boolean,
    )

    /** Retain a picked import URI and derive the metadata needed to dispatch it later. */
    suspend fun retainResourceImport(uri: String): RetainedResourceImport =
        error("Resource import preflight is unavailable")

    /** Release a retained import which the user chose not to dispatch. */
    suspend fun releaseResourceImport(uri: String) = Unit

    suspend fun importFrequencySource(
        uri: String,
        sourceId: String,
        sourceName: String,
        format: FrequencySourceFormat,
        replace: Boolean,
    )

    suspend fun importPitchAccent(
        uri: String,
        sourceId: String,
        sourceName: String,
        format: PitchAccentSourceFormat,
        replace: Boolean,
    )

    /** Every pack the picked archive holds, or null when the preflight did not complete. */
    suspend fun preflightAudioPack(uri: String): List<AudioPackCandidate>?

    suspend fun importAudioPack(
        uri: String,
        pack: AudioPackCandidate,
        replace: Boolean,
    )

    suspend fun discardAudioPackPreflight()

    suspend fun importKnownWords(
        uri: String,
        format: KnownWordsSourceFormat,
    )

    suspend fun importWordList(uri: String, kind: WordListKind)

    suspend fun removeWordList(kind: WordListKind)

    /** Absolute path the engine should read for [kind], or null when no file is installed. */
    fun wordListPath(kind: WordListKind): String?

    suspend fun previewKnownWords(uri: String, fileKind: ResourceImportFileKind)

    suspend fun confirmKnownWordsImport()

    /**
     * Replays only the failed known-word mutation represented by the current failure.
     *
     * Implementations must retain mutation input until success: staged import file and format,
     * removed words, or reset scope. Search is never a mutation retry.
     */
    suspend fun retryKnownWordsFailure()

    fun dismissKnownWordsImportPreview()

    suspend fun searchKnownWords(query: String, loadMore: Boolean = false)

    /**
     * Delete one installed resource. Idempotent: a slot that is already gone still succeeds.
     *
     * UniDic has no delete: it is the tokenizer the whole engine depends on.
     */
    suspend fun deleteInstalledResource(
        kind: InstalledResourceKind,
        id: String,
    ) = Unit

    suspend fun removeKnownWords(words: List<String>)

    suspend fun resetKnownWords(scope: KnownWordsResetScope)

    suspend fun exportKnownWords(uri: String)

    suspend fun lookup(
        slotId: String,
        term: String,
    )

    fun cancelActive()

    fun dismissFailure()

    fun installedDictionaryIds(): List<String> =
        state.value.dictionaries.filter { it.isUsable }.map { it.slotId }

    fun installedFrequencyIds(): List<String> =
        state.value.frequencySources.filter { it.schemaOk && it.entryCount > 0 }.map { it.sourceId }

    fun installedPitchIds(): List<String> =
        state.value.pitchSources.filter { it.schemaOk && it.entryCount > 0 }.map { it.sourceId }

    fun installedAudioPackIds(): List<String> =
        state.value.audioPacks.filter { it.contentAvailable && it.entryCount > 0 }.map { it.packId }

    fun bundledWordsetIds(): List<String> = state.value.wordsets.map { it.wordsetId }
}

internal fun interface PinnedArchiveProvider {
    fun download(
        archive: ResourceArchive,
        cancellation: ResourceCancellationSignal,
        onProgress: (Long, Long, ResourceOperationPhase) -> Unit,
    ): StagedArchive
}

/**
 * Keeps a long resource operation running while the app is not on screen.
 *
 * Importing a pack from the upstream audio collection copies gigabytes across a
 * hundred thousand files, and the operation has no resume: interrupt it and the
 * user starts over. Backed by a foreground service on device, and by nothing at
 * all in host tests, which is why this is a seam and not a Context.
 */
internal interface ResourceForegroundLease {
    fun start(progress: ResourceOperationProgress)

    fun update(progress: ResourceOperationProgress)

    fun stop()

    /** For host tests and for every operation short enough not to earn a notification. */
    object None : ResourceForegroundLease {
        override fun start(progress: ResourceOperationProgress) = Unit

        override fun update(progress: ResourceOperationProgress) = Unit

        override fun stop() = Unit
    }
}

internal class AndroidResourceManager(
    private val safBroker: SafBroker,
    private val bridge: PyBridge,
    private val tokenizerResources: InstalledTokenizerResourceProvider,
    private val bridgeFilesRoot: File,
    private val stagingRoot: File,
    private val resourceExecutor: Executor,
    private val controlExecutor: Executor,
    private val runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
    private val downloader: PinnedResourceDownloader,
    private val safStager: ResourceArchiveStager,
    private val documentWriter: ResourceDocumentWriter,
    private val strings: StringResourceResolver,
    private val safSelectionInventory: SafSelectionInventory = TransientSafSelectionInventory(),
    private val foregroundLease: ResourceForegroundLease = ResourceForegroundLease.None,
    private val stagingAvailableBytes: (File) -> Long = ::usableSpaceForStaging,
    private val pinnedArchiveProvider: PinnedArchiveProvider? = null,
    wordListMover: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    resourceDirectorySync: (File) -> Unit = ::syncResourceDirectory,
) : ResourceManager {
    private data class ActiveOperation(
        val id: String,
        val label: String,
        val cancellation: ResourceCancellationSignal,
        val failureOrigin: ResourceFailureOrigin,
        val failureRetry: ResourceFailureRetry,
        val knownWordsOperation: KnownWordsFailureOperation?,
        val deleteTarget: ResourceDeleteTarget? = null,
        val holdsForegroundLease: Boolean = false,
        val pythonStarted: AtomicBoolean = AtomicBoolean(false),
        val retainJournalForRecovery: AtomicBoolean = AtomicBoolean(false),
        val cancelDelivery: AtomicReference<CancelDelivery> =
            AtomicReference(CancelDelivery.NOT_REQUESTED),
    )

    private data class PendingKnownWordsImport(
        val staged: StagedArchive,
        val format: KnownWordsSourceFormat,
    )

    private data class PendingAudioPackImport(
        val staged: StagedArchive,
        val packs: List<AudioPackCandidate>,
    )

    private sealed interface PendingKnownWordsMutation {
        data class Remove(val words: List<String>) : PendingKnownWordsMutation

        data class Reset(val scope: KnownWordsResetScope) : PendingKnownWordsMutation
    }

    private class ResourceInventoryReconciliationException(cause: Exception) :
        RuntimeException(cause)

    private class ResourceCancellationDeliveryException :
        RuntimeException()

    private enum class CancelDelivery {
        NOT_REQUESTED,
        REQUESTED,
        DELIVERED,
        FAILED,
    }

    private val mutableState = MutableStateFlow(ResourceManagerState())
    override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private val activeMonitor = Any()
    private val retainedResourceImportsMonitor = Any()
    private val retainedResourceImports = mutableMapOf<String, Int>()
    private val pendingKnownWordsRoot = File(stagingRoot.parentFile, "resource-pending-known-words")
    private val pendingAudioPackRoot = File(stagingRoot.parentFile, "resource-pending-audio-pack")
    private val operationJournal =
        ResourceOperationJournal(stagingRoot.parentFile, resourceDirectorySync)

    /**
     * Sibling of the staging root, so the retention rename stays on one volume and [clearStaging]
     * never sweeps a file the engine is expected to keep reading.
     */
    private val wordListRoot = File(stagingRoot.parentFile, "resource-word-lists")
    private val wordListStore =
        CrashSafeWordListStore(wordListRoot, wordListMover, resourceDirectorySync)
    private var active: ActiveOperation? = null
    private var pendingKnownWordsImport: PendingKnownWordsImport? = null
    private var pendingAudioPackImport: PendingAudioPackImport? = null
    private var pendingKnownWordsMutation: PendingKnownWordsMutation? = null

    @Volatile
    private var startupRecoveryTailPending = false

    override suspend fun recoverAndRefresh() {
        startupRecoveryTailPending = false
        mutableState.update { it.copy(startupReadiness = ResourceStartupReadiness.RECOVERING) }
        val interrupted = runOnExecutor(resourceExecutor) { operationJournal.read() }
        val clearInterruptedAudioInput = interrupted?.origin == ResourceFailureOrigin.AUDIO
        val retainKnownWordsInput =
            interrupted?.origin == ResourceFailureOrigin.KNOWN_WORDS &&
                interrupted.knownWordsOperation == KnownWordsFailureOperation.IMPORT
        val recovered =
            runOperation(
                strings.resolve(R.string.resource_operation_refresh),
                ResourceOperationPhase.REFRESHING,
                failureOrigin = ResourceFailureOrigin.SETUP,
                requiresStartupReady = false,
                waitForMutex = true,
            ) { operation ->
                cleanupInterruptedResourceImport(interrupted)
                if (clearInterruptedAudioInput || !restorePendingAudioPackImport()) {
                    clearPendingAudioPackImport()
                }
                if (!retainKnownWordsInput || !restorePendingKnownWordsImport()) {
                    clearPendingKnownWordsImport()
                    mutableState.update { it.copy(knownWordsImportPreview = null) }
                }
                clearStaging()
                downloader.reconcile(FrozenResourceCatalog.value.resources.map { it.archive })
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                ResourceBridgeCodec.decodeCleanup(
                    bridge.dispatch(ResourceBridgeCodec.encodeCleanupRequest(), null),
                )
                startupRecoveryTailPending = true
                // Before refreshFromPython, which fails the whole recovery on a
                // stale pitch source or an unusable dictionary.
                rebuildStaleResourceIndexes()
                operation.cancellation.check()
                refreshFromPython()
                finishStartupRecovery()
                startupRecoveryTailPending = false
            }
        mutableState.update {
            it.copy(
                startupReadiness =
                    if (recovered) {
                        ResourceStartupReadiness.READY
                    } else {
                        ResourceStartupReadiness.FAILED
                    },
            )
        }
        if (recovered) {
            runOnExecutor(resourceExecutor) { operationJournal.clear() }
            interrupted
                ?.takeUnless(::interruptedOperationAlreadyCommitted)
                ?.let { operation ->
                    recordFailure(
                        code = "resource_operation_interrupted",
                        message = strings.resolve(R.string.resource_failure_operation),
                        origin = operation.origin,
                        retry = operation.retry,
                        knownWordsOperation = operation.knownWordsOperation,
                    )
                }
        }
    }

    override suspend fun installUniDic() {
        runOperation(
            strings.resolve(R.string.resource_operation_install_unidic),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.UNIDIC,
            persistForRecovery = true,
        ) { operation ->
            val resource = catalog().unidic
            val staged = download(resource, operation)
            consumePinnedArchive(operation, resource.archive) {
                updateProgress(operation, ResourceOperationPhase.INSTALLING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val installed =
                    ResourceBridgeCodec.decodeInstalledUniDic(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeUniDicInstallRequest(
                                operation.id,
                                resource.resourceId,
                                staged.file.canonicalPath,
                            ),
                            null,
                        ),
                    )
                mutableState.update { it.copy(installedUniDic = installed) }
                refreshFromPython()
            }
        }
    }

    override suspend fun installCatalogDictionary(resourceId: String, replace: Boolean) {
        val allowFailedReadiness =
            replace && mutableState.value.startupReadiness == ResourceStartupReadiness.FAILED
        val completesFailedStartupRecovery = allowFailedReadiness && startupRecoveryTailPending
        runOperation(
            strings.resolve(R.string.resource_operation_import_catalog_dictionary),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.CATALOG_DICTIONARY,
            failureRetry =
                ResourceFailureRetry(
                    action = ResourceFailureAction.RETRY,
                    targetId = resourceId,
                    replace = replace,
                ),
            persistForRecovery = true,
            requiresStartupReady = !allowFailedReadiness,
        ) { operation ->
            val resource =
                catalog().dictionary(resourceId)
                    ?: throw ResourceBridgeException(
                        "resource_unknown",
                        "Dictionary '$resourceId' is not in the pinned catalog",
                    )
            val occupied =
                mutableState.value.dictionaries.any {
                    it.occupied && it.slotId == resource.slotId
                }
            if (occupied && !replace) {
                throw ResourceBridgeException(
                    "resource_already_installed",
                    "Dictionary slot '${resource.slotId}' already exists",
                )
            }
            val staged = download(resource, operation)
            consumePinnedArchive(operation, resource.archive) {
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                decodePublishedMutation(
                    raw =
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeDictionaryImportRequest(
                                operation.id,
                                staged.file.canonicalPath,
                                resource.slotId,
                                replace,
                                resource.resourceId,
                            ),
                            null,
                        ),
                    decode = ResourceBridgeCodec::decodeImportedDictionary,
                )
                refreshAfterCommittedMutation(completesFailedStartupRecovery)
            }
        }
    }

    override suspend fun importCustomDictionary(
        uri: String,
        slotId: String,
        replace: Boolean,
    ) {
        // Replacing a broken slot is a startup repair, exactly like a catalog
        // repair: gating it on READY left FAILED recovery with no custom-slot
        // exit besides deletion.
        val allowFailedReadiness =
            replace && mutableState.value.startupReadiness == ResourceStartupReadiness.FAILED
        val completesFailedStartupRecovery = allowFailedReadiness && startupRecoveryTailPending
        runOperation(
            strings.resolve(R.string.resource_operation_import_custom_dictionary),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.CUSTOM_DICTIONARY,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
            resourceImportUri = uri,
            requiresStartupReady = !allowFailedReadiness,
        ) { operation ->
            val remainingRetainedReferences = consumeRetainedResourceImport(uri)
            val retainedUri =
                if (remainingRetainedReferences != null) {
                    uri
                } else {
                    runBlocking { safBroker.retainReadAccess(uri) }.uri
                }
            val clearSelectionAfterImport =
                remainingRetainedReferences?.let { it == 0 }
                    ?: (safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri == uri)
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        retainedUri,
                        operation.id,
                        operation.cancellation,
                        sourceLabel = "dictionary archive",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                decodePublishedMutation(
                    raw =
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeDictionaryImportRequest(
                                operation.id,
                                staged.file.canonicalPath,
                                slotId,
                                replace,
                                catalogResourceId = null,
                            ),
                            null,
                        ),
                    decode = ResourceBridgeCodec::decodeImportedDictionary,
                )
                refreshAfterCommittedMutation(completesFailedStartupRecovery)
            } finally {
                staged?.file?.delete()
                releaseResourceImportAfterOperation(
                    operation,
                    retainedUri,
                    clearSelectionAfterImport,
                )
            }
        }
    }

    override suspend fun preflightCustomDictionary(uri: String): String? {
        var derivedSlotId: String? = null
        val succeeded =
            runOperation(
                strings.resolve(R.string.resource_operation_import_custom_dictionary),
                ResourceOperationPhase.PREPARING,
                failureOrigin = ResourceFailureOrigin.CUSTOM_DICTIONARY,
                failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                persistForRecovery = true,
                resourceImportUri = uri,
                requiresStartupReady = false,
                waitForMutex = true,
            ) { operation ->
                var staged: StagedArchive? = null
                try {
                    staged =
                        safStager.stage(
                            uri,
                            operation.id,
                            operation.cancellation,
                            sourceLabel = "dictionary archive",
                        ) { current, total ->
                            updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                        }
                    operation.cancellation.check()
                    operation.pythonStarted.set(true)
                    derivedSlotId =
                        ResourceBridgeCodec.decodeDictionaryPreflight(
                            bridge.dispatch(
                                ResourceBridgeCodec.encodeDictionaryPreflightRequest(
                                    operation.id,
                                    staged.file.canonicalPath,
                                ),
                                null,
                            ),
                        )
                } finally {
                    staged?.file?.delete()
                }
            }
        return derivedSlotId.takeIf { succeeded }
    }

    override suspend fun retainResourceImport(uri: String): RetainedResourceImport {
        val retained = safBroker.retainReadAccess(uri)
        var selectionPublished = false
        val fileKind =
            try {
                val detected =
                    detectResourceImportFileKind(
                        displayName = retained.displayName,
                        mimeType = retained.mimeType,
                        readLeadingBytes = {
                            safStager.readLeadingBytes(retained.uri, RESOURCE_IMPORT_PREFIX_BYTES)
                        },
                    )
                withContext(Dispatchers.IO + NonCancellable) {
                    safSelectionInventory.putSelection(
                        SafSelectionSlot.RESOURCE_IMPORT,
                        SafSelectionRecord(retained.uri, RESOURCE_IMPORT_SELECTION_LABEL),
                    )
                    selectionPublished = true
                }
                currentCoroutineContext().ensureActive()
                detected
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try {
                        if (selectionPublished) {
                            withContext(Dispatchers.IO) {
                                clearResourceImportSelection(retained.uri)
                            }
                        }
                        safBroker.releaseReadAccess(retained.uri)
                    } catch (releaseFailure: Throwable) {
                        failure.addSuppressed(releaseFailure)
                    }
                }
                throw failure
            }
        synchronized(retainedResourceImportsMonitor) {
            retainedResourceImports[retained.uri] =
                retainedResourceImports.getOrDefault(retained.uri, 0) + 1
        }
        return RetainedResourceImport(
            uri = retained.uri,
            displayName = retained.displayName,
            fileKind = fileKind,
        )
    }

    override suspend fun releaseResourceImport(uri: String) {
        val remaining =
            synchronized(retainedResourceImportsMonitor) {
                consumeRetainedResourceImportLocked(uri)
            }
        val durablyRetained =
            safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri == uri
        if (remaining != null || durablyRetained) {
            withContext(NonCancellable) {
                if (remaining == null || remaining == 0) {
                    withContext(Dispatchers.IO) { clearResourceImportSelection(uri) }
                }
                safBroker.releaseReadAccess(uri)
            }
        }
    }

    override suspend fun importFrequencySource(
        uri: String,
        sourceId: String,
        sourceName: String,
        format: FrequencySourceFormat,
        replace: Boolean,
    ) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_frequency),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.FREQUENCY,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
            resourceImportUri = uri,
        ) { operation ->
            val remainingRetainedReferences = consumeRetainedResourceImport(uri)
            val retainedUri =
                if (remainingRetainedReferences != null) {
                    uri
                } else {
                    runBlocking { safBroker.retainReadAccess(uri) }.uri
                }
            val clearSelectionAfterImport =
                remainingRetainedReferences?.let { it == 0 }
                    ?: (safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri == uri)
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        sourceUri = retainedUri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = format.fileSuffix,
                        maximumBytes =
                            if (format == FrequencySourceFormat.YOMITAN_ZIP) {
                                FREQUENCY_ARCHIVE_LIMIT
                            } else {
                                FREQUENCY_TEXT_LIMIT
                            },
                        sourceLabel = "frequency source",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                if (format == FrequencySourceFormat.YOMITAN_ZIP) {
                    ResourceBridgeCodec.validateFrequencyArchiveMetadata(staged.file)
                }
                operation.pythonStarted.set(true)
                val imported =
                    decodePublishedMutation(
                        raw =
                            bridge.dispatch(
                                ResourceBridgeCodec.encodeFrequencyImportRequest(
                                    operation.id,
                                    staged.file.canonicalPath,
                                    sourceId,
                                    sourceName,
                                    format,
                                    replace,
                                ),
                                null,
                            ),
                        decode = ResourceBridgeCodec::decodeImportedFrequency,
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshAfterCommittedMutation()
            } finally {
                staged?.file?.delete()
                releaseResourceImportAfterOperation(
                    operation,
                    retainedUri,
                    clearSelectionAfterImport,
                )
            }
        }
    }

    override suspend fun importPitchAccent(
        uri: String,
        sourceId: String,
        sourceName: String,
        format: PitchAccentSourceFormat,
        replace: Boolean,
    ) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_pitch),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.PITCH,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
            resourceImportUri = uri,
        ) { operation ->
            val remainingRetainedReferences = consumeRetainedResourceImport(uri)
            val retainedUri =
                if (remainingRetainedReferences != null) {
                    uri
                } else {
                    runBlocking { safBroker.retainReadAccess(uri) }.uri
                }
            val clearSelectionAfterImport =
                remainingRetainedReferences?.let { it == 0 }
                    ?: (safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri == uri)
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        sourceUri = retainedUri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = format.fileSuffix,
                        maximumBytes =
                            if (format == PitchAccentSourceFormat.YOMITAN_ZIP) {
                                PITCH_ARCHIVE_LIMIT
                            } else {
                                PITCH_TEXT_LIMIT
                            },
                        sourceLabel = "pitch-accent source",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val imported =
                    decodePublishedMutation(
                        raw =
                            bridge.dispatch(
                                ResourceBridgeCodec.encodePitchImportRequest(
                                    operation.id,
                                    staged.file.canonicalPath,
                                    sourceId,
                                    sourceName,
                                    format,
                                    replace,
                                ),
                                null,
                            ),
                        decode = ResourceBridgeCodec::decodeImportedPitch,
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshAfterCommittedMutation()
            } finally {
                staged?.file?.delete()
                releaseResourceImportAfterOperation(
                    operation,
                    retainedUri,
                    clearSelectionAfterImport,
                )
            }
        }
    }

    override suspend fun preflightAudioPack(uri: String): List<AudioPackCandidate>? {
        var detected: List<AudioPackCandidate>? = null
        val succeeded = runOperation(
            strings.resolve(R.string.resource_operation_import_audio_pack),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.AUDIO,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
            holdsForegroundLease = true,
            requiresStartupReady = false,
            waitForMutex = true,
        ) { operation ->
            clearPendingAudioPackImport()
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                val budget = audioArchiveBudget(stagingAvailableBytes(stagingRoot))
                val reported = retained.sizeBytes
                if (reported != null && reported > budget) {
                    throw archiveTooLarge(AUDIO_SOURCE_LABEL, reported, budget)
                }
                staged = stageAudioArchive(retained, operation, budget)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val stagedArchive = requireNotNull(staged)
                val packs =
                    ResourceBridgeCodec.decodeAudioPackPreflight(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeAudioPackPreflightRequest(
                                operation.id,
                                stagedArchive.file.canonicalPath,
                                retained.displayName,
                            ),
                            null,
                        ),
                    )
                if (!pendingAudioPackRoot.exists() && !pendingAudioPackRoot.mkdirs()) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not retain the audio-pack preflight",
                    )
                }
                val retainedFile = File(pendingAudioPackRoot, PENDING_AUDIO_ARCHIVE_NAME)
                retainedFile.delete()
                if (!stagedArchive.file.renameTo(retainedFile)) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not retain the audio-pack preflight",
                    )
                }
                writePendingAudioPackIndex(packs)
                pendingAudioPackImport =
                    PendingAudioPackImport(stagedArchive.copy(file = retainedFile), packs)
                staged = null
                detected = packs
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
        }
        if (!succeeded) {
            discardAudioPackPreflight()
            return null
        }
        return detected
    }

    override suspend fun importAudioPack(
        uri: String,
        pack: AudioPackCandidate,
        replace: Boolean,
    ) {
        // Format is display metadata and is not preserved by the picker state. Stable pack
        // identity is its id plus archive path; anything else restages from the picked URI.
        val pending =
            pendingAudioPackImport?.takeIf { retained ->
                retained.packs.any { candidate ->
                    candidate.packId == pack.packId && candidate.packPath == pack.packPath
                }
            }
        val completed = runOperation(
            strings.resolve(R.string.resource_operation_import_audio_pack),
            if (pending == null) {
                ResourceOperationPhase.PREPARING
            } else {
                ResourceOperationPhase.IMPORTING
            },
            failureOrigin = ResourceFailureOrigin.AUDIO,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
            // The only import measured in gigabytes and tens of minutes. Every
            // other resource operation finishes well inside a foreground window
            // and would raise a notification for nothing.
            holdsForegroundLease = true,
        ) { operation ->
            if (pending == null) clearPendingAudioPackImport()
            var retainedUri: String? = null
            var staged: StagedArchive? = pending?.staged
            try {
                if (staged == null) {
                    val retained = runBlocking { safBroker.retainReadAccess(uri) }
                    retainedUri = retained.uri
                    val budget = audioArchiveBudget(stagingAvailableBytes(stagingRoot))
                    val reported = retained.sizeBytes
                    if (reported != null && reported > budget) {
                        throw archiveTooLarge(AUDIO_SOURCE_LABEL, reported, budget)
                    }
                    staged = stageAudioArchive(retained, operation, budget)
                }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val stagedArchive = requireNotNull(staged)
                val imported =
                    ResourceBridgeCodec.decodeImportedAudioPack(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeAudioPackImportRequest(
                                operation.id,
                                stagedArchive.file.canonicalPath,
                                pack.packId,
                                pack.packPath,
                                replace,
                            ),
                            null,
                        ),
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshAfterCommittedMutation()
            } finally {
                staged?.file?.delete()
                clearPendingAudioPackImport()
                retainedUri?.let { retained ->
                    runBlocking { safBroker.releaseReadAccess(retained) }
                }
            }
        }
        if (!completed) discardAudioPackPreflight()
    }

    override suspend fun discardAudioPackPreflight() {
        operationMutex.lock()
        try {
            runOnExecutor(resourceExecutor) { clearPendingAudioPackImport() }
        } finally {
            operationMutex.unlock()
        }
    }

    override suspend fun importKnownWords(
        uri: String,
        format: KnownWordsSourceFormat,
    ) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_known_words),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.KNOWN_WORDS,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            knownWordsOperation = KnownWordsFailureOperation.IMPORT,
            persistForRecovery = true,
        ) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        sourceUri = retained.uri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = format.fileSuffix,
                        maximumBytes = KNOWN_WORDS_FILE_LIMIT,
                        sourceLabel = "known-word file",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val imported =
                    ResourceBridgeCodec.decodeImportedKnownWords(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeKnownWordsImportRequest(
                                operation.id,
                                staged.file.canonicalPath,
                                format,
                            ),
                            null,
                        ),
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshAfterCommittedMutation()
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
        }
    }

    override suspend fun importWordList(uri: String, kind: WordListKind) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_word_list),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.WORD_LIST,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            persistForRecovery = true,
        ) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        sourceUri = retained.uri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = ".txt",
                        maximumBytes = WORD_LIST_FILE_LIMIT,
                        sourceLabel = "word-list file",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                operation.cancellation.check()
                // Read it the way the engine will, before the file becomes the one every later run
                // depends on. A non-UTF-8 file would otherwise fail at the start of every mine.
                val entryCount =
                    try {
                        WordListFileFormat.normalizeForInstall(staged.file)
                    } catch (failure: CharacterCodingException) {
                        throw ResourceDownloadException(
                            "word_list_not_utf8",
                            "The word-list file is not UTF-8 text",
                            failure,
                        )
                    }
                val target = wordListStore.publish(staged.file, kind)
                staged = null
                publishWordList(InstalledWordList(kind, entryCount, target.length()))
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
        }
    }

    override suspend fun removeWordList(kind: WordListKind) {
        runOperation(
            strings.resolve(R.string.resource_operation_remove_word_list),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.WORD_LIST,
        ) {
            if (!wordListStore.remove(kind)) {
                throw ResourceDownloadException(
                    "word_list_remove_failed",
                    "Could not remove the word-list file",
                )
            }
            mutableState.update { state ->
                state.copy(wordLists = state.wordLists.filterNot { it.kind == kind })
            }
        }
    }

    override fun wordListPath(kind: WordListKind): String? =
        File(wordListRoot, kind.fileName).takeIf { it.isFile }?.canonicalPath

    private fun publishWordList(installed: InstalledWordList) {
        mutableState.update { state ->
            state.copy(
                wordLists =
                    state.wordLists.filterNot { it.kind == installed.kind } + installed,
            )
        }
    }

    /** Re-derive the inventory from disk: nothing about a word list is persisted anywhere else. */
    private fun refreshWordLists() {
        val installed =
            WordListKind.entries.mapNotNull { kind ->
                val file = File(wordListRoot, kind.fileName).takeIf { it.isFile } ?: return@mapNotNull null
                val entryCount =
                    try {
                        WordListFileFormat.entryCount(file)
                    } catch (failure: CharacterCodingException) {
                        AppLog.w(
                            LogComponent.RESOURCES,
                            "word_list.refresh",
                            failure,
                            "kind" to kind.name,
                            "outcome" to "skip",
                        )
                        return@mapNotNull null
                    } catch (failure: IOException) {
                        AppLog.w(
                            LogComponent.RESOURCES,
                            "word_list.refresh",
                            failure,
                            "kind" to kind.name,
                            "outcome" to "skip",
                        )
                        return@mapNotNull null
                    }
                InstalledWordList(kind, entryCount, file.length())
            }
        mutableState.update { it.copy(wordLists = installed) }
    }

    override suspend fun previewKnownWords(uri: String, fileKind: ResourceImportFileKind) {
        val format =
            if (fileKind == ResourceImportFileKind.JSON) {
                KnownWordsSourceFormat.JSON
            } else {
                KnownWordsSourceFormat.TEXT
            }
        runOperation(
            strings.resolve(R.string.resource_operation_preview_known_words),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.KNOWN_WORDS,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            knownWordsOperation = KnownWordsFailureOperation.PREVIEW,
            persistForRecovery = true,
            resourceImportUri = uri,
        ) { operation ->
            clearPendingKnownWordsImport()
            mutableState.update { it.copy(knownWordsImportPreview = null) }
            val remainingRetainedReferences = consumeRetainedResourceImport(uri)
            val retainedUri =
                if (remainingRetainedReferences != null) {
                    uri
                } else {
                    runBlocking { safBroker.retainReadAccess(uri) }.uri
                }
            val clearSelectionAfterPreview =
                remainingRetainedReferences?.let { it == 0 }
                    ?: (safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri == uri)
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        sourceUri = retainedUri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = format.fileSuffix,
                        maximumBytes = KNOWN_WORDS_FILE_LIMIT,
                        sourceLabel = "known-word file",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val preview =
                    ResourceBridgeCodec.decodeKnownWordsPreview(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeKnownWordsPreviewRequest(
                                operation.id,
                                staged.file.canonicalPath,
                                format,
                            ),
                            null,
                        ),
                    )
                if (!pendingKnownWordsRoot.exists() && !pendingKnownWordsRoot.mkdirs()) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not retain the known-word preview",
                    )
                }
                val retainedFile = File(pendingKnownWordsRoot, "${operation.id}${format.fileSuffix}")
                retainedFile.delete()
                if (!staged.file.renameTo(retainedFile)) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not retain the known-word preview",
                    )
                }
                pendingKnownWordsImport =
                    PendingKnownWordsImport(staged.copy(file = retainedFile), format)
                staged = null
                mutableState.update { it.copy(knownWordsImportPreview = preview) }
            } finally {
                staged?.file?.delete()
                releaseResourceImportAfterOperation(
                    operation,
                    retainedUri,
                    clearSelectionAfterPreview,
                )
            }
        }
    }

    override suspend fun confirmKnownWordsImport() {
        if (pendingKnownWordsImport == null) return
        val admittedPending = AtomicReference<PendingKnownWordsImport?>()
        runOperation(
            strings.resolve(R.string.resource_operation_import_known_words),
            ResourceOperationPhase.IMPORTING,
            ResourceFailureOrigin.KNOWN_WORDS,
            knownWordsOperation = KnownWordsFailureOperation.IMPORT,
            persistForRecovery = true,
            onAdmitted = { admittedPending.set(pendingKnownWordsImport) },
        ) { operation ->
            val pending = admittedPending.get() ?: return@runOperation
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            val imported =
                ResourceBridgeCodec.decodeImportedKnownWords(
                    bridge.dispatch(
                        ResourceBridgeCodec.encodeKnownWordsImportRequest(
                            operation.id,
                            pending.staged.file.canonicalPath,
                            pending.format,
                        ),
                        null,
                    ),
                )
            mutableState.update {
                it.copy(lastLocalImport = imported)
            }
            try {
                refreshAfterCommittedMutation()
            } finally {
                clearPendingKnownWordsImport()
                mutableState.update { it.copy(knownWordsImportPreview = null) }
            }
        }
    }

    override suspend fun retryKnownWordsFailure() {
        val failure =
            mutableState.value.failure?.takeIf {
                it.origin == ResourceFailureOrigin.KNOWN_WORDS &&
                    it.retry.action == ResourceFailureAction.RETRY
            } ?: return
        when (failure.knownWordsOperation) {
            KnownWordsFailureOperation.IMPORT -> confirmKnownWordsImport()
            null -> {
                when (val mutation = pendingKnownWordsMutation) {
                    is PendingKnownWordsMutation.Remove -> removeKnownWords(mutation.words)
                    is PendingKnownWordsMutation.Reset -> resetKnownWords(mutation.scope)
                    null -> Unit
                }
            }
            KnownWordsFailureOperation.PREVIEW,
            KnownWordsFailureOperation.EXPORT,
            -> Unit
        }
    }

    override fun dismissKnownWordsImportPreview() {
        if (!operationMutex.tryLock()) return
        val started = AtomicBoolean(false)
        try {
            resourceExecutor.execute {
                started.set(true)
                try {
                    clearPendingKnownWordsImport()
                    mutableState.update { it.copy(knownWordsImportPreview = null) }
                } finally {
                    operationMutex.unlock()
                }
            }
        } catch (failure: Throwable) {
            if (!started.get()) operationMutex.unlock()
            throw failure
        }
    }

    override suspend fun searchKnownWords(query: String, loadMore: Boolean) {
        val current = mutableState.value.knownWordsPage
        val offset =
            if (loadMore && current?.query == query && current.hasMore) current.words.size else 0
        if (loadMore && offset == 0) return
        runOperation(
            strings.resolve(R.string.resource_operation_inspect_known_words),
            ResourceOperationPhase.REFRESHING,
            ResourceFailureOrigin.KNOWN_WORDS,
            clearMatchingFailureOnSuccess = pendingKnownWordsMutation == null,
        ) { operation ->
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            val page =
                ResourceBridgeCodec.decodeKnownWordsPage(
                    bridge.dispatch(
                        ResourceBridgeCodec.encodeKnownWordsListRequest(
                            operation.id,
                            query,
                            offset,
                            KNOWN_WORD_PAGE_SIZE,
                        ),
                        null,
                    ),
                )
            mutableState.update { state ->
                val previous = state.knownWordsPage
                state.copy(
                    knownWordsPage =
                        if (offset > 0 && previous?.query == query) {
                            page.copy(words = previous.words + page.words)
                        } else {
                            page
                        },
                )
            }
        }
    }

    override suspend fun deleteInstalledResource(
        kind: InstalledResourceKind,
        id: String,
    ) {
        val completesFailedStartupRecovery =
            mutableState.value.startupReadiness == ResourceStartupReadiness.FAILED &&
                startupRecoveryTailPending
        runOperation(
            strings.resolve(R.string.resource_operation_delete_resource),
            ResourceOperationPhase.FINALIZING,
            failureOrigin =
                when (kind) {
                    InstalledResourceKind.DICTIONARY -> ResourceFailureOrigin.CUSTOM_DICTIONARY
                    InstalledResourceKind.PITCH -> ResourceFailureOrigin.PITCH
                    InstalledResourceKind.FREQUENCY -> ResourceFailureOrigin.FREQUENCY
                    InstalledResourceKind.AUDIO_PACK -> ResourceFailureOrigin.AUDIO
                },
            failureRetry = ResourceFailureRetry(ResourceFailureAction.RETRY, targetId = id),
            deleteTarget = ResourceDeleteTarget(kind, id),
            // No journal: the Python rename is the commit, and recovery would report a
            // delete that succeeded before process death as an interrupted operation.
            persistForRecovery = false,
            holdsForegroundLease = kind == InstalledResourceKind.AUDIO_PACK,
            // A broken slot leaves readiness FAILED and deleting it is the only repair, so
            // gating on READY would disable this in the case it matters most.
            requiresStartupReady = false,
        ) { operation ->
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            val raw =
                bridge.dispatch(
                    if (kind == InstalledResourceKind.DICTIONARY) {
                        ResourceBridgeCodec.encodeDictionaryDeleteRequest(operation.id, id)
                    } else {
                        ResourceBridgeCodec.encodeLocalResourceDeleteRequest(operation.id, kind, id)
                    },
                    null,
                )
            decodePublishedMutation(raw) { value ->
                if (kind == InstalledResourceKind.DICTIONARY) {
                    ResourceBridgeCodec.decodeDictionaryDeleted(value, id)
                } else {
                    ResourceBridgeCodec.decodeLocalResourceDeleted(value, kind, id)
                }
            }
            refreshAfterCommittedMutation(completesFailedStartupRecovery)
        }
    }

    override suspend fun removeKnownWords(words: List<String>) {
        runKnownWordsMutation(
            strings.resolve(R.string.resource_operation_remove_known_words),
            ResourceOperationPhase.IMPORTING,
            PendingKnownWordsMutation.Remove(words.toList()),
        ) { operation ->
            ResourceBridgeCodec.decodeKnownWordsRemoved(
                bridge.dispatch(
                    ResourceBridgeCodec.encodeKnownWordsRemoveRequest(operation.id, words),
                    null,
                ),
            )
        }
    }

    override suspend fun resetKnownWords(scope: KnownWordsResetScope) {
        runKnownWordsMutation(
            strings.resolve(R.string.resource_operation_reset_known_words),
            ResourceOperationPhase.IMPORTING,
            PendingKnownWordsMutation.Reset(scope),
        ) { operation ->
            ResourceBridgeCodec.decodeKnownWordsReset(
                bridge.dispatch(
                    ResourceBridgeCodec.encodeKnownWordsResetRequest(operation.id, scope),
                    null,
                ),
            )
        }
    }

    override suspend fun exportKnownWords(uri: String) {
        runOperation(
            strings.resolve(R.string.resource_operation_export_known_words),
            ResourceOperationPhase.REFRESHING,
            ResourceFailureOrigin.KNOWN_WORDS,
            ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            knownWordsOperation = KnownWordsFailureOperation.EXPORT,
        ) { operation ->
            val destination =
                try {
                    URI(uri)
                } catch (failure: Exception) {
                    throw ResourceBridgeException(
                        "known_words_export_failed",
                        "Export destination is invalid",
                        cause = failure,
                    )
                }
            if (destination.scheme != "content") {
                throw ResourceBridgeException("known_words_export_failed", "Export destination is invalid")
            }
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            val exported =
                ResourceBridgeCodec.decodeKnownWordsExport(
                    bridge.dispatch(ResourceBridgeCodec.encodeKnownWordsExportRequest(operation.id), null),
                )
            val rawSource = File(exported.exportPath)
            val source = rawSource.canonicalFile
            val exportRoot = File(bridgeFilesRoot, "resource-work/operations").canonicalFile
            val operationRoot = File(exportRoot, operation.id).canonicalFile
            val expectedSource = File(operationRoot, "known_words.txt").canonicalFile
            if (
                source != expectedSource ||
                !source.isFile ||
                java.nio.file.Files.isSymbolicLink(rawSource.toPath()) ||
                source.length() != exported.sizeBytes ||
                source.length() > KNOWN_WORD_EXPORT_LIMIT
            ) {
                throw ResourceBridgeException("known_words_export_failed", "Known-word export is invalid")
            }
            try {
                var destinationOpened = false
                try {
                    CancellableProviderIo.useResource(
                        cancellation = operation.cancellation,
                        open = {
                            CancellableProviderIo.open(operation.cancellation) { signal ->
                                documentWriter.open(uri, signal)
                                    ?.also { destinationOpened = true }
                                    ?: throw ResourceBridgeException(
                                        "known_words_export_failed",
                                        "The selected export document could not be opened",
                                    )
                            }
                        },
                    ) { output ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(EXPORT_BUFFER_BYTES)
                            var copied = 0L
                            updateProgress(
                                operation,
                                ResourceOperationPhase.REFRESHING,
                                copied,
                                source.length(),
                            )
                            while (true) {
                                operation.cancellation.check()
                                val count = input.read(buffer)
                                operation.cancellation.check()
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                copied += count
                                updateProgress(
                                    operation,
                                    ResourceOperationPhase.REFRESHING,
                                    copied,
                                    source.length(),
                                )
                                operation.cancellation.check()
                            }
                            output.flush()
                            operation.cancellation.check()
                        }
                    }
                } catch (failure: ProviderIoCancelledException) {
                    if (destinationOpened) deleteExportDestination(uri)
                    throw ResourceDownloadException(
                        "resource_operation_cancelled",
                        "Resource operation was cancelled",
                        failure,
                    )
                } catch (failure: Exception) {
                    if (destinationOpened) deleteExportDestination(uri)
                    throw failure
                }
            } finally {
                source.delete()
                operationRoot.delete()
            }
        }
    }

    private suspend fun runKnownWordsMutation(
        label: String,
        phase: ResourceOperationPhase,
        retryMutation: PendingKnownWordsMutation,
        mutate: (ActiveOperation) -> Unit,
    ) {
        runOperation(
            label,
            phase,
            ResourceFailureOrigin.KNOWN_WORDS,
            ResourceFailureRetry(ResourceFailureAction.RETRY),
            onAdmitted = { pendingKnownWordsMutation = retryMutation },
        ) { operation ->
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            mutate(operation)
            if (pendingKnownWordsMutation == retryMutation) {
                pendingKnownWordsMutation = null
            }
            mutableState.update { it.copy(knownWordsPage = null) }
            refreshAfterCommittedMutation()
        }
    }

    private fun clearPendingKnownWordsImport() {
        pendingKnownWordsImport?.staged?.file?.delete()
        pendingKnownWordsImport = null
        pendingKnownWordsRoot.listFiles()?.forEach { it.delete() }
        pendingKnownWordsRoot.delete()
    }

    private fun restorePendingKnownWordsImport(): Boolean {
        val files = pendingKnownWordsRoot.listFiles()?.filter(File::isFile).orEmpty()
        if (files.size != 1) return false
        val file = files.single()
        val format =
            KnownWordsSourceFormat.entries.singleOrNull { file.name.endsWith(it.fileSuffix) }
                ?: return false
        pendingKnownWordsImport =
            PendingKnownWordsImport(
                staged = StagedArchive(file, sha256 = "", sizeBytes = file.length()),
                format = format,
            )
        return true
    }

    private fun clearPendingAudioPackImport() {
        pendingAudioPackImport?.staged?.file?.delete()
        pendingAudioPackImport = null
        pendingAudioPackRoot.listFiles()?.forEach { it.delete() }
        pendingAudioPackRoot.delete()
    }

    /**
     * Records what the preflight found beside the archive it found it in.
     *
     * The archive survives a process death; the candidate list has to survive with
     * it, or the restored copy is an archive nobody knows the contents of and the
     * user restages gigabytes to learn them again.
     */
    private fun writePendingAudioPackIndex(packs: List<AudioPackCandidate>) {
        File(pendingAudioPackRoot, PENDING_AUDIO_INDEX_NAME).writeText(
            packs.joinToString("\n") { "${it.packId}\t${it.packPath}\t${it.format}" },
            Charsets.UTF_8,
        )
    }

    private fun restorePendingAudioPackImport(): Boolean {
        if (pendingAudioPackImport?.staged?.file?.isFile == true) return true
        val archive =
            File(pendingAudioPackRoot, PENDING_AUDIO_ARCHIVE_NAME).takeIf(File::isFile)
                ?: return false
        val index = File(pendingAudioPackRoot, PENDING_AUDIO_INDEX_NAME).takeIf(File::isFile)
        val packs = index?.let { readPendingAudioPackIndex(it) } ?: return false
        pendingAudioPackImport = PendingAudioPackImport(
            staged = StagedArchive(archive, sha256 = "", sizeBytes = archive.length()),
            packs = packs,
        )
        return true
    }

    private fun readPendingAudioPackIndex(index: File): List<AudioPackCandidate>? {
        val rows = mutableListOf<AudioPackCandidate>()
        for (line in index.readText(Charsets.UTF_8).lines()) {
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            if (fields.size != 3) return null
            val (packId, packPath, format) = fields
            if (!AUDIO_PACK_ID.matches(packId)) return null
            // Written by this process, but read back after a crash, so it is
            // treated as input rather than as a fact.
            if (packPath.split('/').any { it == "." || it == ".." }) return null
            rows += AudioPackCandidate(packId, packPath, format)
        }
        if (rows.isEmpty() || rows.distinctBy(AudioPackCandidate::packId).size != rows.size) return null
        return rows
    }

    override suspend fun lookup(slotId: String, term: String) {
        runOperation(
            strings.resolve(R.string.resource_operation_dictionary_lookup),
            ResourceOperationPhase.REFRESHING,
            ResourceFailureOrigin.DICTIONARY_LOOKUP,
        ) { operation ->
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            val result =
                ResourceBridgeCodec.decodeLookup(
                    bridge.dispatch(ResourceBridgeCodec.encodeDictionaryLookupRequest(slotId, term), null),
                )
            mutableState.update { it.copy(lastLookup = result) }
        }
    }

    override fun cancelActive() {
        val operation = synchronized(activeMonitor) { active } ?: return
        operation.cancelDelivery.compareAndSet(
            CancelDelivery.NOT_REQUESTED,
            CancelDelivery.REQUESTED,
        )
        operation.cancellation.cancel()
        updateProgress(operation, ResourceOperationPhase.CANCELLING)
        // Dispatch even before Python registration. Its bounded sticky-cancellation registry
        // closes the control/worker race without making cancellation depend on check-then-act.
        controlExecutor.execute {
            try {
                val raw = bridge.dispatch(ResourceBridgeCodec.encodeCancelRequest(operation.id), null)
                check(ResourceBridgeCodec.decodeCancelAccepted(raw, operation.id)) {
                    "Python did not accept resource cancellation"
                }
                operation.cancelDelivery.set(CancelDelivery.DELIVERED)
            } catch (failure: Exception) {
                operation.cancelDelivery.set(CancelDelivery.FAILED)
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.cancel",
                    failure,
                    "operation" to operation.id,
                    "outcome" to "fail",
                    "delivery" to "failed",
                )
                recordCancelDeliveryFailure(operation)
            }
        }
    }

    override fun dismissFailure() {
        mutableState.update { it.copy(failure = null) }
    }

    private suspend fun runOperation(
        label: String,
        initialPhase: ResourceOperationPhase,
        failureOrigin: ResourceFailureOrigin,
        failureRetry: ResourceFailureRetry =
            ResourceFailureRetry(ResourceFailureAction.RETRY),
        knownWordsOperation: KnownWordsFailureOperation? = null,
        deleteTarget: ResourceDeleteTarget? = null,
        persistForRecovery: Boolean = false,
        resourceImportUri: String? = null,
        holdsForegroundLease: Boolean = false,
        requiresStartupReady: Boolean = true,
        waitForMutex: Boolean = false,
        clearMatchingFailureOnSuccess: Boolean = true,
        onAdmitted: (ActiveOperation) -> Unit = {},
        block: (ActiveOperation) -> Unit,
    ): Boolean {
        if (
            requiresStartupReady &&
                mutableState.value.startupReadiness != ResourceStartupReadiness.READY
        ) {
            return false
        }
        if (waitForMutex) {
            operationMutex.lock()
        } else if (!operationMutex.tryLock()) {
            return false
        }
        try {
            val workLease =
                runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE)
            if (workLease == null) {
                recordFailure(
                    "resource_busy",
                    strings.resolve(R.string.resource_failure_busy),
                    failureOrigin,
                    failureRetry,
                    knownWordsOperation,
                    deleteTarget = deleteTarget,
                )
                return false
            }
            val operation =
                ActiveOperation(
                    id = "resource_${UUID.randomUUID().toString().replace("-", "")}",
                    label = label,
                    cancellation = ResourceCancellationSignal(),
                    failureOrigin = failureOrigin,
                    failureRetry = failureRetry,
                    knownWordsOperation = knownWordsOperation,
                    deleteTarget = deleteTarget,
                    holdsForegroundLease = holdsForegroundLease,
                )
            val initialProgress = ResourceOperationProgress(operation.id, label, initialPhase)
            synchronized(activeMonitor) { active = operation }
            mutableState.update { it.copy(activeOperation = initialProgress) }
            var completed = false
            var foregroundStarted = false
            try {
                // Before journal admission and work: without foreground process importance this
                // non-resumable operation must not start.
                if (holdsForegroundLease) {
                    foregroundLease.start(initialProgress)
                    foregroundStarted = true
                }
                onAdmitted(operation)
                runOnExecutor(resourceExecutor) {
                    if (persistForRecovery) {
                        operationJournal.write(
                            PersistedResourceOperation(
                                origin = failureOrigin,
                                retry = failureRetry,
                                knownWordsOperation = knownWordsOperation,
                                resourceImportUri = resourceImportUri,
                                resourceImportOwnership =
                                    resourceImportUri?.let {
                                        ResourceImportOwnershipPhase.INVENTORY_RETAINED
                                    },
                            ),
                        )
                    }
                    block(operation)
                    verifyTerminalCancellation(operation)
                }
                completed = true
                // instrumentation: silent — reconciliation already recorded retry state
            } catch (_: ResourceInventoryReconciliationException) {
                // Mutation committed. The recorded setup failure owns reconciliation-only Retry.
                // instrumentation: silent — cancel-delivery failure is recorded below
            } catch (_: ResourceCancellationDeliveryException) {
                recordCancelDeliveryFailure(operation)
            } catch (failure: CancellationException) {
                operation.cancellation.cancel()
                cancelPython(operation)
                throw failure
            } catch (failure: ResourceDownloadException) {
                if (failure.stableCode == "resource_operation_cancelled") {
                    AppLog.d(LogComponent.RESOURCES, "operation.run") {
                        arrayOf(
                            "operation" to operation.id,
                            "code" to failure.stableCode,
                            "outcome" to "skip",
                        )
                    }
                } else {
                    AppLog.e(
                        LogComponent.RESOURCES,
                        "operation.run",
                        diagnosticFailure(failureOrigin, failure),
                        "operation" to operation.id,
                        "code" to failure.stableCode,
                        "outcome" to "fail",
                    )
                    recordFailure(operation, failure.stableCode, downloadUserMessage(failure))
                }
            } catch (failure: ResourceStorageException) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    diagnosticFailure(failureOrigin, failure),
                    "operation" to operation.id,
                    "code" to "insufficient_storage",
                    "outcome" to "fail",
                )
                recordFailure(
                    operation,
                    "insufficient_storage",
                    strings.resolve(R.string.resource_failure_storage),
                )
            } catch (failure: ResourceBridgeException) {
                if (failure.code == "resource_operation_cancelled") {
                    AppLog.d(LogComponent.RESOURCES, "operation.run") {
                        arrayOf(
                            "operation" to operation.id,
                            "code" to failure.code,
                            "outcome" to "skip",
                        )
                    }
                } else {
                    AppLog.e(
                        LogComponent.RESOURCES,
                        "operation.run",
                        diagnosticFailure(failureOrigin, failure.cause ?: failure),
                        "operation" to operation.id,
                        "code" to failure.code,
                        "fault" to failure.faultId,
                        "outcome" to "fail",
                    )
                    // userMessage(code) is unchanged; the id rides beside it into diagnostics only.
                    recordFailure(operation, failure.code, userMessage(failure.code), failure.faultId)
                }
            } catch (failure: SafAccessException) {
                // Provider-side access failures (no persistable grant, provider gone,
                // unreadable document) get an actionable message instead of falling
                // through to the generic operation failure below.
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    diagnosticFailure(failureOrigin, failure),
                    "operation" to operation.id,
                    "code" to safAccessCode(failure.kind),
                    "outcome" to "fail",
                )
                recordFailure(operation, safAccessCode(failure.kind), safAccessUserMessage(failure.kind))
            } catch (failure: Exception) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    diagnosticFailure(failureOrigin, failure),
                    "operation" to operation.id,
                    "code" to "resource_operation_failed",
                    "outcome" to "fail",
                )
                recordFailure(
                    operation,
                    "resource_operation_failed",
                    strings.resolve(R.string.resource_failure_operation),
                )
            } finally {
                if (completed && clearMatchingFailureOnSuccess) {
                    mutableState.update { current ->
                        if (
                            current.failure?.origin == operation.failureOrigin &&
                                (
                                    operation.failureOrigin != ResourceFailureOrigin.KNOWN_WORDS ||
                                        current.failure.knownWordsOperation ==
                                        operation.knownWordsOperation
                                )
                        ) {
                            current.copy(failure = null)
                        } else {
                            current
                        }
                    }
                }
                try {
                    withContext(NonCancellable) {
                        runOnExecutor(resourceExecutor) {
                            try {
                                clearStaging()
                            } finally {
                                if (
                                    persistForRecovery &&
                                        !operation.retainJournalForRecovery.get()
                                ) {
                                    operationJournal.clear()
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        if (foregroundStarted) foregroundLease.stop()
                    } finally {
                        synchronized(activeMonitor) { if (active === operation) active = null }
                        mutableState.update { it.copy(activeOperation = null) }
                        workLease.close()
                    }
                }
            }
            return completed
        } finally {
            operationMutex.unlock()
        }
    }

    private fun download(
        resource: CatalogResource,
        operation: ActiveOperation,
    ): StagedArchive {
        val provider = pinnedArchiveProvider
        return if (provider != null) {
            provider.download(resource.archive, operation.cancellation) { current, total, phase ->
                updateProgress(operation, phase, current, total)
            }
        } else {
            downloader.download(resource.archive, operation.cancellation) { current, total, phase ->
                updateProgress(operation, phase, current, total)
            }
        }
    }

    private fun stageAudioArchive(
        source: SafDocument,
        operation: ActiveOperation,
        maximumBytes: Long,
    ): StagedArchive {
        val result =
            try {
                safStager.stageAudioArchive(
                    sourceUri = source.uri,
                    operationId = operation.id,
                    cancellation = operation.cancellation,
                    maximumBytes = maximumBytes,
                    sourceLabel = AUDIO_SOURCE_LABEL,
                ) { current, total ->
                    updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                }
            } catch (failure: Exception) {
                val code = (failure as? ResourceDownloadException)?.stableCode
                logAudioArchiveStage(
                    source = source,
                    operationId = operation.id,
                    outcome = if (code == "resource_operation_cancelled") "skip" else "fail",
                    stagedBytes = null,
                    readMode =
                        when (code) {
                            "resource_archive_unrecognized" -> AudioArchiveReadMode.RAW.wireValue
                            "resource_archive_provider_representation" ->
                                AudioArchiveReadMode.ASSET_FALLBACK.wireValue
                            else -> "unknown"
                        },
                    container = "unknown",
                )
                throw failure
            }
        logAudioArchiveStage(
            source = source,
            operationId = operation.id,
            outcome = "ok",
            stagedBytes = result.archive.sizeBytes,
            readMode = result.readMode.wireValue,
            container = result.container.wireValue,
        )
        return result.archive
    }

    private fun logAudioArchiveStage(
        source: SafDocument,
        operationId: String,
        outcome: String,
        stagedBytes: Long?,
        readMode: String,
        container: String,
    ) {
        AppLog.i(
            LogComponent.RESOURCES,
            "audio.archive.stage",
            "outcome" to outcome,
            "operation" to operationId,
            "authority" to normalizedProviderAuthority(source.uri),
            "mime" to normalizedMimeType(source.mimeType),
            "filename_type" to normalizedAudioFilenameType(source.displayName),
            "reported_bytes" to (source.sizeBytes ?: "unknown"),
            "staged_bytes" to (stagedBytes ?: "unknown"),
            "size_agreement" to sizeAgreement(source.sizeBytes, stagedBytes),
            "read_mode" to readMode,
            "container" to container,
        )
    }

    private fun diagnosticFailure(
        origin: ResourceFailureOrigin,
        failure: Throwable,
    ): Throwable =
        if (origin == ResourceFailureOrigin.AUDIO) {
            IOException("Audio resource operation failed")
        } else {
            failure
        }

    private fun normalizedMimeType(mimeType: String?): String =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(MIME_TYPE::matches)
            ?: "unknown"

    private fun normalizedAudioFilenameType(displayName: String): String {
        val normalized = displayName.lowercase(Locale.ROOT)
        return when {
            normalized.endsWith(".tar.xz") -> "tar.xz"
            normalized.endsWith(".tar.gz") -> "tar.gz"
            normalized.endsWith(".tar") -> "tar"
            normalized.endsWith(".zip") -> "zip"
            normalized.endsWith(".torrent") -> "torrent"
            '.' in normalized -> "other"
            else -> "none"
        }
    }

    private fun sizeAgreement(
        reportedBytes: Long?,
        stagedBytes: Long?,
    ): String =
        when {
            reportedBytes == null || stagedBytes == null -> "unknown"
            reportedBytes == stagedBytes -> "match"
            else -> "mismatch"
        }

    private fun refreshAfterCommittedMutation(completesFailedStartupRecovery: Boolean = false) {
        try {
            refreshFromPython()
            if (completesFailedStartupRecovery) {
                finishStartupRecovery()
                startupRecoveryTailPending = false
                mutableState.update {
                    it.copy(
                        startupReadiness = ResourceStartupReadiness.READY,
                        failure =
                            it.failure?.takeUnless { failure ->
                                failure.origin == ResourceFailureOrigin.SETUP
                            },
                    )
                }
            }
        } catch (failure: Exception) {
            recordFailure(
                code = "resource_inventory_failed",
                message = userMessage("resource_inventory_failed"),
                origin = ResourceFailureOrigin.SETUP,
                retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
            )
            throw ResourceInventoryReconciliationException(failure)
        }
    }

    private fun finishStartupRecovery() {
        wordListStore.recover()
        refreshWordLists()
        discardInstalledCatalogDownloads()
    }

    /**
     * Rebuild every schema-stale index that still has the copy it was built from.
     *
     * An engine upgrade can move a resource index schema, and each registry
     * compares on exact equality — so a source installed by an older build stops
     * loading. [refreshFromPython] treats a stale pitch source, and an unusable
     * dictionary, as a fatal inventory failure, so this must run first or
     * startup recovery fails outright for anyone who had either installed.
     *
     * Every importer keeps the original input beside its index, so the rebuild
     * needs no file picker and no SAF grant: it re-runs the same import against
     * that copy, at the path the inventory reports. A slot whose copy is gone
     * has nothing to rebuild from and is left for the fatal check to report.
     *
     * Runs inside the caller's operation. The importers stage and rename, so a
     * kill mid-rebuild leaves the old stale index rather than a torn one, and
     * the next launch simply tries again.
     *
     * Dictionary rebuild failures are caught per slot instead of propagating:
     * [refreshFromPython] publishes inventory before its fatal
     * `dictionary_resource_invalid`, and that failure maps to a replace retry
     * the user can act on — aborting recovery here would wedge startup behind
     * a Retry that can never succeed.
     */
    private fun rebuildStaleResourceIndexes() {
        rebuildStaleDictionaries()
        val inventory =
            ResourceBridgeCodec.decodeLocalResourceList(
                bridge.dispatch(ResourceBridgeCodec.encodeLocalResourceListRequest(), null),
            )
        val staleFrequencies =
            inventory.frequencies.filter { !it.schemaOk && it.rebuildSourcePath != null }
        val stalePitch =
            inventory.pitchSources.filter { !it.schemaOk && it.rebuildSourcePath != null }
        if (staleFrequencies.isEmpty() && stalePitch.isEmpty()) return

        for (source in staleFrequencies) {
            val path = source.rebuildSourcePath ?: continue
            val format =
                FrequencySourceFormat.entries.firstOrNull { path.endsWith(it.fileSuffix) }
                    ?: continue
            ResourceBridgeCodec.decodeImportedFrequency(
                bridge.dispatch(
                    ResourceBridgeCodec.encodeFrequencyImportRequest(
                        "resource_${UUID.randomUUID().toString().replace("-", "")}",
                        path,
                        source.sourceId,
                        source.sourceName,
                        format,
                        overwrite = true,
                    ),
                    null,
                ),
            )
        }
        for (source in stalePitch) {
            val path = source.rebuildSourcePath ?: continue
            val format =
                PitchAccentSourceFormat.entries.firstOrNull { path.endsWith(it.fileSuffix) }
                    ?: continue
            ResourceBridgeCodec.decodeImportedPitch(
                bridge.dispatch(
                    ResourceBridgeCodec.encodePitchImportRequest(
                        "resource_${UUID.randomUUID().toString().replace("-", "")}",
                        path,
                        source.sourceId,
                        source.sourceName,
                        format,
                        overwrite = true,
                    ),
                    null,
                ),
            )
        }
    }

    private fun rebuildStaleDictionaries() {
        val dictionaries =
            ResourceBridgeCodec.decodeDictionaryList(
                bridge.dispatch(ResourceBridgeCodec.encodeDictionaryListRequest(), null),
            )
        for (slot in dictionaries) {
            if (!slot.occupied || slot.schemaOk) continue
            val path = slot.rebuildSourcePath ?: continue
            try {
                ResourceBridgeCodec.decodeImportedDictionary(
                    bridge.dispatch(
                        ResourceBridgeCodec.encodeDictionaryImportRequest(
                            "resource_${UUID.randomUUID().toString().replace("-", "")}",
                            path,
                            slot.slotId,
                            overwrite = true,
                            catalogResourceId = slot.catalogResourceId,
                        ),
                        null,
                    ),
                )
            } catch (failure: Exception) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "dictionary.rebuild",
                    failure,
                    "slot" to slot.slotId,
                    "outcome" to "fail",
                )
                // Degrade: refreshFromPython surfaces dictionary_resource_invalid,
                // which offers the replace retry for this slot.
            }
        }
    }

    /**
     * REQUESTED is transient, not a delivery outcome: [cancelActive] sets it before queueing the
     * bridge dispatch, so a cancel racing a committing worker is still REQUESTED here whenever the
     * control executor has not drained. It reports plain cancellation like DELIVERED — only FAILED
     * is a delivery failure, and the control executor publishes that itself while this operation is
     * still active.
     */
    private fun verifyTerminalCancellation(operation: ActiveOperation) {
        when (operation.cancelDelivery.get()) {
            CancelDelivery.FAILED -> throw ResourceCancellationDeliveryException()
            CancelDelivery.REQUESTED,
            CancelDelivery.DELIVERED,
            CancelDelivery.NOT_REQUESTED,
            -> operation.cancellation.check()
        }
    }

    private fun recordCancelDeliveryFailure(operation: ActiveOperation) {
        val stillActive = synchronized(activeMonitor) { active === operation }
        if (!stillActive) return
        recordFailure(
            code = "resource_cancel_delivery_failed",
            message = strings.resolve(R.string.resource_failure_operation),
            origin = ResourceFailureOrigin.SETUP,
            retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
            knownWordsOperation = operation.knownWordsOperation,
        )
    }

    private fun deleteExportDestination(uri: String) {
        try {
            documentWriter.delete(uri)
            // instrumentation: silent — provider may already have rolled back cancellation
        } catch (_: Exception) {
            // Best effort: provider may already have rolled the cancelled document back.
        }
    }

    private fun interruptedOperationAlreadyCommitted(
        operation: PersistedResourceOperation,
    ): Boolean =
        when (operation.origin) {
            ResourceFailureOrigin.UNIDIC -> mutableState.value.installedUniDic != null
            ResourceFailureOrigin.CATALOG_DICTIONARY -> {
                val resourceId = operation.retry.targetId
                resourceId != null &&
                    mutableState.value.catalogDictionaries.any {
                        it.installed && it.resource.resourceId == resourceId
                    }
            }
            else -> false
        }

    private fun consumePinnedArchive(
        operation: ActiveOperation,
        archive: ResourceArchive,
        block: () -> Unit,
    ) {
        try {
            block()
            downloader.discard(archive)
        } catch (failure: Exception) {
            val invalidArchive =
                failure is ResourceBridgeException && failure.code in PINNED_ARCHIVE_INVALID_CODES
            if (
                operation.cancellation.isCancelled() ||
                failure is CancellationException ||
                invalidArchive
            ) {
                downloader.discard(archive)
            }
            throw failure
        }
    }

    private fun discardInstalledCatalogDownloads() {
        val current = mutableState.value
        val catalog = current.catalog ?: return
        if (current.installedUniDic?.resourceId == catalog.unidic.resourceId) {
            downloader.discard(catalog.unidic.archive)
        }
        current.catalogDictionaries
            .filter { it.installed }
            .forEach { downloader.discard(it.resource.archive) }
    }

    private fun catalog(): ResourceCatalog {
        mutableState.value.catalog?.let { return it }
        val value = ResourceBridgeCodec.decodeCatalog(bridge.dispatch(ResourceBridgeCodec.encodeCatalogRequest(), null))
        mutableState.update { it.copy(catalog = value) }
        return value
    }

    private fun refreshFromPython() {
        val catalog = catalog()
        val dictionaries =
            ResourceBridgeCodec.decodeDictionaryList(
                bridge.dispatch(ResourceBridgeCodec.encodeDictionaryListRequest(), null),
            ).sortedBy { it.slotId }
        val localResources =
            ResourceBridgeCodec.decodeLocalResourceList(
                bridge.dispatch(ResourceBridgeCodec.encodeLocalResourceListRequest(), null),
            )
        val fatalInventoryFailure =
            when {
                dictionaries.any { !it.isUsable } ->
                    ResourceBridgeException(
                        "dictionary_resource_invalid",
                        "An occupied dictionary slot is incomplete, unsafe, or uses an old schema",
                    )
                localResources.pitchSources.any { !it.schemaOk } ->
                    ResourceBridgeException(
                        "pitch_resource_invalid",
                        "Installed pitch-accent data is incomplete or malformed",
                    )
                !localResources.knownWords.schemaOk ->
                    ResourceBridgeException(
                        "known_words_resource_invalid",
                        "Known-word storage is incomplete or malformed",
                    )
                else -> null
            }
        val installed =
            tokenizerResources.installedResource()?.let { resource ->
                val expected = catalog.unidic
                check(
                    resource.resourceId == expected.resourceId &&
                        resource.treeSha256 == expected.install.treeSha256 &&
                        resource.dicDir.canonicalPath.endsWith(
                            "/resources/tokenizer/${expected.resourceId}/dicdir",
                        )
                )
                InstalledUniDic(
                    resource.resourceId,
                    resource.dicDir.canonicalPath,
                    resource.treeSha256,
                    expected.install.fileCount,
                    expected.install.sizeBytes,
                    alreadyInstalled = true,
                    attribution = expected.attribution,
                )
            }
        mutableState.update {
            it.copy(
                startupReadiness =
                    if (fatalInventoryFailure != null) {
                        ResourceStartupReadiness.FAILED
                    } else {
                        it.startupReadiness
                    },
                catalog = catalog,
                dictionaries = dictionaries,
                frequencySources = localResources.frequencies.sortedBy { source -> source.sourceId },
                pitchSources = localResources.pitchSources,
                audioPacks = localResources.audioPacks.sortedBy { pack -> pack.packId },
                knownWords = localResources.knownWords,
                wordsets = localResources.wordsets,
                installedUniDic = installed,
            )
        }
        // Publish the invalid inventory so Setup can explain exactly what must be replaced, then
        // fail closed because both fixed resources are consumed without a selectable chain gate.
        fatalInventoryFailure?.let { throw it }
    }

    /**
     * Python publishes imported resources before it encodes the success envelope. If Kotlin rejects
     * that envelope, refresh durable inventory before reporting the contract failure so same-session
     * state cannot claim the committed mutation did not happen.
     */
    private inline fun <T> decodePublishedMutation(
        raw: String,
        decode: (String) -> T,
    ): T =
        try {
            decode(raw)
        } catch (failure: ResourceBridgeException) {
            try {
                refreshFromPython()
            } catch (reconciliationFailure: Exception) {
                failure.addSuppressed(reconciliationFailure)
            }
            throw failure
        }

    /**
     * Counts belong to the phase that reported them. Entering a new phase without its own numbers
     * resets to `0/0`, so the bar goes indeterminate instead of inheriting the previous phase's
     * completed total and sitting full while the real work runs.
     */
    private fun updateProgress(
        operation: ActiveOperation,
        phase: ResourceOperationPhase,
        current: Long? = null,
        total: Long? = null,
        unit: ResourceProgressUnit = ResourceProgressUnit.BYTES,
    ) {
        val advanced =
            synchronized(activeMonitor) {
                if (active !== operation) return
                var published: ResourceOperationProgress? = null
                mutableState.update { state ->
                    val next =
                        state.activeOperation.advancedTo(
                            operationId = operation.id,
                            label = operation.label,
                            phase = phase,
                            completed = current,
                            total = total,
                            unit = unit,
                        )
                    published = next
                    state.copy(activeOperation = next)
                }
                published
            }
        if (operation.holdsForegroundLease && advanced != null) foregroundLease.update(advanced)
    }

    private fun recordFailure(
        operation: ActiveOperation,
        code: String,
        message: String,
        faultId: String? = null,
    ) {
        val invalidDictionary =
            mutableState.value.dictionaries.firstOrNull { it.occupied && !it.isUsable }
        val (origin, retry) =
            when (code) {
                "dictionary_resource_invalid" ->
                    invalidDictionary
                        ?.catalogResourceId
                        ?.let { resourceId ->
                            ResourceFailureOrigin.CATALOG_DICTIONARY to
                                ResourceFailureRetry(
                                    action = ResourceFailureAction.RETRY,
                                    targetId = resourceId,
                                    replace = true,
                                )
                        }
                        ?: (
                            ResourceFailureOrigin.CUSTOM_DICTIONARY to
                                ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER)
                        )
                "pitch_resource_invalid" ->
                    ResourceFailureOrigin.PITCH to
                        ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER)
                "known_words_resource_invalid" ->
                    ResourceFailureOrigin.KNOWN_WORDS to
                        ResourceFailureRetry(ResourceFailureAction.RESOLVE)
                else -> operation.failureOrigin to operation.failureRetry
            }
        recordFailure(
            code,
            message,
            origin,
            retry,
            operation.knownWordsOperation,
            faultId,
            operation.deleteTarget,
        )
    }

    private fun recordFailure(
        code: String,
        message: String,
        origin: ResourceFailureOrigin,
        retry: ResourceFailureRetry,
        knownWordsOperation: KnownWordsFailureOperation? = null,
        faultId: String? = null,
        deleteTarget: ResourceDeleteTarget? = null,
    ) {
        // Every recorded failure funnels through here, and several distinct
        // codes share one user-facing string: resource_operation_interrupted
        // (journal replay after the process was killed) is indistinguishable
        // on screen from resource_operation_failed (a live exception). Only
        // the latter is logged by its call site, so without this line a bug
        // report cannot say which one fired. INFO, not DEBUG - it has to reach
        // a release diagnostics bundle.
        AppLog.i(
            LogComponent.RESOURCES,
            "failure.record",
            "code" to code,
            "origin" to origin.name,
            "fault" to faultId,
            "retryable" to (code in RETRYABLE_FAILURES),
            "outcome" to "fail",
        )
        mutableState.update {
            it.copy(
                failure =
                    ResourceFailure(
                        code = code,
                        message = message,
                        retryable = code in RETRYABLE_FAILURES,
                        faultId = faultId,
                        origin = origin,
                        retry = retry,
                        knownWordsOperation = knownWordsOperation,
                        deleteTarget = deleteTarget,
                    ),
            )
        }
    }

    private fun safAccessCode(kind: SafAccessFailureKind): String =
        when (kind) {
            SafAccessFailureKind.PERMISSION_REVOKED -> "saf_permission_not_granted"
            SafAccessFailureKind.PROVIDER_UNAVAILABLE -> "saf_provider_unavailable"
            SafAccessFailureKind.INVALID_URI -> "saf_uri_invalid"
        }

    private fun safAccessUserMessage(kind: SafAccessFailureKind): String =
        when (kind) {
            SafAccessFailureKind.PERMISSION_REVOKED ->
                strings.resolve(R.string.resource_failure_saf_permission)
            SafAccessFailureKind.PROVIDER_UNAVAILABLE ->
                strings.resolve(R.string.resource_failure_saf_provider)
            SafAccessFailureKind.INVALID_URI ->
                strings.resolve(R.string.resource_failure_saf_uri)
        }

    private fun downloadUserMessage(failure: ResourceDownloadException): String =
        when (failure.stableCode) {
            "resource_operation_cancelled" ->
                strings.resolve(R.string.resource_failure_download_cancelled)
            "download_redirect_limit" ->
                strings.resolve(R.string.resource_failure_download_redirect_limit)
            "download_redirect_invalid" ->
                strings.resolve(R.string.resource_failure_download_redirect_invalid)
            "download_url_invalid" ->
                strings.resolve(R.string.resource_failure_download_url_invalid)
            "download_staging_failed" ->
                strings.resolve(R.string.resource_failure_download_staging)
            "resource_archive_mismatch" ->
                strings.resolve(R.string.resource_failure_download_archive_mismatch)
            "download_publish_failed" ->
                strings.resolve(R.string.resource_failure_download_publish)
            "download_retry_exhausted" ->
                strings.resolve(
                    R.string.resource_failure_download_retry_exhausted,
                    failure.formatArguments,
                )
            "download_http_retryable" ->
                strings.resolve(
                    R.string.resource_failure_download_http_retryable,
                    failure.formatArguments,
                )
            "download_http_rejected" ->
                strings.resolve(
                    R.string.resource_failure_download_http_rejected,
                    failure.formatArguments,
                )
            "download_incomplete" ->
                strings.resolve(R.string.resource_failure_download_incomplete)
            "download_resume_invalid" ->
                strings.resolve(R.string.resource_failure_download_resume_invalid)
            "import_staging_failed" ->
                strings.resolve(R.string.resource_failure_import_staging)
            "word_list_not_utf8" ->
                strings.resolve(R.string.resource_failure_word_list_not_utf8)
            "word_list_remove_failed" ->
                strings.resolve(R.string.resource_failure_word_list_remove)
            "import_source_unavailable" ->
                strings.resolve(
                    R.string.resource_failure_import_source_unavailable,
                    failure.formatArguments,
                )
            "resource_archive_too_large" ->
                strings.resolve(
                    R.string.resource_failure_archive_too_large,
                    failure.formatArguments,
                )
            "resource_archive_unrecognized" ->
                strings.resolve(R.string.resource_failure_archive_unrecognized)
            "resource_archive_provider_representation" ->
                strings.resolve(R.string.resource_failure_archive_provider_representation)
            else ->
                strings.resolve(
                    R.string.resource_failure_unknown_download_code,
                    listOf(failure.stableCode),
                )
        }

    private fun userMessage(code: String): String =
        when (code) {
            "insufficient_storage", "resource_space_unknown" ->
                strings.resolve(R.string.resource_failure_storage_unavailable)
            "resource_archive_mismatch" ->
                strings.resolve(R.string.resource_failure_archive_mismatch)
            "resource_archive_too_large" ->
                strings.resolve(R.string.resource_failure_bridge_archive_too_large)
            "resource_archive_member_oversized" ->
                strings.resolve(R.string.resource_failure_archive_member_oversized)
            "resource_archive_member_count" ->
                strings.resolve(R.string.resource_failure_archive_member_count)
            "resource_archive_expands_too_large" ->
                strings.resolve(R.string.resource_failure_archive_expands)
            "invalid_resource_archive" ->
                strings.resolve(R.string.resource_failure_archive_invalid)
            "resource_archive_unrecognized" ->
                strings.resolve(R.string.resource_failure_archive_unrecognized)
            "resource_archive_provider_representation" ->
                strings.resolve(R.string.resource_failure_archive_provider_representation)
            "unsafe_resource_archive" ->
                strings.resolve(R.string.resource_failure_archive_unsafe)
            "resource_archive_unsupported_compression" ->
                strings.resolve(R.string.resource_failure_archive_compression)
            "resource_already_installed" ->
                strings.resolve(R.string.resource_failure_slot_exists)
            "dictionary_import_failed" ->
                strings.resolve(R.string.resource_failure_dictionary_import)
            "frequency_import_failed" ->
                strings.resolve(R.string.resource_failure_frequency_import)
            "pitch_import_failed" ->
                strings.resolve(R.string.resource_failure_pitch_import)
            "pitch_resource_invalid" ->
                strings.resolve(R.string.resource_failure_pitch_invalid)
            "audio_pack_import_failed" ->
                strings.resolve(R.string.resource_failure_audio_pack_import)
            "audio_pack_none_detected" ->
                strings.resolve(R.string.resource_failure_audio_pack_none_detected)
            "audio_pack_index_malformed" ->
                strings.resolve(R.string.resource_failure_audio_pack_index_malformed)
            "audio_pack_id_reserved" ->
                strings.resolve(R.string.resource_failure_audio_pack_reserved)
            "known_words_import_failed" ->
                strings.resolve(R.string.resource_failure_known_words_import)
            "known_words_database_unsafe", "resource_inventory_failed" ->
                strings.resolve(R.string.resource_failure_inventory_unsafe)
            "dictionary_schema_mismatch" ->
                strings.resolve(R.string.resource_failure_dictionary_schema)
            "dictionary_resource_invalid" ->
                strings.resolve(R.string.resource_failure_dictionary_invalid)
            "resource_cleanup_failed" ->
                strings.resolve(R.string.resource_failure_delete)
            else -> strings.resolve(R.string.resource_failure_unknown_bridge_code, listOf(code))
        }

    private fun cancelPython(operation: ActiveOperation) {
        if (!operation.pythonStarted.get()) return
        controlExecutor.execute {
            try {
                bridge.dispatch(ResourceBridgeCodec.encodeCancelRequest(operation.id), null)
            } catch (failure: Exception) {
                AppLog.ignored(
                    LogComponent.RESOURCES,
                    "operation.cancel",
                    "worker_owns_cleanup",
                    failure,
                )
                // The worker still owns cleanup and recovery.
            }
        }
    }

    private fun clearStaging() {
        if (!stagingRoot.exists()) return
        stagingRoot.listFiles()?.forEach { child ->
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }

    private fun consumeRetainedResourceImport(uri: String): Int? =
        synchronized(retainedResourceImportsMonitor) {
            consumeRetainedResourceImportLocked(uri)
        }

    private fun consumeRetainedResourceImportLocked(uri: String): Int? {
        val count = retainedResourceImports[uri] ?: return null
        if (count == 1) {
            retainedResourceImports.remove(uri)
        } else {
            retainedResourceImports[uri] = count - 1
        }
        return count - 1
    }

    private fun clearResourceImportSelection(uri: String) {
        val selection = safSelectionInventory.selection(SafSelectionSlot.RESOURCE_IMPORT)
        if (selection?.uri == uri) {
            safSelectionInventory.putSelection(SafSelectionSlot.RESOURCE_IMPORT, null)
        }
    }

    private fun cleanupInterruptedResourceImport(operation: PersistedResourceOperation?) {
        if (operation?.resourceImportOwnership != ResourceImportOwnershipPhase.INVENTORY_RETAINED) {
            return
        }
        val uri = checkNotNull(operation.resourceImportUri)
        clearResourceImportSelection(uri)
        runBlocking { safBroker.releaseReadAccess(uri) }
    }

    private fun releaseResourceImportAfterOperation(
        operation: ActiveOperation,
        uri: String,
        clearSelection: Boolean,
    ) {
        try {
            if (clearSelection) clearResourceImportSelection(uri)
            runBlocking { safBroker.releaseReadAccess(uri) }
        } catch (failure: Throwable) {
            operation.retainJournalForRecovery.set(true)
            throw failure
        }
    }

    private suspend fun <T> runOnExecutor(
        executor: Executor,
        block: () -> T,
    ): T =
        suspendCoroutine { continuation ->
            executor.execute {
                try {
                    continuation.resume(block())
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            }
        }

    private companion object {
        const val RESOURCE_IMPORT_PREFIX_BYTES = 4
        const val RESOURCE_IMPORT_SELECTION_LABEL = "Pending resource import"
        val RETRYABLE_FAILURES =
            setOf(
                "download_retry_exhausted",
                "download_http_retryable",
                "resource_space_unknown",
                "insufficient_storage",
                "resource_busy",
                "resource_already_installed",
                "frequency_import_failed",
                "pitch_import_failed",
                "audio_pack_import_failed",
                "known_words_import_failed",
                "resource_operation_failed",
                "resource_operation_interrupted",
                "resource_inventory_failed",
                "resource_cancel_delivery_failed",
                "resource_cleanup_failed",
            )

        val PINNED_ARCHIVE_INVALID_CODES =
            setOf(
                "resource_archive_mismatch",
                "resource_archive_too_large",
                "resource_archive_unsupported_compression",
                "invalid_resource_archive",
                "unsafe_resource_archive",
                "unidic_provenance_mismatch",
                "dictionary_import_failed",
            )

        const val FREQUENCY_ARCHIVE_LIMIT = 512L * 1024 * 1024
        const val FREQUENCY_TEXT_LIMIT = 64L * 1024 * 1024
        const val PITCH_ARCHIVE_LIMIT = 512L * 1024 * 1024
        const val PITCH_TEXT_LIMIT = 64L * 1024 * 1024
        const val AUDIO_SOURCE_LABEL = "audio-pack archive"
        val AUDIO_PACK_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
        val MIME_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,127}")

        // The retained preflight is one archive of any supported container, so its
        // name carries no format and no pack id. Both are recorded in the index
        // file beside it.
        const val PENDING_AUDIO_ARCHIVE_NAME = "pending-audio-archive"
        const val PENDING_AUDIO_INDEX_NAME = "pending-audio-packs.tsv"
        const val KNOWN_WORDS_FILE_LIMIT = 32L * 1024 * 1024

        /**
         * The engine loads a word list into a set on every run, so this is a working-memory bound,
         * not a storage one. 8 MiB of one-word lines is far past any hand-curated list.
         */
        const val WORD_LIST_FILE_LIMIT = 8L * 1024 * 1024
        const val KNOWN_WORD_EXPORT_LIMIT = 512L * 1024 * 1024
        const val EXPORT_BUFFER_BYTES = 256 * 1024
        const val KNOWN_WORD_PAGE_SIZE = 100
    }
}
