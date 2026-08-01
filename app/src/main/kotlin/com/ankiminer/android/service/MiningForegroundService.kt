package com.ankiminer.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ankiminer.android.MainActivity
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.localization.byteProgressResource
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun warnMalformedForegroundIntent(
    action: String?,
    extraKeys: Set<String>,
) {
    AppLog.w(
        LogComponent.SERVICE,
        "intent.decode",
        IllegalArgumentException("Malformed foreground-service intent"),
        "outcome" to "ignored",
        "action" to action,
        "extraKeys" to extraKeys.sorted(),
    )
}

/**
 * Resource and arguments for the notification's progress line, or null while indeterminate.
 *
 * Separate from the builder because the service needs a real `Context` and the host unit build has
 * no Robolectric, so the unit-to-resource choice would otherwise be untested.
 */
internal fun miningNotificationProgressText(progress: MiningForegroundProgress): LocalizedStringResource? {
    val completed = progress.completed ?: return null
    val total = progress.total ?: return null
    return when (progress.unit) {
        MiningForegroundProgressUnit.ITEMS ->
            LocalizedStringResource(R.string.mining_notification_count, listOf(completed, total))
        // The same scale selection the mining screen renders bytes through, so the notification and
        // the screen cannot disagree about one copy's units.
        MiningForegroundProgressUnit.BYTES ->
            byteProgressResource(completed.toLong(), total.toLong())
    }
}

internal fun decodeMiningForegroundIntentIdentity(
    action: String?,
    extraKeys: Set<String>,
    runId: String?,
    generation: Long,
    leaseId: String?,
    warnOnFailure: Boolean = true,
): MiningForegroundSessionIdentity? {
    val identity =
        if (
            extraKeys != MiningForegroundService.IDENTITY_EXTRA_KEYS ||
                runId == null ||
                leaseId == null
        ) {
            null
        } else {
            runCatching {
                MiningForegroundSessionIdentity(runId, generation, leaseId)
            }.onFailure { failure ->
                AppLog.ignored(
                    LogComponent.SERVICE,
                    "intent.identity",
                    "malformed_intent_warning_follows",
                    failure,
                )
            }.getOrNull()
        }
    if (identity == null && warnOnFailure) {
        warnMalformedForegroundIntent(action, extraKeys)
    }
    return identity
}

class MiningForegroundService : Service() {
    private val registry = ProcessMiningForegroundSessions.registry
    private val serviceToken = UUID.randomUUID().toString()
    private val createdAtNanos = System.nanoTime()
    private var sessionIdentity: MiningForegroundSessionIdentity? = null
    private lateinit var cpuWakeLease: MiningCpuWakeLease

