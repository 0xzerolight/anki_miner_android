package com.ankiminer.android.uiaudit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.ankiminer.android.data.resources.InstalledPitchAccent
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.reading.ReadingCurationCandidateUiState
import com.ankiminer.android.ui.reading.ReadingCurationUiState
import com.ankiminer.android.ui.reading.ReadingDocumentSlotState
import com.ankiminer.android.ui.reading.ReadingMiningCommandError
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.ui.reading.ReadingSourceKindUi
import com.ankiminer.android.ui.settings.AnkiRecoveryCard
import com.ankiminer.android.ui.settings.AnkiTargetCard
import com.ankiminer.android.ui.settings.AudioPackImportCard
import com.ankiminer.android.ui.settings.BooleanSetting
import com.ankiminer.android.ui.settings.BundledWordsetInventoryCard
import com.ankiminer.android.ui.settings.CatalogDictionaryCards
import com.ankiminer.android.ui.settings.CustomDictionaryImportCard
import com.ankiminer.android.ui.settings.DictionaryInventoryCard
import com.ankiminer.android.ui.settings.DictionaryLookupCard
import com.ankiminer.android.ui.settings.FrequencyImportCard
import com.ankiminer.android.ui.settings.KnownWordsImportCard
import com.ankiminer.android.ui.settings.NumericField
import com.ankiminer.android.ui.settings.PitchImportCard
import com.ankiminer.android.ui.settings.ResourceCard
import com.ankiminer.android.ui.settings.ResourceChainEditor
import com.ankiminer.android.ui.settings.SettingTextField
import com.ankiminer.android.ui.settings.SettingsSection
import com.ankiminer.android.ui.settings.SettingsSectionHeading
import com.ankiminer.android.ui.settings.SystemStatusCard
import com.ankiminer.android.ui.video.CurationCandidateUiState
import com.ankiminer.android.ui.video.CurationUiState
import com.ankiminer.android.ui.video.DocumentSlotState
import com.ankiminer.android.ui.video.MiningCommandError
import com.ankiminer.android.ui.video.VideoMiningUiState
import com.ankiminer.android.vm.SetupUiState

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
    FULL("full"),
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
                        candidates =
                            candidates.mapIndexed { index, candidate ->
                                CurationCandidateUiState(
                                    candidate = candidate,
                                    selected = index % 3 != 0,
                                    sentenceId =
                                        if (index % 4 == 0) {
                                            candidate.sentences.last().sentenceId
                                        } else {
                                            candidate.defaultSentenceId
                                        },
                                )
                            },
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
                        candidates =
                            candidates.mapIndexed { index, candidate ->
                                ReadingCurationCandidateUiState(
                                    candidate = candidate,
                                    selected = index % 4 != 0,
                                    sentenceId =
                                        if (index % 3 == 0) {
                                            candidate.sentences.last().sentenceId
                                        } else {
                                            candidate.defaultSentenceId
                                        },
                                )
                            },
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
                ),
            ),
        pitchAccent =
            InstalledPitchAccent(
                sourceName = "Kanjium pitch accents",
                sourceRevision = "2025-02",
                sourceFormat = "yomitan",
                entryCount = 163_284,
                fileSizeBytes = 8_412_391,
                schemaOk = true,
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
        customSlotId = "custom-classical-japanese",
        frequencySourceId = "jpdb-v2",
        frequencySourceName = "JPDB frequency 2025",
        pitchSourceName = "Kanjium pitch accents",
        audioPackId = "jpod101-extra",
        knownWordsSearch = "美",
    )
}

@Composable
internal fun UiAuditSettingsFixture(
    focus: SettingsAuditState,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val setup = setupAuditState()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .testTag(UiAuditTags.SETTINGS_SCROLL)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (focus) {
            SettingsAuditState.TOP,
            SettingsAuditState.ERROR_SNACKBAR,
            -> SettingsTopFixture(setup)
            SettingsAuditState.ANKI -> SettingsAnkiFixture(setup)
            SettingsAuditState.RESOURCES -> SettingsResourcesFixture(setup)
            SettingsAuditState.FULL -> {
                SettingsTopFixture(setup)
                SettingsAnkiFixture(setup)
                SettingsResourcesFixture(setup)
                SettingsExtraFixture()
            }
        }
    }
}

