package com.ankiminer.android.uiaudit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.resources.BundledWordset
import com.ankiminer.android.data.resources.CatalogDictionaryStatus
import com.ankiminer.android.data.resources.DictionaryLookup
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationBlockBox
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPageContext
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.reading.ReadingCurationUiState
import com.ankiminer.android.ui.reading.ReadingDocumentSlotState
import com.ankiminer.android.ui.reading.ReadingMiningCommandError
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.ui.reading.ReadingSourceKindUi
import com.ankiminer.android.ui.settings.AnkiTargetCard
import com.ankiminer.android.ui.settings.BooleanSetting
import com.ankiminer.android.ui.settings.DictionaryLookupCard
import com.ankiminer.android.ui.settings.InlineFailureContainer
import com.ankiminer.android.ui.settings.KnownWordsImportCard
import com.ankiminer.android.ui.settings.NullableToggle
import com.ankiminer.android.ui.settings.NumericField
import com.ankiminer.android.ui.settings.ResourceCard
import com.ankiminer.android.ui.settings.ResourceChainPanel
import com.ankiminer.android.ui.settings.ResourcePanelAction
import com.ankiminer.android.ui.settings.SettingTextField
import com.ankiminer.android.ui.settings.SettingsCardIndexRecorder
import com.ankiminer.android.ui.settings.SettingsCategory
import com.ankiminer.android.ui.settings.SettingsCategoryLayout
import com.ankiminer.android.ui.settings.SettingsPanelExpansion
import com.ankiminer.android.ui.settings.SettingsScreenCallbacks
import com.ankiminer.android.ui.settings.SettingsSection
import com.ankiminer.android.ui.settings.dictionaryPanelRows
import com.ankiminer.android.ui.settings.dictionaryRowStrings
import com.ankiminer.android.ui.settings.mediaSettings
import com.ankiminer.android.ui.settings.pitchPanelRows
import com.ankiminer.android.ui.settings.resourceRowStrings
import com.ankiminer.android.ui.settings.settingsCard
import com.ankiminer.android.ui.settings.settingsCategoryContent
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.video.CurationPlayerUiState
import com.ankiminer.android.ui.video.CurationUiState
import com.ankiminer.android.ui.video.DocumentSlotState
import com.ankiminer.android.ui.video.MiningCommandError
import com.ankiminer.android.ui.video.VideoMiningUiState
import com.ankiminer.android.vm.DiagnosticsExportState
import com.ankiminer.android.vm.SettingsBackupState
import com.ankiminer.android.vm.SettingsDraft
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal enum class MiningAuditState(val fileName: String) {
    IDLE("idle"),
    SOURCE_SELECTED("source-selected"),
    RUNNING("running"),
    CURATION("curation"),
    RESULTS("results"),
    ERROR("error"),
}

internal enum class SettingsAuditState(val fileName: String) {
    TOP("top"),
    ANKI("anki"),
    RESOURCES("dictionaries-resources"),
    ERROR_SNACKBAR("error-snackbar"),
    MEDIA("media"),
}

internal object UiAuditTags {
    const val SETTINGS_SCROLL = "ui_audit_settings_scroll"
}

