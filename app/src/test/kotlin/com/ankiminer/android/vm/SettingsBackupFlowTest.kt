package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.KnownWordsImportPreview
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.SettingsBackupCodec
import com.ankiminer.android.data.settings.SettingsBackupWriter
import com.ankiminer.android.data.settings.SettingsDocumentReader
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.localization.LocalizedStringResource
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Saving and loading a settings file through [SettingsViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBackupFlowTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a failed save is reported as a failed save`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                viewModel(
                    SessionSettingsRepository(AppSettings()),
                    writer = SettingsBackupWriter { _, _ -> throw IOException("drive offline") },
                )
            advanceUntilIdle()

            viewModel.exportSettings("content://out.json")

            assertEquals(
                SettingsBackupState.Failed(
                    LocalizedStringResource(R.string.settings_backup_export_failed),
                    SettingsBackupOperation.EXPORT,
                ),
                viewModel.settle(),
            )
        }

    @Test
    fun `a failed load is reported as a failed load`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                viewModel(SessionSettingsRepository(AppSettings()), document = """{"hello":"world"}""")
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")

            assertEquals(
                SettingsBackupState.Failed(
                    LocalizedStringResource(R.string.settings_backup_not_a_backup),
                    SettingsBackupOperation.IMPORT,
                ),
                viewModel.settle(),
            )
        }

    @Test
    fun `a save before the inventory was read is refused, not written without match keys`() =
        runTest(mainDispatcherRule.dispatcher) {
            var written = false
            val viewModel =
                viewModel(
                    SessionSettingsRepository(
                        AppSettings(dictionarySources = listOf(ResourceChainSelection("jitendex", enabled = true))),
                    ),
                    // Recovery failed before it read the lists: they are empty, not "nothing installed".
                    resources = ResourceManagerState(startupReadiness = ResourceStartupReadiness.FAILED),
                    writer = SettingsBackupWriter { _, _ -> written = true },
                )
            advanceUntilIdle()

            viewModel.exportSettings("content://out.json")

            assertEquals(
                SettingsBackupState.Failed(
                    LocalizedStringResource(R.string.settings_backup_export_not_ready),
                    SettingsBackupOperation.EXPORT,
                ),
                viewModel.settle(),
            )
            assertFalse(written)
        }

    @Test
    fun `a save after recovery failed on a broken slot still carries every key it can`() =
        runTest(mainDispatcherRule.dispatcher) {
            val output = StringBuilder()
            val viewModel =
                viewModel(
                    SessionSettingsRepository(
                        AppSettings(
                            dictionarySources =
                                listOf(
                                    ResourceChainSelection("jitendex", enabled = true),
                                    ResourceChainSelection("broken", enabled = true),
                                ),
                        ),
                    ),
                    // Recovery published the lists, then failed on the broken slot.
                    resources =
                        ResourceManagerState(
                            startupReadiness = ResourceStartupReadiness.FAILED,
                            dictionaries = listOf(dictionary("jitendex", valid = true), dictionary("broken", valid = false)),
                        ),
                    writer = SettingsBackupWriter { _, bytes -> output.append(bytes.toString(Charsets.UTF_8)) },
                )
            advanceUntilIdle()

            viewModel.exportSettings("content://out.json")

            assertEquals(SettingsBackupState.Exported, viewModel.settle())
            val chain = SettingsBackupCodec.parse(output.toString()).resourceChains.getValue("dictionary_sources_v1")
            assertEquals(listOf("jitendex", "broken"), chain.map { it.resourceId })
            assertNotNull(chain[0].matchKey)
            assertNull(chain[1].matchKey)
        }

    @Test
    fun `a load before the inventory has loaded keeps this device's chains`() =
        runTest(mainDispatcherRule.dispatcher) {
            val chains =
                listOf(
                    ResourceChainSelection("jitendex", enabled = true),
                    ResourceChainSelection("jmdict", enabled = false),
                )
            val repository = SessionSettingsRepository(AppSettings(dictionarySources = chains))
            val viewModel =
                viewModel(
                    repository,
                    resources = ResourceManagerState(startupReadiness = ResourceStartupReadiness.PENDING),
                    document = JAPANESE_EMPTY_CHAINS_BACKUP,
                )
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            val state = viewModel.settle()
            advanceUntilIdle()

            assertEquals(chains, repository.current.dictionarySources)
            assertEquals(ThemeMode.DARK, repository.current.theme)
            assertEquals(SettingsBackupState.Imported(applied = 2, ignored = 0, rejected = 4), state)
        }

    private fun dictionary(
        id: String,
        valid: Boolean,
    ): InstalledDictionary =
        InstalledDictionary(
            slotId = id,
            occupied = true,
            valid = valid,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
            rebuildSourcePath = null,
        )

    @Test
    fun `a file for another language parks this language's settings, as a switch does`() =
        runTest(mainDispatcherRule.dispatcher) {
            val japanese =
                AppSettings(
                    deckName = "JP::Mining",
                    noteType = "Lapis",
                    fieldMap = mapOf("word" to "Word", "sentence" to "Sentence"),
                    jishoEnabled = true,
                )
            val repository = SessionSettingsRepository(japanese)
            val viewModel = viewModel(repository, document = backup(HEBREW))
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            val state = viewModel.settle()
            advanceUntilIdle()

            assertTrue(state.toString(), state is SettingsBackupState.Imported)
            assertEquals("he", repository.current.language)
            assertEquals("Hebrew", repository.current.deckName)
            assertEquals(ThemeMode.DARK, repository.current.theme)

            // Back through the Language tab: everything Japanese had is still there.
            assertTrue(viewModel.switchLanguage("ja"))
            advanceUntilIdle()
            assertEquals("JP::Mining", repository.current.deckName)
            assertEquals("Lapis", repository.current.noteType)
            assertEquals(japanese.fieldMap, repository.current.fieldMap)
            assertTrue(repository.current.jishoEnabled)
        }

    @Test
    fun `pending edits are parked with the outgoing language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            val viewModel = viewModel(repository, document = backup(HEBREW))
            advanceUntilIdle()

            // A typed rank is debounced: it is still only in the draft when the import starts.
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(maxFrequency = "30000"))
            runCurrent()
            assertEquals(null, repository.current.maxFrequencyRank)

            viewModel.importSettings("content://in.json")
            viewModel.settle()
            advanceUntilIdle()

            assertEquals("he", repository.current.language)
            assertEquals(30000, repository.current.languageStash["ja"]?.get("max_frequency_rank"))
        }

    @Test
    fun `an open known-words preview refuses a file for another language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preview =
                KnownWordsImportPreview(
                    format = "plain",
                    importedCount = 1,
                    totalEntries = 1,
                    isGeneric = true,
                    sampleWords = listOf("猫"),
                )
            assertRefused(
                resources = READY_INVENTORY.copy(knownWordsImportPreview = preview),
                expected = LocalizedStringResource(R.string.language_switch_blocked_known_words),
            )
        }

    @Test
    fun `a restored pending known-words import refuses a file for another language`() =
        runTest(mainDispatcherRule.dispatcher) {
            // What recovery records when it restores an import interrupted by process death.
            val restored =
                READY_INVENTORY.copy(
                    failure =
                        ResourceFailure(
                            code = "resource_operation_interrupted",
                            message = "interrupted",
                            retryable = true,
                            origin = ResourceFailureOrigin.KNOWN_WORDS,
                            retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                            knownWordsOperation = KnownWordsFailureOperation.IMPORT,
                        ),
                )
            assertRefused(
                resources = restored,
                expected = LocalizedStringResource(R.string.language_switch_blocked_known_words),
            )
        }

    @Test
    fun `a running task refuses a file for another language`() =
        runTest(mainDispatcherRule.dispatcher) {
            assertRefused(
                runtimeWorkState = MutableStateFlow(RuntimeWorkCoordinator.Kind.MINING),
                expected = LocalizedStringResource(R.string.settings_backup_language_busy, listOf("he")),
            )
        }

    @Test
    fun `a file for a language whose data is missing is refused`() =
        runTest(mainDispatcherRule.dispatcher) {
            // ar reads language_data_required in the contract fixture.
            assertRefused(
                document = backup(ARABIC),
                expected = LocalizedStringResource(R.string.settings_backup_language_unavailable, listOf("ar")),
            )
        }

    @Test
    fun `a file for a language this build cannot mine loads the rest and keeps the language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings(deckName = "JP::Mining"))
            val viewModel =
                viewModel(
                    repository,
                    document = backup(ARABIC),
                    // UNSUPPORTED: no download makes it minable, so it is no switch target at all.
                    profiles =
                        LanguageProfileFixtures.all.map { profile ->
                            if (profile.code == "ar") {
                                profile.copy(unavailableReason = LanguageUnavailableReason.UNSUPPORTED)
                            } else {
                                profile
                            }
                        },
                )
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            val state = viewModel.settle()
            advanceUntilIdle()

            assertEquals("ja", repository.current.language)
            assertEquals("JP::Mining", repository.current.deckName)
            assertEquals(ThemeMode.DARK, repository.current.theme)
            assertEquals("ar", (state as SettingsBackupState.Imported).unknownLanguage)
        }

    /**
     * The Import button is disabled while busy; a picker opened before the task started still
     * delivers its file, and a file in the current language switches nothing, so it loads.
     */
    @Test
    fun `a same-language file already picked still loads while a task runs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            val viewModel =
                viewModel(
                    repository,
                    document = JAPANESE_EMPTY_CHAINS_BACKUP,
                    runtimeWorkState = MutableStateFlow(RuntimeWorkCoordinator.Kind.MINING),
                )
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            val state = viewModel.settle()
            advanceUntilIdle()

            assertEquals(SettingsBackupState.Imported(applied = 6, ignored = 0, rejected = 0), state)
            assertEquals(ThemeMode.DARK, repository.current.theme)
        }

    @Test
    fun `the Language tab's switch is refused while a task holds the runtime`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            val viewModel =
                viewModel(repository, runtimeWorkState = MutableStateFlow(RuntimeWorkCoordinator.Kind.RESOURCE))
            advanceUntilIdle()

            assertFalse(viewModel.switchLanguage("he"))
            advanceUntilIdle()
            assertEquals("ja", repository.current.language)
        }

    /** Import a Hebrew file (by default) onto Japanese settings and expect a refusal, nothing written. */
    private suspend fun TestScope.assertRefused(
        expected: LocalizedStringResource,
        resources: ResourceManagerState = READY_INVENTORY,
        runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
        document: String = backup(HEBREW),
    ) {
        val repository = SessionSettingsRepository(AppSettings(deckName = "JP::Mining"))
        val viewModel =
            viewModel(repository, resources = resources, document = document, runtimeWorkState = runtimeWorkState)
        advanceUntilIdle()
        val writesBefore = repository.writeAttempts

        viewModel.importSettings("content://in.json")

        assertEquals(SettingsBackupState.Failed(expected, SettingsBackupOperation.IMPORT), viewModel.settle())
        advanceUntilIdle()
        assertEquals(writesBefore, repository.writeAttempts)
        assertEquals("ja", repository.current.language)
        assertEquals("JP::Mining", repository.current.deckName)
    }

    private fun backup(settings: AppSettings): String =
        SettingsBackupCodec.encode(settings, "0.9.0", READY_INVENTORY)

    private fun viewModel(
        repository: SessionSettingsRepository,
        resources: ResourceManagerState = READY_INVENTORY,
        document: String = "",
        writer: SettingsBackupWriter = SettingsBackupWriter { _, _ -> },
        runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
        profiles: List<LanguageProfileInfo> = LanguageProfileFixtures.all,
    ): SettingsViewModel =
        SettingsViewModel(
            repository = repository,
            resources = SessionResourceManager(resources),
            documentReader = SettingsDocumentReader { document },
            backupWriter = writer,
            languageProfileSource = { Result.success(profiles) },
            runtimeWorkState = runtimeWorkState,
        )

    /** The state the started save or load ends in; call right after starting one. */
    private suspend fun SettingsViewModel.settle(): SettingsBackupState =
        backupState.first { it !is SettingsBackupState.Working }

    private companion object {
        val READY_INVENTORY = ResourceManagerState(startupReadiness = ResourceStartupReadiness.READY)

        val HEBREW = AppSettings(language = "he", deckName = "Hebrew", theme = ThemeMode.DARK)

        val ARABIC = AppSettings(language = "ar", deckName = "Arabic", theme = ThemeMode.DARK)

        /** A format-5 Japanese file whose four chains are empty, as an export with nothing installed writes. */
        val JAPANESE_EMPTY_CHAINS_BACKUP =
            """{"ankiMinerAndroidSettings":5,"appVersion":"0.9.0","schemaVersion":3,""" +
                """"settings":{"mining_language":"ja","theme_mode":"dark","dictionary_sources_v1":null,""" +
                """"frequency_sources_v1":null,"pitch_sources_v1":null,"audio_packs_v1":null},""" +
                """"resourceChains":{"dictionary_sources_v1":[],"frequency_sources_v1":[],""" +
                """"pitch_sources_v1":[],"audio_packs_v1":[]}}"""
    }
}