@Composable
private fun SettingsTopFixture(setup: SetupUiState) {
    Text(
        stringResource(R.string.settings_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(stringResource(R.string.settings_intro))
    SystemStatusCard(
        state = setup,
        onRefresh = {},
        onRequestPermissions = {},
        onOpenAppSettings = {},
        onInstallAnkiDroid = {},
        onOpenAnkiDroid = {},
    )
}

@Composable
private fun SettingsAnkiFixture(setup: SetupUiState) {
    Column(
        modifier = Modifier.testTag("ui_audit_settings_anki"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionHeading(stringResource(R.string.settings_anki_target))
        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                value = "Japanese::Mining",
                onChange = {},
                label = stringResource(R.string.settings_deck_name),
                supporting = stringResource(R.string.settings_deck_default),
            )
            Text(
                stringResource(R.string.settings_excluded_decks),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.settings_excluded_decks_help),
                style = MaterialTheme.typography.bodySmall,
            )
            setup.availableDeckNames.forEachIndexed { index, deck ->
                BooleanSetting(
                    label = deck,
                    help = "",
                    checked = index == 1,
                    onCheckedChange = {},
                )
            }
            BooleanSetting(
                label = stringResource(R.string.settings_tags_override),
                help = stringResource(R.string.settings_tags_override_help),
                checked = true,
                onCheckedChange = {},
            )
            SettingTextField(
                value = "ankiminer japanese subs2srs",
                onChange = {},
                label = stringResource(R.string.settings_tags),
                supporting = stringResource(R.string.settings_tags_help),
            )
        }
        AnkiTargetCard(
            state = setup,
            onSelectNoteType = {},
            onSetFieldMapping = { _, _ -> },
            onVerify = {},
        )
        AnkiRecoveryCard(
            state = setup,
            onRefresh = {},
            onReconcile = {},
            onRetryStaging = {},
            onAcknowledgeMedia = {},
            onAcknowledgeUncertainMedia = {},
            onResolveReview = { _, _ -> },
        )
    }
}

@Composable
private fun SettingsResourcesFixture(setup: SetupUiState) {
    Column(
        modifier = Modifier.testTag("ui_audit_settings_resources"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionHeading(stringResource(R.string.settings_dictionaries))
        ResourceCard(
            title = stringResource(R.string.unidic_resource_title),
            description = stringResource(R.string.unidic_resource_description),
            installed = true,
            busy = false,
            action = {},
            actionLabel = stringResource(R.string.unidic_repair),
        )
        CatalogDictionaryCards(setup, onInstall = {})
        CustomDictionaryImportCard(
            state = setup,
            onSlotChanged = {},
            onReplaceChanged = {},
            onImport = {},
        )
        PitchImportCard(
            state = setup,
            onNameChanged = {},
            onFormatChanged = {},
            onReplaceChanged = {},
            onImport = {},
        )
        SettingsSection(stringResource(R.string.settings_dictionary_chain)) {
            Text(
                stringResource(R.string.settings_dictionary_chain_help),
                style = MaterialTheme.typography.bodySmall,
            )
            ResourceChainEditor(
                choices =
                    setup.dictionaries.map { dictionary ->
                        ResourceChainSelection(dictionary.slotId)
                    },
                labels =
                    setup.dictionaries.associate { dictionary ->
                        dictionary.slotId to
                            "${dictionary.sourceName} (${dictionary.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_dictionaries),
                onChange = {},
            )
        }
        DictionaryInventoryCard(setup)
        DictionaryLookupCard(
            state = setup.copy(lookup = null),
            onTermChanged = {},
            onSelectSlot = {},
            onLookup = {},
        )
        SettingsSectionHeading(stringResource(R.string.settings_frequency_section))
        FrequencyImportCard(
            state = setup,
            onIdChanged = {},
            onNameChanged = {},
            onFormatChanged = {},
            onReplaceChanged = {},
            onImport = {},
        )
        SettingsSectionHeading(stringResource(R.string.settings_audio_section))
        AudioPackImportCard(
            state = setup,
            onIdChanged = {},
            onReplaceChanged = {},
            onImport = {},
        )
        BundledWordsetInventoryCard(setup)
        KnownWordsImportCard(
            state = setup,
            onFormatChanged = {},
            onImport = {},
            onConfirmImport = {},
            onDismissImport = {},
            onSearchChanged = {},
            onSearch = {},
            onLoadMore = {},
            onRemove = {},
            onExport = {},
            onReset = {},
        )
    }
}

@Composable
private fun SettingsExtraFixture() {
    SettingsSectionHeading(stringResource(R.string.settings_media))
    SettingsSection(stringResource(R.string.settings_media)) {
        NumericField(
            value = "0.25",
            onChange = {},
            label = stringResource(R.string.settings_audio_padding),
            supporting = stringResource(R.string.settings_audio_padding_default),
        )
        NumericField(
            value = "-0.15",
            onChange = {},
            label = stringResource(R.string.settings_subtitle_offset),
            supporting = stringResource(R.string.settings_subtitle_offset_default),
            allowNegative = true,
        )
    }
    SettingsSectionHeading(stringResource(R.string.settings_filtering))
    SettingsSection(stringResource(R.string.settings_filtering)) {
        BooleanSetting(
            label = stringResource(R.string.settings_known_words),
            help = stringResource(R.string.settings_known_words_inventory, 1_208, 4_917, 717),
            checked = true,
            onCheckedChange = {},
        )
        BooleanSetting(
            label = stringResource(R.string.settings_deduplicate),
            help = stringResource(R.string.settings_android_override),
            checked = true,
            onCheckedChange = {},
        )
        BooleanSetting(
            label = stringResource(R.string.settings_i_plus_one),
            help = stringResource(R.string.settings_default_off),
            checked = false,
            onCheckedChange = {},
        )
    }
    SettingsSectionHeading(stringResource(R.string.settings_ui_section))
    SettingsSection(stringResource(R.string.settings_ui_section)) {
        Text(stringResource(R.string.settings_theme))
        Text(stringResource(R.string.settings_theme_dark))
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
