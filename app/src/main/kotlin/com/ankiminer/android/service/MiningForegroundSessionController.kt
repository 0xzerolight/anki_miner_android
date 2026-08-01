package com.ankiminer.android.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MiningForegroundSessionController private constructor(
    context: Context,
    private val registry: ForegroundSessionRegistry,
) {
    constructor(context: Context) : this(context, ProcessMiningForegroundSessions.registry)

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startSession(
        runId: String,
        generation: Long,
        listener: MiningForegroundSessionListener,
    ): CompletableFuture<MiningForegroundLease> {
        val identity = MiningForegroundSessionIdentity.create(runId, generation)
        val registration = registry.register(identity, listener)
        val leaseFuture =
            registration.started.thenApply<MiningForegroundLease> {
                AndroidMiningForegroundLease(
                    identity = identity,
                    applicationContext = applicationContext,
                    registry = registry,
                )
            }
        if (!registration.accepted) return leaseFuture

        val timeout =
            Runnable {
                if (
                    registry.failBeforeForeground(
                        identity,
                        TimeoutException("Foreground service did not start within the deadline"),
                    )
                ) {
                    runCatching {
                        applicationContext.stopService(
                            MiningForegroundService.serviceIntent(applicationContext),
                        )
                    }
                }
            }
        mainHandler.postDelayed(timeout, START_HANDSHAKE_TIMEOUT_MS)
        leaseFuture.whenComplete { _, _ ->
            mainHandler.removeCallbacks(timeout)
            if (leaseFuture.isCancelled && registry.cancelAbandonedStart(identity)) {
                val stopped =
                    runCatching {
                        applicationContext.stopService(
                            MiningForegroundService.serviceIntent(applicationContext),
                        )
                    }.getOrDefault(false)
                if (!stopped) registry.expectedServiceWasAbsent(identity)
            }
        }

        try {
            ContextCompat.startForegroundService(
                applicationContext,
                MiningForegroundService.startIntent(applicationContext, identity),
            )
        } catch (cause: RuntimeException) {
            registry.failBeforeForeground(identity, cause)
            runCatching {
                applicationContext.stopService(
                    MiningForegroundService.serviceIntent(applicationContext),
                )
            }
        }
        return leaseFuture
    }

    private class AndroidMiningForegroundLease(
        override val identity: MiningForegroundSessionIdentity,
        private val applicationContext: Context,
        private val registry: ForegroundSessionRegistry,
    ) : MiningForegroundLease {
        private val redrawMonitor = Any()
        private var lastRedrawn: MiningForegroundProgress? = null

        override fun markCancelling(): Boolean {
            if (!registry.markCancelling(identity)) return false
            return notifyService()
        }

        override fun updateProgress(progress: MiningForegroundProgress): Boolean {
            // The registry keeps the live counts either way, so a skipped redraw cannot make the
            // snapshot the service reads stale, and a dead session is still detected here.
            if (!registry.updateProgress(identity, progress)) return false
            if (!claimRedraw(progress)) return true
            return notifyService()
        }

        override fun parkCpuWake(): Boolean = setCpuWakeParked(parked = true)

        override fun resumeCpuWake(): Boolean = setCpuWakeParked(parked = false)

        private fun setCpuWakeParked(parked: Boolean): Boolean {
            if (!registry.setCpuWakeParked(identity, parked)) return false
            return notifyService()
        }

        /** Every producer reaches the notification through here, so the guard belongs here. */
        private fun claimRedraw(progress: MiningForegroundProgress): Boolean =
            synchronized(redrawMonitor) {
                val previous = lastRedrawn
                if (previous != null && !miningNotificationRedrawRequired(previous, progress)) {
                    return false
                }
                lastRedrawn = progress
                true
            }

        private fun notifyService(): Boolean {
            return try {
                val component =
                    applicationContext.startService(
                        MiningForegroundService.updateIntent(applicationContext, identity),
                    )
                if (component == null) {
                    registry.controllerObservedServiceLoss(identity)
                    stopServiceAfterObservedLoss()
                    false
                } else {
                    true
                }
            } catch (_: RuntimeException) {
                registry.controllerObservedServiceLoss(identity)
                stopServiceAfterObservedLoss()
                false
            }
        }

        private fun stopServiceAfterObservedLoss() {
            val stopped =
                runCatching {
                    applicationContext.stopService(
                        MiningForegroundService.serviceIntent(applicationContext),
                    )
                }.getOrDefault(false)
            if (!stopped) {
                registry.expectedServiceWasAbsent(identity)
            }
        }

        override fun close() {
            if (!registry.beginExpectedClose(identity)) return
            val stopped =
                runCatching {
                    applicationContext.stopService(
                        MiningForegroundService.serviceIntent(applicationContext),
                    )
                }.getOrDefault(false)
            if (!stopped) {
                registry.expectedServiceWasAbsent(identity)
            }
        }
    }

    companion object {
        private val START_HANDSHAKE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
    }
}
