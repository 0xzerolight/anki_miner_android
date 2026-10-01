package com.ankiminer.android.ui.settings

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusCardTest {
    @Test
    fun readyStateShowsOnlyTheSummary() {
        val result = setupTaskStatus(SetupTaskFacts.ready())

        assertEquals(SetupSummaryKind.READY, result.summary)
        assertEquals(0, result.requiredAttentionCount)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun optionalNotificationWarningDoesNotMakeMiningSetupIncomplete() {
        val result = setupTaskStatus(SetupTaskFacts.ready().copy(notificationReady = false))

        assertEquals(SetupSummaryKind.READY_WITH_OPTIONAL_WARNING, result.summary)
        assertEquals(0, result.requiredAttentionCount)
        assertEquals(listOf(SetupTaskRow(SetupTaskId.NOTIFICATIONS, SetupTaskRole.OPTIONAL_WARNING)), result.rows)
    }

    @Test
    fun aFailingAnkiDroidHidesTheRowsItExplains() {
        val result =
            setupTaskStatus(
                SetupTaskFacts(
                    ankiReady = false,
                    noteTypeReady = false,
                    recoveryReady = false,
                    uniDicReady = false,
                    notificationReady = true,
                    checking = false,
                ),
            )

        assertEquals(SetupSummaryKind.ATTENTION, result.summary)
        assertEquals(listOf(SetupTaskId.ANKIDROID, SetupTaskId.UNIDIC), result.rows.map { it.id })
        assertEquals(2, result.requiredAttentionCount)
        assertTrue(result.rows.all { it.role == SetupTaskRole.REQUIRED_ACTION })
        assertTrue(result.rows.none { it.inProgress })
    }

    @Test
    fun aBrokenRuntimeHidesTheResourceRowsItExplains() {
        val result =
            setupTaskStatus(
                SetupTaskFacts.ready().copy(runtimeReady = false, dictionaryReady = false, uniDicReady = false),
            )

        assertEquals(listOf(SetupTaskId.RUNTIME), result.rows.map { it.id })
    }

    @Test
    fun aLanguageWithoutUniDicNeverListsIt() {
        val result = setupTaskStatus(SetupTaskFacts.ready().copy(uniDicRequired = false, uniDicReady = false))

        assertEquals(SetupSummaryKind.READY, result.summary)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun onlyStartupMakesTheWholeCardWait() {
        val result = setupTaskStatus(SetupTaskFacts.ready().copy(checking = true, dictionaryReady = false))

        assertEquals(SetupSummaryKind.BUSY, result.summary)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun aRunningDownloadLeavesTheOtherRowsJudged() {
        val downloading =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                miningTarget = AnkiMiningTargetReadiness.Ready,
                uniDicInstalled = true,
                operation = ResourceOperationProgress("op", "JMdict", ResourceOperationPhase.DOWNLOADING),
            )

        assertEquals(listOf(SetupTaskId.DICTIONARY), downloading.setupTaskStatus().rows.map { it.id })
        assertEquals(1, downloading.setupAttentionCount())
        // The running download marks only its own row.
        assertTrue(downloading.setupTaskStatus().rows.single().inProgress)
        val ankiAlsoMissing = downloading.copy(anki = AnkiProviderReadiness.NotInstalled)
        assertFalse(ankiAlsoMissing.setupTaskStatus().rows.single { it.id == SetupTaskId.ANKIDROID }.inProgress)
    }
}
