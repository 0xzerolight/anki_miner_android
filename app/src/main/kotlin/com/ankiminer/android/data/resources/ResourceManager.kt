package com.ankiminer.android.data.resources

import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface ResourceManager {
    val state: StateFlow<ResourceManagerState>

    suspend fun recoverAndRefresh()

    suspend fun installUniDic()

    suspend fun installCatalogDictionary(resourceId: String, replace: Boolean)

    suspend fun importCustomDictionary(
        uri: String,
        slotId: String,
        replace: Boolean,
    )

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

    suspend fun importAudioPack(
        uri: String,
        packId: String,
        replace: Boolean,
    )

    suspend fun importKnownWords(
        uri: String,
        format: KnownWordsSourceFormat,
    )

    suspend fun importWordList(uri: String, kind: WordListKind)

    suspend fun removeWordList(kind: WordListKind)

    /** Absolute path the engine should read for [kind], or null when no file is installed. */
    fun wordListPath(kind: WordListKind): String?

    suspend fun previewKnownWords(uri: String, format: KnownWordsSourceFormat)

    suspend fun confirmKnownWordsImport()

    fun dismissKnownWordsImportPreview()

    suspend fun searchKnownWords(query: String, loadMore: Boolean = false)

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

    fun installedAudioPackIds(): List<String> =
        state.value.audioPacks.filter { it.contentAvailable && it.entryCount > 0 }.map { it.packId }

    fun bundledWordsetIds(): List<String> = state.value.wordsets.map { it.wordsetId }
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
    private val stagingAvailableBytes: (File) -> Long = ::usableSpaceForStaging,
) : ResourceManager {
    private data class ActiveOperation(
        val id: String,
        val label: String,
        val cancellation: ResourceCancellationSignal,
        val failureOrigin: ResourceFailureOrigin,
        val failureRetry: ResourceFailureRetry,
        val knownWordsOperation: KnownWordsFailureOperation?,
        val pythonStarted: AtomicBoolean = AtomicBoolean(false),
    )

    private data class PendingKnownWordsImport(
        val staged: StagedArchive,
        val format: KnownWordsSourceFormat,
    )

    private val mutableState = MutableStateFlow(ResourceManagerState())
    override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private val activeMonitor = Any()
    private val pendingKnownWordsRoot = File(stagingRoot.parentFile, "resource-pending-known-words")

    /**
     * Sibling of the staging root, so the retention rename stays on one volume and [clearStaging]
     * never sweeps a file the engine is expected to keep reading.
     */
    private val wordListRoot = File(stagingRoot.parentFile, "resource-word-lists")
    private var active: ActiveOperation? = null
    private var pendingKnownWordsImport: PendingKnownWordsImport? = null

    override suspend fun recoverAndRefresh() {
        val startupWasReady = mutableState.value.startupReadiness == ResourceStartupReadiness.READY
        if (!startupWasReady) {
            mutableState.update { it.copy(startupReadiness = ResourceStartupReadiness.RECOVERING) }
        }
        runOperation(
            strings.resolve(R.string.resource_operation_refresh),
            ResourceOperationPhase.REFRESHING,
            failureOrigin = ResourceFailureOrigin.SETUP,
        ) { operation ->
            clearPendingKnownWordsImport()
            mutableState.update { it.copy(knownWordsImportPreview = null) }
            clearStaging()
            downloader.reconcile(FrozenResourceCatalog.value.resources.map { it.archive })
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            ResourceBridgeCodec.decodeCleanup(
                bridge.dispatch(ResourceBridgeCodec.encodeCleanupRequest(), null),
            )
            refreshFromPython()
            refreshWordLists()
            discardInstalledCatalogDownloads()
        }
        if (!startupWasReady) {
            mutableState.update { current ->
                current.copy(
                    startupReadiness =
                        if (
                            current.failure == null &&
                            current.catalog != null
                        ) {
                            ResourceStartupReadiness.READY
                        } else {
                            ResourceStartupReadiness.FAILED
                        },
                )
            }
        }
    }

    override suspend fun installUniDic() {
        runOperation(
            strings.resolve(R.string.resource_operation_install_unidic),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.UNIDIC,
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
                ResourceBridgeCodec.decodeImportedDictionary(
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
                )
                refreshFromPython()
            }
        }
    }

    override suspend fun importCustomDictionary(
        uri: String,
        slotId: String,
        replace: Boolean,
    ) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_custom_dictionary),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.CUSTOM_DICTIONARY,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
        ) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(retained.uri, operation.id, operation.cancellation) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                ResourceBridgeCodec.decodeImportedDictionary(
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
                )
                refreshFromPython()
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
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
                operation.pythonStarted.set(true)
                val imported =
                    ResourceBridgeCodec.decodeImportedFrequency(
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
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshFromPython()
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
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
                    ResourceBridgeCodec.decodeImportedPitch(
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
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshFromPython()
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
        }
    }

    override suspend fun importAudioPack(
        uri: String,
        packId: String,
        replace: Boolean,
    ) {
        runOperation(
            strings.resolve(R.string.resource_operation_import_audio_pack),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.AUDIO,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
        ) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                // Audio packs are multi-gigabyte. Reject on the size the picker
                // already reported rather than after streaming the whole ZIP.
                val budget = audioArchiveBudget(stagingAvailableBytes(stagingRoot))
                val reported = retained.sizeBytes
                if (reported != null && reported > budget) {
                    throw archiveTooLarge(AUDIO_SOURCE_LABEL, reported, budget)
                }
                staged =
                    safStager.stage(
                        sourceUri = retained.uri,
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = ".zip",
                        maximumBytes = budget,
                        sourceLabel = AUDIO_SOURCE_LABEL,
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val imported =
                    ResourceBridgeCodec.decodeImportedAudioPack(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodeAudioPackImportRequest(
                                operation.id,
                                staged.file.canonicalPath,
                                packId,
                                replace,
                            ),
                            null,
                        ),
                    )
                mutableState.update { it.copy(lastLocalImport = imported) }
                refreshFromPython()
            } finally {
                staged?.file?.delete()
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
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
                refreshFromPython()
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
                        WordListFileFormat.entryCount(staged.file)
                    } catch (failure: CharacterCodingException) {
                        throw ResourceDownloadException(
                            "word_list_not_utf8",
                            "The word-list file is not UTF-8 text",
                            failure,
                        )
                    }
                if (!wordListRoot.exists() && !wordListRoot.mkdirs()) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not store the word-list file",
                    )
                }
                val target = File(wordListRoot, kind.fileName)
                target.delete()
                if (!staged.file.renameTo(target)) {
                    throw ResourceDownloadException(
                        "import_staging_failed",
                        "Could not store the word-list file",
                    )
                }
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
            val target = File(wordListRoot, kind.fileName)
            if (target.exists() && !target.delete()) {
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

    override suspend fun previewKnownWords(uri: String, format: KnownWordsSourceFormat) {
        runOperation(
            strings.resolve(R.string.resource_operation_preview_known_words),
            ResourceOperationPhase.PREPARING,
            failureOrigin = ResourceFailureOrigin.KNOWN_WORDS,
            failureRetry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            knownWordsOperation = KnownWordsFailureOperation.PREVIEW,
        ) { operation ->
            clearPendingKnownWordsImport()
            mutableState.update { it.copy(knownWordsImportPreview = null) }
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
                runBlocking { safBroker.releaseReadAccess(retained.uri) }
            }
        }
    }

    override suspend fun confirmKnownWordsImport() {
        val pending = pendingKnownWordsImport ?: return
        runOperation(
            strings.resolve(R.string.resource_operation_import_known_words),
            ResourceOperationPhase.IMPORTING,
            ResourceFailureOrigin.KNOWN_WORDS,
            knownWordsOperation = KnownWordsFailureOperation.IMPORT,
        ) { operation ->
            try {
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
                    it.copy(lastLocalImport = imported, knownWordsImportPreview = null)
                }
                refreshFromPython()
            } finally {
                clearPendingKnownWordsImport()
                mutableState.update { it.copy(knownWordsImportPreview = null) }
            }
        }
    }

    override fun dismissKnownWordsImportPreview() {
        clearPendingKnownWordsImport()
        mutableState.update { it.copy(knownWordsImportPreview = null) }
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

    override suspend fun removeKnownWords(words: List<String>) {
        runKnownWordsMutation(
            strings.resolve(R.string.resource_operation_remove_known_words),
            ResourceOperationPhase.IMPORTING,
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
                documentWriter.open(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                } ?: throw ResourceBridgeException(
                    "known_words_export_failed",
                    "The selected export document could not be opened",
                )
            } finally {
                source.delete()
                operationRoot.delete()
            }
        }
    }

    private suspend fun runKnownWordsMutation(
        label: String,
        phase: ResourceOperationPhase,
        mutate: (ActiveOperation) -> Unit,
    ) {
        runOperation(
            label,
            phase,
            ResourceFailureOrigin.KNOWN_WORDS,
            ResourceFailureRetry(ResourceFailureAction.RESOLVE),
        ) { operation ->
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            mutate(operation)
            refreshFromPython()
            mutableState.update { it.copy(knownWordsPage = null) }
        }
    }

    private fun clearPendingKnownWordsImport() {
        pendingKnownWordsImport?.staged?.file?.delete()
        pendingKnownWordsImport = null
        pendingKnownWordsRoot.listFiles()?.forEach { it.delete() }
        pendingKnownWordsRoot.delete()
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
        operation.cancellation.cancel()
        updateProgress(operation, ResourceOperationPhase.CANCELLING)
        // Dispatch even before Python registration. Its bounded sticky-cancellation registry
        // closes the control/worker race without making cancellation depend on check-then-act.
        controlExecutor.execute {
            try {
                val raw = bridge.dispatch(ResourceBridgeCodec.encodeCancelRequest(operation.id), null)
                ResourceBridgeCodec.decodeCancelAccepted(raw, operation.id)
            } catch (failure: Exception) {
                AppLog.ignored(
                    LogComponent.RESOURCES,
                    "operation.cancel",
                    "worker_owns_terminal_state",
                    failure,
                )
                // The operation worker owns terminal state. A late/failed cancel means it already
                // crossed its final cancellation checkpoint or completed.
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
        block: (ActiveOperation) -> Unit,
    ) {
        if (!operationMutex.tryLock()) {
            return
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
                )
                return
            }
            val operation =
                ActiveOperation(
                    id = "resource_${UUID.randomUUID().toString().replace("-", "")}",
                    label = label,
                    cancellation = ResourceCancellationSignal(),
                    failureOrigin = failureOrigin,
                    failureRetry = failureRetry,
                    knownWordsOperation = knownWordsOperation,
                )
            synchronized(activeMonitor) { active = operation }
            mutableState.update {
                it.copy(
                    activeOperation = ResourceOperationProgress(operation.id, label, initialPhase),
                )
            }
            var completed = false
            try {
                runOnExecutor(resourceExecutor) { block(operation) }
                completed = true
            } catch (failure: CancellationException) {
                operation.cancellation.cancel()
                cancelPython(operation)
                throw failure
            } catch (failure: ResourceDownloadException) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    failure,
                    "operation" to operation.id,
                    "code" to failure.stableCode,
                    "outcome" to "fail",
                )
                if (failure.stableCode != "resource_operation_cancelled") {
                    recordFailure(operation, failure.stableCode, downloadUserMessage(failure))
                }
            } catch (failure: ResourceStorageException) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    failure,
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
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    failure.cause ?: failure,
                    "operation" to operation.id,
                    "code" to failure.code,
                    "fault" to failure.faultId,
                    "outcome" to "fail",
                )
                if (failure.code != "resource_operation_cancelled") {
                    // userMessage(code) is unchanged; the id rides beside it into diagnostics only.
                    recordFailure(operation, failure.code, userMessage(failure.code), failure.faultId)
                }
            } catch (failure: Exception) {
                AppLog.e(
                    LogComponent.RESOURCES,
                    "operation.run",
                    failure,
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
                if (completed) {
                    mutableState.update { current ->
                        if (current.failure?.origin == operation.failureOrigin) {
                            current.copy(failure = null)
                        } else {
                            current
                        }
                    }
                }
                try {
                    clearStaging()
                } finally {
                    try {
                        synchronized(activeMonitor) { if (active === operation) active = null }
                        mutableState.update { it.copy(activeOperation = null) }
                    } finally {
                        workLease.close()
                    }
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    private fun download(
        resource: CatalogResource,
        operation: ActiveOperation,
    ): StagedArchive =
        downloader.download(resource.archive, operation.cancellation) { current, total, phase ->
            updateProgress(operation, phase, current, total)
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
                    } else if (it.startupReadiness == ResourceStartupReadiness.FAILED) {
                        ResourceStartupReadiness.READY
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
        synchronized(activeMonitor) {
            if (active !== operation) return
            mutableState.update { state ->
                state.copy(
                    activeOperation =
                        state.activeOperation.advancedTo(
                            operationId = operation.id,
                            label = operation.label,
                            phase = phase,
                            completed = current,
                            total = total,
                            unit = unit,
                        ),
                )
            }
        }
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
        recordFailure(code, message, origin, retry, operation.knownWordsOperation, faultId)
    }

    private fun recordFailure(
        code: String,
        message: String,
        origin: ResourceFailureOrigin,
        retry: ResourceFailureRetry,
        knownWordsOperation: KnownWordsFailureOperation? = null,
        faultId: String? = null,
    ) {
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
                    ),
            )
        }
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
            "invalid_resource_archive" ->
                strings.resolve(R.string.resource_failure_archive_invalid)
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
            "known_words_import_failed" ->
                strings.resolve(R.string.resource_failure_known_words_import)
            "known_words_database_unsafe", "resource_inventory_failed" ->
                strings.resolve(R.string.resource_failure_inventory_unsafe)
            "dictionary_schema_mismatch" ->
                strings.resolve(R.string.resource_failure_dictionary_schema)
            "dictionary_resource_invalid" ->
                strings.resolve(R.string.resource_failure_dictionary_invalid)
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
        const val AUDIO_SOURCE_LABEL = "audio-pack ZIP"
        const val KNOWN_WORDS_FILE_LIMIT = 32L * 1024 * 1024

        /**
         * The engine loads a word list into a set on every run, so this is a working-memory bound,
         * not a storage one. 8 MiB of one-word lines is far past any hand-curated list.
         */
        const val WORD_LIST_FILE_LIMIT = 8L * 1024 * 1024
        const val KNOWN_WORD_EXPORT_LIMIT = 512L * 1024 * 1024
        const val KNOWN_WORD_PAGE_SIZE = 100
    }
}
