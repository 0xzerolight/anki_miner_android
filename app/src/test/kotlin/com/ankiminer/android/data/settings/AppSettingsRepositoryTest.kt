package com.ankiminer.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiProviderReadService
import com.ankiminer.android.anki.provider.AnkiRunStateRegistry
import com.ankiminer.android.anki.provider.FakeAnkiProviderGateway
import com.ankiminer.android.anki.provider.FakeProviderCursor
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.anki.provider.ProviderEndpoint
import com.ankiminer.android.anki.provider.modelRow
import com.ankiminer.android.anki.provider.templateRow
import com.ankiminer.android.ui.settings.SettingsResetAction
import com.ankiminer.android.ui.settings.SettingsResetConfirmationState
import com.ankiminer.android.ui.settings.dispatchConfirmedSettingsReset
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `every persisted preference decodes and quarantines independently`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "corruption")
            val repository = DataStoreAppSettingsRepository(dataStore)
            val original = populatedSettings()
            repository.update(original)
            val validPreferences = dataStore.data.first()
            val cases = corruptionCases(original)

            assertEquals(
                DataStoreAppSettingsRepository.persistedPreferenceKeyNames,
                cases.mapTo(linkedSetOf(), CorruptionCase::keyName),
            )

            cases.forEach { case ->
                val corrupted =
                    validPreferences.toMutablePreferences().apply {
                        removeByName(case.keyName)
                        case.writeCorruptValue(this)
                    }.toPreferences()

                val migrated = DataStoreAppSettingsRepository.migratePreferences(corrupted)

                validPreferences.asMap().forEach { (key, value) ->
                    if (key.name != case.keyName) {
                        assertEquals("${case.keyName} removed ${key.name}", value, migrated.asMap()[key])
                    }
                }
                if (case.keyName == "settings_schema_version") {
                    assertEquals(
                        DataStoreAppSettingsRepository.CURRENT_SCHEMA_VERSION,
                        migrated[intPreferencesKey(case.keyName)],
                    )
                } else {
                    assertFalse(
                        "${case.keyName} was not quarantined",
                        migrated.asMap().keys.any { it.name == case.keyName },
                    )
                }
                assertEquals(
                    case.keyName,
                    case.expectedSettings,
                    DataStoreAppSettingsRepository.decodePreferences(migrated),
                )
            }
        }

    @Test
    fun `targeted migration removes only invalid keys and records schema version`() {
        val invalidNoteType = stringPreferencesKey("note_type")
        val deckName = stringPreferencesKey("deck_name")
        val unknownFutureKey = stringPreferencesKey("future_key")
        val preferences =
            preferencesOf(
                deckName to "Japanese",
                invalidNoteType to " Lapis",
                unknownFutureKey to "preserve me",
            )

        val migrated = DataStoreAppSettingsRepository.migratePreferences(preferences)

        assertEquals("Japanese", migrated[deckName])
        assertFalse(migrated.contains(invalidNoteType))
        assertEquals("preserve me", migrated[unknownFutureKey])
        assertEquals(
            DataStoreAppSettingsRepository.CURRENT_SCHEMA_VERSION,
            migrated[intPreferencesKey("settings_schema_version")],
        )
    }

    @Test
    fun `failed repository DataStore write preserves the complete prior store`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "failed-write")
            val original = populatedSettings()
            DataStoreAppSettingsRepository(dataStore).update(original)
            val priorPreferences = dataStore.data.first()
            val failingRepository =
                DataStoreAppSettingsRepository(AbortAfterTransformDataStore(dataStore))

            try {
                failingRepository.update(
                    original.copy(
                        deckName = "After",
                        noteType = null,
                        tags = null,
                        dictionarySources = emptyList(),
                    ),
                )
                fail("Expected simulated write failure")
            } catch (failure: SimulatedInterruption) {
                assertSame(SimulatedInterruption, failure)
            }

            assertEquals(priorPreferences.asMap(), dataStore.data.first().asMap())
            assertEquals(original, failingRepository.settings.first())
        }

    @Test
    fun `restore mining defaults changes only mining settings`() {
        val original = populatedSettings()

        val restored = original.restoreMiningDefaults()

        assertEquals(
            original.copy(
                tags = null,
                audioPaddingSeconds = null,
                screenshotOffsetSeconds = null,
                subtitleOffsetSeconds = null,
                audioFormat = null,
                audioBitrateKbps = null,
                useKnownWordsDatabase = null,
                excludeHiraganaOnly = null,
                excludeKatakanaOnly = null,
                boldTargetInSentence = null,
                deduplicateSentences = null,
                useIPlusOneFilter = null,
                useSentenceLengthFilter = null,
                maxSentenceDurationSeconds = null,
                maxSentenceCharacters = null,
                readingMinimumOccurrence = null,
                maxFrequencyRank = null,
                pitchCategoryFormat = null,
                maxParallelWorkers = null,
                readingTtsEnabled = false,
            ),
            restored,
        )
        assertTrue(restored.setupWizardSeen)
        assertEquals("Japanese", restored.deckName)
        assertEquals("Lapis", restored.noteType)
        assertEquals(original.fieldMap, restored.fieldMap)
    }

    @Test
    fun `reset Anki target changes only deck note type and field map`() {
        val original = populatedSettings()

        val reset = original.resetAnkiTarget()

        assertEquals(
            original.copy(deckName = null, noteType = null, fieldMap = emptyMap()),
            reset,
        )
    }

    @Test
    fun `reset resource choices changes only resource selections`() {
        val original = populatedSettings()

        val reset =
            original.resetResourceChoices(
                dictionaryIds = listOf("jitendex"),
                frequencyIds = listOf("bccwj"),
                audioPackIds = listOf("local-audio"),
            )

        assertEquals(
            original.copy(
                dictionarySources = listOf(ResourceChainSelection("jitendex", enabled = false)),
                frequencySources = listOf(ResourceChainSelection("bccwj", enabled = false)),
                audioPacks = listOf(ResourceChainSelection("local-audio", enabled = false)),
                excludedWordsets = emptyList(),
                jishoEnabled = false,
            ),
            reset,
        )
    }

    @Test
    fun `cancelled reset leaves every callback and persisted store untouched for every action`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "cancelled-reset")
            val repository = DataStoreAppSettingsRepository(dataStore)
            val original = populatedSettings()
            repository.update(original)

            SettingsResetAction.entries.forEach { requestedAction ->
                val callbackCounts = SettingsResetAction.entries.associateWith { 0 }.toMutableMap()
                val requested = SettingsResetConfirmationState().request(requestedAction)
                val cancelled = requested.cancel()
                val (finalState, confirmedAction) = cancelled.confirm()

                dispatchConfirmedSettingsReset(
                    action = confirmedAction,
                    onRestoreMiningDefaults = {
                        callbackCounts[SettingsResetAction.RESTORE_MINING_DEFAULTS] = 1
                        launch { repository.update(AppSettings::restoreMiningDefaults) }
                    },
                    onResetAnkiTarget = {
                        callbackCounts[SettingsResetAction.RESET_ANKI_TARGET] = 1
                        launch { repository.update(AppSettings::resetAnkiTarget) }
                    },
                    onResetResourceChoices = {
                        callbackCounts[SettingsResetAction.RESET_RESOURCE_CHOICES] = 1
                        launch {
                            repository.update { current ->
                                current.resetResourceChoices(
                                    dictionaryIds = listOf("jitendex"),
                                    frequencyIds = listOf("bccwj"),
                                    audioPackIds = listOf("local-audio"),
                                )
                            }
                        }
                    },
                )
                advanceUntilIdle()

                assertNull(requestedAction.name, finalState.pendingAction)
                assertNull(requestedAction.name, confirmedAction)
                assertEquals(
                    requestedAction.name,
                    SettingsResetAction.entries.associateWith { 0 },
                    callbackCounts,
                )
                assertEquals(requestedAction.name, original, repository.settings.first())
            }
        }

    @Test
    fun `legacy duplicate raw field map is quarantined without erasing unrelated settings`() {
        val preferences =
            preferencesOf(
                booleanPreferencesKey("setup_wizard_seen") to true,
                stringPreferencesKey("theme_mode") to "light",
                stringPreferencesKey("deck_name") to "Japanese",
                stringPreferencesKey("note_type") to "Lapis",
                stringPreferencesKey("field_map_v1") to
                    "field-map-v1\nword=Expression\nsentence=Expression\n",
                stringPreferencesKey("tags") to "mined japanese",
                stringPreferencesKey("dictionary_sources_v1") to
                    "resource-selection-v1\n+jitendex\n-custom-dictionary\n",
                stringPreferencesKey("frequency_sources_v1") to
                    "resource-selection-v1\n+bccwj\n",
                stringPreferencesKey("audio_packs_v1") to
                    "resource-selection-v1\n+local-audio\n",
                stringPreferencesKey("excluded_wordsets_v1") to
                    "resource-selection-v1\n+place-names\n",
                booleanPreferencesKey("reading_tts_enabled") to true,
                booleanPreferencesKey("jisho_enabled") to true,
            )

        val settings = DataStoreAppSettingsRepository.decodePreferences(preferences)

        assertTrue(settings.setupWizardSeen)
        assertEquals(ThemeMode.LIGHT, settings.theme)
        assertEquals("Japanese", settings.deckName)
        assertEquals("Lapis", settings.noteType)
        assertTrue(settings.fieldMap.isEmpty())
        assertEquals("mined japanese", settings.tags)
        assertEquals(
            listOf(
                ResourceChainSelection("jitendex"),
                ResourceChainSelection("custom-dictionary", enabled = false),
            ),
            settings.dictionarySources,
        )
        assertEquals(listOf(ResourceChainSelection("bccwj")), settings.frequencySources)
        assertEquals(listOf(ResourceChainSelection("local-audio")), settings.audioPacks)
        assertEquals(listOf("place-names"), settings.excludedWordsets)
        assertTrue(settings.readingTtsEnabled)
        assertTrue(settings.jishoEnabled)

        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(modelRow(name = "Lapis", fields = "Expression\u001fMeaning")),
                    )
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                else -> error("unexpected query $query")
            }
        }
        val status =
            AnkiProviderReadService(gateway, AnkiRunStateRegistry())
                .verifyUserNoteType(
                    requireNotNull(settings.noteType),
                    settings.fieldMap,
                    AnkiCancellation.NONE,
                )

        assertEquals(
            NoteTypeSetupStatus.FieldMapInvalid(
                destination = "Expression",
                logicalKeys = listOf(AnkiFieldKeys.WORD),
            ),
            status,
        )
        assertTrue(gateway.noteCommands.isEmpty())
    }

    private fun populatedSettings() =
        AppSettings(
            setupWizardSeen = true,
            theme = ThemeMode.LIGHT,
            deckName = "Japanese",
            noteType = "Lapis",
            fieldMap =
                mapOf(
                    AnkiFieldKeys.WORD to "Expression",
                    "sentence" to "Sentence",
                ),
            tags = "mined japanese",
            audioPaddingSeconds = 0.1,
            screenshotOffsetSeconds = 0.2,
            subtitleOffsetSeconds = -0.3,
            audioFormat = AudioFormat.OPUS,
            audioBitrateKbps = 96,
            useKnownWordsDatabase = true,
            excludeHiraganaOnly = true,
            excludeKatakanaOnly = true,
            boldTargetInSentence = true,
            deduplicateSentences = false,
            useIPlusOneFilter = true,
            useSentenceLengthFilter = true,
            maxSentenceDurationSeconds = 8.0,
            maxSentenceCharacters = 48,
            readingMinimumOccurrence = 2,
            maxFrequencyRank = 10_000,
            pitchCategoryFormat = PitchCategoryFormat.ROMAJI,
            maxParallelWorkers = 3,
            dictionarySources = listOf(ResourceChainSelection("jitendex")),
            frequencySources = listOf(ResourceChainSelection("bccwj")),
            audioPacks = listOf(ResourceChainSelection("local-audio")),
            excludedWordsets = listOf("place-names"),
            readingTtsEnabled = true,
            jishoEnabled = true,
        )

    private fun createDataStore(
        scope: CoroutineScope,
        name: String,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
        )

    private fun corruptionCases(original: AppSettings): List<CorruptionCase> {
        val defaults = AppSettings()
        return listOf(
            corruptInt("settings_schema_version", original),
            corruptBoolean("setup_wizard_seen", original.copy(setupWizardSeen = defaults.setupWizardSeen)),
            corruptString("theme_mode", original.copy(theme = defaults.theme)),
            corruptString("deck_name", original.copy(deckName = defaults.deckName)),
            corruptString("note_type", original.copy(noteType = defaults.noteType)),
            corruptString("field_map_v1", original.copy(fieldMap = defaults.fieldMap)),
            corruptString("tags", original.copy(tags = defaults.tags)),
            corruptDouble(
                "audio_padding_seconds",
                original.copy(audioPaddingSeconds = defaults.audioPaddingSeconds),
            ),
            corruptDouble(
                "screenshot_offset_seconds",
                original.copy(screenshotOffsetSeconds = defaults.screenshotOffsetSeconds),
            ),
            corruptDouble(
                "subtitle_offset_seconds",
                original.copy(subtitleOffsetSeconds = defaults.subtitleOffsetSeconds),
            ),
            corruptString("audio_format", original.copy(audioFormat = defaults.audioFormat)),
            corruptInt(
                "audio_bitrate_kbps",
                original.copy(audioBitrateKbps = defaults.audioBitrateKbps),
            ),
            corruptBoolean(
                "use_known_words_database",
                original.copy(useKnownWordsDatabase = defaults.useKnownWordsDatabase),
            ),
            corruptBoolean(
                "exclude_hiragana_only",
                original.copy(excludeHiraganaOnly = defaults.excludeHiraganaOnly),
            ),
            corruptBoolean(
                "exclude_katakana_only",
                original.copy(excludeKatakanaOnly = defaults.excludeKatakanaOnly),
            ),
            corruptBoolean(
                "bold_target",
                original.copy(boldTargetInSentence = defaults.boldTargetInSentence),
            ),
            corruptBoolean(
                "deduplicate_sentences",
                original.copy(deduplicateSentences = defaults.deduplicateSentences),
            ),
            corruptBoolean(
                "use_i_plus_one",
                original.copy(useIPlusOneFilter = defaults.useIPlusOneFilter),
            ),
            corruptBoolean(
                "use_sentence_length",
                original.copy(useSentenceLengthFilter = defaults.useSentenceLengthFilter),
            ),
            corruptDouble(
                "max_sentence_duration_seconds",
                original.copy(maxSentenceDurationSeconds = defaults.maxSentenceDurationSeconds),
            ),
            corruptInt(
                "max_sentence_characters",
                original.copy(maxSentenceCharacters = defaults.maxSentenceCharacters),
            ),
            corruptInt(
                "reading_minimum_occurrence",
                original.copy(readingMinimumOccurrence = defaults.readingMinimumOccurrence),
            ),
            corruptInt(
                "max_frequency_rank",
                original.copy(maxFrequencyRank = defaults.maxFrequencyRank),
            ),
            corruptString(
                "pitch_category_format",
                original.copy(pitchCategoryFormat = defaults.pitchCategoryFormat),
            ),
            corruptInt(
                "max_parallel_workers",
                original.copy(maxParallelWorkers = defaults.maxParallelWorkers),
            ),
            corruptString(
                "dictionary_sources_v1",
                original.copy(dictionarySources = defaults.dictionarySources),
            ),
            corruptString(
                "frequency_sources_v1",
                original.copy(frequencySources = defaults.frequencySources),
            ),
            corruptString("audio_packs_v1", original.copy(audioPacks = defaults.audioPacks)),
            corruptString(
                "excluded_wordsets_v1",
                original.copy(excludedWordsets = defaults.excludedWordsets),
            ),
            corruptBoolean(
                "reading_tts_enabled",
                original.copy(readingTtsEnabled = defaults.readingTtsEnabled),
            ),
            corruptBoolean("jisho_enabled", original.copy(jishoEnabled = defaults.jishoEnabled)),
        )
    }

    private fun corruptBoolean(
        keyName: String,
        expectedSettings: AppSettings,
    ) = CorruptionCase(keyName, expectedSettings) { it[stringPreferencesKey(keyName)] = "corrupt" }

    private fun corruptDouble(
        keyName: String,
        expectedSettings: AppSettings,
    ) = CorruptionCase(keyName, expectedSettings) { it[stringPreferencesKey(keyName)] = "corrupt" }

    private fun corruptInt(
        keyName: String,
        expectedSettings: AppSettings,
    ) = CorruptionCase(keyName, expectedSettings) { it[stringPreferencesKey(keyName)] = "corrupt" }

    private fun corruptString(
        keyName: String,
        expectedSettings: AppSettings,
    ) = CorruptionCase(keyName, expectedSettings) { it[intPreferencesKey(keyName)] = Int.MIN_VALUE }

    private fun MutablePreferences.removeByName(keyName: String) {
        asMap().keys.single { it.name == keyName }.let { this -= it }
    }

    private data class CorruptionCase(
        val keyName: String,
        val expectedSettings: AppSettings,
        val writeCorruptValue: (MutablePreferences) -> Unit,
    )

    private class AbortAfterTransformDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { current ->
                transform(current)
                throw SimulatedInterruption
            }
    }

    private object SimulatedInterruption : RuntimeException()
}
