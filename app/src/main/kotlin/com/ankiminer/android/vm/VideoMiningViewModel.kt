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
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningSource
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.mining.cancellationToken
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.ui.mining.MiningPendingAction
import com.ankiminer.android.ui.mining.MiningPendingState
import com.ankiminer.android.ui.mining.SharedCurationDraft
import com.ankiminer.android.ui.mining.defaultCurationDraft
import com.ankiminer.android.ui.mining.draftFor
import com.ankiminer.android.ui.mining.toCurationSessionState
import com.ankiminer.android.ui.video.CurationUiState
import com.ankiminer.android.ui.video.DocumentSelectionError
import com.ankiminer.android.ui.video.DocumentSlotState
import com.ankiminer.android.ui.video.MiningCommandError
import com.ankiminer.android.ui.video.VideoMiningUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoMiningViewModel internal constructor(
    private val repository: MiningRepository,
    private val safBroker: SafBroker,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    selectionInventory: SafSelectionInventory? = null,
) : ViewModel() {
    private data class LocalState(
        val video: DocumentSlotState = DocumentSlotState(),
        val subtitle: DocumentSlotState = DocumentSlotState(),
        val curationDraft: SharedCurationDraft? = null,
        val previousPageSelectedCount: Int = 0,
        val pending: MiningPendingState = MiningPendingState(),
        val commandError: MiningCommandError? = null,
    )

    private enum class DocumentKind {
        VIDEO,
        SUBTITLE,
    }

    private val localState = MutableStateFlow(LocalState())
    private var videoDocumentRequest = 0L
    private var subtitleDocumentRequest = 0L
    private var videoDocumentJob: Job? = null
    private var subtitleDocumentJob: Job? = null
    private val videoSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "videoMining.video",
            inventory = selectionInventory,
            inventorySlot = SafSelectionSlot.VIDEO,
        )
    private val subtitleSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "videoMining.subtitle",
            inventory = selectionInventory,
            inventorySlot = SafSelectionSlot.VIDEO_SUBTITLE,
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

    val uiState: StateFlow<VideoMiningUiState> =
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
            VideoMiningUiState(
                video = local.video,
                subtitle = local.subtitle,
                runState = runState,
                curation = curation,
                startPending = local.pending.start,
                curationPending = local.pending.curation || repositoryCurationPending,
                cancelPending = local.pending.cancel,
                resetPending = local.pending.reset,
                commandError = local.commandError,
                runtimeConflict =
                    activeKind?.toRuntimeConflict()?.takeIf { runState == MiningRunState.Idle },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = VideoMiningUiState(runState = repository.state.value),
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
        videoSelection.restore()?.let { selection ->
            resolveDocument(DocumentKind.VIDEO, selection.uri, restoring = true)
        } ?: videoSelection.clear()
        subtitleSelection.restore()?.let { selection ->
            resolveDocument(DocumentKind.SUBTITLE, selection.uri, restoring = true)
        } ?: subtitleSelection.clear()
    }

    fun onVideoPicked(uri: String) = resolveDocument(DocumentKind.VIDEO, uri)

    fun onSubtitlePicked(uri: String) = resolveDocument(DocumentKind.SUBTITLE, uri)

    fun clearVideo() {
        if (repository.state.value != MiningRunState.Idle || localState.value.pending.start) return
        videoDocumentRequest += 1
        videoDocumentJob?.cancel()
        val document = localState.value.video.document
        localState.update { it.copy(video = DocumentSlotState()) }
        videoSelection.clear()
        document?.let(::releaseDocument)
    }

    fun clearSubtitle() {
        if (repository.state.value != MiningRunState.Idle || localState.value.pending.start) return
        subtitleDocumentRequest += 1
        subtitleDocumentJob?.cancel()
        val document = localState.value.subtitle.document
        localState.update { it.copy(subtitle = DocumentSlotState()) }
        subtitleSelection.clear()
        document?.let(::releaseDocument)
    }

    fun dismissDocumentError(kind: DocumentSelectionError) {
        localState.update { local ->
            when (kind) {
                DocumentSelectionError.VIDEO ->
                    local.copy(video = local.video.copy(error = null))
                DocumentSelectionError.SUBTITLE ->
                    local.copy(subtitle = local.subtitle.copy(error = null))
            }
        }
    }

    fun dismissCommandError() {
        localState.update { it.copy(commandError = null) }
    }

    fun start() {
        while (true) {
            val local = localState.value
            val video = local.video.document ?: return
            val subtitle = local.subtitle.document ?: return
            if (repository.state.value != MiningRunState.Idle ||
                runtimeWorkState.value != null ||
                local.video.isResolving ||
                local.subtitle.isResolving ||
                local.pending.start ||
                local.pending.reset
            ) {
                return
            }
            if (localState.compareAndSet(
                    local,
                    local.copy(
                        pending = local.pending.begin(MiningPendingAction.START),
                        commandError = null,
                        previousPageSelectedCount = 0,
                    ),
                )
            ) {
                launchStart(video, subtitle)
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
        while (acceptedSelection == null) {
            val local = localState.value
            if (local.pending.curation || local.pending.cancel) return
            val draft =
                local.curationDraft?.forRequest(runState.request)
                    ?: runState.request.defaultCurationDraft()
            val currentSelection = draft.selections(runState.request)
            if (localState.compareAndSet(
                    local,
                    local.copy(
                        pending = local.pending.begin(MiningPendingAction.CURATION),
                        commandError = null,
                    ),
                )
            ) {
                acceptedSelection = currentSelection
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
                    localState.update { local ->
                        local.copy(
                            previousPageSelectedCount =
                                Math.addExact(
                                    local.previousPageSelectedCount,
                                    selection.size,
                                ),
                        )
                    }
                    saveCurationSession(runState.request)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.CURATION) }
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
            if (localState.compareAndSet(
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
                        commandError = MiningCommandError.CANCEL,
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
                localState.update { it.copy(commandError = MiningCommandError.RESET) }
            } finally {
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.RESET))
                }
            }
        }
    }

    fun retry() {
        val failed = repository.state.value as? MiningRunState.Failed ?: return
        if (!failed.failure.retryable ||
            localState.value.pending.reset ||
            localState.value.pending.start
        ) {
            return
        }
        val video = localState.value.video.document ?: return
        val subtitle = localState.value.subtitle.document ?: return
        localState.update {
            it.copy(pending = it.pending.beginRetry(), commandError = null)
        }
        viewModelScope.launch {
            try {
                repository.reset()
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.RESET))
                }
                repository.startVideo(video.toInput(subtitle))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.START) }
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

    private fun launchStart(
        video: SafDocument,
        subtitle: SafDocument,
    ) {
        viewModelScope.launch {
            try {
                repository.startVideo(video.toInput(subtitle))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.START) }
            } finally {
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.START))
                }
            }
        }
    }

    private fun resolveDocument(
        kind: DocumentKind,
        uri: String,
        restoring: Boolean = false,
    ) {
        if (uri.isBlank() ||
            (!restoring && repository.state.value != MiningRunState.Idle) ||
            localState.value.pending.start
        ) {
            return
        }
        val sequence =
            when (kind) {
                DocumentKind.VIDEO -> ++videoDocumentRequest
                DocumentKind.SUBTITLE -> ++subtitleDocumentRequest
            }
        localState.update { local ->
            when (kind) {
                DocumentKind.VIDEO ->
                    local.copy(video = local.video.copy(isResolving = true, error = null))
                DocumentKind.SUBTITLE ->
                    local.copy(subtitle = local.subtitle.copy(isResolving = true, error = null))
            }
        }
        when (kind) {
            DocumentKind.VIDEO -> videoDocumentJob?.cancel()
            DocumentKind.SUBTITLE -> subtitleDocumentJob?.cancel()
        }
        val job =
            viewModelScope.launch {
                try {
                    // Android's withContext(IO) has a prompt-cancellation handoff: without this
                    // ownership-transfer section, cancellation could discard a SafDocument after
                    // takePersistableUriPermission succeeded, leaving no URI token to release.
                    val document =
                        withContext(NonCancellable) {
                            safBroker.retainReadAccess(uri)
                        }
                    if (isCurrentDocumentRequest(kind, sequence)) {
                        if (!selectionStore(kind).save(document)) {
                            releaseDocumentNow(document)
                            localState.update { local -> local.withDocumentFailure(kind) }
                            return@launch
                        }
                        var replaced: SafDocument? = null
                        localState.update { local ->
                            replaced = local.document(kind)
                            local.withDocument(kind, document)
                        }
                        replaced?.let(::releaseDocument)
                    } else {
                        // A non-cooperative provider can finish after cancellation. Its newly
                        // retained grant has no selection owner and must be released.
                        releaseDocumentNow(document)
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    if (isCurrentDocumentRequest(kind, sequence)) {
                        localState.update { local -> local.withDocumentFailure(kind) }
                        if (restoring) selectionStore(kind).clear()
                    }
                }
            }
        when (kind) {
            DocumentKind.VIDEO -> {
                videoDocumentJob = job
            }
            DocumentKind.SUBTITLE -> {
                subtitleDocumentJob = job
            }
        }
    }

    override fun onCleared() {
        // Any acquisition completing after ViewModel teardown must take the stale path and release
        // its grant rather than publishing ownership into an unreachable LocalState.
        videoDocumentRequest += 1
        subtitleDocumentRequest += 1
        val local = localState.value
        val video = local.video.document
        val subtitle = local.subtitle.document
        val activeOwnershipTransferred =
            if (video != null && subtitle != null) {
                try {
                    repository.detachActiveSources(video.toInput(subtitle))
                } catch (_: RuntimeException) {
                    false
                }
            } else {
                false
            }
        if (!activeOwnershipTransferred) {
            video?.let { safBroker.releaseReadAccessEventually(it.uri) }
            subtitle?.let { safBroker.releaseReadAccessEventually(it.uri) }
        }
        super.onCleared()
    }

    private fun releaseDocument(document: SafDocument) {
        // Once LocalState drops this document, the ViewModel no longer has enough information to
        // retry cleanup from onCleared. Transfer release to the process-scoped broker immediately;
        // a queued viewModelScope coroutine could be cancelled before its body ever starts.
        safBroker.releaseReadAccessEventually(document.uri)
    }

    private suspend fun releaseDocumentNow(document: SafDocument) {
        try {
            // A cancelled, non-cooperative retain may resume with a newly acquired platform grant.
            // Cleanup must therefore outlive cancellation of the superseded picker coroutine.
            withContext(NonCancellable) {
                safBroker.releaseReadAccess(document.uri)
            }
        } catch (_: Exception) {
            // Startup reconciliation in the application-scoped coordinator owns retrying an
            // uncertain platform release; clearing the UI selection must remain usable.
        }
    }

    private fun isCurrentDocumentRequest(
        kind: DocumentKind,
        sequence: Long,
    ): Boolean =
        when (kind) {
            DocumentKind.VIDEO -> sequence == videoDocumentRequest
            DocumentKind.SUBTITLE -> sequence == subtitleDocumentRequest
        }

    private fun selectionStore(kind: DocumentKind): SavedDocumentSelectionStore =
        when (kind) {
            DocumentKind.VIDEO -> videoSelection
            DocumentKind.SUBTITLE -> subtitleSelection
        }

    private fun LocalState.withDocument(
        kind: DocumentKind,
        document: SafDocument,
    ): LocalState =
        when (kind) {
            DocumentKind.VIDEO -> copy(video = DocumentSlotState(document = document))
            DocumentKind.SUBTITLE -> copy(subtitle = DocumentSlotState(document = document))
        }

    private fun LocalState.document(kind: DocumentKind): SafDocument? =
        when (kind) {
            DocumentKind.VIDEO -> video.document
            DocumentKind.SUBTITLE -> subtitle.document
        }

    private fun LocalState.withDocumentFailure(kind: DocumentKind): LocalState =
        when (kind) {
            DocumentKind.VIDEO ->
                copy(
                    video =
                        video.copy(
                            isResolving = false,
                            error = DocumentSelectionError.VIDEO,
                        ),
                )
            DocumentKind.SUBTITLE ->
                copy(
                    subtitle =
                        subtitle.copy(
                            isResolving = false,
                            error = DocumentSelectionError.SUBTITLE,
                        ),
                )
        }

    private fun SafDocument.toInput(subtitle: SafDocument): VideoMiningInput =
        VideoMiningInput(
            video = MiningSource(uri = uri, displayName = displayName),
            subtitle = MiningSource(uri = subtitle.uri, displayName = subtitle.displayName),
        )

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

    private fun CurationRequest.toUiState(
        draft: SharedCurationDraft?,
        previousPageSelectedCount: Int,
    ): CurationUiState {
        val current = draft?.forRequest(this) ?: defaultCurationDraft()
        return CurationUiState(
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

    internal class Factory(
        private val repository: MiningRepository,
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
            require(modelClass.isAssignableFrom(VideoMiningViewModel::class.java))
            return VideoMiningViewModel(
                repository = repository,
                safBroker = safBroker,
                runtimeWorkState = runtimeWorkState,
                savedStateHandle = savedStateHandleFactory(extras),
                selectionInventory = selectionInventory,
            ) as T
        }
    }
}
