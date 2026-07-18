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
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.cancellationToken
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.reading.ReadingSourceSelection
import com.ankiminer.android.ui.reading.ReadingCurationCandidateUiState
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

/** Owns the user's persisted SAF selections while the reading screen is alive. */
class ReadingMiningViewModel internal constructor(
    private val repository: ReadingMiningRepository,
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
        val source: ReadingDocumentSlotState = ReadingDocumentSlotState(),
        val archive: ReadingDocumentSlotState = ReadingDocumentSlotState(),
        val sourceKind: ReadingSourceKindUi? = null,
        val subtitleSeriesName: String = "",
        val curationDraft: CurationDraft? = null,
        val startPending: Boolean = false,
        val curationPending: Boolean = false,
        val cancelPending: Boolean = false,
        val resetPending: Boolean = false,
        val commandError: ReadingMiningCommandError? = null,
    )

    private enum class DocumentKind {
        SOURCE,
        ARCHIVE,
    }

    private val localState = MutableStateFlow(LocalState())
    private var sourceDocumentRequest = 0L
    private var archiveDocumentRequest = 0L
    private var sourceDocumentJob: Job? = null
    private var archiveDocumentJob: Job? = null

    val uiState: StateFlow<ReadingMiningUiState> =
        combine(repository.state, localState, runtimeWorkState) { runState, local, activeKind ->
            val curation =
                (runState as? MiningRunState.Curating)?.request?.let { request ->
                    request.toUiState(local.curationDraft)
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
            initialValue = ReadingMiningUiState(runState = repository.state.value),
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

    fun onSourcePicked(uri: String) = resolveDocument(DocumentKind.SOURCE, uri)

    fun onArchivePicked(uri: String) {
        if (localState.value.sourceKind != ReadingSourceKindUi.MOKURO) return
        resolveDocument(DocumentKind.ARCHIVE, uri)
    }

    fun onSubtitleSeriesNameChanged(value: String) {
        if (repository.state.value != MiningRunState.Idle || localState.value.startPending) return
        localState.update {
            it.copy(subtitleSeriesName = value.takeCodePoints(MAX_SERIES_NAME_CODE_POINTS))
        }
    }

    fun clearSource() {
        if (repository.state.value != MiningRunState.Idle || localState.value.startPending) return
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
            )
        }
        local.source.document?.let(::releaseDocument)
        local.archive.document?.let(::releaseDocument)
    }

    fun clearArchive() {
        if (repository.state.value != MiningRunState.Idle || localState.value.startPending) return
        archiveDocumentRequest += 1
        archiveDocumentJob?.cancel()
        val document = localState.value.archive.document
        localState.update { it.copy(archive = ReadingDocumentSlotState()) }
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
                local.startPending ||
                local.resetPending
            ) {
                return
            }
            if (
                localState.compareAndSet(
                    local,
                    local.copy(startPending = true, commandError = null),
                )
            ) {
                launchStart(input)
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
            val selection =
                runState.request.candidates.mapNotNull { candidate ->
                    if (candidate.candidateId !in draft.selectedCandidateIds) {
                        return@mapNotNull null
                    }
                    CurationSelection(
                        candidateId = candidate.candidateId,
                        sentenceId = draft.sentenceIds.getValue(candidate.candidateId),
                    )
                }
            if (
                localState.compareAndSet(
                    local,
                    local.copy(curationPending = true, commandError = null),
                )
            ) {
                acceptedSelection = selection
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
                localState.update { it.copy(commandError = ReadingMiningCommandError.CURATION) }
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
            if (
                localState.compareAndSet(
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
                localState.update { it.copy(commandError = ReadingMiningCommandError.CANCEL) }
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
                localState.update { it.copy(commandError = ReadingMiningCommandError.RESET) }
            } finally {
                localState.update { it.copy(resetPending = false) }
            }
        }
    }

    fun retry() {
        val failed = repository.state.value as? MiningRunState.Failed ?: return
        if (
            !failed.failure.retryable ||
            localState.value.resetPending ||
            localState.value.startPending
        ) {
            return
        }
        val input = localState.value.toInputOrNull() ?: return
        localState.update {
            it.copy(resetPending = true, startPending = true, commandError = null)
        }
        viewModelScope.launch {
            try {
                repository.reset()
                localState.update { it.copy(resetPending = false) }
                repository.startReading(input)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: RuntimeException) {
                localState.update { it.copy(commandError = ReadingMiningCommandError.START) }
            } finally {
                localState.update { it.copy(resetPending = false, startPending = false) }
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
                localState.update { it.copy(startPending = false) }
            }
        }
    }

    private fun resolveDocument(
        kind: DocumentKind,
        uri: String,
    ) {
        if (
            uri.isBlank() ||
            repository.state.value != MiningRunState.Idle ||
            localState.value.startPending ||
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
                    // Persistable permission acquisition is an ownership transfer. It must finish
                    // even if a newer picker result cancels this coroutine, so the stale result
                    // can release the newly acquired grant deterministically.
                    val document =
                        withContext(NonCancellable) {
                            safBroker.retainReadAccess(uri)
                        }
                    if (!isCurrentDocumentRequest(kind, sequence)) {
                        releaseDocumentNow(document)
                        return@launch
                    }
                    when (kind) {
                        DocumentKind.SOURCE -> acceptResolvedSource(document)
                        DocumentKind.ARCHIVE -> acceptResolvedArchive(document)
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    if (isCurrentDocumentRequest(kind, sequence)) {
                        localState.update { local -> local.withAccessFailure(kind) }
                    }
                }
            }
        setDocumentJob(kind, job)
    }

    private suspend fun acceptResolvedSource(document: SafDocument) {
        val newKind = readingSourceKind(document.displayName)
        if (newKind == null) {
            localState.update { local ->
                local.copy(
                    source =
                        local.source.copy(
                            isResolving = false,
                            error = ReadingDocumentSelectionError.SOURCE_TYPE,
                        ),
                )
            }
            releaseDocumentNow(document)
            return
        }

        var replacedSource: SafDocument? = null
        var removedArchive: SafDocument? = null
        localState.update { local ->
            replacedSource = local.source.document
            val archiveCanRemain =
                newKind == ReadingSourceKindUi.MOKURO &&
                    local.archive.document?.let { archive ->
                        readingArchiveMatches(document.displayName, archive.displayName)
                    } == true
            if (!archiveCanRemain) removedArchive = local.archive.document
            local.copy(
                source = ReadingDocumentSlotState(document = document),
                archive = if (archiveCanRemain) local.archive else ReadingDocumentSlotState(),
                sourceKind = newKind,
            )
        }
        // Any archive picker was launched against the previous primary filename. Even when the
        // currently installed archive remains compatible, a late picker result must go stale.
        archiveDocumentRequest += 1
        archiveDocumentJob?.cancel()
        replacedSource?.let(::releaseDocument)
        removedArchive?.let(::releaseDocument)
    }

    private suspend fun acceptResolvedArchive(document: SafDocument) {
        val local = localState.value
        val source = local.source.document
        val error =
            when {
                !isReadingArchive(document.displayName) ->
                    ReadingDocumentSelectionError.ARCHIVE_TYPE
                local.sourceKind != ReadingSourceKindUi.MOKURO || source == null ->
                    ReadingDocumentSelectionError.ARCHIVE_NAME
                !readingArchiveMatches(source.displayName, document.displayName) ->
                    ReadingDocumentSelectionError.ARCHIVE_NAME
                else -> null
            }
        if (error != null) {
            localState.update { current ->
                current.copy(
                    archive =
                        current.archive.copy(
                            isResolving = false,
                            error = error,
                        ),
                )
            }
            releaseDocumentNow(document)
            return
        }
        var replaced: SafDocument? = null
        localState.update { current ->
            replaced = current.archive.document
            current.copy(archive = ReadingDocumentSlotState(document = document))
        }
        replaced?.let(::releaseDocument)
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

    private suspend fun releaseDocumentNow(document: SafDocument) {
        try {
            withContext(NonCancellable) {
                safBroker.releaseReadAccess(document.uri)
            }
        } catch (_: Exception) {
            // Process-start reconciliation owns retrying an uncertain platform release.
        }
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

    private fun CurationRequest.toUiState(draft: CurationDraft?): ReadingCurationUiState {
        val current = draft.forRequest(this)
        return ReadingCurationUiState(
            runId = runId,
            requestId = requestId,
            page = page,
            candidates =
                candidates.map { candidate ->
                    ReadingCurationCandidateUiState(
                        candidate = candidate,
                        selected = candidate.candidateId in current.selectedCandidateIds,
                        sentenceId = current.sentenceIds.getValue(candidate.candidateId),
                    )
                },
        )
    }

    private fun isCurationSubmissionPending(): Boolean =
        localState.value.curationPending ||
            (repository.state.value as? MiningRunState.Curating)?.pageSubmissionPending == true

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            require(modelClass.isAssignableFrom(ReadingMiningViewModel::class.java))
            return ReadingMiningViewModel(repository, safBroker, runtimeWorkState) as T
        }
    }

    private companion object {
        const val MAX_SERIES_NAME_CODE_POINTS = 120
    }
}