internal fun videoAuditState(
    auditState: MiningAuditState,
    candidateCount: Int = 12,
): VideoMiningUiState {
    val video = DocumentSlotState(document("video", "葬送のフリーレン 第12話.mkv"))
    val subtitle = DocumentSlotState(document("subtitle", "frieren_episode_12.ja.srt"))
    return when (auditState) {
        MiningAuditState.IDLE -> VideoMiningUiState()
        MiningAuditState.SOURCE_SELECTED ->
            VideoMiningUiState(
                video = video,
                subtitle = subtitle,
            )
        MiningAuditState.RUNNING ->
            VideoMiningUiState(
                video = video,
                subtitle = subtitle,
                runState =
                    MiningRunState.Running(
                        runId = "ui-audit-video-running",
                        progress =
                            MiningProgress(
                                current = 47,
                                total = 100,
                                description = "字幕を解析して候補を照合しています",
                            ),
                    ),
            )
        MiningAuditState.CURATION -> {
            val candidates = curationCandidates(candidateCount)
            // Protocol pages cap requests at 100. The 200-row jank fixture keeps valid request
            // metadata while deliberately expanding the presentation state used by the screen.
            val requestCandidates = candidates.take(100)
            val request =
                CurationRequest(
                    runId = "ui-audit-video-curation",
                    requestId = "ui-audit-video-page",
                    candidates = requestCandidates,
                )
            VideoMiningUiState(
                video = video,
                subtitle = subtitle,
                runState = MiningRunState.Curating(request),
                curation =
                    CurationUiState(
                        runId = request.runId,
                        requestId = request.requestId,
                        candidates = candidates,
                        selectedCandidateIds =
                            candidates.mapIndexedNotNull { index, candidate ->
                                candidate.candidateId.takeIf { index % 3 != 0 }
                            }.toSet(),
                        sentenceIds =
                            candidates.associate { candidate ->
                                val index = candidate.candidateId.substringAfterLast("-").toInt()
                                candidate.candidateId to
                                    if (index % 4 == 0) {
                                        candidate.sentences.last().sentenceId
                                    } else {
                                        candidate.defaultSentenceId
                                    }
                            },
                        focusedCandidateId = candidates.getOrNull(1)?.candidateId,
                        player =
                            CurationPlayerUiState(
                                videoPath = "/cache/ui-audit-curation.media",
                                cues =
                                    listOf(
                                        SubtitleCue(
                                            startSeconds = 0.0,
                                            endSeconds = 2.4,
                                            text = "壁に古い時計を掛ける。",
                                        ),
                                        SubtitleCue(
                                            startSeconds = 2.5,
                                            endSeconds = 4.9,
                                            text = "夜明け前の空は美しい。",
                                        ),
                                        SubtitleCue(
                                            startSeconds = 5.0,
                                            endSeconds = 7.4,
                                            text = "懐かしい景色を思い出す。",
                                        ),
                                    ),
                                cuesUnavailable = false,
                            ),
                    ),
            )
        }
        MiningAuditState.RESULTS ->
            VideoMiningUiState(
                video = video,
                subtitle = subtitle,
                runState =
                    MiningRunState.Success(
                        runId = "ui-audit-video-results",
                        result = processingResult("video"),
                    ),
            )
        MiningAuditState.ERROR ->
            VideoMiningUiState(
                video = video,
                subtitle = subtitle,
                runState =
                    MiningRunState.Failed(
                        runId = "ui-audit-video-error",
                        failure =
                            MiningFailure(
                                message = "字幕の時間情報を読み取れませんでした。" +
                                    "別の字幕ファイルで再試行してください。",
                                retryable = true,
                            ),
                        result = null,
                    ),
                commandError = MiningCommandError.START,
            )
    }
}

