package com.ankiminer.android.data.resources

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.update.DictionaryUpdateStamp
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Desktop `UPDATE_INTERVAL_SECONDS`: Hoshi Reader's weekly default. */
internal const val DICTIONARY_UPDATE_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1000L

/** What the Updates block on Settings → Resources → Dictionaries says under its button. */
internal sealed interface DictionaryUpdateResult {
    data object Checking : DictionaryUpdateResult

    data object Downloading : DictionaryUpdateResult

    /** Every publisher that answered had nothing newer; [uncheckedCount] did not answer. */
    data class UpToDate(val uncheckedCount: Int) : DictionaryUpdateResult

    /** No publisher answered. */
    data object CouldNotCheck : DictionaryUpdateResult

    /** No installed slot of the language names where its latest version lives. */
    data object NothingPublishes : DictionaryUpdateResult

    /** The language or a chain changed while the publishers answered: nothing still applied. */
    data object ChangedDuringCheck : DictionaryUpdateResult

    /** Titles of the updates that installed and of those that failed on their own. */
    data class Finished(val updated: List<String>, val failed: List<String>) : DictionaryUpdateResult

    /** Startup recovery, a mining run or another resource operation held the resources. */
    data object Busy : DictionaryUpdateResult
}

internal data class DictionaryUpdateUiState(val result: DictionaryUpdateResult? = null) {
    /** A run someone asked for (Update Now, or the automatic run it joined) is still going. */
    val running: Boolean
        get() = result == DictionaryUpdateResult.Checking || result == DictionaryUpdateResult.Downloading
}

/** What Settings drives: the result line and Update Now. */
internal interface DictionaryUpdateActions {
    val state: StateFlow<DictionaryUpdateUiState>

    fun updateNow()
}

/**
 * Desktop's dictionary updates (`MainWindow._maybe_auto_update_resources` and its handlers): ask
 * each installed slot's publisher for a newer revision, then rebuild the slots that have one in
 * place, keeping chain order and on/off state.
 *
 * The automatic run is weekly and starts while the UI is visible ([whileVisible]), never from
 * the Application's startup sequence: that also runs for background process starts, where the
 * install's foreground service is refused. A process latch makes it desktop's once per launch,
 * so resuming from a picker, AnkiDroid or a rotation never asks every publisher again while an
 * install waits for an unmetered network. Update Now ignores the latch and the unmetered rule,
 * and joins a run already going.
 *
 * Stamping follows desktop: a check that reached a publisher and found nothing, or an install run
 * that ran its course (an item failing on its own included), ends the week. An unreachable check,
 * a deferred install (busy, no foreground start, metered) or a cancelled one does not, so the
 * next due run tries again.
 */
