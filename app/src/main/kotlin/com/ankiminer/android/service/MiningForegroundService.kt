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
import java.util.UUID
import java.util.concurrent.TimeUnit

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
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_CANCEL -> handleCancel(intent)
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
        val activeIdentity = sessionIdentity
        if (activeIdentity != null) {
            if (identity == activeIdentity) {
                failActiveSession("start")
            }
            // A stale start must never terminate or mutate the current generation.
            return
        }
        if (identity == null) {
            stopImmediately()
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

    private fun handleUpdate(intent: Intent) {
        val identity = decodeIdentity(intent)
        val activeIdentity = sessionIdentity
        if (identity == null || identity != activeIdentity) {
            // Old notification/update intents are inert against a newer generation.
            return
        }
        val snapshot = registry.snapshotForService(identity, serviceToken)
        if (snapshot == null) {
            failActiveSession("update")
            return
        }
        try {
            startForegroundTyped(buildNotification(identity, snapshot.progress))
        } catch (failure: RuntimeException) {
            failActiveSession("update", failure)
        }
    }

    private fun handleCancel(intent: Intent) {
        val identity = decodeIdentity(intent)
        val activeIdentity = sessionIdentity
        if (identity == null || identity != activeIdentity) {
            // An obsolete notification action cannot cancel the current run.
            return
        }
        if (
            !registry.requestCancellation(
                identity,
                serviceToken,
                MiningForegroundCancellationReason.USER_REQUESTED,
            )
        ) {
            failActiveSession("cancel")
            return
        }
        try {
            startForegroundTyped(buildCancellingNotification(identity))
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
        AppLog.w(
            LogComponent.SERVICE,
            "intent.decode",
            IllegalArgumentException("Malformed foreground-service intent"),
            "outcome" to "ignored",
            "action" to intent?.action,
            "extraKeys" to (intent?.extras?.keySet()?.sorted() ?: emptyList<String>()),
        )
        val identity = intent?.let(::decodeIdentity)
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
            if (progress.completed != null && progress.total != null) {
                getString(
                    R.string.mining_notification_count,
                    progress.completed,
                    progress.total,
                )
            } else {
                getString(R.string.mining_notification_preparing)
            }
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

    private fun decodeIdentity(intent: Intent): MiningForegroundSessionIdentity? {
        if (intent.extras?.keySet() != IDENTITY_EXTRA_KEYS) return null
        if (
            !intent.hasExtra(EXTRA_RUN_ID) ||
            !intent.hasExtra(EXTRA_GENERATION) ||
            !intent.hasExtra(EXTRA_LEASE_ID)
        ) {
            return null
        }
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return null
        val generation = intent.getLongExtra(EXTRA_GENERATION, Long.MIN_VALUE)
        val leaseId = intent.getStringExtra(EXTRA_LEASE_ID) ?: return null
        return runCatching {
            MiningForegroundSessionIdentity(runId, generation, leaseId)
        }.getOrNull()
    }

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
        private val IDENTITY_EXTRA_KEYS = setOf(EXTRA_RUN_ID, EXTRA_GENERATION, EXTRA_LEASE_ID)

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