internal fun readingAuditState(
    auditState: MiningAuditState,
    candidateCount: Int = 12,
    longResult: Boolean = false,
): ReadingMiningUiState {
    val source = ReadingDocumentSlotState(document("reading", "銀河鉄道の夜.epub"))
    return when (auditState) {
        MiningAuditState.IDLE -> ReadingMiningUiState()
        MiningAuditState.SOURCE_SELECTED ->
            ReadingMiningUiState(
                source = source,
                sourceKind = ReadingSourceKindUi.EPUB,
            )
        MiningAuditState.RUNNING ->
            ReadingMiningUiState(
                source = source,
                sourceKind = ReadingSourceKindUi.EPUB,
                runState =
                    MiningRunState.Running(
                        runId = "ui-audit-reading-running",
                        progress =
                            MiningProgress(
                                current = 63,
                                total = 120,
                                description = "章と段落を解析しています",
                            ),
                    ),
            )
        MiningAuditState.CURATION -> {
            val candidates = curationCandidates(candidateCount)
            val requestCandidates = candidates.take(100)
            val request =
                CurationRequest(
                    runId = "ui-audit-reading-curation",
                    requestId = "ui-audit-reading-page",
                    candidates = requestCandidates,
                )
            ReadingMiningUiState(
                source = source,
                sourceKind = ReadingSourceKindUi.EPUB,
                runState = MiningRunState.Curating(request),
                curation =
                    ReadingCurationUiState(
                        runId = request.runId,
                        requestId = request.requestId,
                        candidates = candidates,
                        selectedCandidateIds =
                            candidates.mapIndexedNotNull { index, candidate ->
                                candidate.candidateId.takeIf { index % 4 != 0 }
                            }.toSet(),
                        sentenceIds =
                            candidates.associate { candidate ->
                                val index = candidate.candidateId.substringAfterLast("-").toInt()
                                candidate.candidateId to
                                    if (index % 3 == 0) {
                                        candidate.sentences.last().sentenceId
                                    } else {
                                        candidate.defaultSentenceId
                                    }
                            },
                        focusedCandidateId = candidates.getOrNull(1)?.candidateId,
                    ),
            )
        }
        MiningAuditState.RESULTS ->
            ReadingMiningUiState(
                source = source,
                sourceKind = ReadingSourceKindUi.EPUB,
                runState =
                    MiningRunState.Success(
                        runId = "ui-audit-reading-results",
                        result =
                            if (longResult) {
                                processingResult("reading").copy(
                                    minedForms =
                                        (1..250).map { index ->
                                            "${JAPANESE_WORDS[index % JAPANESE_WORDS.size]}-$index"
                                        },
                                    cardIds = (10_001L..10_250L).toList(),
                                    errors =
                                        (1..50).map { index ->
                                            "項目 $index は既存ノートと重複したため" +
                                                "スキップされました"
                                        },
                                )
                            } else {
                                processingResult("reading")
                            },
                    ),
            )
        MiningAuditState.ERROR ->
            ReadingMiningUiState(
                source = source,
                sourceKind = ReadingSourceKindUi.EPUB,
                runState =
                    MiningRunState.Failed(
                        runId = "ui-audit-reading-error",
                        failure =
                            MiningFailure(
                                message = "EPUB の本文を抽出できませんでした。" +
                                    "暗号化されていない文書を選んでください。",
                                retryable = true,
                            ),
                        result = null,
                    ),
                commandError = ReadingMiningCommandError.START,
            )
    }
}

