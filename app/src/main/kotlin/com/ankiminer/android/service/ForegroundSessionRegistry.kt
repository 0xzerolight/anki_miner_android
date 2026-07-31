package com.ankiminer.android.service

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
    )

    private val lock = Any()
    private var current: Record? = null

    fun register(
        identity: MiningForegroundSessionIdentity,
        listener: MiningForegroundSessionListener,
    ): Registration {
        val started = CompletableFuture<Unit>()
        val accepted =
            synchronized(lock) {
                if (current != null) {
                    false
                } else {
                    current = Record(identity, listener, started)
                    true
                }
            }
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
    ): Boolean =
        synchronized(lock) {
            val record = current
            if (record?.identity != identity || record.phase != Phase.PENDING) {
                false
            } else {
                record.phase = Phase.CLAIMED
                record.serviceToken = serviceToken
                true
            }
        }

    fun foregroundStarted(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
    ): Boolean {
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
                    record.phase = Phase.ACTIVE
                    record.started
                }
            }
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
                    record.started
                }
            }
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
                    completion = record.started
                    cancellation = null
                    shouldStop = true
                }

                record.phase == Phase.ACTIVE -> {
                    record.phase = Phase.CLOSING
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
                )
            }
        }

    fun markCancelling(identity: MiningForegroundSessionIdentity): Boolean =
        synchronized(lock) {
            val record = current
            if (record?.identity != identity) {
                false
            } else {
                when (record.phase) {
                    Phase.ACTIVE -> {
                        record.phase = Phase.CANCELLING
                        true
                    }
                    Phase.CANCELLING -> true
                    Phase.PENDING, Phase.CLAIMED, Phase.CLOSING -> false
                }
            }
        }

    fun requestCancellation(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
        reason: MiningForegroundCancellationReason,
    ): Boolean {
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
                    record.phase = Phase.CANCELLING
                    true to cancellationNotification(record, reason)
                }
            }
        dispatchCancellation(result.second)
        return result.first
    }

    fun beginExpectedClose(identity: MiningForegroundSessionIdentity): Boolean =
        synchronized(lock) {
            val record = current
            if (
                record?.identity != identity ||
                (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
            ) {
                false
            } else {
                record.phase = Phase.CLOSING
                true
            }
        }

    fun beginServiceTermination(
        identity: MiningForegroundSessionIdentity,
        serviceToken: String,
        reason: MiningForegroundCancellationReason,
    ): Boolean {
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
                    record.phase = Phase.CLOSING
                    record to cancellationNotification(record, reason)
                }
            }
        dispatchCancellation(result?.second)
        return result != null
    }

    fun controllerObservedServiceLoss(identity: MiningForegroundSessionIdentity) {
        val cancellation =
            synchronized(lock) {
                val record = current
                if (
                    record?.identity != identity ||
                    (record.phase != Phase.ACTIVE && record.phase != Phase.CANCELLING)
                ) {
                    null
                } else {
                    record.phase = Phase.CLOSING
                    cancellationNotification(
                        record,
                        MiningForegroundCancellationReason.SERVICE_LOST,
                    )
                }
            }
        dispatchCancellation(cancellation)
    }

    fun expectedServiceWasAbsent(identity: MiningForegroundSessionIdentity) {
        synchronized(lock) {
            val record = current
            if (record?.identity == identity && record.phase == Phase.CLOSING) {
                current = null
            }
        }
    }

    fun serviceDestroyed(
        identity: MiningForegroundSessionIdentity?,
        serviceToken: String,
    ) {
        val completion: CompletableFuture<Unit>?
        val cancellation: CancellationNotification?
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
        if (completion != null) {
            completeExceptionally(
                completion,
                IllegalStateException("Foreground service ended before its start handshake"),
            )
        }
        dispatchCancellation(cancellation)
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
