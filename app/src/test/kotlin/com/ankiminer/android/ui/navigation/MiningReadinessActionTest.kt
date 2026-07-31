package com.ankiminer.android.ui.navigation

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationSummary
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonBootstrapStage
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.vm.MiningReadinessAction
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class MiningReadinessActionTest(
    private val name: String,
    private val state: SetupUiState,
    private val expected: MiningReadinessAction,
) {
    @Test
    fun derivesSingleCorrectiveAction() {
        assertEquals(name, expected, state.miningReadinessAction)
    }

    companion object {
        private fun readyState() =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                uniDicInstalled = true,
            )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val ready = readyState()
            fun providerError(reason: NoteTypeProviderErrorReason) =
                NoteTypeSetupStatus.ProviderError(
                    reason = reason,
                    code = AnkiErrorCode.QUERY_FAILED,
                    retryable = true,
                    stableMessage = "Provider check failed",
                )
            return listOf(
                arrayOf(
                    "python startup waits",
                    ready.copy(python = PythonRuntimeReadiness.Starting),
                    MiningReadinessAction.WAIT,
                ),
                arrayOf(
                    "failed python checks again",
                    ready.copy(
                        python =
                            PythonRuntimeReadiness.Failed(
                                PythonBootstrapStage.START,
                                "UnsatisfiedLinkError @ Python.start:1",
                            ),
                    ),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "resource startup waits",
                    ready.copy(resourceStartup = ResourceStartupReadiness.PENDING),
                    MiningReadinessAction.WAIT,
                ),
                arrayOf(
                    "resource recovery waits",
                    ready.copy(resourceStartup = ResourceStartupReadiness.RECOVERING),
                    MiningReadinessAction.WAIT,
                ),
                arrayOf(
                    "failed resource startup checks again",
                    ready.copy(resourceStartup = ResourceStartupReadiness.FAILED),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "active setup work waits",
                    ready.copy(
                        uniDicInstalled = false,
                        runtimeWorkKind = RuntimeWorkCoordinator.Kind.RESOURCE,
                    ),
                    MiningReadinessAction.WAIT,
                ),
                arrayOf(
                    "missing UniDic installs UniDic",
                    ready.copy(uniDicInstalled = false),
                    MiningReadinessAction.INSTALL_UNIDIC,
                ),
                arrayOf(
                    "missing AnkiDroid installs",
                    ready.copy(anki = AnkiProviderReadiness.NotInstalled),
                    MiningReadinessAction.INSTALL_ANKIDROID,
                ),
                arrayOf(
                    "uninitialized AnkiDroid opens",
                    ready.copy(anki = AnkiProviderReadiness.Uninitialized),
                    MiningReadinessAction.OPEN_ANKIDROID,
                ),
                arrayOf(
                    "denied provider connects",
                    ready.copy(anki = AnkiProviderReadiness.PermissionDenied),
                    MiningReadinessAction.CONNECT_ANKIDROID,
                ),
                arrayOf(
                    "unchecked provider checks again",
                    ready.copy(anki = AnkiProviderReadiness.NotChecked),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "incompatible provider installs update",
                    ready.copy(anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = 1)),
                    MiningReadinessAction.INSTALL_ANKIDROID,
                ),
                arrayOf(
                    "disabled provider opens AnkiDroid",
                    ready.copy(anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = null)),
                    MiningReadinessAction.OPEN_ANKIDROID,
                ),
                arrayOf(
                    "missing note type chooses note type",
                    ready.copy(noteTypeStatus = NoteTypeSetupStatus.NotSelected),
                    MiningReadinessAction.CHOOSE_NOTE_TYPE,
                ),
                arrayOf(
                    "note type provider permission connects AnkiDroid",
                    ready.copy(
                        noteTypeStatus =
                            providerError(NoteTypeProviderErrorReason.PERMISSION_REQUIRED),
                    ),
                    MiningReadinessAction.CONNECT_ANKIDROID,
                ),
                arrayOf(
                    "note type provider incompatibility installs update",
                    ready.copy(
                        noteTypeStatus =
                            providerError(NoteTypeProviderErrorReason.API_INCOMPATIBLE),
                    ),
                    MiningReadinessAction.INSTALL_ANKIDROID,
                ),
                arrayOf(
                    "note type provider unavailability opens AnkiDroid",
                    ready.copy(
                        noteTypeStatus =
                            providerError(NoteTypeProviderErrorReason.PROVIDER_UNAVAILABLE),
                    ),
                    MiningReadinessAction.OPEN_ANKIDROID,
                ),
                arrayOf(
                    "note type query failure checks again",
                    ready.copy(
                        noteTypeStatus =
                            providerError(NoteTypeProviderErrorReason.QUERY_FAILED),
                    ),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "blocked recovery resolves recovery",
                    ready.copy(ankiRecovery = AnkiRecoveryReadiness.Blocked),
                    MiningReadinessAction.RESOLVE_RECOVERY,
                ),
                arrayOf(
                    "pending recovery item resolves recovery",
                    ready.copy(
                        remediations =
                            AnkiRemediationInventory(
                                listOf(
                                    AnkiPendingRemediation(
                                        id = 1L,
                                        type = AnkiRemediationType.STAGING_QUARANTINED,
                                        summaryReason =
                                            AnkiRemediationSummary.STAGING_CLEANUP_RETRY,
                                        title = "Cleanup required",
                                        summary = "Retry cleanup",
                                        compactEvidence = null,
                                        createdAtMs = 1L,
                                        updatedAtMs = 1L,
                                        availableActions =
                                            setOf(
                                                AnkiRemediationActionKind.RETRY_STAGING_CLEANUP,
                                            ),
                                    ),
                                ),
                            ),
                    ),
                    MiningReadinessAction.RESOLVE_RECOVERY,
                ),
                arrayOf(
                    "unchecked recovery checks again",
                    ready.copy(
                        ankiRecovery = AnkiRecoveryReadiness.NotChecked,
                        recoveryInventoryStatus = AnkiRecoveryInventoryStatus.NOT_CHECKED,
                    ),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "unavailable recovery inventory checks again",
                    ready.copy(
                        recoveryInventoryStatus = AnkiRecoveryInventoryStatus.UNAVAILABLE,
                    ),
                    MiningReadinessAction.CHECK_AGAIN,
                ),
                arrayOf(
                    "unexpected not-ready state checks again",
                    ready,
                    MiningReadinessAction.CHECK_AGAIN,
                ),
            )
        }
    }
}