internal fun setupAuditState(): SetupUiState {
    val catalog = FrozenResourceCatalog.value
    val dictionaries =
        catalog.dictionaries.mapIndexed { index, resource ->
            InstalledDictionary(
                slotId = resource.slotId,
                occupied = true,
                valid = true,
                sourceName = resource.displayName,
                sourceRevision = resource.dictionary.revision,
                format = "yomitan",
                entryCount = if (index == 0) 212_846 else 209_184,
                schemaOk = true,
                embeddedAttribution =
                    mapOf(
                        "author" to "辞書プロジェクトの共同編集者",
                        "description" to "日本語学習用の英語定義辞書",
                    ),
                catalogResourceId = resource.resourceId,
                attribution = resource.attribution,
                rebuildSourcePath = null,
            )
        }
    val noteFields =
        listOf(
            "Expression",
            "Sentence",
            "Meaning",
            "Audio",
            "Picture",
            "Reading",
            "Frequency",
            "Source",
        )
    val destinations =
        listOf(
            "Expression",
            "Sentence",
            "Meaning",
            "",
            "Picture",
            "Audio",
            "",
            "Reading",
            "",
            "",
            "",
            "",
            "",
            "",
            "Frequency",
            "",
            "Source",
            "",
        )
    return SetupUiState(
        python = PythonRuntimeReadiness.Ready("/data/user/0/com.ankiminer.android/files/python"),
        resourceStartup = ResourceStartupReadiness.READY,
        anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 21_800_000),
        ankiRecovery = AnkiRecoveryReadiness.Ready,
        notifications = NotificationPermissionReadiness.READY,
        noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1_700_000_001),
        availableNoteTypes =
            listOf(
                ModelSummary(
                    id = 1_700_000_001,
                    name = "Anki Miner Japanese",
                    fieldNames = noteFields,
                ),
                ModelSummary(
                    id = 1_700_000_002,
                    name = "Japanese recognition",
                    fieldNames = listOf("Expression", "Meaning", "Reading"),
                ),
            ),
        availableDeckNames =
            listOf(
                "Japanese::Mining",
                "Japanese::Reading",
                "日本語::復習",
            ),
        deckName = "Japanese::Mining",
        noteType = "Anki Miner Japanese",
        fieldMap = AnkiFieldKeys.ALL.zip(destinations).toMap(),
        remediations = AnkiRemediationInventory(emptyList()),
        recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
        wizardSeen = true,
        uniDicInstalled = true,
        catalogDictionaries =
            catalog.dictionaries.map { resource ->
                CatalogDictionaryStatus(
                    resource = resource,
                    installed = true,
                    slotOccupied = true,
                )
            },
        dictionaries = dictionaries,
        frequencySources =
            listOf(
                InstalledFrequencySource(
                    sourceId = "jpdb-v2",
                    sourceName = "JPDB frequency 2025",
                    format = "yomitan",
                    entryCount = 198_422,
                    schemaOk = true,
                    schemaVersion = 1,
                    isCategorical = false,
                    rebuildSourcePath = null,
                ),
            ),
        pitchSources =
            listOf(
                InstalledPitchSource(
                    sourceId = "kanjium",
                    sourceName = "Kanjium pitch accents",
                    sourceRevision = "2025-02",
                    format = "csv",
                    entryCount = 163_284,
                    schemaOk = true,
                    schemaVersion = 1,
                    rebuildSourcePath = null,
                ),
            ),
        audioPacks =
            listOf(
                InstalledAudioPack(
                    packId = "jpod101",
                    sourceName = "JapanesePod101 audio",
                    format = "zip",
                    entryCount = 81_004,
                    contentAvailable = true,
                ),
            ),
        knownWords =
            KnownWordsInventory(
                totalCount = 6_842,
                userCount = 1_208,
                ankiCount = 4_917,
                minedCount = 717,
                schemaOk = true,
            ),
        knownWordsPage =
            KnownWordsPage(
                query = "美",
                offset = 0,
                totalCount = 3,
                words = listOf("美しい", "美容", "美術館"),
                hasMore = false,
            ),
        wordsets =
            listOf(
                BundledWordset("core", "Core grammar words", 1_248),
                BundledWordset("names", "Common names", 8_302),
            ),
        lookup =
            DictionaryLookup(
                slotId = dictionaries.first().slotId,
                term = "掛ける",
                html = "<h3>掛ける</h3><p>to hang; to apply; to call</p>",
            ),
        lookupTerm = "掛ける",
        lookupSlotId = dictionaries.first().slotId,
        knownWordsSearch = "美",
    )
}

