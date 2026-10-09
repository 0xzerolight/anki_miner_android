package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.SettingsBackupWriter
import com.ankiminer.android.data.settings.SettingsDocumentReader
import com.ankiminer.android.localization.LocalizedStringResource
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    }
}