internal class DictionaryUpdateCoordinator(
    private val resources: ResourceManager,
    private val settings: AppSettingsRepository,
    private val stamp: DictionaryUpdateStamp,
    private val runtimeWork: StateFlow<RuntimeWorkCoordinator.Kind?>,
    private val unmeteredNetwork: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newOperationId: () -> String = {
        "resource_${UUID.randomUUID().toString().replace("-", "")}"
    },
) : DictionaryUpdateActions {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Set when an automatic run starts; process-scoped because this coordinator is. */
    private val automaticStarted = AtomicBoolean(false)
    private val runLock = Any()
    private var inFlight: Deferred<Unit>? = null

    /** Someone asked: the run reports every outcome and installs on any network. */
    @Volatile
    private var watched = false

    /** The in-flight run is the automatic one, so a check that never ran reopens the latch. */
    @Volatile
    private var automaticRun = false

    /** The phase the result line shows while a watched run goes, so a joiner sees it at once. */
    @Volatile
    private var phase: DictionaryUpdateResult? = null

    private val mutableState = MutableStateFlow(DictionaryUpdateUiState())
    override val state: StateFlow<DictionaryUpdateUiState> = mutableState.asStateFlow()

    /**
     * Collect while the UI is visible: every edge to startup READY with an idle runtime tries the
     * automatic run, which the latch keeps to one per process.
     */
    suspend fun whileVisible() {
        combine(resources.state, runtimeWork) { current, work ->
            current.startupReadiness == ResourceStartupReadiness.READY && work == null
        }.distinctUntilChanged()
            .collect { idle -> if (idle) onUiVisible() }
    }

    fun onUiVisible(): Job = scope.launch { runAutomaticIfDue() }

    override fun updateNow() {
        scope.launch { runNow() }
    }

    suspend fun runAutomaticIfDue() {
        if (automaticStarted.get()) return
        val current = settings.settingsOrNull.first() ?: return
        if (!current.autoUpdateDictionaries || !idle() || !due()) return
        if (!automaticStarted.compareAndSet(false, true)) return
        startOrJoin(manual = false).await()
    }

    suspend fun runNow() {
        startOrJoin(manual = true).await()
    }

    private fun startOrJoin(manual: Boolean): Deferred<Unit> =
        synchronized(runLock) {
            inFlight?.takeIf { it.isActive }?.let { running ->
                if (manual && !watched) {
                    watched = true
                    phase?.let(::publish)
                }
                return running
            }
            watched = manual
            automaticRun = !manual
            phase = null
            // Lazy, so an unconfined dispatcher cannot run the body before inFlight names it.
            scope.async(start = CoroutineStart.LAZY) { performRun() }
                .also {
                    inFlight = it
                    it.start()
                }
        }

    private suspend fun performRun() {
        try {
            report(run())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AppLog.w(LogComponent.RESOURCES, "update.run", failure, "outcome" to "fail")
            report(DictionaryUpdateResult.CouldNotCheck)
        } finally {
            phase = null
        }
    }

    /** The run's outcome for the result line; null when there is nothing to say. */
    private suspend fun run(): DictionaryUpdateResult? {
        if (!idle()) return checkRefused("resource_busy")
        val checked = settings.settingsOrNull.first() ?: return DictionaryUpdateResult.CouldNotCheck
        val language = checked.language
        enter(DictionaryUpdateResult.Checking)
        val chains = chains(checked, language)
        val found =
            try {
                resources.checkResourceUpdates(
                    ResourceUpdateCheckRequest(
                        operationId = newOperationId(),
                        language = language,
                        dictionaryIds = chains.dictionaries,
                        frequencyIds = chains.frequencies,
                        pitchIds = chains.pitch,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ResourceBridgeException) {
                return checkRefused(failure.code)
            } catch (failure: IOException) {
                AppLog.w(LogComponent.RESOURCES, "update.check", failure, "outcome" to "fail")
                return DictionaryUpdateResult.CouldNotCheck
            }
        AppLog.i(
            LogComponent.RESOURCES,
            "update.check",
            "outcome" to "ok",
            "checked" to found.checked,
            "updates" to found.updates.size,
            "failed" to found.failedCount,
        )
        if (found.updates.isEmpty()) {
            if (found.reached) recordStamp()
            return when {
                found.checked == 0 -> DictionaryUpdateResult.NothingPublishes
                !found.reached -> DictionaryUpdateResult.CouldNotCheck
                else -> DictionaryUpdateResult.UpToDate(found.failedCount)
            }
        }
        if (stillValid(found.updates, language).isEmpty()) return DictionaryUpdateResult.ChangedDuringCheck
        return install(found.updates, language)
    }

    private fun checkRefused(code: String): DictionaryUpdateResult {
        AppLog.i(LogComponent.RESOURCES, "update.check", "outcome" to "fail", "code" to code)
        return when (code) {
            // The check never ran or was cut short: recovery or the runtime got there first, or
            // Python cancelled it. The next visible idle edge may retry.
            "resource_operation_cancelled", "resource_not_ready", "resource_busy" -> {
                if (automaticRun) automaticStarted.set(false)
                DictionaryUpdateResult.Busy
            }
            else -> DictionaryUpdateResult.CouldNotCheck
        }
    }

    private suspend fun install(
        updates: List<ResourceUpdate>,
        language: String,
    ): DictionaryUpdateResult? {
        enter(DictionaryUpdateResult.Downloading)
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (update in updates) {
            // Each install takes minutes; the language or chain may move between two of them.
            if (stillValid(listOf(update), language).isEmpty()) continue
            if (!watched && !unmeteredNetwork()) {
                // Jitendex is about 38 MB: an automatic run never spends mobile data on it, and
                // Wi-Fi can drop during the previous install. No stamp, so the next due run resumes.
                AppLog.i(LogComponent.RESOURCES, "update.install", "outcome" to "skip", "code" to "metered")
                return null
            }
            val code = resources.installResourceUpdate(update, language)
            AppLog.i(
                LogComponent.RESOURCES,
                "update.install",
                "outcome" to if (code == null) "ok" else "fail",
                "slot" to update.slotId,
                "code" to code,
            )
            when (code) {
                null -> updated += update.title
                in DEFERRED_INSTALL_CODES -> return DictionaryUpdateResult.Busy
                "resource_operation_cancelled" -> return null
                "resource_update_stale" -> Unit
                else -> failed += update.title
            }
        }
        if (updated.isEmpty() && failed.isEmpty()) return DictionaryUpdateResult.ChangedDuringCheck
        recordStamp()
        return DictionaryUpdateResult.Finished(updated, failed)
    }

    /** Desktop `updates_still_valid`: the same language, and each slot still in its live chain. */
    private suspend fun stillValid(
        updates: List<ResourceUpdate>,
        language: String,
    ): List<ResourceUpdate> {
        val live = settings.settingsOrNull.first() ?: return emptyList()
        if (live.language != language) return emptyList()
        val chains = chains(live, language)
        return updates.filter { update ->
            when (update.kind) {
                ResourceUpdateKind.DICTIONARY -> update.slotId in chains.dictionaries
                ResourceUpdateKind.FREQUENCY -> update.slotId in chains.frequencies
                ResourceUpdateKind.PITCH -> update.slotId in chains.pitch
            }
        }
    }

    private data class Chains(
        val dictionaries: List<String>,
        val frequencies: List<String>,
        val pitch: List<String>,
    )

    /** What a run of [language] consults, in chain order, disabled entries included. */
    private fun chains(
        current: AppSettings,
        language: String,
    ): Chains =
        Chains(
            dictionaries = chain(current.dictionarySources, resources.installedDictionaryIds(language)),
            frequencies = chain(current.frequencySources, resources.installedFrequencyIds(language)),
            pitch = chain(current.pitchSources, resources.installedPitchIds(language)),
        )

    private fun chain(
        persisted: List<ResourceChainSelection>,
        installed: List<String>,
    ): List<String> = EngineSettingsSnapshotMapper.resolveResourceChain(persisted, installed).map { it.resourceId }

    private fun idle(): Boolean =
        resources.state.value.startupReadiness == ResourceStartupReadiness.READY && runtimeWork.value == null

    /** Desktop `update_check_due`: due unless a check completed within the week; a future stamp is due. */
    private suspend fun due(): Boolean {
        val last = stamp.dictionaryUpdatesCheckedAt() ?: return true
        val elapsed = now() - last
        return elapsed < 0L || elapsed >= DICTIONARY_UPDATE_INTERVAL_MILLIS
    }

    /** Desktop `mark_update_checked`: never fails the run; a missed stamp only means an early re-check. */
    private suspend fun recordStamp() {
        try {
            stamp.recordDictionaryUpdatesChecked(now())
        } catch (failure: IOException) {
            AppLog.w(LogComponent.RESOURCES, "update.stamp", failure, "outcome" to "fail")
        }
    }

    private fun enter(next: DictionaryUpdateResult) {
        phase = next
        if (watched) publish(next)
    }

    /**
     * A watched run says how it ended. An automatic run nobody watched only names what it
     * installed, as desktop's status bar does; its failures are logged.
     */
    private fun report(result: DictionaryUpdateResult?) {
        if (watched) {
            publish(result)
            return
        }
        val installed = (result as? DictionaryUpdateResult.Finished)?.updated.orEmpty()
        if (installed.isNotEmpty()) publish(DictionaryUpdateResult.Finished(installed, emptyList()))
    }

    private fun publish(result: DictionaryUpdateResult?) {
        mutableState.value = DictionaryUpdateUiState(result)
    }

    private companion object {
        /** Nothing installed and nothing went wrong with the update itself: a later due run retries. */
        val DEFERRED_INSTALL_CODES = setOf("resource_busy", "resource_not_ready", "resource_foreground_refused")
    }
}
