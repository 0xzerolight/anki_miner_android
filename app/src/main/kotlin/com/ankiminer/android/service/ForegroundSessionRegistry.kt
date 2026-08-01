package com.ankiminer.android.service

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal class ForegroundSessionRegistry(
    private val callbackExecutor: Executor,
) {
    internal data class Registration(
        val accepted: Boolean,
        val started: CompletableFuture<Unit>,
    )

    internal data class ServiceSnapshot(
        val progress: MiningForegroundProgress,
        val cancelling: Boolean,
        val cpuWakeParked: Boolean,
    )

    private enum class Phase {
        PENDING,
        CLAIMED,
        ACTIVE,
        CANCELLING,
        CLOSING,
    }

    private data class Record(
        val identity: MiningForegroundSessionIdentity,
        val listener: MiningForegroundSessionListener,
        val started: CompletableFuture<Unit>,
        var phase: Phase = Phase.PENDING,
        var serviceToken: String? = null,
        var progress: MiningForegroundProgress = MiningForegroundProgress(),
        var cancellationDelivered: Boolean = false,
        var cpuWakeParked: Boolean = false,
    )

    /**
     * A registry phase change captured under [lock] and emitted after the critical section.
     */
    private data class PhaseTransition(
        val identity: MiningForegroundSessionIdentity,
        val from: Phase?,
        val to: Phase?,
        val detail: String,
    )

    private val lock = Any()
    private var current: Record? = null

    fun register(
        identity: MiningForegroundSessionIdentity,
        listener: MiningForegroundSessionListener,
    ): Registration {
        val started = CompletableFuture<Unit>()
        var transition: PhaseTransition? = null
        val accepted =
            synchronized(lock) {
                if (current != null) {
                    false
                } else {
                    current = Record(identity, listener, started)
                    transition = PhaseTransition(identity, null, Phase.PENDING, "register")
                    true
                }
            }
        transition.emit()
        if (!accepted) {
            completeExceptionally(
                started,
                IllegalStateException("A foreground mining session is already registered"),
            )
        }
        return Registration(accepted, started)
    }

    fun claimStart(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
    ): Boolean {
        var transition: PhaseTransition? = null
        val claimed =
            synchronized(lock) {
                val record = current
                if (record?.identity != identity || record.phase != Phase.PENDING) {
                    false
                } else {
                    transition = record.transition(Phase.CLAIMED, "start_claimed")
                    record.serviceToken = serviceToken
                    true
                }
            }
        transition.emit()
        return claimed
    }

    fun foregroundStarted(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
    ): Boolean {
        var transition: PhaseTransition? = null
        val started =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    record.phase != Phase.CLAIMED ||
                    record.serviceToken != serviceToken
                ) {
                    null
                } else {
                    transition = record.transition(Phase.ACTIVE, "foreground_started")
                    record.started
                }
            }
        transition.emit()
        if (started != null) {
            callbackExecutor.execute { started.complete(Unit) }
            return true
        }
        return false
    }

    fun failBeforeForeground(
        identity: MiningForegroundSessionIdentity,
        cause: Throwable,
    ): Boolean {
        var transition: PhaseTransition? = null
        val started =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.PENDING && record.phase != Phase.CLAIMED)
                ) {
                    null
                } else {
                    current = null
                    transition = record.removed("foreground_failed")
                    record.started
                }
            }
        transition.emit()
        if (started != null) {
            completeExceptionally(started, cause)
            return true
        }
        return false
    }

    fun cancelAbandonedStart(identity: MiningForegroundSessionIdentity): Boolean {
        val completion: CompletableFuture<Unit>?
        val cancellation: CancellationNotification?
        val shouldStop: Boolean
        var transition: PhaseTransition? = null
        synchronized(lock) {
            val record = current
            when {
                record?.identity != identity -> {
                    completion = null
                    cancellation = null
                    shouldStop = false
                }

                record.phase == Phase.PENDING || record.phase == Phase.CLAIMED -> {
                    current = null
                    transition = record.removed("start_abandoned")
                    completion = record.started
                    cancellation = null
                    shouldStop = true
                }

                record.phase == Phase.ACTIVE -> {
                    transition = record.transition(Phase.CLOSING, "active_abandoned")
                    completion = null
                    cancellation =
                        cancellationNotification(
                            record,
                            MiningForegroundCancellationReason.USER_REQUESTED,
                        )
                    shouldStop = true
                }

                else -> {
                    completion = null
                    cancellation = null
                    shouldStop = false
                }
            }
        }
        transition.emit()
        if (completion != null) {
            completeExceptionally(completion, CancellationException("Foreground start abandoned"))
        }
        dispatchCancellation(cancellation)
        return shouldStop
    }

    fun updateProgress(
        identity: MiningForegroundSessionIdentity,
        progress: MiningForegroundProgress,
    ): Boolean =
        synchronized(lock) {
            val record = current
            if (
                record?.identity != identity ||
                (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
            ) {
                false
            } else {
                record.progress = progress
                true
            }
        }

    /**
     * Records whether the run wants its CPU wake lock dropped; the service applies it on the next
     * command. Curation is the only wait that sets it, and the service stays in the foreground.
     */
    fun setCpuWakeParked(
        identity: MiningForegroundSessionIdentity,
        parked: Boolean,
    ): Boolean =
        synchronized(lock) {
            val record = current
            if (
                record?.identity != identity ||
                (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
            ) {
                false
            } else {
                record.cpuWakeParked = parked
                true
            }
        }

    fun snapshotForService(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
    ): ServiceSnapshot? =
        synchronized(lock) {
            val record = current
            if (
                record?.identity != identity ||
                (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING) ||
                record.serviceToken != serviceToken
            ) {
                null
            } else {
                ServiceSnapshot(
                    progress = record.progress,
                    cancelling = record.phase == Phase.CANCELLING,
                    cpuWakeParked = record.cpuWakeParked,
                )
            }
        }

    fun markCancelling(identity: MiningForegroundSessionIdentity): Boolean {
        var transition: PhaseTransition? = null
        val marked =
            synchronized(lock) {
                val record = current
                if (record?.identity != identity) {
                    false
                } else {
                    when (record.phase) {
                        Phase.ACTIVE -> {
                            transition = record.transition(Phase.CANCELLING, "mark_cancelling")
                            true
                        }
                        Phase.CANCELLING -> true
                        Phase.PENDING, Phase.CLAIMED, Phase.CLOSING -> false
                    }
                }
            }
        transition.emit()
        return marked
    }

    fun requestCancellation(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
        reason: MiningForegroundCancellationReason,
    ): Boolean {
        var transition: PhaseTransition? = null
        val result =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING) ||
                    record.serviceToken != serviceToken
                ) {
                    false to null
                } else {
                    if (record.phase == Phase.ACTIVE) {
                        transition = record.transition(Phase.CANCELLING, "cancel_requested")
                    }
                    true to cancellationNotification(record, reason)
                }
            }
        transition.emit()
        dispatchCancellation(result.second)
        return result.first
    }

    fun beginExpectedClose(identity: MiningForegroundSessionIdentity): Boolean {
        var transition: PhaseTransition? = null
        val closing =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
                ) {
                    false
                } else {
                    transition = record.transition(Phase.CLOSING, "expected_close")
                    true
                }
            }
        transition.emit()
        return closing
    }

    fun beginServiceTermination(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
        reason: MiningForegroundCancellationReason,
    ): Boolean {
        var transition: PhaseTransition? = null
        val result =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING) ||
                    record.serviceToken != serviceToken
                ) {
                    null
                } else {
                    transition = record.transition(Phase.CLOSING, "service_termination")
                    record to cancellationNotification(record, reason)
                }
            }
        transition.emit()
        dispatchCancellation(result?.second)
        return result != null
    }

    fun controllerObservedServiceLoss(identity: MiningForegroundSessionIdentity) {
        var transition: PhaseTransition? = null
        val cancellation =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
                ) {
                    null
                } else {
                    transition = record.transition(Phase.CLOSING, "service_lost")
                    cancellationNotification(
                        record,
                        MiningForegroundCancellationReason.SERVICE_LOST,
                    )
                }
            }
        transition.emit()
        dispatchCancellation(cancellation)
    }

    fun expectedServiceWasAbsent(identity: MiningForegroundSessionIdentity) {
        var transition: PhaseTransition? = null
        synchronized(lock) {
            val record = current
            if (record?.identity == identity && record.phase == Phase.CLOSING) {
                current = null
                transition = record.removed("service_absent")
            }
        }
        transition.emit()
    }

    fun serviceDestroyed(
        identity: MiningForegroundSessionIdentity?,
        serviceToken: String,
    ) {
        val completion: CompletableFuture<Unit>?
        val cancellation: CancellationNotification?
        var transition: PhaseTransition? = null
        synchronized(lock) {
            val record = current
            if (
                record == null ||
                identity == null ||
                record.identity != identity ||
                record.serviceToken != serviceToken
            ) {
                completion = null
                cancellation = null
            } else {
                current = null
                transition = record.removed("service_destroyed")
                when (record.phase) {
                    Phase.PENDING -> {
                        completion = record.started
                        cancellation = null
                    }

                    Phase.CLAIMED -> {
                        completion = record.started
                        cancellation = null
                    }

                    Phase.ACTIVE -> {
                        completion = null
                        cancellation =
                            cancellationNotification(
                                record,
                                MiningForegroundCancellationReason.SERVICE_LOST,
                            )
                    }

                    Phase.CANCELLING -> {
                        completion = null
                        cancellation = null
                    }

                    Phase.CLOSING -> {
                        completion = null
                        cancellation = null
                    }
                }
            }
        }
        transition.emit()
        if (completion != null) {
            completeExceptionally(
                completion,
                IllegalStateException("Foreground service ended before its start handshake"),
            )
        }
        dispatchCancellation(cancellation)
    }

    /** The only writer of [Record.phase]; every caller holds [lock] and emits after it. */
    private fun Record.transition(
        to: Phase,
        detail: String,
    ): PhaseTransition {
        val from = phase
        phase = to
        return PhaseTransition(identity, from, to, detail)
    }

    /** Captures removal of this record from the registry. Caller holds [lock]. */
    private fun Record.removed(detail: String): PhaseTransition =
        PhaseTransition(identity, phase, null, detail)

    /** No-op when the caller took a path that changed no phase. */
    private fun PhaseTransition?.emit() {
        val transition = this ?: return
        AppLog.state(
            LogComponent.SERVICE,
            "phase",
            transition.from?.name ?: "NONE",
            transition.to?.name ?: "NONE",
            "outcome" to "ok",
            "detail" to transition.detail,
            "runId" to transition.identity.runId,
            "generation" to transition.identity.generation,
            "leaseId" to transition.identity.leaseId,
        )
    }

    private data class CancellationNotification(
        val identity: MiningForegroundSessionIdentity,
        val listener: MiningForegroundSessionListener,
        val reason: MiningForegroundCancellationReason,
    )

    private fun cancellationNotification(
        record: Record,
        reason: MiningForegroundCancellationReason,
    ): CancellationNotification? {
        if (record.cancellationDelivered) return null
        record.cancellationDelivered = true
        return CancellationNotification(record.identity, record.listener, reason)
    }

    private fun dispatchCancellation(notification: CancellationNotification?) {
        if (notification == null) return
        callbackExecutor.execute {
            runCatching {
                notification.listener.onCancellationRequested(
                    notification.identity,
                    notification.reason,
                )
            }
        }
    }

    private fun completeExceptionally(
        future: CompletableFuture<Unit>,
        cause: Throwable,
    ) {
        callbackExecutor.execute { future.completeExceptionally(cause) }
    }
}

internal object ProcessMiningForegroundSessions {
    private val callbackExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "anki-miner-fgs-callback").apply { isDaemon = true }
        }

    val registry = ForegroundSessionRegistry(callbackExecutor)
}