    override fun onCreate() {
        super.onCreate()
        cpuWakeLease = MiningCpuWakeLease(AndroidMiningCpuWakeLock.create(this))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.mining_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.mining_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_UPDATE -> handleUpdate(intent, startId)
            ACTION_CANCEL -> handleCancel(intent, startId)
            else -> handleMalformedIntent(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        handleSystemTimeout()
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        handleSystemTimeout()
    }

    override fun onDestroy() {
        try {
            cpuWakeLease.close()
        } finally {
            try {
                registry.serviceDestroyed(sessionIdentity, serviceToken)
                sessionIdentity = null
            } finally {
                super.onDestroy()
            }
        }
    }

    private fun handleStart(intent: Intent) {
        val identity = decodeIdentity(intent)
        if (identity == null) {
            if (sessionIdentity == null) stopImmediately()
            return
        }
        val activeIdentity = sessionIdentity
        if (activeIdentity != null) {
            if (identity == activeIdentity) {
                failActiveSession("start")
            }
            // A stale start must never terminate or mutate the current generation.
            return
        }
        if (!registry.claimStart(identity, serviceToken)) {
            // The pending registry entry belongs to another generation. Reject only this start.
            stopImmediately()
            return
        }
        sessionIdentity = identity

        try {
            startForegroundTyped(buildNotification(identity, MiningForegroundProgress()))
            // Curation remains parked until this foreground + CPU-wake handshake completes.
            cpuWakeLease.acquire()
        } catch (cause: RuntimeException) {
            registry.failBeforeForeground(identity, cause)
            stopImmediately()
            return
        }

        if (!registry.foregroundStarted(identity, serviceToken)) {
            stopImmediately()
        }
    }

    private fun handleUpdate(
        intent: Intent,
        startId: Int,
    ) {
        val identity = decodeIdentity(intent)
        val activeIdentity = sessionIdentity
        when (foregroundCommandDisposition(activeIdentity, identity)) {
            ForegroundCommandDisposition.STOP_COLD_SERVICE -> {
                stopColdStart(startId)
                return
            }
            ForegroundCommandDisposition.IGNORE_STALE_COMMAND -> return
            ForegroundCommandDisposition.HANDLE_ACTIVE_COMMAND -> Unit
        }
        val currentIdentity = requireNotNull(identity)
        val snapshot = registry.snapshotForService(currentIdentity, serviceToken)
        if (snapshot == null) {
            failActiveSession("update")
            return
        }
        try {
            applyCpuWakeState(currentIdentity, snapshot.cpuWakeParked)
            startForegroundTyped(
                if (snapshot.cancelling) {
                    buildCancellingNotification(currentIdentity)
                } else {
                    buildNotification(currentIdentity, snapshot.progress)
                },
            )
        } catch (failure: RuntimeException) {
            failActiveSession("update", failure)
        }
    }

    /**
     * Idempotent, so it is safe to run on every command rather than needing its own intent action.
     */
    private fun applyCpuWakeState(
        identity: MiningForegroundSessionIdentity,
        parked: Boolean,
    ) {
        val alreadyApplied = if (parked) !cpuWakeLease.isOwned() else cpuWakeLease.isOwned()
        if (alreadyApplied) return
        if (parked) cpuWakeLease.park() else cpuWakeLease.acquire()
        // acquire() is a no-op once the lease is closed, so the wanted state has to be re-read
        // rather than assumed: a resume racing teardown legitimately does nothing.
        val reached = parked != cpuWakeLease.isOwned()
        AppLog.d(LogComponent.SERVICE, "cpu_wake") {
            arrayOf(
                "outcome" to if (reached) "ok" else "skip",
                "parked" to parked,
                "runId" to identity.runId,
                "generation" to identity.generation,
                "leaseId" to identity.leaseId,
            )
        }
    }

    private fun handleCancel(
        intent: Intent,
        startId: Int,
    ) {
        val identity = decodeIdentity(intent)
        val activeIdentity = sessionIdentity
        when (foregroundCommandDisposition(activeIdentity, identity)) {
            ForegroundCommandDisposition.STOP_COLD_SERVICE -> {
                stopColdStart(startId)
                return
            }
            ForegroundCommandDisposition.IGNORE_STALE_COMMAND -> return
            ForegroundCommandDisposition.HANDLE_ACTIVE_COMMAND -> Unit
        }
        val currentIdentity = requireNotNull(identity)
        if (
            !registry.requestCancellation(
                currentIdentity,
                serviceToken,
                MiningForegroundCancellationReason.USER_REQUESTED,
            )
        ) {
            failActiveSession("cancel")
            return
        }
        try {
            startForegroundTyped(buildCancellingNotification(currentIdentity))
        } catch (failure: RuntimeException) {
            failActiveSession("cancel", failure)
        }
    }

    private fun handleSystemTimeout() {
        val identity = sessionIdentity
        AppLog.i(
            LogComponent.SERVICE,
            "foreground.timeout",
            "outcome" to "ok",
            "ms" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos),
            "runId" to identity?.runId,
            "generation" to identity?.generation,
            "leaseId" to identity?.leaseId,
        )
        if (identity != null) {
            registry.beginServiceTermination(
                identity,
                serviceToken,
                MiningForegroundCancellationReason.SYSTEM_TIMEOUT,
            )
        }
        stopImmediately()
    }

    private fun handleMalformedIntent(intent: Intent?) {
        warnMalformedForegroundIntent(
            intent?.action,
            intent?.extras?.keySet() ?: emptySet(),
        )
        val identity = intent?.let { decodeIdentity(it, warnOnFailure = false) }
        if (identity != null && identity == sessionIdentity) {
            failActiveSession("malformed")
        } else if (sessionIdentity == null) {
            stopImmediately()
        }
    }

    private fun failActiveSession(
        site: String,
        failure: RuntimeException? = null,
    ) {
        val identity = sessionIdentity ?: return
        if (failure != null) {
            AppLog.e(
                LogComponent.SERVICE,
                "notification.post",
                failure,
                "outcome" to "fail",
                "site" to site,
                "runId" to identity.runId,
                "generation" to identity.generation,
                "leaseId" to identity.leaseId,
            )
        }
        registry.beginServiceTermination(
            identity,
            serviceToken,
            MiningForegroundCancellationReason.PROTOCOL_ERROR,
        )
        stopImmediately()
    }