@Composable
internal fun UiAuditSettingsFixture(
    focus: SettingsAuditState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val baseSetup = setupAuditState()
    val setup =
        if (focus == SettingsAuditState.ERROR_SNACKBAR) {
            val targetId = baseSetup.catalogDictionaries.first().resource.resourceId
            baseSetup.copy(
                failure =
                    ResourceFailure(
                        code = "resource_archive_too_large",
                        message = "Dictionary archive contains an oversized file",
                        retryable = true,
                        origin = ResourceFailureOrigin.CATALOG_DICTIONARY,
                        retry =
                            ResourceFailureRetry(
                                action = ResourceFailureAction.RETRY,
                                targetId = targetId,
                            ),
                    ),
            )
        } else {
            baseSetup
        }
    val category =
        when (focus) {
            SettingsAuditState.TOP -> SettingsCategory.DIAGNOSTICS
            SettingsAuditState.ANKI -> SettingsCategory.ANKI
            SettingsAuditState.RESOURCES,
            SettingsAuditState.ERROR_SNACKBAR,
            -> SettingsCategory.RESOURCES
            SettingsAuditState.MEDIA -> SettingsCategory.MEDIA
        }
    val recorder = remember { SettingsCardIndexRecorder() }
    SettingsCategoryLayout(
        selectedCategory = category,
        onSelectedCategory = {},
        recorder = recorder,
        header = {},
        modifier = modifier.testTag(UiAuditTags.SETTINGS_SCROLL),
        listStates = mapOf(category to listState),
    ) { selected ->
        when (selected) {
            SettingsCategory.DIAGNOSTICS ->
                // The UniDic card lives in Diagnostics now and only appears when the tokenizer
                // is missing or failing, so the fixture captures that state rather than the
                // installed one, which renders nothing.
                settingsCard(selected, recorder, "audit-unidic") {
                    ResourceCard(
                        title = stringResource(R.string.unidic_resource_title),
                        description = stringResource(R.string.unidic_resource_description),
                        installed = false,
                        busy = false,
                        action = {},
                        actionLabel = stringResource(R.string.unidic_install),
                    )
                }
            SettingsCategory.ANKI ->
                settingsCard(selected, recorder, "audit-anki") { SettingsAnkiFixture(setup) }
            SettingsCategory.RESOURCES ->
                settingsCard(selected, recorder, "audit-resources") {
                    SettingsResourcesFixture(setup)
                }
            SettingsCategory.FILTERING -> {
                settingsCard(selected, recorder, "audit-filtering") { SettingsFilteringFixture() }
                settingsCard(selected, recorder, "audit-known-words") {
                    KnownWordsImportCard(
                        state = setup,
                        onImport = {},
                        onConfirmImport = {},
                        onDismissImport = {},
                        onManage = {},
                    )
                }
            }
            // The real media group, not a hand-built stand-in: the animated-screenshot controls
            // carry long supporting text, which is exactly what the 200% font-scale captures are
            // for. Rendered with the feature on so the tuning fields appear enabled.
            SettingsCategory.MEDIA ->
                mediaSettings(
                    SettingsDraft.from(
                        AppSettings(
                            animatedScreenshotsEnabled = true,
                            animatedScreenshotDurationSeconds = 2.0,
                            animatedScreenshotQuality = 30,
                        ),
                        ResourceManagerState(),
                    ),
                    recorder,
                ) {}
            else ->
                settingsCard(selected, recorder, "audit-placeholder") {
                    Text(stringResource(selected.label))
                }
        }
    }
}

