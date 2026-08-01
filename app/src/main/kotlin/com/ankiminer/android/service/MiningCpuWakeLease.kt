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
 * Owns exactly one bounded CPU wake lease for the media-processing phases.
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
    private var closed = false

    init {
        require(timeoutMillis > 0)
    }

    fun acquire() {
        synchronized(monitor) {
            // A stale service command after teardown must not resurrect the platform lock.
            if (owned || closed) return
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

    /**
     * Drops the lock for a wait the user owns, leaving the service in the foreground.
     *
     * Curation parks the engine on a `threading.Event` while the user reads candidate sentences:
     * no media is processed, so a six-hour CPU lease over it is drain with no work behind it.
     * [acquire] re-arms with a fresh bound when phases 3-5 resume.
     */
    fun park() {
        synchronized(monitor) { releaseLocked() }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            releaseLocked()
        }
    }

    /** Caller holds [monitor]. Releases at most once per acquisition. */
    private fun releaseLocked() {
        if (!owned) return
        owned = false
        // A timed platform lease may expire between isHeld and release. Cleanup must never
        // prevent stopForeground/stopSelf or the registry teardown which follows it.
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    internal fun isOwned(): Boolean = synchronized(monitor) { owned }

    private companion object {
        val MAX_MEDIA_PROCESSING_WAKE_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
