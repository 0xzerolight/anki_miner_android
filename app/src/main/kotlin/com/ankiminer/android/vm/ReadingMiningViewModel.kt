package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.cancellationPending
import com.ankiminer.android.mining.cancellationToken
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.reading.ReadingSourceSelection
import com.ankiminer.android.ui.mining.MiningPendingAction
import com.ankiminer.android.ui.mining.MiningPendingState
import com.ankiminer.android.ui.mining.SharedCurationDraft
import com.ankiminer.android.ui.mining.defaultCurationDraft
import com.ankiminer.android.ui.mining.draftFor
import com.ankiminer.android.ui.mining.toCurationSessionState
import com.ankiminer.android.ui.reading.ReadingCurationUiState
import com.ankiminer.android.ui.reading.ReadingDocumentSelectionError
import com.ankiminer.android.ui.reading.ReadingDocumentSlotState
import com.ankiminer.android.ui.reading.ReadingMiningCommandError
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.ui.reading.ReadingSourceKindUi
import com.ankiminer.android.ui.reading.isReadingArchive
import com.ankiminer.android.ui.reading.readingArchiveMatches
import com.ankiminer.android.ui.reading.readingSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns live SAF grants and revalidates saved URI/display-name metadata after recreation. */
class ReadingMiningViewModel internal constructor(
    private val repository: ReadingMiningRepository,
    private val safBroker: SafBroker,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    selectionInventory: SafSelectionInventory? = null,
    selectionIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class LocalState(
        val source: ReadingDocumentSlotState = ReadingDocumentSlotState(),
        val archive: ReadingDocumentSlotState = ReadingDocumentSlotState(),
        val sourceKind: ReadingSourceKindUi? = null,
        val subtitleSeriesName: String = "",
        val curationDraft: SharedCurationDraft? = null,
        val previousPageSelectedCount: Int = 0,
        val pending: MiningPendingState = MiningPendingState(),
        val commandError: ReadingMiningCommandError? = null,
    )

    private enum class DocumentKind {
        SOURCE,
        ARCHIVE,
    }

    private data class DocumentPublication(
        val replacedDocument: SafDocument?,
        val archiveToClear: SafDocument?,
        val clearArchive: Boolean,
        val clearSeries: Boolean,
    )

    private var sourceDocumentRequest = 0L
    private var archiveDocumentRequest = 0L
    private var sourceDocumentJob: Job? = null
    private var archiveDocumentJob: Job? = null
    private var sourceRestoreInFlight = false
    private var pendingArchiveReplacedUri: String? = null
    private val sourceSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "readingMining.source",
            inventory = selectionInventory,
            inventorySlot = SafSelectionSlot.READING_SOURCE,
            ioDispatcher = selectionIoDispatcher,
        )
    private val archiveSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "readingMining.archive",
            inventory = selectionInventory,
            inventorySlot = SafSelectionSlot.READING_ARCHIVE,
            ioDispatcher = selectionIoDispatcher,
        )
    private val subtitleSeriesSelection =
        SavedTextValueStore(
            savedStateHandle = savedStateHandle,
            savedStateKey = "readingMining.subtitleSeriesName",
            inventory = selectionInventory,
            inventorySlot = SafSelectionSlot.READING_SUBTITLE_SERIES,
        )
    private val localState =
        MutableStateFlow(
            LocalState(subtitleSeriesName = subtitleSeriesSelection.restore()),
        )

    /** Small app-shell state; progress-only repository updates are filtered before composition. */
    internal val navigationWorkflowState: StateFlow<NavigationWorkflowState> =
        repository.state
            .map { it.toNavigationWorkflowState() }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = repository.state.value.toNavigationWorkflowState(),
            )

    val uiState: StateFlow<ReadingMiningUiState> =
        combine(repository.state, localState, runtimeWorkState) { runState, local, activeKind ->
            val curation =
                (runState as? MiningRunState.Curating)?.request?.let { request ->
                    request.toUiState(
                        draft = local.curationDraft,
                        previousPageSelectedCount = local.previousPageSelectedCount,
                    )
                }
            val repositoryCurationPending =
                (runState as? MiningRunState.Curating)?.pageSubmissionPending == true
            ReadingMiningUiState(
                source = local.source,
                archive = local.archive,
                sourceKind = local.sourceKind,
                subtitleSeriesName = local.subtitleSeriesName,
                runState = runState,
                curation = curation,
                startPending = local.pending.start,
                curationPending = local.pending.curation || repositoryCurationPending,
                cancelPending = local.pending.cancel || runState.cancellationPending,
                resetPending = local.pending.reset,
                commandError = local.commandError,
                runtimeConflict =
                    activeKind?.toRuntimeConflict()?.takeIf { runState == MiningRunState.Idle },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ReadingMiningUiState(runState = repository.state.value),
        )

    init {
        viewModelScope.launch {
            repository.state.collect { runState ->
                if (runState is MiningRunState.Curating) {
                    val saved = repository.curationSessionState()
                    localState.update { local ->
                        if (local.curationDraft?.matches(runState.request) == true) {
                            local
                        } else {
                            local.copy(
                                curationDraft =
                                    saved?.draftFor(runState.request)
                                        ?: runState.request.defaultCurationDraft(),
                                previousPageSelectedCount =
                                    saved
                                        ?.previousPageSelectedCount
                                        ?.takeIf { saved.runId == runState.request.runId }
                                        ?: local.previousPageSelectedCount.takeIf {
                                            local.curationDraft?.runId == runState.request.runId
                                        } ?: 0,
                            )
                        }
                    }
                    saveCurationSession(runState.request)
                } else if (runState.isTerminal) {
                    repository.clearCurationSessionState(runState.runId)
                    localState.update { local ->
                        local.copy(
                            curationDraft = null,
                            previousPageSelectedCount = 0,
                            pending = local.pending.afterTerminalState(),
                        )
                    }
                }
            }
        }
        restoreSelections()
    }

    fun onSourcePicked(uri: String) =
        resolveDocument(
            kind = DocumentKind.SOURCE,
            uri = uri,
            onResolved = { accepted ->
                if (accepted) resolvePendingArchive()
            },
        )

    fun onArchivePicked(uri: String) {
        if (uri.isBlank()) return
        pendingArchiveUri = uri
        if (localState.value.sourceKind == ReadingSourceKindUi.MOKURO) {
            resolvePendingArchive()
        } else if (!sourceRestoreInFlight) {
            rejectPendingArchive()
        }
    }

    fun onSubtitleSeriesNameChanged(value: String) {
        if (repository.state.value != MiningRunState.Idle || localState.value.pending.start) return
        val bounded = value.takeCodePoints(MAX_SERIES_NAME_CODE_POINTS)
        localState.update {
            it.copy(subtitleSeriesName = bounded)
        }
        subtitleSeriesSelection.save(bounded)
    }

    fun clearSource() {
        if (repository.state.value != MiningRunState.Idle || localState.value.pending.start) return
        sourceDocumentRequest += 1
        archiveDocumentRequest += 1
        sourceDocumentJob?.cancel()
        archiveDocumentJob?.cancel()
        val local = localState.value
        localState.update {
            it.copy(
                source = ReadingDocumentSlotState(),
                archive = ReadingDocumentSlotState(),
                sourceKind = null,
                subtitleSeriesName = "",
            )
        }
        sourceSelection.clear()
        archiveSelection.clear()
        subtitleSeriesSelection.clear()
        local.source.document?.let(::releaseDocument)
        local.archive.document?.let(::releaseDocument)
    }

    fun clearArchive() {
        if (repository.state.value != MiningRunState.Idle || localState.value.pending.start) return
        archiveDocumentRequest += 1
        archiveDocumentJob?.cancel()
        val document = localState.value.archive.document
        localState.update { it.copy(archive = ReadingDocumentSlotState()) }
        archiveSelection.clear()
        document?.let(::releaseDocument)
    }

    fun dismissDocumentError(error: ReadingDocumentSelectionError) {
        localState.update { local ->
            when (error) {
                ReadingDocumentSelectionError.SOURCE_ACCESS,
                ReadingDocumentSelectionError.SOURCE_TYPE,
                -> local.copy(source = local.source.copy(error = null))
                ReadingDocumentSelectionError.ARCHIVE_ACCESS,
                ReadingDocumentSelectionError.ARCHIVE_TYPE,
                ReadingDocumentSelectionError.ARCHIVE_NAME,
                -> local.copy(archive = local.archive.copy(error = null))
            }
        }
    }

    fun dismissCommandError() {
        localState.update { it.copy(commandError = null) }
    }

    fun start() {
        while (true) {
            val local = localState.value
            val input = local.toInputOrNull() ?: return
            if (
                repository.state.value != MiningRunState.Idle ||
                runtimeWorkState.value != null ||
                local.source.isResolving ||
                local.archive.isResolving ||
                local.pending.start ||
                local.pending.reset
            ) {
                return
            }
            if (
                localState.compareAndSet(
                    local,
                    local.copy(
                        pending = local.pending.begin(MiningPendingAction.START),
                        commandError = null,
                        previousPageSelectedCount = 0,
                    ),
                )
            ) {
                launchStart(input)
                return
            }
        }
    }

    /** Row taps: move the detail, leave inclusion alone. */
    fun focusCandidate(candidateId: String?) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.pending.cancel) return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            local.copy(curationDraft = draft.focusCandidate(request, candidateId))
        }
        saveCurationSession(request)
    }

    /** Checkbox: change inclusion, leave the detail where it is. */
    fun setCandidateSelected(
        candidateId: String,
        selected: Boolean,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.pending.cancel) return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            local.copy(curationDraft = draft.setCandidateSelected(request, candidateId, selected))
        }
        saveCurationSession(request)
    }

    /**
     * Bulk change over the rows the user can actually see. Hidden selections survive, and focus is
     * reconciled against the projection the caller supplies.
     */
    fun setSelectionForVisible(
        visibleCandidateIds: List<String>,
        selected: Boolean,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.pending.cancel) return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            local.copy(
                curationDraft =
                    draft
                        .setSelectionForVisible(request, visibleCandidateIds, selected)
                        .reconcileFocus(visibleCandidateIds),
            )
        }
        saveCurationSession(request)
    }

    /**
     * Page-wide selection, kept distinct from the visible-scope action so the UI can label each
     * one for what it actually reaches.
     */
    fun setSelectionForPage(selected: Boolean) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        setSelectionForVisible(request.candidates.map { it.candidateId }, selected)
    }

    /** Called when search, filter, or sort changes which candidates remain on screen. */
    fun reconcileCurationFocus(
        visibleCandidateIds: List<String>,
        previousVisibleIds: List<String>,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: return@update local
            local.copy(
                curationDraft = draft.reconcileFocus(visibleCandidateIds, previousVisibleIds),
            )
        }
        saveCurationSession(request)
    }

    fun selectSentence(
        candidateId: String,
        sentenceId: String,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.pending.cancel) return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            val updated = draft.selectSentence(request, candidateId, sentenceId) ?: return@update local
            local.copy(curationDraft = updated)
        }
        saveCurationSession(request)
    }

    fun confirmCuration() {
        val runState = repository.state.value as? MiningRunState.Curating ?: return
        if (runState.pageSubmissionPending) return
        var acceptedSelection: List<CurationSelection>? = null
        var acceptedDraft: SharedCurationDraft? = null
        var submittedPreviousPageCount: Int? = null
        while (acceptedSelection == null) {
            val local = localState.value
            if (local.pending.curation || local.pending.cancel) return
            val draft =
                local.curationDraft?.forRequest(runState.request)
                    ?: runState.request.defaultCurationDraft()
            val selection = draft.selections(runState.request)
            if (
                localState.compareAndSet(
                    local,
                    local.copy(
                        pending = local.pending.begin(MiningPendingAction.CURATION),
                        commandError = null,
                    ),
                )
            ) {
                acceptedSelection = selection
                acceptedDraft = draft
                if (!runState.request.isFinalPage) {
                    submittedPreviousPageCount =
                        Math.addExact(local.previousPageSelectedCount, selection.size)
                }
            }
        }
        val selection = requireNotNull(acceptedSelection)
        viewModelScope.launch {
            try {
                repository.confirmCuration(
                    runId = runState.request.runId,
                    requestId = runState.request.requestId,
                    selection = selection,
                    pageIndex = runState.request.page?.pageIndex,
                )
                if (!runState.request.isFinalPage) {
                    val previousPageSelectedCount =
                        requireNotNull(submittedPreviousPageCount)
                    repository.saveCurationSessionState(
                        requireNotNull(acceptedDraft)
                            .toCurationSessionState(previousPageSelectedCount),
                    )
                    localState.update { local ->
                        local.copy(
                            previousPageSelectedCount = previousPageSelectedCount,
                        )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = ReadingMiningCommandError.CURATION) }
            } finally {
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.CURATION))
                }
            }
        }
    }

    fun cancel() {
        val runState = repository.state.value
        val cancellationToken = runState.cancellationToken
        val runId = runState.runId
        if (cancellationToken == null && runId == null) return
        while (true) {
            val local = localState.value
            if (local.pending.cancel) return
            if (
                localState.compareAndSet(
                    local,
                    local.copy(
                        pending = local.pending.begin(MiningPendingAction.CANCEL),
                        commandError = null,
                    ),
                )
            ) {
                break
            }
        }
        viewModelScope.launch {
            try {
                if (cancellationToken != null) {
                    repository.cancel(cancellationToken)
                } else {
                    repository.cancel(requireNotNull(runId))
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update {
                    it.copy(
                        pending = it.pending.complete(MiningPendingAction.CANCEL),
                        commandError = ReadingMiningCommandError.CANCEL,
                    )
                }
            }
        }
    }

    fun reset() {
        if (!repository.state.value.isTerminal || localState.value.pending.reset) return
        localState.update {
            it.copy(
                pending = it.pending.begin(MiningPendingAction.RESET),
                commandError = null,
            )
        }
        viewModelScope.launch {
            try {
                repository.reset()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = ReadingMiningCommandError.RESET) }
            } finally {
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.RESET))
                }
            }
        }
    }

    fun retry() {
        val failed = repository.state.value as? MiningRunState.Failed ?: return
        if (
            !failed.failure.retryable ||
            localState.value.pending.reset ||
            localState.value.pending.start
        ) {
            return
        }
        val input = localState.value.toInputOrNull() ?: return
        localState.update {
            it.copy(pending = it.pending.beginRetry(), commandError = null)
        }
        viewModelScope.launch {
            try {
                repository.reset()
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.RESET))
                }
                repository.startReading(input)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = ReadingMiningCommandError.START) }
            } finally {
                localState.update {
                    it.copy(
                        pending =
                            it.pending
                                .complete(MiningPendingAction.RESET)
                                .complete(MiningPendingAction.START),
                    )
                }
            }
        }
    }

    private fun launchStart(input: ReadingMiningInput) {
        viewModelScope.launch {
            try {
                repository.startReading(input)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = ReadingMiningCommandError.START) }
            } finally {
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.START))
                }
            }
        }
    }

    private fun restoreSelections() {
        val source = sourceSelection.restore()
        if (source == null) {
            sourceSelection.clear()
            archiveSelection.clear()
            subtitleSeriesSelection.clear()
            pendingArchiveUri = null
            localState.update { it.copy(subtitleSeriesName = "") }
            return
        }
        sourceRestoreInFlight = true
        resolveDocument(
            kind = DocumentKind.SOURCE,
            uri = source.uri,
            restoring = true,
            onResolved = { accepted ->
                sourceRestoreInFlight = false
                if (!accepted) {
                    rejectPendingArchive()
                } else if (localState.value.sourceKind == ReadingSourceKindUi.MOKURO) {
                    if (pendingArchiveUri != null) {
                        pendingArchiveReplacedUri =
                            archiveSelection.restore()?.uri?.takeIf { it != pendingArchiveUri }
                        resolvePendingArchive()
                    } else {
                        archiveSelection.restore()?.let { archive ->
                            resolveDocument(
                                kind = DocumentKind.ARCHIVE,
                                uri = archive.uri,
                                restoring = true,
                            )
                        } ?: archiveSelection.clear()
                    }
                } else {
                    rejectPendingArchive()
                }
            },
        )
    }

    private fun resolveDocument(
        kind: DocumentKind,
        uri: String,
        restoring: Boolean = false,
        onResolved: ((Boolean) -> Unit)? = null,
    ) {
        if (
            uri.isBlank() ||
            (!restoring && repository.state.value != MiningRunState.Idle) ||
            localState.value.pending.start ||
            (kind == DocumentKind.ARCHIVE &&
                localState.value.sourceKind != ReadingSourceKindUi.MOKURO)
        ) {
            return
        }
        val sequence = nextDocumentRequest(kind)
        localState.update { local -> local.withResolving(kind) }
        documentJob(kind)?.cancel()
        val job =
            viewModelScope.launch {
                try {
                    var rejectionError: ReadingDocumentSelectionError? = null
                    val result =
                        SafSelectionOwnershipTransaction(
                            broker = safBroker,
                            store = selectionStore(kind),
                        ).acquirePersistPublish(
                            uri = uri,
                            accept = { document ->
                                documentSelectionError(kind, document)
                                    .also { rejectionError = it } == null
                            },
                            discardPersistedOnRejection = restoring,
                            publish = { document ->
                                check(isCurrentDocumentRequest(kind, sequence)) {
                                    "Document request became stale before publication"
                                }
                                when (kind) {
                                    DocumentKind.SOURCE ->
                                        publishResolvedSource(document, restoring)
                                    DocumentKind.ARCHIVE ->
                                        publishResolvedArchive(document)
                                }
                            },
                        )
                    val accepted =
                        when (result) {
                            is SafSelectionOwnershipResult.Published -> {
                                finishDocumentPublication(kind, result.value)
                                true
                            }
                            is SafSelectionOwnershipResult.Rejected -> {
                                localState.update { local ->
                                    local.withSelectionRejection(kind, rejectionError)
                                }
                                if (restoring && kind == DocumentKind.SOURCE) {
                                    clearRestoredSourceDependents()
                                }
                                false
                            }
                        }
                    onResolved?.invoke(accepted)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (isCurrentDocumentRequest(kind, sequence)) {
                        if (kind == DocumentKind.SOURCE && restoring) {
                            sourceRestoreInFlight = false
                        }
                        localState.update { local -> local.withAccessFailure(kind) }
                        if (restoring && failure.provesPermanentSafAccessLoss()) {
                            clearPermanentlyLostSelection(kind)
                            if (kind == DocumentKind.SOURCE) {
                                rejectPendingArchive()
                            }
                        }
                    }
                }
            }
        setDocumentJob(kind, job)
    }

    private suspend fun clearPermanentlyLostSelection(kind: DocumentKind) {
        try {
            val local = localState.value
            when (kind) {
                DocumentKind.SOURCE ->
                    SafSelectionOwnershipTransaction(safBroker, sourceSelection)
                        .clearPersistPublishRelease(
                            ownedDocument = local.source.document,
                            additionalTargets =
                                listOf(
                                    SafSelectionClearTarget(
                                        store = archiveSelection,
                                        ownedDocument = local.archive.document,
                                    ),
                                ),
                            publish = {
                                subtitleSeriesSelection.clear()
                                localState.update { current ->
                                    current.copy(
                                        source =
                                            current.source.copy(
                                                document = null,
                                                isResolving = false,
                                            ),
                                        archive = ReadingDocumentSlotState(),
                                        sourceKind = null,
                                        subtitleSeriesName = "",
                                    )
                                }
                            },
                        )
                DocumentKind.ARCHIVE ->
                    SafSelectionOwnershipTransaction(safBroker, archiveSelection)
                        .clearPersistPublishRelease(local.archive.document) {
                            localState.update { current ->
                                current.copy(
                                    archive =
                                        current.archive.copy(
                                            document = null,
                                            isResolving = false,
                                        ),
                                )
                            }
                        }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Failed durable clear leaves saved metadata and platform grants retryable.
        }
    }

    private fun documentSelectionError(
        kind: DocumentKind,
        document: SafDocument,
    ): ReadingDocumentSelectionError? =
        when (kind) {
            DocumentKind.SOURCE ->
                if (readingSourceKind(document.displayName) == null) {
                    ReadingDocumentSelectionError.SOURCE_TYPE
                } else {
                    null
                }
            DocumentKind.ARCHIVE -> {
                val local = localState.value
                val source = local.source.document
                when {
                    !isReadingArchive(document.displayName) ->
                        ReadingDocumentSelectionError.ARCHIVE_TYPE
                    local.sourceKind != ReadingSourceKindUi.MOKURO || source == null ->
                        ReadingDocumentSelectionError.ARCHIVE_NAME
                    !readingArchiveMatches(source.displayName, document.displayName) ->
                        ReadingDocumentSelectionError.ARCHIVE_NAME
                    else -> null
                }
            }
        }

    private fun publishResolvedSource(
        document: SafDocument,
        restoring: Boolean,
    ): DocumentPublication {
        val newKind = requireNotNull(readingSourceKind(document.displayName))
        var replacedSource: SafDocument? = null
        var archiveToClear: SafDocument? = null
        var clearArchive = false
        localState.update { local ->
            replacedSource = local.source.document
            val archiveCanRemain =
                newKind == ReadingSourceKindUi.MOKURO &&
                    local.archive.document?.let { archive ->
                        readingArchiveMatches(document.displayName, archive.displayName)
                    } == true
            val preserveUnresolvedArchive =
                restoring &&
                    newKind == ReadingSourceKindUi.MOKURO &&
                    local.archive.document == null
            clearArchive = !archiveCanRemain && !preserveUnresolvedArchive
            if (clearArchive) archiveToClear = local.archive.document
            local.copy(
                source = ReadingDocumentSlotState(document = document),
                sourceKind = newKind,
            )
        }
        return DocumentPublication(
            replacedDocument = replacedSource,
            archiveToClear = archiveToClear,
            clearArchive = clearArchive,
            clearSeries = newKind != ReadingSourceKindUi.SUBTITLE,
        )
    }

    private fun publishResolvedArchive(document: SafDocument): DocumentPublication {
        var replaced: SafDocument? = null
        localState.update { current ->
            replaced = current.archive.document
            current.copy(archive = ReadingDocumentSlotState(document = document))
        }
        return DocumentPublication(
            replacedDocument = replaced,
            archiveToClear = null,
            clearArchive = false,
            clearSeries = false,
        )
    }

    private suspend fun finishDocumentPublication(
        kind: DocumentKind,
        publication: DocumentPublication,
    ) {
        if (kind == DocumentKind.SOURCE) {
            // Any archive picker was launched against the previous primary filename. Even when an
            // installed archive remains compatible, a late result for the previous source is stale.
            archiveDocumentRequest += 1
            archiveDocumentJob?.cancel()
            if (publication.clearArchive) {
                SafSelectionOwnershipTransaction(safBroker, archiveSelection)
                    .clearPersistPublishRelease(publication.archiveToClear) {
                        localState.update {
                            it.copy(archive = ReadingDocumentSlotState())
                        }
                    }
            }
            if (publication.clearSeries) {
                subtitleSeriesSelection.clear()
                localState.update { it.copy(subtitleSeriesName = "") }
            }
        }
        publication.replacedDocument?.let(::releaseDocument)
    }

    private suspend fun clearRestoredSourceDependents() {
        archiveDocumentRequest += 1
        archiveDocumentJob?.cancel()
        val archive = localState.value.archive.document
        SafSelectionOwnershipTransaction(safBroker, archiveSelection)
            .clearPersistPublishRelease(archive) {
                subtitleSeriesSelection.clear()
                localState.update {
                    it.copy(
                        archive = ReadingDocumentSlotState(),
                        subtitleSeriesName = "",
                    )
                }
            }
    }

    private fun resolvePendingArchive() {
        val uri = pendingArchiveUri ?: return
        if (localState.value.sourceKind != ReadingSourceKindUi.MOKURO) return
        resolveDocument(
            kind = DocumentKind.ARCHIVE,
            uri = uri,
            onResolved = { accepted ->
                if (pendingArchiveUri == uri) {
                    pendingArchiveUri = null
                    if (accepted) {
                        pendingArchiveReplacedUri?.let(safBroker::releaseReadAccessEventually)
                    }
                    pendingArchiveReplacedUri = null
                }
            },
        )
    }

    private fun rejectPendingArchive() {
        if (pendingArchiveUri == null) return
        pendingArchiveUri = null
        pendingArchiveReplacedUri = null
        localState.update { local ->
            local.copy(
                archive =
                    local.archive.copy(
                        isResolving = false,
                        error = ReadingDocumentSelectionError.ARCHIVE_NAME,
                    ),
            )
        }
    }

    override fun onCleared() {
        sourceDocumentRequest += 1
        archiveDocumentRequest += 1
        val local = localState.value
        val input = local.toInputOrNull()
        val ownershipTransferred =
            if (input != null) {
                try {
                    repository.detachActiveSources(input)
                } catch (_: RuntimeException) {
                    false
                }
            } else {
                false
            }
        if (!ownershipTransferred) {
            local.source.document?.let { safBroker.releaseReadAccessEventually(it.uri) }
            local.archive.document?.let { safBroker.releaseReadAccessEventually(it.uri) }
        } else if (input?.selection is ReadingSourceSelection.Single) {
            // Defensive cleanup for an impossible stale archive slot: a single-source run only
            // accepts ownership of its primary document.
            local.archive.document?.let { safBroker.releaseReadAccessEventually(it.uri) }
        }
        super.onCleared()
    }

    private fun releaseDocument(document: SafDocument) {
        safBroker.releaseReadAccessEventually(document.uri)
    }

    private fun nextDocumentRequest(kind: DocumentKind): Long =
        when (kind) {
            DocumentKind.SOURCE -> ++sourceDocumentRequest
            DocumentKind.ARCHIVE -> ++archiveDocumentRequest
        }

    private fun isCurrentDocumentRequest(
        kind: DocumentKind,
        sequence: Long,
    ): Boolean =
        when (kind) {
            DocumentKind.SOURCE -> sequence == sourceDocumentRequest
            DocumentKind.ARCHIVE -> sequence == archiveDocumentRequest
        }

    private fun documentJob(kind: DocumentKind): Job? =
        when (kind) {
            DocumentKind.SOURCE -> sourceDocumentJob
            DocumentKind.ARCHIVE -> archiveDocumentJob
        }

    private fun selectionStore(kind: DocumentKind): SavedDocumentSelectionStore =
        when (kind) {
            DocumentKind.SOURCE -> sourceSelection
            DocumentKind.ARCHIVE -> archiveSelection
        }

    private fun setDocumentJob(
        kind: DocumentKind,
        job: Job,
    ) {
        when (kind) {
            DocumentKind.SOURCE -> sourceDocumentJob = job
            DocumentKind.ARCHIVE -> archiveDocumentJob = job
        }
    }

    private fun LocalState.withResolving(kind: DocumentKind): LocalState =
        when (kind) {
            DocumentKind.SOURCE ->
                copy(source = source.copy(isResolving = true, error = null))
            DocumentKind.ARCHIVE ->
                copy(archive = archive.copy(isResolving = true, error = null))
        }

    private fun LocalState.withAccessFailure(kind: DocumentKind): LocalState =
        when (kind) {
            DocumentKind.SOURCE ->
                copy(
                    source =
                        source.copy(
                            isResolving = false,
                            error = ReadingDocumentSelectionError.SOURCE_ACCESS,
                        ),
                )
            DocumentKind.ARCHIVE ->
                copy(
                    archive =
                        archive.copy(
                            isResolving = false,
                            error = ReadingDocumentSelectionError.ARCHIVE_ACCESS,
                        ),
                )
        }

    private fun LocalState.withSelectionRejection(
        kind: DocumentKind,
        error: ReadingDocumentSelectionError?,
    ): LocalState =
        when (kind) {
            DocumentKind.SOURCE ->
                copy(
                    source =
                        source.copy(
                            isResolving = false,
                            error = error ?: ReadingDocumentSelectionError.SOURCE_ACCESS,
                        ),
                )
            DocumentKind.ARCHIVE ->
                copy(
                    archive =
                        archive.copy(
                            isResolving = false,
                            error = error ?: ReadingDocumentSelectionError.ARCHIVE_ACCESS,
                        ),
                )
        }

    private fun LocalState.toInputOrNull(): ReadingMiningInput? {
        val document = source.document ?: return null
        val kind = sourceKind ?: return null
        val selection =
            if (kind == ReadingSourceKindUi.MOKURO) {
                val archiveDocument = archive.document
                if (archiveDocument == null) {
                    ReadingSourceSelection.Single(document)
                } else {
                    if (!readingArchiveMatches(document.displayName, archiveDocument.displayName)) {
                        return null
                    }
                    ReadingSourceSelection.MokuroArchivePair(document, archiveDocument)
                }
            } else {
                ReadingSourceSelection.Single(document)
            }
        return ReadingMiningInput(
            selection = selection,
            subtitleSeriesName =
                subtitleSeriesName.trim().takeIf {
                    kind == ReadingSourceKindUi.SUBTITLE && it.isNotEmpty()
                },
        )
    }

    private fun CurationRequest.toUiState(
        draft: SharedCurationDraft?,
        previousPageSelectedCount: Int,
    ): ReadingCurationUiState {
        val current = draft?.forRequest(this) ?: defaultCurationDraft()
        return ReadingCurationUiState(
            runId = runId,
            requestId = requestId,
            page = page,
            candidates = candidates,
            selectedCandidateIds = current.selectedCandidateIds,
            sentenceIds = current.sentenceIds,
            focusedCandidateId = current.focusedCandidateId,
            previousPageSelectedCount = previousPageSelectedCount,
        )
    }

    private fun isCurationSubmissionPending(): Boolean =
        localState.value.pending.curation ||
            (repository.state.value as? MiningRunState.Curating)?.pageSubmissionPending == true

    private fun saveCurationSession(request: CurationRequest) {
        val local = localState.value
        val draft = local.curationDraft?.takeIf { it.matches(request) } ?: return
        repository.saveCurationSessionState(
            draft.toCurationSessionState(local.previousPageSelectedCount),
        )
    }

    private fun RuntimeWorkCoordinator.Kind.toRuntimeConflict(): RuntimeWorkConflict =
        when (this) {
            RuntimeWorkCoordinator.Kind.MINING -> RuntimeWorkConflict.MINING
            RuntimeWorkCoordinator.Kind.RESOURCE -> RuntimeWorkConflict.RESOURCE
            RuntimeWorkCoordinator.Kind.ANKI_SETUP -> RuntimeWorkConflict.ANKI_SETUP
        }

    private fun String.takeCodePoints(maximum: Int): String {
        require(maximum >= 0)
        val count = codePointCount(0, length)
        return if (count <= maximum) this else substring(0, offsetByCodePoints(0, maximum))
    }

    internal class Factory(
        private val repository: ReadingMiningRepository,
        private val safBroker: SafBroker,
        private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
        private val selectionInventory: SafSelectionInventory? = null,
        private val savedStateHandleFactory: (CreationExtras) -> SavedStateHandle =
            { extras -> extras.createSavedStateHandle() },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            require(modelClass.isAssignableFrom(ReadingMiningViewModel::class.java))
            return ReadingMiningViewModel(
                repository = repository,
                safBroker = safBroker,
                runtimeWorkState = runtimeWorkState,
                savedStateHandle = savedStateHandleFactory(extras),
                selectionInventory = selectionInventory,
            ) as T
        }
    }

    private companion object {
        const val MAX_SERIES_NAME_CODE_POINTS = 120
        const val PENDING_ARCHIVE_URI_KEY = "readingMining.pendingArchiveUri"
    }

    private var pendingArchiveUri: String?
        get() = savedStateHandle[PENDING_ARCHIVE_URI_KEY]
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>(PENDING_ARCHIVE_URI_KEY)
            } else {
                savedStateHandle[PENDING_ARCHIVE_URI_KEY] = value
            }
        }
}
