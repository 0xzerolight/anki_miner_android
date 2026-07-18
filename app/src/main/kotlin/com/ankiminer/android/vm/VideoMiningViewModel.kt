package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningSource
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.mining.cancellationToken
import com.ankiminer.android.ui.video.CurationCandidateUiState
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoMiningViewModel internal constructor(
    private val repository: MiningRepository,
    private val safBroker: SafBroker,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
) : ViewModel() {
    private data class CurationDraft(
        val requestId: String,
        val pageIndex: Long?,
        val selectedCandidateIds: Set<String>,
        val sentenceIds: Map<String, String>,
    )

    private data class LocalState(
        val video: DocumentSlotState = DocumentSlotState(),
        val subtitle: DocumentSlotState = DocumentSlotState(),
        val curationDraft: CurationDraft? = null,
        val startPending: Boolean = false,
        val curationPending: Boolean = false,
        val cancelPending: Boolean = false,
        val resetPending: Boolean = false,
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

    val uiState: StateFlow<VideoMiningUiState> =
        combine(repository.state, localState, runtimeWorkState) { runState, local, activeKind ->
            val curation =
                (runState as? MiningRunState.Curating)?.request?.let { request ->
                    request.toUiState(local.curationDraft)
                }
            val repositoryCurationPending =
                (runState as? MiningRunState.Curating)?.pageSubmissionPending == true
            VideoMiningUiState(
                video = local.video,
                subtitle = local.subtitle,
                runState = runState,
                curation = curation,
                startPending = local.startPending,
                curationPending = local.curationPending || repositoryCurationPending,
                cancelPending = local.cancelPending,
                resetPending = local.resetPending,
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
                    localState.update { local ->
                        if (local.curationDraft.matches(runState.request)) {
                            local
                        } else {
                            local.copy(curationDraft = runState.request.defaultDraft())
                        }
                    }
                }
            }
        }
    }

    fun onVideoPicked(uri: String) = resolveDocument(DocumentKind.VIDEO, uri)

    fun onSubtitlePicked(uri: String) = resolveDocument(DocumentKind.SUBTITLE, uri)

    fun clearVideo() {
        if (repository.state.value != MiningRunState.Idle || localState.value.startPending) return
        videoDocumentRequest += 1
        videoDocumentJob?.cancel()
        val document = localState.value.video.document
        localState.update { it.copy(video = DocumentSlotState()) }
        document?.let(::releaseDocument)
    }

    fun clearSubtitle() {
        if (repository.state.value != MiningRunState.Idle || localState.value.startPending) return
        subtitleDocumentRequest += 1
        subtitleDocumentJob?.cancel()
        val document = localState.value.subtitle.document
        localState.update { it.copy(subtitle = DocumentSlotState()) }
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
                local.startPending ||
                local.resetPending
            ) {
                return
            }
            if (localState.compareAndSet(
                    local,
                    local.copy(startPending = true, commandError = null),
                )
            ) {
                launchStart(video, subtitle)
                return
            }
        }
    }

    fun toggleCandidate(candidateId: String) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.cancelPending) return
        require(request.candidates.any { it.candidateId == candidateId })
        localState.update { local ->
            val draft = local.curationDraft.forRequest(request)
            val selected = draft.selectedCandidateIds.toMutableSet()
            if (!selected.add(candidateId)) selected.remove(candidateId)
            local.copy(curationDraft = draft.copy(selectedCandidateIds = selected))
        }
    }

    fun selectAllCandidates(selected: Boolean) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.cancelPending) return
        localState.update { local ->
            val draft = local.curationDraft.forRequest(request)
            local.copy(
                curationDraft =
                    draft.copy(
                        selectedCandidateIds =
                            if (selected) {
                                request.candidates.mapTo(linkedSetOf()) { it.candidateId }
                            } else {
                                emptySet()
                            },
                    ),
            )
        }
    }

    fun selectSentence(
        candidateId: String,
        sentenceId: String,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.cancelPending) return
        val candidate = request.candidates.singleOrNull { it.candidateId == candidateId } ?: return
        if (candidate.sentences.none { it.sentenceId == sentenceId }) return
        localState.update { local ->
            val draft = local.curationDraft.forRequest(request)
            local.copy(
                curationDraft =
                    draft.copy(sentenceIds = draft.sentenceIds + (candidateId to sentenceId)),
            )
        }
    }

    fun confirmCuration() {
        val runState = repository.state.value as? MiningRunState.Curating ?: return
        if (runState.pageSubmissionPending) return
        var acceptedSelection: List<CurationSelection>? = null
        while (acceptedSelection == null) {
            val local = localState.value
            if (local.curationPending || local.cancelPending) return
            val draft = local.curationDraft.forRequest(runState.request)
            val currentSelection =
                runState.request.candidates.mapNotNull { candidate ->
                    if (candidate.candidateId !in draft.selectedCandidateIds) {
                        return@mapNotNull null
                    }
                    CurationSelection(
                        candidateId = candidate.candidateId,
                        sentenceId = draft.sentenceIds.getValue(candidate.candidateId),
                    )
                }
            if (localState.compareAndSet(
                    local,
                    local.copy(curationPending = true, commandError = null),
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
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.CURATION) }
            } finally {
                localState.update { it.copy(curationPending = false) }
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
            if (local.cancelPending) return
            if (localState.compareAndSet(
                    local,
                    local.copy(cancelPending = true, commandError = null),
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
                localState.update { it.copy(commandError = MiningCommandError.CANCEL) }
            } finally {
                localState.update { it.copy(cancelPending = false) }
            }
        }
    }

    fun reset() {
        if (!repository.state.value.isTerminal || localState.value.resetPending) return
        localState.update { it.copy(resetPending = true, commandError = null) }
        viewModelScope.launch {
            try {
                repository.reset()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.RESET) }
            } finally {
                localState.update { it.copy(resetPending = false) }
            }
        }
    }

    fun retry() {
        val failed = repository.state.value as? MiningRunState.Failed ?: return
        if (!failed.failure.retryable || localState.value.resetPending || localState.value.startPending) {
            return
        }
        val video = localState.value.video.document ?: return
        val subtitle = localState.value.subtitle.document ?: return
        localState.update {
            it.copy(resetPending = true, startPending = true, commandError = null)
        }
        viewModelScope.launch {
            try {
                repository.reset()
                localState.update { it.copy(resetPending = false) }
                repository.startVideo(video.toInput(subtitle))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = MiningCommandError.START) }
            } finally {
                localState.update { it.copy(resetPending = false, startPending = false) }
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
                localState.update { it.copy(startPending = false) }
            }
        }
    }

    private fun resolveDocument(
        kind: DocumentKind,
        uri: String,
    ) {
        if (uri.isBlank() ||
            repository.state.value != MiningRunState.Idle ||
            localState.value.startPending
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

    private fun CurationRequest.defaultDraft(): CurationDraft =
        CurationDraft(
            requestId = requestId,
            pageIndex = page?.pageIndex,
            selectedCandidateIds = candidates.mapTo(linkedSetOf()) { it.candidateId },
            sentenceIds = candidates.associate { it.candidateId to it.defaultSentenceId },
        )

    private fun CurationDraft?.forRequest(request: CurationRequest): CurationDraft =
        if (matches(request)) requireNotNull(this) else request.defaultDraft()

    private fun CurationDraft?.matches(request: CurationRequest): Boolean =
        this?.let { draft ->
            draft.requestId == request.requestId &&
                draft.pageIndex == request.page?.pageIndex
        } == true

    private fun isCurationSubmissionPending(): Boolean =
        localState.value.curationPending ||
            (repository.state.value as? MiningRunState.Curating)?.pageSubmissionPending == true

    private fun RuntimeWorkCoordinator.Kind.toRuntimeConflict(): RuntimeWorkConflict =
        when (this) {
            RuntimeWorkCoordinator.Kind.MINING -> RuntimeWorkConflict.MINING
            RuntimeWorkCoordinator.Kind.RESOURCE -> RuntimeWorkConflict.RESOURCE
            RuntimeWorkCoordinator.Kind.ANKI_SETUP -> RuntimeWorkConflict.ANKI_SETUP
        }

    private fun CurationRequest.toUiState(draft: CurationDraft?): CurationUiState {
        val current = draft.forRequest(this)
        return CurationUiState(
            runId = runId,
            requestId = requestId,
            page = page,
            candidates =
                candidates.map { candidate ->
                    CurationCandidateUiState(
                        candidate = candidate,
                        selected = candidate.candidateId in current.selectedCandidateIds,
                        sentenceId = current.sentenceIds.getValue(candidate.candidateId),
                    )
                },
        )
    }

    internal class Factory(
        private val repository: MiningRepository,
        private val safBroker: SafBroker,
        private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            require(modelClass.isAssignableFrom(VideoMiningViewModel::class.java))
            return VideoMiningViewModel(repository, safBroker, runtimeWorkState) as T
        }
    }
}
