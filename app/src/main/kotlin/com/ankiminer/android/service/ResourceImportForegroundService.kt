package com.ankiminer.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.R

internal fun handleResourceImportStopCommand(
    startId: Int,
    stopSelfResult: (Int) -> Boolean,
    parkCpuWakeLease: () -> Unit,
    removeForeground: () -> Unit,
) {
    if (!stopSelfResult(startId)) return
    parkCpuWakeLease()
    removeForeground()
}

internal fun handleResourceImportSystemTimeout(
    cancelActiveOperation: () -> Unit,
    closeCpuWakeLease: () -> Unit,
    removeForeground: () -> Unit,
    stopService: () -> Unit,
) {
    cancelActiveOperation()
    closeCpuWakeLease()
    removeForeground()
    stopService()
}

/**
 * Keeps a multi-gigabyte resource import running while the user is elsewhere.
 *
 * An audio pack from the upstream collection is thousands of seconds of copying
 * across a hundred thousand files. Without a foreground service the process is
 * cached the moment the user leaves the screen and killed under any memory
 * pressure, and the import has no resume — it restarts from zero. The type is
 * `dataSync` rather than `mediaProcessing` because nothing here is transcoded.
 *
 * The service owns the notification and the CPU lease and nothing else: the work
 * itself stays on the resource executor, which is what decides when this stops.
 */
class ResourceImportForegroundService : Service() {
    private lateinit var cpuWakeLease: MiningCpuWakeLease

    override fun onCreate() {
        super.onCreate()
        cpuWakeLease = MiningCpuWakeLease(AndroidMiningCpuWakeLock.create(this, WAKE_LOCK_TAG))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.resource_import_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.resource_import_notification_channel_description)
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
            ACTION_START -> {
                startForegroundTyped(
                    buildNotification(
                        intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.resource_import_notification_working),
                        intent.getIntExtra(EXTRA_COMPLETED, 0),
                        intent.getIntExtra(EXTRA_TOTAL, 0),
                    ),
                )
                cpuWakeLease.acquire()
            }

            ACTION_STOP -> {
                handleResourceImportStopCommand(
                    startId = startId,
                    stopSelfResult = ::stopSelfResult,
                    parkCpuWakeLease = cpuWakeLease::park,
                    removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
                )
            }

            // A restart the platform delivered with no intent of ours. There is no
            // operation behind it, so take the process back down rather than sit in
            // the foreground holding a lease over nothing.
            else -> {
                cpuWakeLease.close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

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
        cpuWakeLease.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleSystemTimeout() {
        handleResourceImportSystemTimeout(
            cancelActiveOperation = {
                (application as AnkiMinerApplication).resourceManager.cancelActive()
            },
            closeCpuWakeLease = cpuWakeLease::close,
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = ::stopSelf,
        )
    }

    @SuppressLint("InlinedApi")
    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        label: String,
        completed: Int,
        total: Int,
    ): Notification =
        NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText(getString(R.string.resource_import_notification_working))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, completed, total <= 0)
            .build()

    companion object {
        private const val ACTION_START = "com.ankiminer.android.action.RESOURCE_IMPORT_START"
        private const val ACTION_STOP = "com.ankiminer.android.action.RESOURCE_IMPORT_STOP"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_COMPLETED = "completed"
        private const val EXTRA_TOTAL = "total"
        private const val NOTIFICATION_CHANNEL_ID = "resource-import"
        private const val NOTIFICATION_ID = 0x52494D50
        private const val WAKE_LOCK_TAG = "resource-import"

        fun startIntent(
            context: Context,
            label: String,
            completed: Int,
            total: Int,
        ): Intent =
            Intent(context, ResourceImportForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_TOTAL, total)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ResourceImportForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