    private fun stopImmediately() {
        cpuWakeLease.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopColdStart(startId: Int) {
        cpuWakeLease.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    @SuppressLint("InlinedApi")
    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        identity: MiningForegroundSessionIdentity,
        progress: MiningForegroundProgress,
    ): Notification {
        val text =
            miningNotificationProgressText(progress)
                ?.let { getString(it.resourceId, *it.formatArguments.toTypedArray()) }
                ?: getString(R.string.mining_notification_preparing)
        return baseNotification(identity, text)
            .setProgress(
                progress.total ?: 0,
                progress.completed ?: 0,
                progress.total == null,
            ).addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.mining_notification_cancel),
                cancelPendingIntent(identity),
            ).build()
    }

    private fun buildCancellingNotification(identity: MiningForegroundSessionIdentity): Notification =
        baseNotification(identity, getString(R.string.mining_notification_cancelling))
            .setProgress(0, 0, true)
            .build()

    private fun baseNotification(
        identity: MiningForegroundSessionIdentity,
        text: String,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mining)
            .setContentTitle(getString(R.string.mining_notification_title))
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(identity))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

    private fun openAppPendingIntent(identity: MiningForegroundSessionIdentity): PendingIntent {
        return PendingIntent.getActivity(
            this,
            identity.leaseId.hashCode(),
            openAppIntent(this, identity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(identity: MiningForegroundSessionIdentity): PendingIntent =
        PendingIntent.getService(
            this,
            identity.leaseId.hashCode() xor CANCEL_REQUEST_CODE_MASK,
            cancelIntent(this, identity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun decodeIdentity(
        intent: Intent,
        warnOnFailure: Boolean = true,
    ): MiningForegroundSessionIdentity? =
        decodeMiningForegroundIntentIdentity(
            action = intent.action,
            extraKeys = intent.extras?.keySet() ?: emptySet(),
            runId = intent.getStringExtra(EXTRA_RUN_ID),
            generation = intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE),
            leaseId = intent.getStringExtra(EXTRA_LEASE_ID),
            warnOnFailure = warnOnFailure,
        )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "mining"
        private const val NOTIFICATION_ID = 1001
        private const val CANCEL_REQUEST_CODE_MASK = 0x4d494e45

        private const val ACTION_START = "com.ankiminer.android.service.START"
        private const val ACTION_UPDATE = "com.ankiminer.android.service.UPDATE"
        private const val ACTION_CANCEL = "com.ankiminer.android.service.CANCEL"
        private const val ACTION_OPEN_APP = "com.ankiminer.android.service.OPEN_APP"

        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_GENERATION = "generation"
        private const val EXTRA_LEASE_ID = "lease_id"
        internal val IDENTITY_EXTRA_KEYS = setOf(EXTRA_RUN_ID, EXTRA_GENERATION, EXTRA_LEASE_ID)

        internal fun serviceIntent(context: Context): Intent =
            Intent(context, MiningForegroundService::class.java)

        internal fun startIntent(
            context: Context,
            identity: MiningForegroundSessionIdentity,
        ): Intent =
            serviceIntent(context).apply {
                action = ACTION_START
                putIdentity(identity)
            }

        internal fun updateIntent(
            context: Context,
            identity: MiningForegroundSessionIdentity,
        ): Intent =
            serviceIntent(context).apply {
                action = ACTION_UPDATE
                putIdentity(identity)
            }

        /** Resolve only this service's immutable notification-open payload. */
        internal fun openedRunId(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN_APP || intent.extras?.keySet() != IDENTITY_EXTRA_KEYS) {
                return null
            }
            val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return null
            val generation = intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE)
            val leaseId = intent.getStringExtra(EXTRA_LEASE_ID) ?: return null
            return runCatching {
                MiningForegroundSessionIdentity(runId, generation, leaseId).runId
            }.onFailure { failure ->
                AppLog.ignored(
                    LogComponent.SERVICE,
                    "notification.identity",
                    "invalid_notification_identity",
                    failure,
                )
            }.getOrNull()
        }

        /** Resolve and remove a notification-open payload so Activity recreation cannot replay it. */
        internal fun consumeOpenedRunId(intent: Intent?): String? {
            val consumedIntent = intent ?: return null
            val runId = openedRunId(consumedIntent) ?: return null
            consumedIntent.action = null
            IDENTITY_EXTRA_KEYS.forEach(consumedIntent::removeExtra)
            return runId
        }

        internal fun openAppIntent(
            context: Context,
            identity: MiningForegroundSessionIdentity,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_APP
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putIdentity(identity)
            }

        private fun cancelIntent(
            context: Context,
            identity: MiningForegroundSessionIdentity,
        ): Intent =
            serviceIntent(context).apply {
                action = ACTION_CANCEL
                putIdentity(identity)
            }

        private fun Intent.putIdentity(identity: MiningForegroundSessionIdentity) {
            putExtra(EXTRA_RUN_ID, identity.runId)
            putExtra(EXTRA_GENERATION, identity.generation)
            putExtra(EXTRA_LEASE_ID, identity.leaseId)
        }
    }
}

internal enum class ForegroundCommandDisposition {
    HANDLE_ACTIVE_COMMAND,
    IGNORE_STALE_COMMAND,
    STOP_COLD_SERVICE,
}

internal fun foregroundCommandDisposition(
    activeIdentity: MiningForegroundSessionIdentity?,
    commandIdentity: MiningForegroundSessionIdentity?,
): ForegroundCommandDisposition =
    when {
        activeIdentity == null -> ForegroundCommandDisposition.STOP_COLD_SERVICE
        commandIdentity == activeIdentity -> ForegroundCommandDisposition.HANDLE_ACTIVE_COMMAND
        else -> ForegroundCommandDisposition.IGNORE_STALE_COMMAND
    }
