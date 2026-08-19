package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationSummary
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.anki.AnkiSetupOperation
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {
    @Test
    fun verifiedUniDicAndAUsableDictionaryAreBothRequired() {
        val recovered =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/private/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(2, 24L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                miningTarget = AnkiMiningTargetReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                uniDicInstalled = true,
                dictionaries = listOf(usableDictionary()),
            )

        assertTrue(recovered.targetReady)
        assertTrue(recovered.isMiningReady)
        assertTrue(
            recovered.copy(
                notifications = NotificationPermissionReadiness.PERMISSION_DENIED,
            ).isMiningReady,
        )
        assertFalse(recovered.copy(uniDicInstalled = false).isMiningReady)
        // The engine raises SetupError before any work when no offline dictionary can serve the
        // run, so "ready" without one hands the user a Start button that always fails.
        assertFalse(recovered.copy(dictionaries = emptyList()).isMiningReady)
        assertFalse(
            recovered.copy(dictionaries = listOf(usableDictionary().copy(valid = false)))
                .isMiningReady,
        )
        assertFalse(
            recovered.copy(dictionaries = listOf(usableDictionary().copy(schemaOk = false)))
                .isMiningReady,
        )
        assertFalse(
            recovered.copy(dictionaries = listOf(usableDictionary().copy(occupied = false)))
                .isMiningReady,
        )
        val fieldsMissing =
            recovered.copy(noteTypeStatus = NoteTypeSetupStatus.FieldsMissing(listOf("sentence")))
        assertFalse(fieldsMissing.targetReady)
        assertFalse(fieldsMissing.isMiningReady)
        val admissionBlocked =
            recovered.copy(
                miningTarget =
                    AnkiMiningTargetReadiness.Blocked(
                        message = "Recovery item pending",
                        retryable = true,
                    ),
            )
        assertFalse(admissionBlocked.targetReady)
        assertFalse(admissionBlocked.isMiningReady)
        assertFalse(
            recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED).isMiningReady,
        )
        assertFalse(recovered.copy(ankiOperation = AnkiSetupOperation.REFRESHING).isMiningReady)
        assertTrue(recovered.copy(resourceStartup = ResourceStartupReadiness.PENDING).busy)
        assertTrue(recovered.copy(resourceStartup = ResourceStartupReadiness.RECOVERING).busy)
        // FAILED is terminal, not in-progress: it must not report busy, or the WAIT
        // branch would shadow its own CHECK_AGAIN action and strand the user.
        val startupFailed = recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED)
        assertFalse(startupFailed.busy)
        assertEquals(MiningReadinessAction.CHECK_AGAIN, startupFailed.miningReadinessAction)
        assertTrue(
            recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING).isMiningReady,
        )
        assertFalse(
            recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.RESOURCE).isMiningReady,
        )
        assertTrue(recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING).busy)
        val pending =
            AnkiPendingRemediation(
                id = 1L,
                type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
                summaryReason = AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN,
                title = "Media save needs review",
                summary = "Review the media write",
                compactEvidence = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                availableActions = emptySet<AnkiRemediationActionKind>(),
            )
        assertFalse(
            recovered.copy(remediations = AnkiRemediationInventory(listOf(pending))).isMiningReady,
        )
    }

    @Test
    fun aMissingDictionaryOffersItsOwnActionWithoutOutrankingStartup() {
        val ready =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/private/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(2, 24L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                miningTarget = AnkiMiningTargetReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                uniDicInstalled = true,
                dictionaries = emptyList(),
            )

        assertFalse(ready.dictionaryReady)
        assertEquals(MiningReadinessAction.INSTALL_DICTIONARY, ready.miningReadinessAction)
        // UniDic is the tokenizer the dictionary index is built against, so it stays ahead.
        assertEquals(
            MiningReadinessAction.INSTALL_UNIDIC,
            ready.copy(uniDicInstalled = false).miningReadinessAction,
        )
        // The inventory is not loaded yet during startup: offering an install there would send
        // the user to fix something the app has not finished looking for.
        assertEquals(
            MiningReadinessAction.WAIT,
            ready.copy(resourceStartup = ResourceStartupReadiness.PENDING).miningReadinessAction,
        )
        assertTrue(ready.copy(dictionaries = listOf(usableDictionary())).isMiningReady)
    }

    @Test
    fun dictionaryReadinessMirrorsTheEngineProviderGate() {
        val recovered =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/private/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(2, 24L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                miningTarget = AnkiMiningTargetReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                uniDicInstalled = true,
                dictionaries = listOf(usableDictionary()),
            )
        assertTrue(recovered.dictionaryReady)

        // The engine requires entry_count > 0; an intact-but-empty index must not count.
        val empty =
            recovered.copy(dictionaries = listOf(usableDictionary().copy(entryCount = 0L)))
        assertFalse(empty.dictionaryReady)
        assertFalse(empty.isMiningReady)
        assertEquals(MiningReadinessAction.ENABLE_DICTIONARY, empty.miningReadinessAction)

        // A persisted enabled=false chain entry is skipped by the engine's provider chain,
        // so readiness has to respect it too (pre-v0.7.0 resource resets left these behind).
        val disabled =
            recovered.copy(
                dictionarySources = listOf(ResourceChainSelection("dictionary-1", enabled = false)),
            )
        assertFalse(disabled.dictionaryReady)
        assertFalse(disabled.isMiningReady)
        assertEquals(MiningReadinessAction.ENABLE_DICTIONARY, disabled.miningReadinessAction)

        // A stale persisted entry for an uninstalled slot never blocks the installed one,
        // which resolveResourceChain appends enabled.
        val strayChain =
            recovered.copy(
                dictionarySources = listOf(ResourceChainSelection("gone-dict", enabled = false)),
            )
        assertTrue(strayChain.dictionaryReady)
        assertTrue(strayChain.isMiningReady)

        // With no slot occupied at all the corrective action stays an install, not a review.
        assertEquals(
            MiningReadinessAction.INSTALL_DICTIONARY,
            recovered.copy(dictionaries = emptyList()).miningReadinessAction,
        )
    }

    @Test
    fun wizardSeenStaysUnknownUntilTheSettingsStoreEmits() {
        assertNull(SetupUiState().wizardSeen)
        assertEquals(false, SetupUiState(wizardSeen = false).wizardSeen)
        assertEquals(true, SetupUiState(wizardSeen = true).wizardSeen)
    }

    @Test
    fun customDictionaryImportCarriesNoUserChosenSlotState() {
        assertFalse(
            SetupUiState::class.java.declaredFields.any { field -> field.name == "custom" + "SlotId" },
        )
    }

    @Test
    fun ankiDroidActionMatchesEachExternalReadinessState() {
        assertEquals(
            AnkiDroidSetupAction.INSTALL,
            SetupUiState(anki = AnkiProviderReadiness.NotInstalled).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.OPEN,
            SetupUiState(anki = AnkiProviderReadiness.Uninitialized).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.REQUEST_PERMISSION,
            SetupUiState(anki = AnkiProviderReadiness.PermissionDenied).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.OPEN_OR_INSTALL,
            SetupUiState(
                anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = null),
            ).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.INSTALL,
            SetupUiState(
                anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = 1),
            ).ankiDroidAction,
        )
        assertNull(SetupUiState(anki = AnkiProviderReadiness.NotChecked).ankiDroidAction)
        assertEquals(
            MiningReadinessAction.CHECK_AGAIN,
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/private/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.NotChecked,
                uniDicInstalled = true,
                dictionaries = listOf(usableDictionary()),
            ).miningReadinessAction,
        )
        assertNull(
            SetupUiState(anki = AnkiProviderReadiness.Ready(apiSpecVersion = 2, versionCode = 7L))
                .ankiDroidAction,
        )
    }

    private fun usableDictionary(): InstalledDictionary =
        InstalledDictionary(
            slotId = "dictionary-1",
            occupied = true,
            valid = true,
            sourceName = "Jitendex",
            sourceRevision = "2026-08-01",
            format = "yomitan",
            entryCount = 1_000L,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = "jitendex",
            attribution = emptyList(),
            rebuildSourcePath = null,
        )
}
