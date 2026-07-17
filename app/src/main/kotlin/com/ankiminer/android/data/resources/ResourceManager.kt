package com.ankiminer.android.data.resources

import android.content.ContentResolver
import android.net.Uri
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import java.io.File
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

    suspend fun installRecommendedDictionary(replace: Boolean)

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
    private val resolver: ContentResolver,
    private val safBroker: SafBroker,
    private val bridge: PyBridge,
    private val tokenizerResources: InstalledTokenizerResourceProvider,
    private val stagingRoot: File,
    private val resourceExecutor: Executor,
    private val controlExecutor: Executor,
    private val runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
    private val downloader: PinnedResourceDownloader,
    private val safStager: SafArchiveStager = SafArchiveStager(resolver, stagingRoot),
) : ResourceManager {
    private data class ActiveOperation(
        val id: String,
        val label: String,
        val cancellation: ResourceCancellationSignal,
        val pythonStarted: AtomicBoolean = AtomicBoolean(false),
    )

    private val mutableState = MutableStateFlow(ResourceManagerState())
    override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private val activeMonitor = Any()
    private var active: ActiveOperation? = null

    override suspend fun recoverAndRefresh() {
        val startupWasReady = mutableState.value.startupReadiness == ResourceStartupReadiness.READY
        if (!startupWasReady) {
            mutableState.update { it.copy(startupReadiness = ResourceStartupReadiness.RECOVERING) }
        }
        runOperation("Refresh resources", ResourceOperationPhase.REFRESHING) { operation ->
            clearStaging()
            downloader.reconcile(FrozenResourceCatalog.value.resources.map { it.archive })
            operation.cancellation.check()
            operation.pythonStarted.set(true)
            ResourceBridgeCodec.decodeCleanup(
                bridge.dispatch(ResourceBridgeCodec.encodeCleanupRequest(), null),
            )
            refreshFromPython()
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
        runOperation("Install UniDic", ResourceOperationPhase.PREPARING) { operation ->
            val resource = catalog().unidic
            val staged = download(resource, operation)
            consumePinnedArchive(operation, resource.archive) {
                updateProgress(operation, ResourceOperationPhase.INSTALLING, resource.archive.sizeBytes, resource.archive.sizeBytes)
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

    override suspend fun installRecommendedDictionary(replace: Boolean) {
        runOperation("Import recommended dictionary", ResourceOperationPhase.PREPARING) { operation ->
            val resource = catalog().recommendedDictionary
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
                updateProgress(operation, ResourceOperationPhase.IMPORTING, resource.archive.sizeBytes, resource.archive.sizeBytes)
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
        runOperation("Import custom dictionary", ResourceOperationPhase.PREPARING) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(Uri.parse(retained.uri), operation.id, operation.cancellation) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING, staged.sizeBytes, staged.sizeBytes)
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
        runOperation("Import frequency source", ResourceOperationPhase.PREPARING) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        source = Uri.parse(retained.uri),
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
                updateProgress(operation, ResourceOperationPhase.IMPORTING, staged.sizeBytes, staged.sizeBytes)
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
        sourceName: String,
        format: PitchAccentSourceFormat,
        replace: Boolean,
    ) {
        runOperation("Import pitch accent", ResourceOperationPhase.PREPARING) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        source = Uri.parse(retained.uri),
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
                updateProgress(operation, ResourceOperationPhase.IMPORTING, staged.sizeBytes, staged.sizeBytes)
                operation.cancellation.check()
                operation.pythonStarted.set(true)
                val imported =
                    ResourceBridgeCodec.decodeImportedPitch(
                        bridge.dispatch(
                            ResourceBridgeCodec.encodePitchImportRequest(
                                operation.id,
                                staged.file.canonicalPath,
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
        runOperation("Import local audio pack", ResourceOperationPhase.PREPARING) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        source = Uri.parse(retained.uri),
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = ".zip",
                        maximumBytes = AUDIO_ARCHIVE_LIMIT,
                        sourceLabel = "audio-pack ZIP",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING, staged.sizeBytes, staged.sizeBytes)
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
        runOperation("Import known words", ResourceOperationPhase.PREPARING) { operation ->
            val retained = runBlocking { safBroker.retainReadAccess(uri) }
            var staged: StagedArchive? = null
            try {
                staged =
                    safStager.stage(
                        source = Uri.parse(retained.uri),
                        operationId = operation.id,
                        cancellation = operation.cancellation,
                        fileSuffix = format.fileSuffix,
                        maximumBytes = KNOWN_WORDS_FILE_LIMIT,
                        sourceLabel = "known-word export",
                    ) { current, total ->
                        updateProgress(operation, ResourceOperationPhase.PREPARING, current, total)
                    }
                updateProgress(operation, ResourceOperationPhase.IMPORTING, staged.sizeBytes, staged.sizeBytes)
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

    override suspend fun lookup(slotId: String, term: String) {
        runOperation("Dictionary lookup", ResourceOperationPhase.REFRESHING) { operation ->
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
            } catch (_: Exception) {
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
                    "Finish or cancel the active mining run before changing resources",
                )
                return
            }
            val operation =
                ActiveOperation(
                    id = "resource_${UUID.randomUUID().toString().replace("-", "")}",
                    label = label,
                    cancellation = ResourceCancellationSignal(),
                )
            synchronized(activeMonitor) { active = operation }
            mutableState.update {
                it.copy(
                    activeOperation = ResourceOperationProgress(operation.id, label, initialPhase),
                    failure = null,
                )
            }
            try {
                runOnExecutor(resourceExecutor) { block(operation) }
            } catch (failure: CancellationException) {
                operation.cancellation.cancel()
                cancelPython(operation)
                throw failure
            } catch (failure: ResourceDownloadException) {
                if (failure.stableCode != "resource_operation_cancelled") {
                    recordFailure(failure.stableCode, failure.message ?: "Resource download failed")
                }
            } catch (failure: ResourceStorageException) {
                recordFailure("insufficient_storage", "Not enough free private storage for this resource")
            } catch (failure: ResourceBridgeException) {
                if (failure.code != "resource_operation_cancelled") {
                    recordFailure(failure.code, userMessage(failure.code, failure.message))
                }
            } catch (_: Exception) {
                recordFailure("resource_operation_failed", "The resource operation did not complete")
            } finally {
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
        if (current.hasRecommendedDictionary) {
            downloader.discard(catalog.recommendedDictionary.archive)
        }
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
                localResources.pitchAccent?.schemaOk == false ->
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
                pitchAccent = localResources.pitchAccent,
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

    private fun updateProgress(
        operation: ActiveOperation,
        phase: ResourceOperationPhase,
        current: Long = mutableState.value.activeOperation?.completedBytes ?: 0,
        total: Long = mutableState.value.activeOperation?.totalBytes ?: 0,
    ) {
        synchronized(activeMonitor) {
            if (active !== operation) return
            mutableState.update {
                it.copy(
                    activeOperation =
                        ResourceOperationProgress(
                            operation.id,
                            operation.label,
                            phase,
                            current.coerceAtLeast(0),
                            total.coerceAtLeast(0),
                        ),
                )
            }
        }
    }

    private fun recordFailure(code: String, message: String) {
        mutableState.update {
            it.copy(
                failure =
                    ResourceFailure(
                        code = code,
                        message = message,
                        retryable = code in RETRYABLE_FAILURES,
                    ),
            )
        }
    }

    private fun userMessage(code: String, fallback: String): String =
        when (code) {
            "insufficient_storage", "resource_space_unknown" ->
                "Not enough private storage is available for this resource"
            "resource_archive_mismatch" ->
                "The downloaded archive did not match the pinned catalog"
            "resource_already_installed" ->
                "That dictionary slot already exists; choose Replace to update it"
            "dictionary_import_failed" ->
                "The selected ZIP is not a supported Yomitan dictionary"
            "frequency_import_failed" ->
                "The selected file is not a supported frequency source"
            "pitch_import_failed" ->
                "The selected file is not supported pitch-accent data"
            "pitch_resource_invalid" ->
                "Installed pitch-accent data is damaged; replace it before mining"
            "audio_pack_import_failed" ->
                "The selected ZIP is not a supported local audio pack"
            "known_words_import_failed" ->
                "The selected file contains no supported known words"
            "known_words_database_unsafe", "resource_inventory_failed" ->
                "A local resource is damaged or unsafe and needs attention"
            "dictionary_schema_mismatch" ->
                "This dictionary must be replaced because its index schema is stale"
            "dictionary_resource_invalid" ->
                "An occupied dictionary slot is damaged or stale; explicitly replace it before mining"
            else -> fallback
        }

    private fun cancelPython(operation: ActiveOperation) {
        if (!operation.pythonStarted.get()) return
        controlExecutor.execute {
            try {
                bridge.dispatch(ResourceBridgeCodec.encodeCancelRequest(operation.id), null)
            } catch (_: Exception) {
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
                "invalid_resource_archive",
                "unsafe_resource_archive",
                "unidic_provenance_mismatch",
                "dictionary_import_failed",
            )

        const val FREQUENCY_ARCHIVE_LIMIT = 512L * 1024 * 1024
        const val FREQUENCY_TEXT_LIMIT = 64L * 1024 * 1024
        const val PITCH_ARCHIVE_LIMIT = 512L * 1024 * 1024
        const val PITCH_TEXT_LIMIT = 64L * 1024 * 1024
        const val AUDIO_ARCHIVE_LIMIT = 2L * 1024 * 1024 * 1024
        const val KNOWN_WORDS_FILE_LIMIT = 32L * 1024 * 1024
    }
}
