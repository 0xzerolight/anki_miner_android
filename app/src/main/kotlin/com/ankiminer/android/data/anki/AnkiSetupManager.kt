package com.ankiminer.android.data.anki

import com.ankiminer.android.R
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.localization.StringResourceResolver
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class AnkiSetupOperation {
    REFRESHING,
    RECONCILING,
    RESOLVING_REMEDIATION,
}

internal enum class AnkiSetupFailureOrigin {
    TARGET,
    RECOVERY,
}

internal enum class AnkiSetupFailureAction {
    RETRY,
    RESOLVE,
}

internal data class AnkiSetupFailure(
    val code: String,
    val message: String,
    val origin: AnkiSetupFailureOrigin = AnkiSetupFailureOrigin.TARGET,
    val action: AnkiSetupFailureAction = AnkiSetupFailureAction.RETRY,
)

internal enum class AnkiRecoveryInventoryStatus {
    NOT_CHECKED,
    AVAILABLE,
    UNAVAILABLE,
}

internal data class AnkiSetupManagerState(
    val noteTypeStatus: NoteTypeSetupStatus = NoteTypeSetupStatus.NotSelected,
    val availableNoteTypes: List<ModelSummary> = emptyList(),
    val availableDeckNames: List<String> = emptyList(),
    val remediations: AnkiRemediationInventory = AnkiRemediationInventory(emptyList()),
    val recoveryInventoryStatus: AnkiRecoveryInventoryStatus =
        AnkiRecoveryInventoryStatus.NOT_CHECKED,
    val operation: AnkiSetupOperation? = null,
    val failure: AnkiSetupFailure? = null,
    val recoveryFailure: AnkiSetupFailure? = null,
) {
    val busy: Boolean
        get() = operation != null
}

/** Worker-only provider surfaces kept behind one process-owned setup controller. */
internal interface AnkiSetupBackend {
    fun listNoteTypes(cancellation: AnkiCancellation): List<ModelSummary>

    fun listDeckNames(cancellation: AnkiCancellation): List<String>

    fun verifyNoteType(
        noteType: String?,
        fieldMap: Map<String, String>,
        cancellation: AnkiCancellation,
    ): NoteTypeSetupStatus

    fun remediationInventory(cancellation: AnkiCancellation): AnkiRemediationInventory

    fun reconcileInterruptedWork(cancellation: AnkiCancellation): AnkiRemediationInventory

    fun performRemediation(
        command: AnkiRemediationCommand,
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory
}

internal interface AnkiSetupManager {
    val state: StateFlow<AnkiSetupManagerState>

    fun refresh(noteType: String?, fieldMap: Map<String, String>)

    fun reconcileInterruptedWork()

    fun performRemediation(command: AnkiRemediationCommand)