@Composable
internal fun UiAuditFullSettingsFixture(
    selectedCategory: SettingsCategory,
    listStates: Map<SettingsCategory, LazyListState>,
    recorder: SettingsCardIndexRecorder,
    modifier: Modifier = Modifier,
) {
    val setup = remember { setupAuditState() }
    val resources = remember(setup) { setup.auditResourceState() }
    var draft by
        remember(resources) {
            mutableStateOf(
                SettingsDraft.from(
                    AppSettings(
                        animatedScreenshotsEnabled = true,
                        animatedScreenshotDurationSeconds = 2.0,
                        animatedScreenshotQuality = 30,
                    ),
                    resources,
                ),
            )
        }
    val setupViewModel = uiAuditSetupViewModel()
    val callbacks =
        SettingsScreenCallbacks(
            onDraftChange = { draft = it },
            onRequestReset = { _ -> },
            resetEnabled = true,
            onRequestPermissions = {},
            onOpenAppSettings = {},
            onInstallAnkiDroid = {},
            onOpenAnkiDroid = {},
            onOpenSpeechSettings = {},
            onShareDiagnosticsBundle = {},
            onRetryDiagnosticsExport = {},
            onDismissDiagnosticsExport = {},
            backupState = SettingsBackupState.Idle,
            onExportSettings = {},
            onImportSettings = {},
            onDismissBackupState = {},
            onReturnToActiveRun = null,
            onAttributions = {},
            onRunSetupWizard = {},
            onImportCustom = {},
            onDownloadRecommended = {},
            onReplaceCustom = { _ -> },
            onImportFrequency = {},
            onImportPitch = {},
            onImportAudioPack = {},
            onImportKnownWords = {},
            onImportWordList = { _ -> },
            onExportKnownWords = {},
            onManageKnownWords = {},
            verboseLogging = false,
            onVerboseLoggingChange = { _ -> },
            updateCheck = UpdateCheckUiState(),
            onUpdateCheckEnabledChange = { _ -> },
            onCheckForUpdates = {},
            onSkipUpdate = {},
        )
    val auditPanelExpansion =
        remember {
            SettingsPanelExpansion(
                listOf(
                    "dictionary-sources",
                    "pitch-sources",
                    "audio-sources",
                    "frequency-sources",
                ),
            )
        }
    SettingsCategoryLayout(
        selectedCategory = selectedCategory,
        onSelectedCategory = {},
        recorder = recorder,
        header = {},
        modifier = modifier.testTag(UiAuditTags.SETTINGS_SCROLL),
        listStates = listStates,
    ) { category ->
        settingsCategoryContent(
            category = category,
            draft = draft,
            resources = resources,
            setup = setup,
            setupViewModel = setupViewModel,
            diagnostics =
                TesterDiagnosticsIdentity(
                    versionLabel = "UI audit",
                    sourceLabel = "fixture",
                ),
            diagnosticsExport = DiagnosticsExportState.Idle,
            recorder = recorder,
            // Open, not closed: the jank flow and the screenshot matrix exist to measure these
            // panels, and a fixture that arrives collapsed would stop exercising them.
            expansion = auditPanelExpansion,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun uiAuditSetupViewModel(): SetupViewModel {
    val app = LocalContext.current.applicationContext as AnkiMinerApplication
    val factory =
        remember(app) {
            SetupViewModel.Factory(
                resources = app.resourceManager,
                settings = app.settingsRepository,
                ankiSetup = app.ankiSetupManager,
                python = app.pythonRuntimeReadiness,
                admission = app.miningAdmissionState,
                runtimeWorkState = app.runtimeWorkState,
                refreshExternalReadiness = app::refreshExternalReadiness,
                strings = app.stringResourceResolver,
            )
        }
    return viewModel(key = "ui-audit-full-settings", factory = factory)
}

private fun SetupUiState.auditResourceState(): ResourceManagerState =
    ResourceManagerState(
        startupReadiness = resourceStartup,
        catalog = FrozenResourceCatalog.value,
        dictionaries = dictionaries,
        frequencySources = frequencySources,
        pitchSources = pitchSources,
        audioPacks = audioPacks,
        knownWords = knownWords,
        wordsets = wordsets,
        wordLists = wordLists,
        lastLocalImport = lastLocalImport,
        knownWordsImportPreview = knownWordsImportPreview,
        knownWordsPage = knownWordsPage,
        activeOperation = operation,
        failure = failure,
        lastLookup = lookup,
    )

@Composable
private fun SettingsAnkiFixture(setup: SetupUiState) {
    Column(
        modifier = Modifier.testTag("ui_audit_settings_anki"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                value = "Japanese::Mining",
                onChange = {},
                label = stringResource(R.string.settings_deck_name),
                singleLine = false,
                maxLines = 2,
            )
            Text(
                stringResource(R.string.settings_excluded_decks),
                style = MaterialTheme.typography.titleSmall,
            )
            setup.availableDeckNames.forEachIndexed { index, deck ->
                BooleanSetting(
                    label = deck,
                    checked = index == 1,
                    onCheckedChange = {},
                )
            }
            SettingTextField(
                value = "ankiminer japanese subs2srs",
                onChange = {},
                label = stringResource(R.string.settings_tags),
            )
            SupportingText(stringResource(R.string.settings_tags_help))
        }
        AnkiTargetCard(
            state = setup,
            onSelectNoteType = {},
            onSetFieldMapping = { _, _ -> },
            onSelectCardType = {},
            onSelectCardTypeMarker = {},
            onRemapFields = {},
        )
    }
}

/**
 * What the Dictionaries tab actually renders: the two priority panels and the lookup card.
 *
 * The catalog install cards are deliberately absent — they moved to the wizard, and on this tab
 * a missing dictionary is an entry in the dictionary panel's Add menu. The catalog failure the
 * ERROR_SNACKBAR state carries lands in the dictionary panel's footer, where the real screen
 * routes `CATALOG_DICTIONARY` failures.
 */
@Composable
private fun SettingsResourcesFixture(setup: SetupUiState) {
    Column(
        modifier = Modifier.testTag("ui_audit_settings_resources"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_dictionaries_heading),
            explanation = stringResource(R.string.resource_panel_dictionaries_explanation),
            rows =
                dictionaryPanelRows(
                    chain =
                        setup.dictionaries.map { dictionary ->
                            ResourceChainSelection(dictionary.slotId)
                        },
                    installed = setup.dictionaries,
                    jishoEnabled = false,
                    strings = dictionaryRowStrings(),
                    onChainChange = {},
                    onJishoChange = {},
                    onRepair = {},
                    onReplace = {},
                ),
            emptyMessage = stringResource(R.string.settings_no_dictionaries),
            onMove = { _, _ -> },
            onRemove = {},
            addPrimary =
                ResourcePanelAction(stringResource(R.string.resource_panel_add_dictionary)) {},
            addMenu =
                listOf(
                    ResourcePanelAction(stringResource(R.string.resource_panel_import_yomitan_zip)) {},
                ),
            busy = setup.busy,
            footer = {
                val failure = setup.failure
                if (failure?.origin == ResourceFailureOrigin.CATALOG_DICTIONARY) {
                    InlineFailureContainer(
                        message = failure.message,
                        actionLabel = stringResource(R.string.b3_retry),
                        onAction = {},
                        onDismiss = {},
                    )
                }
                SupportingText(stringResource(R.string.settings_jisho_disclosure))
            },
        )
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_pitch_heading),
            explanation = stringResource(R.string.resource_panel_pitch_explanation),
            rows =
                pitchPanelRows(
                    chain = emptyList(),
                    installed = setup.pitchSources,
                    strings = resourceRowStrings(),
                    onChainChange = {},
                ),
            emptyMessage = stringResource(R.string.settings_pitch_not_installed),
            onMove = { _, _ -> },
            onRemove = {},
            addPrimary = ResourcePanelAction(stringResource(R.string.resource_panel_add_pitch)) {},
            busy = setup.busy,
        )
        DictionaryLookupCard(
            state = setup.copy(lookup = null),
            onTermChanged = {},
            onSelectSlot = {},
            onLookup = {},
        )
    }
}

