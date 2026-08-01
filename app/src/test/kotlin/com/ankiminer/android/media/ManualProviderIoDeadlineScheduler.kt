package com.ankiminer.android.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Deadline scheduler that never fires on its own: the test decides when a window elapses, so
 * stall behaviour is exercised without wall-clock sleeps.
 */
internal class ManualProviderIoDeadlineScheduler : ProviderIoDeadlineScheduler {
    private val lock = ReentrantLock()
    private val armedCondition = lock.newCondition()
    private var armed: (() -> Unit)? = null

    /** Number of deadline windows opened, including every rearm. */
    val armCount = AtomicInteger()

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ProviderIoCancellationRegistration {
        lock.withLock {
            armed = action
            armCount.incrementAndGet()
            armedCondition.signalAll()
        }
        return ProviderIoCancellationRegistration {
            lock.withLock { if (armed === action) armed = null }
        }
    }

    /** Runs the armed deadline on the calling thread, waiting briefly for one to be armed. */
    fun fireArmedDeadline(timeoutMillis: Long = 5_000L) {
        val action =
            lock.withLock {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                while (armed == null) {
                    check(remaining > 0L) { "no provider deadline was armed" }
                    remaining = armedCondition.awaitNanos(remaining)
                }
                checkNotNull(armed)
            }
        action()
    }
}