    fun dismissFailure()
}

/**
 * Serializes explicit setup/recovery writes with mining and resource publication. Refresh is a
 * read-only inspection and does not hold the process mutation lease.
 *
 * This object is process-owned, so Activity recreation cannot duplicate a provider mutation. The
 * provider/journal layer owns crash recovery; this controller only schedules one bounded worker
 * operation and publishes UI-safe state.
 */
internal class ProcessAnkiSetupManager(
    private val backend: AnkiSetupBackend,
    private val executor: Executor,
    private val runtimeWorkCoordinator: RuntimeWorkCoordinator,
    private val strings: StringResourceResolver,
) : AnkiSetupManager {
    private val mutableState = MutableStateFlow(AnkiSetupManagerState())
    override val state: StateFlow<AnkiSetupManagerState> = mutableState.asStateFlow()

    private val monitor = Any()
    private var active = false

    override fun refresh(noteType: String?, fieldMap: Map<String, String>) {
        runOperation(AnkiSetupOperation.REFRESHING) {
            refreshRecoveryInventory()
            refreshProviderSetup(noteType, fieldMap)
        }
    }

    override fun reconcileInterruptedWork() {
        runOperation(AnkiSetupOperation.RECONCILING) {
            val remediations = backend.reconcileInterruptedWork(AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(
                    remediations = remediations,
                    recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                    failure = null,
                    recoveryFailure = null,
                )
            }
        }
    }

    override fun performRemediation(command: AnkiRemediationCommand) {
        runOperation(AnkiSetupOperation.RESOLVING_REMEDIATION) {
            val remediations = backend.performRemediation(command, AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(
                    remediations = remediations,
                    recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                    failure = null,
                    recoveryFailure = null,
                )
            }
        }
    }

    override fun dismissFailure() {
        mutableState.update { it.copy(failure = null, recoveryFailure = null) }
    }

    private fun refreshRecoveryInventory() {
        try {
            val remediations = backend.remediationInventory(AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(
                    remediations = remediations,
                    recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                    recoveryFailure = null,
                )
            }
        } catch (_: RuntimeException) {
            mutableState.update { current ->
                current.copy(
                    recoveryInventoryStatus = AnkiRecoveryInventoryStatus.UNAVAILABLE,
                    recoveryFailure =
                        AnkiSetupFailure(
                            "anki_recovery_inventory_unavailable",
                            strings.resolve(R.string.anki_setup_recovery_read_failed),
                            origin = AnkiSetupFailureOrigin.RECOVERY,
                            action = AnkiSetupFailureAction.RETRY,
                        ),
                )
            }
        }
    }

    private fun refreshProviderSetup(
        noteType: String?,
        fieldMap: Map<String, String>,
    ) {
        try {
            val available = backend.listNoteTypes(AnkiCancellation.NONE)
            val decks = backend.listDeckNames(AnkiCancellation.NONE)
            val status = backend.verifyNoteType(noteType, fieldMap, AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(
                    availableNoteTypes = available,
                    availableDeckNames = decks,
                    noteTypeStatus = status,
                    failure = null,
                )
            }
        } catch (_: RuntimeException) {
            val message = strings.resolve(R.string.anki_setup_provider_unavailable)
            mutableState.update { current ->
                current.copy(
                    availableNoteTypes = emptyList(),
                    availableDeckNames = emptyList(),
                    noteTypeStatus =
                        NoteTypeSetupStatus.ProviderError(
                            reason = NoteTypeProviderErrorReason.PROVIDER_UNAVAILABLE,
                            code = AnkiErrorCode.PROVIDER_UNAVAILABLE,
                            retryable = true,
                            stableMessage = message,
                        ),
                    failure =
                        AnkiSetupFailure(
                            "anki_provider_unavailable",
                            message,
                            origin = AnkiSetupFailureOrigin.TARGET,
                            action = AnkiSetupFailureAction.RETRY,
                        ),
                )
            }
        }
    }

    private fun runOperation(
        operation: AnkiSetupOperation,
        work: () -> Unit,
    ) {
        synchronized(monitor) {
            if (active) return
            active = true
        }
        val lease =
            if (operation == AnkiSetupOperation.REFRESHING) {
                null
            } else {
                runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.ANKI_SETUP)
            }
        if (operation != AnkiSetupOperation.REFRESHING && lease == null) {
            synchronized(monitor) { active = false }
            recordFailure(
                "runtime_busy",
                strings.resolve(R.string.anki_setup_runtime_busy),
                operation,
            )
            return
        }
        mutableState.update { it.copy(operation = operation) }
        try {
            executor.execute {
                try {
                    work()
                } catch (_: RuntimeException) {
                    recordFailure(
                        "anki_setup_failed",
                        strings.resolve(R.string.anki_setup_failed),
                        operation,
                    )
                } finally {
                    try {
                        lease?.close()
                    } finally {
                        synchronized(monitor) { active = false }
                        // Publish the terminal state only after releasing process exclusion so a
                        // collector-triggered admission refresh cannot race the old lease.
                        mutableState.update { it.copy(operation = null) }
                    }
                }
            }
        } catch (_: RuntimeException) {
            try {
                lease?.close()
            } finally {
                synchronized(monitor) { active = false }
                mutableState.update { it.copy(operation = null) }
            }
            recordFailure(
                "anki_setup_unavailable",
                strings.resolve(R.string.anki_setup_schedule_failed),
                operation,
            )
        }
    }

    private fun recordFailure(
        code: String,
        message: String,
        operation: AnkiSetupOperation,
    ) {
        val origin =
            if (operation == AnkiSetupOperation.REFRESHING) {
                AnkiSetupFailureOrigin.TARGET
            } else {
                AnkiSetupFailureOrigin.RECOVERY
            }
        mutableState.update {
            it.copy(
                failure =
                    AnkiSetupFailure(
                        code = code,
                        message = message,
                        origin = origin,
                        action =
                            if (origin == AnkiSetupFailureOrigin.RECOVERY) {
                                AnkiSetupFailureAction.RESOLVE
                            } else {
                                AnkiSetupFailureAction.RETRY
                            },
                    ),
            )
        }
    }
}
