package com.ankiminer.android.data.anki

import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.data.RuntimeWorkCoordinator
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class AnkiSetupOperation {
    REFRESHING,
    PROVISIONING_MODEL,
    RECONCILING,
    RESOLVING_REMEDIATION,
}

internal data class AnkiSetupFailure(
    val code: String,
    val message: String,
)

internal data class AnkiSetupManagerState(
    val model: AnkiMinerModelProvisioningResult? = null,
    val remediations: AnkiRemediationInventory = AnkiRemediationInventory(emptyList()),
    val operation: AnkiSetupOperation? = null,
    val failure: AnkiSetupFailure? = null,
) {
    val busy: Boolean
        get() = operation != null
}

/** Worker-only provider surfaces kept behind one process-owned setup controller. */
internal interface AnkiSetupBackend {
    fun inspectModel(cancellation: AnkiCancellation): AnkiMinerModelProvisioningResult

    fun provisionModel(cancellation: AnkiCancellation): AnkiMinerModelProvisioningResult

    fun remediationInventory(cancellation: AnkiCancellation): AnkiRemediationInventory

    fun reconcileInterruptedWork(cancellation: AnkiCancellation): AnkiRemediationInventory

    fun performRemediation(
        command: AnkiRemediationCommand,
        cancellation: AnkiCancellation,
    ): AnkiRemediationInventory
}

internal interface AnkiSetupManager {
    val state: StateFlow<AnkiSetupManagerState>

    fun refresh()

    fun provisionModel()

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
) : AnkiSetupManager {
    private val mutableState = MutableStateFlow(AnkiSetupManagerState())
    override val state: StateFlow<AnkiSetupManagerState> = mutableState.asStateFlow()

    private val monitor = Any()
    private var active = false

    override fun refresh() {
        runOperation(AnkiSetupOperation.REFRESHING) {
            val model = backend.inspectModel(AnkiCancellation.NONE)
            val remediations = backend.remediationInventory(AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(model = model, remediations = remediations, failure = null)
            }
        }
    }

    override fun provisionModel() {
        runOperation(AnkiSetupOperation.PROVISIONING_MODEL) {
            val model = backend.provisionModel(AnkiCancellation.NONE)
            val remediations = backend.remediationInventory(AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(model = model, remediations = remediations, failure = null)
            }
        }
    }

    override fun reconcileInterruptedWork() {
        runOperation(AnkiSetupOperation.RECONCILING) {
            val remediations = backend.reconcileInterruptedWork(AnkiCancellation.NONE)
            val model = backend.inspectModel(AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(model = model, remediations = remediations, failure = null)
            }
        }
    }

    override fun performRemediation(command: AnkiRemediationCommand) {
        runOperation(AnkiSetupOperation.RESOLVING_REMEDIATION) {
            val remediations = backend.performRemediation(command, AnkiCancellation.NONE)
            mutableState.update { current ->
                current.copy(remediations = remediations, failure = null)
            }
        }
    }

    override fun dismissFailure() {
        mutableState.update { it.copy(failure = null) }
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
                "Finish or cancel the active mining or resource operation before changing Anki setup",
            )
            return
        }
        mutableState.update { it.copy(operation = operation, failure = null) }
        try {
            executor.execute {
                try {
                    work()
                } catch (_: RuntimeException) {
                    recordFailure(
                        "anki_setup_failed",
                        "Anki setup did not complete. Review the status and try again.",
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
            recordFailure("anki_setup_unavailable", "Anki setup could not be scheduled")
        }
    }

    private fun recordFailure(
        code: String,
        message: String,
    ) {
        mutableState.update { it.copy(failure = AnkiSetupFailure(code, message)) }
    }
}
