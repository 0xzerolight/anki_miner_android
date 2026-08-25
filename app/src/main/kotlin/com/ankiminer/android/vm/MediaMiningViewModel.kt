package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogContext
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.hasSupportedSubtitleExtension
import com.ankiminer.android.mining.CurationMediaBinding
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.ENGINE_DEFAULT_SUBTITLE_OFFSET
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningSource
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.TokenizerConfigurationFailure
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.mining.cancellationPending
import com.ankiminer.android.mining.cancellationToken
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.subtitles.SubtitleCueLookupService
import com.ankiminer.android.timing.TimingPreviewBusyException
import com.ankiminer.android.timing.TimingPreviewOpener
import com.ankiminer.android.timing.TimingPreviewSession
import com.ankiminer.android.tracks.AudioTrackList
import com.ankiminer.android.tracks.AudioTrackProbeBusyException
import com.ankiminer.android.tracks.AudioTrackProbeOpener
import com.ankiminer.android.ui.mining.CurationDefinitionState
import com.ankiminer.android.ui.mining.DefinitionQuery
import com.ankiminer.android.ui.mining.MiningPendingAction
import com.ankiminer.android.ui.mining.MiningPendingState
import com.ankiminer.android.ui.mining.SharedCurationDraft
import com.ankiminer.android.ui.mining.TimingPreviewState
import com.ankiminer.android.ui.mining.completed
import com.ankiminer.android.ui.mining.defaultCurationDraft
import com.ankiminer.android.ui.mining.draftFor
import com.ankiminer.android.ui.mining.forRequest
import com.ankiminer.android.ui.mining.request
import com.ankiminer.android.ui.mining.toCurationSessionState
import com.ankiminer.android.ui.video.AudioTrackPickerError
import com.ankiminer.android.ui.video.AudioTrackPickerState
import com.ankiminer.android.ui.video.CurationPlayerUiState
import com.ankiminer.android.ui.video.CurationUiState
import com.ankiminer.android.ui.video.DocumentSelectionError
import com.ankiminer.android.ui.video.DocumentSlotState
import com.ankiminer.android.ui.video.MiningCommandError
import com.ankiminer.android.ui.video.TimingPreviewError
import com.ankiminer.android.ui.video.VideoMiningUiState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFINITION_DEBOUNCE_MS = 150L
private const val MAX_SUBTITLE_OFFSET_DRAFT_CODE_POINTS = 64
internal val AUDIO_EXTENSIONS = setOf("m4b", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

class MediaMiningViewModel internal constructor(
    private val repository: MiningRepository,
    private val safBroker: SafBroker,
    internal val lane: MiningLane,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    selectionInventory: SafSelectionInventory? = null,
    selectionIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val definitionLookup: DefinitionLookupService? = null,
    private val cueLookup: SubtitleCueLookupService = NO_CUE_LOOKUP,
    effectiveSubtitleOffset: Flow<Double?> = flowOf(null),
    fieldMap: Flow<Map<String, String>> = flowOf(emptyMap()),
    audioPacks: Flow<List<InstalledAudioPack>> = flowOf(emptyList()),
    private val timingPreviewOpener: TimingPreviewOpener? = null,
    timingPreviewCleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val audioTrackProbeOpener: AudioTrackProbeOpener? = null,
) : ViewModel() {
    private val subtitleOffsetDraftKey = "${lane.savedStateKeyPrefix}.subtitleOffsetDraft"

    private data class LocalState(
        val video: DocumentSlotState = DocumentSlotState(),
        val subtitle: DocumentSlotState = DocumentSlotState(),
        val subtitleOffsetDraft: String = "",
        val globalSubtitleOffset: Double? = null,
        val fieldMap: Map<String, String> = emptyMap(),
        val audioPacks: List<InstalledAudioPack> = emptyList(),
        val curationDraft: SharedCurationDraft? = null,
        val previousPageSelectedCount: Int = 0,
        val pending: MiningPendingState = MiningPendingState(),
        val commandError: MiningCommandError? = null,
        val timingPreviewPending: Boolean = false,
        val timingPreviewError: TimingPreviewError? = null,
        val audioTrackOverride: Long? = null,
        val audioTrackProbePending: Boolean = false,
        val audioTrackPickerError: AudioTrackPickerError? = null,
    )

    private enum class DocumentKind {
        VIDEO,
        SUBTITLE,
    }

    private data class DefinitionFocusKey(
        val runId: String,
        val requestId: String,
        val pageIndex: Long?,
        val focusedCandidateId: String?,
    )

    private data class CueLookupKey(
        val runId: String,
        val videoPath: String,
        val subtitlePath: String,
    )

    private data class CueState(
        val key: CueLookupKey,
        val cues: List<SubtitleCue> = emptyList(),
        val unavailable: Boolean = false,
    )

    private val restoredSubtitleOffsetDraft =
        savedStateHandle
            .get<String>(subtitleOffsetDraftKey)
            .orEmpty()
            .takeCodePoints(MAX_SUBTITLE_OFFSET_DRAFT_CODE_POINTS)
            .also { draft ->
                if (draft.isEmpty()) {
                    savedStateHandle.remove<String>(subtitleOffsetDraftKey)
                } else {
                    savedStateHandle[subtitleOffsetDraftKey] = draft
                }
            }
    private val localState =
        MutableStateFlow(
            LocalState(
                subtitleOffsetDraft = restoredSubtitleOffsetDraft,
            ),
        )
    private val definitionState = MutableStateFlow(CurationDefinitionState())
    private val cueState = MutableStateFlow<CueState?>(null)
    private val mutableTimingPreviewState = MutableStateFlow<TimingPreviewState?>(null)
    val timingPreviewState: StateFlow<TimingPreviewState?> = mutableTimingPreviewState
    private val mutableAudioTrackPickerState = MutableStateFlow<AudioTrackPickerState?>(null)
    val audioTrackPickerState: StateFlow<AudioTrackPickerState?> = mutableAudioTrackPickerState
    private val timingPreviewCleanupScope =
        CoroutineScope(SupervisorJob() + timingPreviewCleanupDispatcher)
    private var timingPreviewSession: TimingPreviewSession? = null
    private var timingPreviewOpenJob: Job? = null
    private var definitionJob: Job? = null
    private var videoDocumentRequest = 0L
    private var subtitleDocumentRequest = 0L
    private var videoDocumentJob: Job? = null
    private var subtitleDocumentJob: Job? = null
    private val videoSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "${lane.savedStateKeyPrefix}.document",
            inventory = selectionInventory,
            inventorySlot = lane.documentSlot,
            ioDispatcher = selectionIoDispatcher,
        )
    private val subtitleSelection =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "${lane.savedStateKeyPrefix}.subtitle",
            inventory = selectionInventory,
            inventorySlot = lane.subtitleSlot,
            ioDispatcher = selectionIoDispatcher,
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
        combine(
            repository.state,
            localState,
            runtimeWorkState,
            definitionState,
            cueState,
        ) { runState, local, activeKind, definition, cues ->
            val curating = runState as? MiningRunState.Curating
            val curation =
                curating?.request?.let { request ->
                    request.toUiState(
                        draft = local.curationDraft,
                        previousPageSelectedCount = local.previousPageSelectedCount,
                        definition = definition.visible,
                        player = curating.toPlayerUiState(cues),
                    )
                }
            val repositoryCurationPending =
                (runState as? MiningRunState.Curating)?.pageSubmissionPending == true
            VideoMiningUiState(
                video = local.video,
                subtitle = local.subtitle,
                subtitleOffsetDraft = local.subtitleOffsetDraft,
                subtitleOffsetDraftInvalid = local.subtitleOffsetDraftInvalid,
                effectiveSubtitleOffset =
                    local.subtitleOffsetOverride
                        ?: local.globalSubtitleOffset
                        ?: ENGINE_DEFAULT_SUBTITLE_OFFSET,
                audioFieldUnmapped =
                    lane == MiningLane.AUDIO &&
                        local.fieldMap["audio"].isNullOrBlank() &&
                        !local.fieldMap["picture"].isNullOrBlank(),
                // Not lane-gated: expression audio applies to every mining lane.
                // Fires only when a usable pack proves the user wants word audio;
                // with no pack there is no source to warn about.
                expressionAudioFieldUnmapped =
                    local.fieldMap["expression_audio"].isNullOrBlank() &&
                        local.audioPacks.any { it.contentAvailable && it.entryCount > 0 },
                unusableAudioPackInstalled =
                    local.audioPacks.any { !(it.contentAvailable && it.entryCount > 0) },
                runState = runState,
                curation = curation,
                startPending = local.pending.start,
                curationPending = local.pending.curation || repositoryCurationPending,
                cancelPending = local.pending.cancel || runState.cancellationPending,
                resetPending = local.pending.reset,
                commandError = local.commandError,
                timingPreviewPending = local.timingPreviewPending,
                timingPreviewError = local.timingPreviewError,
                audioTrackOverride = local.audioTrackOverride,
                audioTrackProbePending = local.audioTrackProbePending,
                audioTrackPickerError = local.audioTrackPickerError,
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
            effectiveSubtitleOffset.distinctUntilChanged().collect { offset ->
                localState.update { local ->
                    local.copy(globalSubtitleOffset = offset?.takeIf { it.isFinite() })
                }
            }
        }
        viewModelScope.launch {
            fieldMap.distinctUntilChanged().collect { currentFieldMap ->
                localState.update { local -> local.copy(fieldMap = currentFieldMap) }
            }
        }
        viewModelScope.launch {
            audioPacks.distinctUntilChanged().collect { currentPacks ->
                localState.update { local -> local.copy(audioPacks = currentPacks) }
            }
        }
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
                    definitionJob?.cancel()
                    definitionJob = null
                    requestDefinition(null, null)
                    repository.clearCurationSessionState(runState.runId)
                    localState.update { local ->
                        local.copy(
                            curationDraft = null,
                            previousPageSelectedCount = 0,
                            pending = local.pending.afterTerminalState(),
                            audioTrackOverride =
                                if (runState is MiningRunState.Success) null else local.audioTrackOverride,
                        )
                    }
                }
            }
        }
        if (definitionLookup != null) {
            viewModelScope.launch {
                uiState
                    .map { state ->
                        state.curation?.let { curation ->
                            DefinitionFocusKey(
                                runId = curation.runId,
                                requestId = curation.requestId,
                                pageIndex = curation.page?.pageIndex,
                                focusedCandidateId = curation.focusedCandidateId,
                            )
                        }
                    }.distinctUntilChanged()
                    .collect { focus ->
                        val request =
                            (repository.state.value as? MiningRunState.Curating)
                                ?.request
                                ?.takeIf {
                                    focus != null &&
                                        it.runId == focus.runId &&
                                        it.requestId == focus.requestId &&
                                        it.page?.pageIndex == focus.pageIndex
                                }
                        requestDefinition(request, focus?.focusedCandidateId)
                    }
            }
        }
        viewModelScope.launch {
            repository.state
                .map { runState -> (runState as? MiningRunState.Curating)?.cueLookupKey() }
                .distinctUntilChanged()
                .collectLatest { key -> loadCues(key) }
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
        if (
            repository.state.value != MiningRunState.Idle ||
            localState.value.pending.start ||
            localState.value.timingPreviewPending ||
            mutableTimingPreviewState.value != null ||
            localState.value.audioTrackProbePending ||
            mutableAudioTrackPickerState.value != null
        ) {
            return
        }
        AppLog.i(
            LogComponent.UI,
            "command",
            "command" to "source_clear",
            "source" to "video",
            "outcome" to "ok",
        )
        videoDocumentRequest += 1
        videoDocumentJob?.cancel()
        val document = localState.value.video.document
        localState.update { it.copy(video = DocumentSlotState(), audioTrackOverride = null) }
        videoSelection.clear()
        document?.let(::releaseDocument)
    }

    fun clearSubtitle() {
        if (
            repository.state.value != MiningRunState.Idle ||
            localState.value.pending.start ||
            localState.value.timingPreviewPending ||
            mutableTimingPreviewState.value != null ||
            localState.value.audioTrackProbePending ||
            mutableAudioTrackPickerState.value != null
        ) {
            return
        }
        AppLog.i(
            LogComponent.UI,
            "command",
            "command" to "source_clear",
            "source" to "subtitle",
            "outcome" to "ok",
        )
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
                DocumentSelectionError.AUDIO_TYPE ->
                    local.copy(video = local.video.copy(error = null))
                DocumentSelectionError.SUBTITLE ->
                    local.copy(subtitle = local.subtitle.copy(error = null))
            }
        }
    }

    fun dismissCommandError() {
        localState.update { it.copy(commandError = null) }
    }

    fun dismissTimingPreviewError() {
        localState.update { it.copy(timingPreviewError = null) }
    }

    fun setSubtitleOffsetDraft(value: String) {
        val local = localState.value
        if (
            repository.state.value != MiningRunState.Idle ||
            local.pending.start ||
            local.pending.reset ||
            local.timingPreviewPending ||
            mutableTimingPreviewState.value != null
        ) {
            return
        }
        updateSubtitleOffsetDraft(value)
    }

    fun openTimingPreview() {
        val opener = timingPreviewOpener ?: return
        while (true) {
            val local = localState.value
            val video = local.video.document ?: return
            val subtitle = local.subtitle.document ?: return
            if (
                local.video.isResolving ||
                local.subtitle.isResolving ||
                repository.state.value != MiningRunState.Idle ||
                local.subtitleOffsetDraftInvalid ||
                local.pending.start ||
                local.pending.reset ||
                local.timingPreviewPending ||
                mutableTimingPreviewState.value != null ||
                local.audioTrackProbePending ||
                mutableAudioTrackPickerState.value != null
            ) {
                return
            }
            if (
                localState.compareAndSet(
                    local,
                    local.copy(
                        timingPreviewPending = true,
                        timingPreviewError = null,
                    ),
                )
            ) {
                val videoRequest = videoDocumentRequest
                val subtitleRequest = subtitleDocumentRequest
                val initialOffset =
                    local.subtitleOffsetOverride
                        ?: local.globalSubtitleOffset
                        ?: ENGINE_DEFAULT_SUBTITLE_OFFSET
                timingPreviewOpenJob =
                    viewModelScope.launch {
                        try {
                            val result = opener.open(subtitle)
                            result.fold(
                                onSuccess = { session ->
                                    val current = localState.value
                                    if (
                                        repository.state.value != MiningRunState.Idle ||
                                        current.video.isResolving ||
                                        current.subtitle.isResolving ||
                                        current.video.document?.uri != video.uri ||
                                        current.subtitle.document?.uri != subtitle.uri ||
                                        !isCurrentDocumentRequest(
                                            DocumentKind.VIDEO,
                                            videoRequest,
                                        ) ||
                                        !isCurrentDocumentRequest(
                                            DocumentKind.SUBTITLE,
                                            subtitleRequest,
                                        ) ||
                                        mutableTimingPreviewState.value != null
                                    ) {
                                        queueTimingPreviewClose(session)
                                    } else {
                                        timingPreviewSession = session
                                        mutableTimingPreviewState.value =
                                            TimingPreviewState(
                                                initialOffset = initialOffset,
                                                workingOffset = initialOffset,
                                                previewingUnshifted = false,
                                                cues = session.cues,
                                                selectedCueIndex = null,
                                            )
                                    }
                                },
                                onFailure = { failure ->
                                    publishTimingPreviewFailure(failure)
                                },
                            )
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            publishTimingPreviewFailure(failure)
                        } finally {
                            localState.update { it.copy(timingPreviewPending = false) }
                            timingPreviewOpenJob = null
                        }
                    }
                return
            }
        }
    }

    fun selectTimingPreviewCue(index: Int) {
        mutableTimingPreviewState.update { state -> state?.selectCue(index) }
    }

    fun nudgeTimingPreview(delta: Double) {
        mutableTimingPreviewState.update { state -> state?.nudge(delta) }
    }

    fun setTimingPreviewWorkingOffset(value: Double) {
        if (!value.isFinite()) return
        mutableTimingPreviewState.update { state -> state?.setWorking(value) }
    }

    fun toggleTimingPreviewUnshifted() {
        mutableTimingPreviewState.update { state -> state?.toggleUnshifted() }
    }

    fun applyTimingPreview() {
        val state = mutableTimingPreviewState.value ?: return
        updateSubtitleOffsetDraft(state.workingOffset.toString())
        closeTimingPreview()
    }

    fun closeTimingPreview() {
        mutableTimingPreviewState.value = null
        val session = timingPreviewSession
        timingPreviewSession = null
        if (session != null) {
            localState.update { it.copy(timingPreviewPending = true) }
            queueTimingPreviewClose(session, clearPendingOnFinish = true)
        }
    }

    fun openAudioTrackPicker() {
        val opener = audioTrackProbeOpener ?: return
        while (true) {
            val local = localState.value
            val video = local.video.document ?: return
            if (
                local.video.isResolving ||
                repository.state.value != MiningRunState.Idle ||
                local.pending.start ||
                local.pending.reset ||
                local.timingPreviewPending ||
                mutableTimingPreviewState.value != null ||
                local.audioTrackProbePending ||
                mutableAudioTrackPickerState.value != null
            ) {
                return
            }
            if (
                localState.compareAndSet(
                    local,
                    local.copy(
                        audioTrackProbePending = true,
                        audioTrackPickerError = null,
                    ),
                )
            ) {
                val videoRequest = videoDocumentRequest
                viewModelScope.launch {
                    try {
                        val result = opener.probe(video)
                        result.fold(
                            onSuccess = { list ->
                                publishAudioTrackPicker(video, videoRequest, list)
                            },
                            onFailure = { failure ->
                                publishAudioTrackPickerFailure(failure)
                            },
                        )
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        publishAudioTrackPickerFailure(failure)
                    } finally {
                        localState.update { it.copy(audioTrackProbePending = false) }
                    }
                }
                return
            }
        }
    }

    fun selectAudioTrack(index: Long?) {
        mutableAudioTrackPickerState.update { state -> state?.copy(selectedAudioIndex = index) }
    }

    fun applyAudioTrackPicker() {
        val state = mutableAudioTrackPickerState.value ?: return
        if (state.tracks.size >= 2) {
            localState.update { it.copy(audioTrackOverride = state.selectedAudioIndex) }
        }
        mutableAudioTrackPickerState.value = null
    }

    fun dismissAudioTrackPicker() {
        mutableAudioTrackPickerState.value = null
    }

    fun dismissAudioTrackPickerError() {
        localState.update { it.copy(audioTrackPickerError = null) }
    }

    fun start() {
        while (true) {
            val local = localState.value
            val input = buildVideoInput(local) ?: return
            if (repository.state.value != MiningRunState.Idle ||
                runtimeWorkState.value != null ||
                local.video.isResolving ||
                local.subtitle.isResolving ||
                local.pending.start ||
                local.pending.reset ||
                local.timingPreviewPending ||
                mutableTimingPreviewState.value != null ||
                local.audioTrackProbePending ||
                mutableAudioTrackPickerState.value != null
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
        LogContext.withRunId(request.runId) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "target_change",
                "target" to "candidate",
                "selected" to selected,
                "outcome" to "ok",
            )
        }
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            local.copy(curationDraft = draft.setCandidateSelected(request, candidateId, selected))
        }
        saveCurationSession(request)
    }

    /** Stage one row for the known-word list; the repository commits only after final confirm. */
    fun markCandidateKnown(
        candidateId: String,
        known: Boolean,
    ) {
        val request = (repository.state.value as? MiningRunState.Curating)?.request ?: return
        if (isCurationSubmissionPending() || localState.value.pending.cancel) return
        localState.update { local ->
            val draft = local.curationDraft?.forRequest(request) ?: request.defaultCurationDraft()
            local.copy(curationDraft = draft.markKnown(request, candidateId, known))
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
        LogContext.withRunId(request.runId) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "target_change",
                "target" to "selection",
                "count" to visibleCandidateIds.size,
                "selected" to selected,
                "outcome" to "ok",
            )
        }
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
        LogContext.withRunId(request.runId) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "target_change",
                "target" to "sentence",
                "outcome" to "ok",
            )
        }
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
                acceptedDraft = draft
                if (!runState.request.isFinalPage) {
                    submittedPreviousPageCount =
                        Math.addExact(local.previousPageSelectedCount, currentSelection.size)
                }
            }
        }
        val selection = requireNotNull(acceptedSelection)
        val draft = requireNotNull(acceptedDraft)
        viewModelScope.launch(LogContext.asContextElement(runState.request.runId)) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "curation",
                "outcome" to "ok",
            )
            try {
                repository.confirmCuration(
                    runId = runState.request.runId,
                    requestId = runState.request.requestId,
                    selection = selection,
                    pageIndex = runState.request.page?.pageIndex,
                    knownCandidateIds = draft.knownCandidateIds.toList(),
                )
                if (!runState.request.isFinalPage) {
                    val previousPageSelectedCount =
                        requireNotNull(submittedPreviousPageCount)
                    repository.saveCurationSessionState(
                        draft.toCurationSessionState(previousPageSelectedCount),
                    )
                    localState.update { local ->
                        local.copy(
                            previousPageSelectedCount = previousPageSelectedCount,
                        )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.e(
                    LogComponent.UI,
                    "command",
                    failure,
                    "command" to "curation",
                    "outcome" to "fail",
                )
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
        viewModelScope.launch(LogContext.asContextElement(runId)) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "cancel",
                "outcome" to "ok",
            )
            try {
                if (cancellationToken != null) {
                    repository.cancel(cancellationToken)
                } else {
                    repository.cancel(requireNotNull(runId))
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.e(
                    LogComponent.UI,
                    "command",
                    failure,
                    "command" to "cancel",
                    "outcome" to "fail",
                )
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
        val runState = repository.state.value
        if (!runState.isTerminal || localState.value.pending.reset) return
        updateSubtitleOffsetDraft("")
        localState.update {
            it.copy(
                pending = it.pending.begin(MiningPendingAction.RESET),
                commandError = null,
            )
        }
        viewModelScope.launch(LogContext.asContextElement(runState.runId)) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "reset",
                "outcome" to "ok",
            )
            try {
                repository.reset()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.e(
                    LogComponent.UI,
                    "command",
                    failure,
                    "command" to "reset",
                    "outcome" to "fail",
                )
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
        val input = buildVideoInput(localState.value) ?: return
        localState.update {
            it.copy(pending = it.pending.beginRetry(), commandError = null)
        }
        viewModelScope.launch(LogContext.asContextElement(failed.runId)) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "retry",
                "outcome" to "ok",
            )
            try {
                repository.reset()
                localState.update {
                    it.copy(pending = it.pending.complete(MiningPendingAction.RESET))
                }
                repository.startVideo(input)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.e(
                    LogComponent.UI,
                    "command",
                    failure,
                    "command" to "retry",
                    "outcome" to "fail",
                )
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

    private fun launchStart(input: VideoMiningInput) {
        viewModelScope.launch {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "start",
                "outcome" to "ok",
            )
            try {
                repository.startVideo(input)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.e(
                    LogComponent.UI,
                    "command",
                    failure,
                    "command" to "start",
                    "outcome" to "fail",
                )
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
            localState.value.pending.start ||
            localState.value.timingPreviewPending ||
            mutableTimingPreviewState.value != null ||
            localState.value.audioTrackProbePending ||
            mutableAudioTrackPickerState.value != null
        ) {
            return
        }
        if (!restoring) {
            AppLog.i(
                LogComponent.UI,
                "command",
                "command" to "source_pick",
                "source" to kind.name.lowercase(),
                "outcome" to "ok",
            )
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
                    var rejectionError: DocumentSelectionError? = null
                    val result =
                        SafSelectionOwnershipTransaction(
                            broker = safBroker,
                            store = selectionStore(kind),
                        ).acquirePersistPublish(
                            uri = uri,
                            accept = { document ->
                                val error =
                                    when (kind) {
                                        DocumentKind.VIDEO -> documentSelectionError(document)
                                        DocumentKind.SUBTITLE ->
                                            if (hasSupportedSubtitleExtension(document.displayName)) {
                                                null
                                            } else {
                                                DocumentSelectionError.SUBTITLE
                                            }
                                    }
                                error.also { rejectionError = it } == null
                            },
                            discardPersistedOnRejection = restoring,
                            publish = { document ->
                                check(isCurrentDocumentRequest(kind, sequence)) {
                                    "Document request became stale before publication"
                                }
                                var replaced: SafDocument? = null
                                localState.update { local ->
                                    replaced = local.document(kind)
                                    local.withDocument(kind, document)
                                }
                                replaced
                            },
                        )
                    when (result) {
                        is SafSelectionOwnershipResult.Published ->
                            result.supersededDocuments(result.value).forEach(::releaseDocument)
                        is SafSelectionOwnershipResult.Rejected ->
                            localState.update { local ->
                                local.withSelectionRejection(kind, rejectionError)
                            }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (isCurrentDocumentRequest(kind, sequence)) {
                        localState.update { local -> local.withDocumentFailure(kind) }
                        if (restoring && failure.provesPermanentSafAccessLoss()) {
                            clearPermanentlyLostSelection(kind)
                        }
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

    private suspend fun clearPermanentlyLostSelection(kind: DocumentKind) {
        try {
            SafSelectionOwnershipTransaction(safBroker, selectionStore(kind))
                .clearPersistPublishRelease(localState.value.document(kind)) { Unit }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Failed durable clear leaves the saved selection and grant retryable.
        }
    }

    override fun onCleared() {
        // Any acquisition completing after ViewModel teardown must take the stale path and release
        // its grant rather than publishing ownership into an unreachable LocalState.
        videoDocumentRequest += 1
        subtitleDocumentRequest += 1
        timingPreviewOpenJob?.cancel()
        timingPreviewOpenJob = null
        closeTimingPreview()
        val local = localState.value
        val video = local.video.document
        val subtitle = local.subtitle.document
        val activeOwnershipTransferred =
            if (video != null && subtitle != null) {
                try {
                    buildVideoInput(local)?.let(repository::detachActiveSources) == true
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

    private fun publishTimingPreviewFailure(failure: Throwable) {
        val error =
            when (failure) {
                is TimingPreviewBusyException -> TimingPreviewError.BUSY
                is TokenizerConfigurationFailure.Required ->
                    TimingPreviewError.TOKENIZER_REQUIRED
                else -> TimingPreviewError.OPEN
            }
        localState.update { it.copy(timingPreviewError = error) }
    }

    /** Re-validates against the current state before publishing, dropping a now-stale result. */
    private fun publishAudioTrackPicker(
        video: SafDocument,
        videoRequest: Long,
        list: AudioTrackList,
    ) {
        val current = localState.value
        if (
            repository.state.value != MiningRunState.Idle ||
            current.video.document?.uri != video.uri ||
            !isCurrentDocumentRequest(DocumentKind.VIDEO, videoRequest) ||
            mutableAudioTrackPickerState.value != null
        ) {
            return
        }
        val preselect =
            current.audioTrackOverride?.takeIf { override ->
                list.tracks.any { it.audioIndex == override }
            }
        mutableAudioTrackPickerState.value =
            AudioTrackPickerState(
                tracks = list.tracks,
                autoAudioIndex = list.autoAudioIndex,
                selectedAudioIndex = preselect,
            )
        if (list.tracks.size < 2) {
            localState.update { it.copy(audioTrackOverride = null) }
        }
    }

    private fun publishAudioTrackPickerFailure(failure: Throwable) {
        val error =
            when (failure) {
                is AudioTrackProbeBusyException -> AudioTrackPickerError.BUSY
                else -> AudioTrackPickerError.PROBE
            }
        localState.update { it.copy(audioTrackPickerError = error) }
    }

    private fun queueTimingPreviewClose(
        session: TimingPreviewSession,
        clearPendingOnFinish: Boolean = false,
    ) {
        timingPreviewCleanupScope.launch(NonCancellable) {
            try {
                session.close()
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.SAF,
                    "timing_preview.close",
                    failure,
                    "outcome" to "fail",
                )
            } finally {
                if (clearPendingOnFinish) {
                    localState.update { it.copy(timingPreviewPending = false) }
                }
            }
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

    private fun documentSelectionError(document: SafDocument): DocumentSelectionError? {
        if (lane != MiningLane.AUDIO) return null
        val extension = document.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in AUDIO_EXTENSIONS) null else DocumentSelectionError.AUDIO_TYPE
    }

    private fun LocalState.withDocument(
        kind: DocumentKind,
        document: SafDocument,
    ): LocalState =
        when (kind) {
            DocumentKind.VIDEO ->
                copy(video = DocumentSlotState(document = document), audioTrackOverride = null)
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

    private fun LocalState.withSelectionRejection(
        kind: DocumentKind,
        error: DocumentSelectionError?,
    ): LocalState =
        when (kind) {
            DocumentKind.VIDEO ->
                copy(
                    video =
                        video.copy(
                            isResolving = false,
                            error = error ?: DocumentSelectionError.VIDEO,
                        ),
                )
            DocumentKind.SUBTITLE ->
                copy(
                    subtitle =
                        subtitle.copy(
                            isResolving = false,
                            error = error ?: DocumentSelectionError.SUBTITLE,
                        ),
                )
        }

    private fun buildVideoInput(local: LocalState): VideoMiningInput? {
        if (local.subtitleOffsetDraftInvalid) return null
        val video = local.video.document ?: return null
        val subtitle = local.subtitle.document ?: return null
        return VideoMiningInput(
            video = MiningSource(uri = video.uri, displayName = video.displayName),
            subtitle = MiningSource(uri = subtitle.uri, displayName = subtitle.displayName),
            subtitleOffsetOverride = local.subtitleOffsetOverride,
            audioTrackOverride = local.audioTrackOverride,
        )
    }

    private fun updateSubtitleOffsetDraft(value: String) {
        val bounded = value.takeCodePoints(MAX_SUBTITLE_OFFSET_DRAFT_CODE_POINTS)
        if (bounded.isEmpty()) {
            savedStateHandle.remove<String>(subtitleOffsetDraftKey)
        } else {
            savedStateHandle[subtitleOffsetDraftKey] = bounded
        }
        localState.update { it.copy(subtitleOffsetDraft = bounded) }
    }

    private val LocalState.subtitleOffsetDraftInvalid: Boolean
        get() = !AppSettingsDraftParser.isOptionalDouble(subtitleOffsetDraft)

    private val LocalState.subtitleOffsetOverride: Double?
        get() =
            if (subtitleOffsetDraftInvalid) {
                null
            } else {
                AppSettingsDraftParser.optionalDouble(subtitleOffsetDraft)
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

    private fun definitionCacheKey(request: CurationRequest?): String? =
        request?.let { "${it.runId}:${it.requestId}:${it.page?.pageIndex ?: -1L}" }

    /** Rebinds preview state, then requests the focused candidate's offline definition. */
    private fun requestDefinition(
        request: CurationRequest?,
        candidateId: String?,
    ) {
        val lookup = definitionLookup ?: return
        val cacheKey = definitionCacheKey(request)
        val current = definitionState.value
        if (current.cacheKey != cacheKey) {
            definitionJob?.cancel()
            definitionJob = null
        }
        val rebound = current.forRequest(cacheKey)
        val candidate = request?.candidates?.firstOrNull { it.candidateId == candidateId }
        val query =
            candidate?.let {
                DefinitionQuery(
                    term = it.minedForm,
                    // Miss-only: a canonical lemma may not replace a hit on card-front spelling.
                    fallbackTerm =
                        it.lemma.takeIf { lemma ->
                            lemma.isNotBlank() && lemma != it.minedForm
                        },
                )
            }
        val transition = rebound.request(query)
        definitionState.value = transition.state
        val dispatch = transition.dispatch ?: return
        dispatchDefinition(lookup, requireNotNull(request).runId, dispatch, transition.generation)
    }

    private fun dispatchDefinition(
        lookup: DefinitionLookupService,
        runId: String,
        query: DefinitionQuery,
        generation: Long,
    ) {
        definitionJob =
            viewModelScope.launch {
                delay(DEFINITION_DEBOUNCE_MS)
                val outcome =
                    lookup.define(runId, query.term, query.fallbackTerm).fold(
                        onSuccess = { result ->
                            if (result.entries.isEmpty()) {
                                CurationDefinition.Missing
                            } else {
                                CurationDefinition.Loaded(result.matchedTerm, result.entries)
                            }
                        },
                        onFailure = { CurationDefinition.Unavailable },
                    )
                val landed = definitionState.value.completed(generation, query, outcome)
                definitionState.value = landed.state
                landed.dispatch?.let { next ->
                    dispatchDefinition(lookup, runId, next, landed.state.generation)
                }
            }
    }

    private fun RuntimeWorkCoordinator.Kind.toRuntimeConflict(): RuntimeWorkConflict =
        when (this) {
            RuntimeWorkCoordinator.Kind.MINING -> RuntimeWorkConflict.MINING
            RuntimeWorkCoordinator.Kind.RESOURCE -> RuntimeWorkConflict.RESOURCE
            RuntimeWorkCoordinator.Kind.ANKI_SETUP -> RuntimeWorkConflict.ANKI_SETUP
        }

    private suspend fun loadCues(key: CueLookupKey?) {
        if (key == null) {
            cueState.value = null
            return
        }
        cueState.value = CueState(key)
        val result =
            try {
                cueLookup.cues(key.runId, key.subtitlePath)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        if ((repository.state.value as? MiningRunState.Curating)?.cueLookupKey() != key) return
        cueState.value =
            result.fold(
                onSuccess = { cues -> CueState(key = key, cues = cues) },
                onFailure = { CueState(key = key, unavailable = true) },
            )
    }

    private fun MiningRunState.Curating.cueLookupKey(): CueLookupKey? =
        media?.toCueLookupKey(request.runId)

    private fun CurationMediaBinding.toCueLookupKey(runId: String): CueLookupKey =
        CueLookupKey(
            runId = runId,
            videoPath = videoPath,
            subtitlePath = subtitlePath,
        )

    private fun MiningRunState.Curating.toPlayerUiState(cues: CueState?): CurationPlayerUiState? =
        media?.let { media ->
            val key = media.toCueLookupKey(request.runId)
            val current = cues?.takeIf { it.key == key }
            CurationPlayerUiState(
                videoPath = media.videoPath,
                cues = current?.cues.orEmpty(),
                cuesUnavailable = current?.unavailable == true,
                audioOnly = media.audioOnly,
                audioTrackOverride = media.audioTrackOverride,
            )
        }

    private fun CurationRequest.toUiState(
        draft: SharedCurationDraft?,
        previousPageSelectedCount: Int,
        definition: CurationDefinition?,
        player: CurationPlayerUiState?,
    ): CurationUiState {
        val current = draft?.forRequest(this) ?: defaultCurationDraft()
        return CurationUiState(
            runId = runId,
            requestId = requestId,
            page = page,
            candidates = candidates,
            selectedCandidateIds = current.selectedCandidateIds,
            knownCandidateIds = current.knownCandidateIds,
            sentenceIds = current.sentenceIds,
            focusedCandidateId = current.focusedCandidateId,
            previousPageSelectedCount = previousPageSelectedCount,
            definition = definition,
            player = player,
        )
    }

    internal class Factory(
        private val repository: MiningRepository,
        private val safBroker: SafBroker,
        private val lane: MiningLane,
        private val definitionLookup: DefinitionLookupService,
        private val cueLookup: SubtitleCueLookupService = NO_CUE_LOOKUP,
        private val effectiveSubtitleOffset: Flow<Double?> = flowOf(null),
        private val fieldMap: Flow<Map<String, String>> = flowOf(emptyMap()),
        private val audioPacks: Flow<List<InstalledAudioPack>> = flowOf(emptyList()),
        private val timingPreviewOpener: TimingPreviewOpener? = null,
        private val timingPreviewCleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val audioTrackProbeOpener: AudioTrackProbeOpener? = null,
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
            require(modelClass.isAssignableFrom(MediaMiningViewModel::class.java))
            return MediaMiningViewModel(
                repository = repository,
                safBroker = safBroker,
                lane = lane,
                runtimeWorkState = runtimeWorkState,
                savedStateHandle = savedStateHandleFactory(extras),
                selectionInventory = selectionInventory,
                definitionLookup = definitionLookup,
                cueLookup = cueLookup,
                effectiveSubtitleOffset = effectiveSubtitleOffset,
                fieldMap = fieldMap,
                audioPacks = audioPacks,
                timingPreviewOpener = timingPreviewOpener,
                timingPreviewCleanupDispatcher = timingPreviewCleanupDispatcher,
                audioTrackProbeOpener = audioTrackProbeOpener,
            ) as T
        }
    }

    private companion object {
        val NO_CUE_LOOKUP =
            SubtitleCueLookupService { _, _ -> Result.success(emptyList()) }
    }
}

private fun String.takeCodePoints(maximum: Int): String {
    require(maximum >= 0)
    var end = 0
    repeat(maximum) {
        if (end == length) return this
        end += Character.charCount(Character.codePointAt(this, end))
    }
    return if (end == length) this else substring(0, end)
}
