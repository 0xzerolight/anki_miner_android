package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.SettingsBackupCodec
import com.ankiminer.android.data.settings.SettingsBackupWriter
import com.ankiminer.android.data.settings.SettingsDocumentReader
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.localization.LocalizedStringResource
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun viewModel(
        repository: SessionSettingsRepository,
        resources: ResourceManagerState = READY_INVENTORY,
        document: String = "",
        writer: SettingsBackupWriter = SettingsBackupWriter { _, _ -> },
    ): SettingsViewModel =
        SettingsViewModel(
            repository = repository,
            resources = SessionResourceManager(resources),
            documentReader = SettingsDocumentReader { document },
            backupWriter = writer,
            languageProfileSource = { Result.success(LanguageProfileFixtures.all) },
        )

    /** The state the started save or load ends in; call right after starting one. */
    private suspend fun SettingsViewModel.settle(): SettingsBackupState =
        backupState.first { it !is SettingsBackupState.Working }

    private companion object {
        val READY_INVENTORY = ResourceManagerState(startupReadiness = ResourceStartupReadiness.READY)

        /** A format-5 Japanese file whose four chains are empty, as an export with nothing installed writes. */
        val JAPANESE_EMPTY_CHAINS_BACKUP =
            """{"ankiMinerAndroidSettings":5,"appVersion":"0.9.0","schemaVersion":3,""" +
                """"settings":{"mining_language":"ja","theme_mode":"dark","dictionary_sources_v1":null,""" +
                """"frequency_sources_v1":null,"pitch_sources_v1":null,"audio_packs_v1":null},""" +
                """"resourceChains":{"dictionary_sources_v1":[],"frequency_sources_v1":[],""" +
                """"pitch_sources_v1":[],"audio_packs_v1":[]}}"""
    }
}