@Composable
private fun SettingsFilteringFixture() {
    SettingsSection(stringResource(R.string.settings_filtering)) {
        NullableToggle(
            label = stringResource(R.string.settings_known_words),
            value = true,
            desktopDefault = false,
            onChange = {},
        )
        NullableToggle(
            label = stringResource(R.string.settings_deduplicate),
            // Android defaults this off against the desktop engine's true, so the screenshot lane
            // shows the override styling a fresh install actually gets.
            value = false,
            desktopDefault = true,
            onChange = {},
        )
        NullableToggle(
            label = stringResource(R.string.settings_i_plus_one),
            value = false,
            desktopDefault = false,
            onChange = {},
        )
        NumericField(
            value = "12",
            onChange = {},
            label = stringResource(R.string.settings_max_duration),
        )
        NumericField(
            value = "160",
            onChange = {},
            label = stringResource(R.string.settings_max_characters),
            integer = true,
        )
    }
}

internal fun attributionAuditDictionaries(): List<InstalledDictionary> =
    setupAuditState().dictionaries

private fun curationCandidates(count: Int): List<CurationCandidate> =
    (0 until count).map { index ->
        val word = JAPANESE_WORDS[index % JAPANESE_WORDS.size]
        val sentenceBase = JAPANESE_SENTENCES[index % JAPANESE_SENTENCES.size]
        val sentences =
            listOf(
                CurationSentence(
                    sentenceId = "candidate-$index-sentence-1",
                    sentence = sentenceBase,
                    sentenceFurigana = sentenceBase,
                    sentenceReading = sentenceBase,
                    startTime = index.toDouble(),
                    endTime = index + 2.4,
                    duration = 2.4,
                    pageContext = if (index == 0) {
                        CurationPageContext(
                            imageEntry = "pages/001.png",
                            blockBox = CurationBlockBox(xMin = 10, yMin = 20, xMax = 300, yMax = 400),
                            locationLabel = "p.1",
                        )
                    } else {
                        null
                    },
                ),
                CurationSentence(
                    sentenceId = "candidate-$index-sentence-2",
                    sentence = "美しい景色を眺めながら、忘れかけていた約束について" +
                        "静かに考え続けた。",
                    sentenceFurigana = "美[うつく]しい 景色[けしき]を 眺[なが]めながら、" +
                        " 忘[わす]れかけていた 約束[やくそく]について " +
                        "静[しず]かに 考[かんが]え 続[つづ]けた。",
                    sentenceReading = "うつくしいけしきをながめながら、" +
                        "わすれかけていたやくそくについてしずかにかんがえつづけた。",
                    startTime = index + 2.5,
                    endTime = index + 7.2,
                    duration = 4.7,
                ),
            )
        CurationCandidate(
            candidateId = "candidate-$index",
            minedForm = word,
            surface = word,
            lemma = word,
            reading = JAPANESE_READINGS[index % JAPANESE_READINGS.size],
            expressionReading = JAPANESE_READINGS[index % JAPANESE_READINGS.size],
            partOfSpeech = if (index % 2 == 0) "動詞" else "形容詞",
            frequencyRank = 800L + index * 137L,
            occurrenceCount = 1L + index % 7,
            defaultSentenceId = sentences.first().sentenceId,
            sentences = sentences,
        )
    }

