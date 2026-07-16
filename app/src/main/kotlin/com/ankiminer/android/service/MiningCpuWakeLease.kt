package com.ankiminer.android.service

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.TimeUnit

/** Minimal seam around PowerManager.WakeLock so ownership is host-unit-testable. */
internal interface MiningCpuWakeLock {
    val isHeld: Boolean

    fun acquire(timeoutMillis: Long)

    fun release()
}

internal class AndroidMiningCpuWakeLock private constructor(
    private val delegate: PowerManager.WakeLock,
) : MiningCpuWakeLock {
    override val isHeld: Boolean
        get() = delegate.isHeld

    override fun acquire(timeoutMillis: Long) = delegate.acquire(timeoutMillis)

    override fun release() = delegate.release()

    companion object {
        fun create(context: Context): AndroidMiningCpuWakeLock {
            val manager = context.getSystemService(PowerManager::class.java)
            val wakeLock =
                manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${context.packageName}:mining-media",
                )
            wakeLock.setReferenceCounted(false)
            return AndroidMiningCpuWakeLock(wakeLock)
        }
    }
}

/**
 * Owns exactly one bounded CPU wake lease for the post-curation foreground phase.
 *
 * Android also drops the platform lock when this process dies. The explicit close path covers
 * every service-controlled exit, while the timeout bounds damage if an OEM skips a callback.
 */
internal class MiningCpuWakeLease(
    private val wakeLock: MiningCpuWakeLock,
    private val timeoutMillis: Long = MAX_MEDIA_PROCESSING_WAKE_MILLIS,
) : AutoCloseable {
    private val monitor = Any()
    private var owned = false

    init {
        require(timeoutMillis > 0)
    }

    fun acquire() {
        synchronized(monitor) {
            if (owned) return
            try {
                wakeLock.acquire(timeoutMillis)
                check(wakeLock.isHeld) { "CPU wake lock was not acquired" }
                owned = true
            } catch (failure: RuntimeException) {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
                throw failure
            }
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (!owned) return
            owned = false
            // A timed platform lease may expire between isHeld and release. Cleanup must never
            // prevent stopForeground/stopSelf or the registry teardown which follows it.
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    internal fun isOwned(): Boolean = synchronized(monitor) { owned }

    private companion object {
        val MAX_MEDIA_PROCESSING_WAKE_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
