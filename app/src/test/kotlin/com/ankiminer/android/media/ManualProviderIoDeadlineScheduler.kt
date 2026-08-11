package com.ankiminer.android.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

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

/** Prevent a deliberately wedged provider fake from leaking into the next JVM test. */
internal fun awaitProviderIoWorkerRelease(timeoutMillis: Long = 1_000L) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val scope = CoroutineScope(SupervisorJob())
    try {
        while (System.nanoTime() < deadline) {
            try {
                runBlocking {
                    CancellableProviderIo.execute(
                        scope = scope,
                        timeoutMillis = timeoutMillis,
                    ) { Unit }
                }
                return
            } catch (_: ProviderIoTimeoutException) {
                Thread.yield()
            }
        }
        throw AssertionError("Timed out waiting for cancelled provider worker to return")
    } finally {
        scope.cancel()
    }
}