private fun document(
    id: String,
    displayName: String,
): SafDocument =
    SafDocument(
        uri = "content://com.ankiminer.android.uiaudit/$id",
        displayName = displayName,
        mimeType = null,
        sizeBytes = 128_000_000,
    )

private fun processingResult(kind: String): ProcessingResult =
    ProcessingResult(
        totalWordsFound = 1_284,
        newWordsFound = 6,
        cardsCreated = 6,
        errors = listOf("懐かしい は既存ノートと重複したためスキップされました"),
        elapsedTime = 18.7,
        comprehensionPercentage = 87.4,
        cardIds = listOf(10_401, 10_402, 10_403, 10_404, 10_405, 10_406),
        videoFile = if (kind == "video") "episode.mkv" else "",
        subtitleFile = if (kind == "video") "episode.srt" else "銀河鉄道の夜.epub",
        minedForms = listOf("掛ける", "美しい", "懐かしい", "辿り着く", "見落とす", "鮮やか"),
        ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
        failureIsTransient = false,
    )

private val JAPANESE_WORDS =
    listOf(
        "掛ける",
        "美しい",
        "懐かしい",
        "辿り着く",
        "見落とす",
        "鮮やか",
        "思い出す",
        "立ち止まる",
        "振り返る",
        "受け継ぐ",
        "語り掛ける",
        "巡り会う",
    )

private val JAPANESE_READINGS =
    listOf(
        "かける",
        "うつくしい",
        "なつかしい",
        "たどりつく",
        "みおとす",
        "あざやか",
        "おもいだす",
        "たちどまる",
        "ふりかえる",
        "うけつぐ",
        "かたりかける",
        "めぐりあう",
    )

private val JAPANESE_SENTENCES =
    listOf(
        "壁に古い時計を掛けると、部屋の雰囲気が少しだけ変わった。",
        "夜明け前の空は言葉では言い表せないほど美しい。",
        "この曲を聴くたびに、子どもの頃の懐かしい景色を思い出す。",
        "長い森を抜けて、ようやく小さな村へ辿り着いた。",
        "急いでいたので、大切な注意書きを見落としてしまった。",
        "雨上がりの庭には鮮やかな紫陽花が咲いていた。",
    )
