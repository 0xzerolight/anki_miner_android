package com.ankiminer.android.media

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun interface ProviderIoCancellationRegistration : Closeable {
    override fun close()
}

/**
 * Sticky cancellation for provider operations.
 *
 * Implementations must invoke a newly registered listener immediately when cancellation already
 * happened. Closing the returned registration removes a listener unless cancellation has already
 * captured it for delivery. Listeners may run on any thread and must therefore remain small and
 * thread-safe.
 */
internal fun interface ProviderIoCancellation {
    fun isCancelled(): Boolean

    fun invokeOnCancellation(
        listener: () -> Unit,
    ): ProviderIoCancellationRegistration = ProviderIoCancellationRegistration { }

    companion object {
        val NONE = ProviderIoCancellation { false }
    }
}

/** Thread-safe cancellation source used by provider-I/O owners and tests. */
internal class ProviderIoCancellationController : ProviderIoCancellation {
    private val monitor = Any()
    private var cancelled = false
    private var nextRegistration = 1L
    private val listeners = linkedMapOf<Long, () -> Unit>()

    override fun isCancelled(): Boolean = synchronized(monitor) { cancelled }

    override fun invokeOnCancellation(
        listener: () -> Unit,
    ): ProviderIoCancellationRegistration {
        val registration =
            synchronized(monitor) {
                if (cancelled) null else nextRegistration++.also { listeners[it] = listener }
            }
        if (registration == null) listenerSafely(listener)
        return ProviderIoCancellationRegistration {
            if (registration != null) synchronized(monitor) { listeners.remove(registration) }
        }
    }

    fun cancel(): Boolean {
        val callbacks =
            synchronized(monitor) {
                if (cancelled) return false
                cancelled = true
                listeners.values.toList().also { listeners.clear() }
            }
        callbacks.forEach(::listenerSafely)
        return true
    }

    private fun listenerSafely(listener: () -> Unit) {
        try {
            listener()
            // instrumentation: silent — sticky cancellation outlives an already closed resource
        } catch (_: Exception) {
            // Sticky cancellation survives a resource which another cleanup path already closed.
        }
    }
}

internal fun ProviderIoCancellation.combine(
    other: ProviderIoCancellation,
): ProviderIoCancellation =
    object : ProviderIoCancellation {
        override fun isCancelled(): Boolean =
            this@combine.isCancelled() || other.isCancelled()

        override fun invokeOnCancellation(
            listener: () -> Unit,
        ): ProviderIoCancellationRegistration {
            val invoked = AtomicBoolean(false)
            val invokeOnce = { if (invoked.compareAndSet(false, true)) listener() }
            val first = this@combine.invokeOnCancellation(invokeOnce)
            val second = other.invokeOnCancellation(invokeOnce)
            return ProviderIoCancellationRegistration {
                try {
                    first.close()
                } finally {
                    second.close()
                }
            }
        }
    }

internal class ProviderIoCancelledException(cause: Throwable? = null) :
    IOException("Provider I/O was cancelled", cause)

internal class ProviderIoTimeoutException :
    IOException("Provider I/O exceeded its deadline")

internal fun interface ProviderIoDeadlineScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ProviderIoCancellationRegistration
}

internal object RealProviderIoDeadlineScheduler : ProviderIoDeadlineScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(
            1,
            ThreadFactory { runnable ->
                Thread(runnable, "saf-provider-deadline").apply { isDaemon = true }
            },
        ).apply { removeOnCancelPolicy = true }

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ProviderIoCancellationRegistration {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return ProviderIoCancellationRegistration { future.cancel(false) }
    }
}

/**
 * Shared cancellation boundary for blocking provider open, read, query, and copy work.
 *
 * Android provider opens and queries must run through [withCancellationSignal], which wires the
 * caller to the platform [CancellationSignal]. Blocking reads must run through [useResource],
 * which exposes the active stream to the cancellation listener and closes it exactly once from a
 * cancelling thread. [execute] moves provider work to a supplied process scope, returns promptly
 * on coroutine cancellation or deadline expiry, and leaves late provider completion unable to
 * resume the caller.
 *
 * A provider may ignore both its platform signal and descriptor closure. In that case the worker
 * can remain blocked, but it owns no global SAF bookkeeping lock and cannot publish a late result.
 */
internal object CancellableProviderIo {
    fun <T : Closeable, R> useResource(
        cancellation: ProviderIoCancellation,
        open: () -> T,
        block: (T) -> R,
    ): R {
        val slot = CloseOnceSlot<T>()
        val registration = cancellation.invokeOnCancellation(slot::cancelSafely)
        return withCleanup(
            cleanup = arrayOf({ registration.close() }, { slot.close() }),
        ) {
            try {
                throwIfCancelled(cancellation)
                val resource = CloseOnce(open())
                slot.install(resource)
                throwIfCancelled(cancellation)
                block(resource.value)
            } catch (failure: Throwable) {
                throw cancellationFailure(cancellation, failure)
            }
        }
    }

    fun <T : Closeable> open(
        cancellation: ProviderIoCancellation,
        block: (CancellationSignal) -> T,
    ): T {
        val signal = CancellationSignal()
        val slot = CloseOnceSlot<T>()
        val registration =
            cancellation.invokeOnCancellation {
                try {
                    cancelSafely(signal::cancel)
                } finally {
                    slot.cancelSafely()
                }
            }
        return withCleanup(
            cleanup = arrayOf({ registration.close() }, { slot.close() }),
        ) {
            try {
                throwIfCancelled(cancellation)
                val resource = CloseOnce(block(signal))
                slot.install(resource)
                throwIfCancelled(cancellation)
                checkNotNull(slot.publish(cancellation)) {
                    "Provider resource was cancelled before ownership publication"
                }
            } catch (failure: Throwable) {
                throw cancellationFailure(cancellation, failure)
            }
        }
    }

    fun <T> withCancellationSignal(
        cancellation: ProviderIoCancellation,
        block: (CancellationSignal) -> T,
    ): T {
        val signal = CancellationSignal()
        val registration =
            cancellation.invokeOnCancellation {
                cancelSafely(signal::cancel)
            }
        return withCleanup(cleanup = arrayOf({ registration.close() })) {
            try {
                throwIfCancelled(cancellation)
                val result = block(signal)
                throwIfCancelled(cancellation)
                result
            } catch (failure: Throwable) {
                throw cancellationFailure(cancellation, failure)
            }
        }
    }

    suspend fun <T> execute(
        scope: CoroutineScope,
        timeoutMillis: Long,
        scheduler: ProviderIoDeadlineScheduler = RealProviderIoDeadlineScheduler,
        operation: (ProviderIoCancellation) -> T,
    ): T {
        require(timeoutMillis > 0L) { "Provider I/O deadline must be positive" }
        return suspendCancellableCoroutine { continuation ->
            val cancellation = ProviderIoCancellationController()
            val completed = AtomicBoolean(false)
            val deadline = AtomicReference<ProviderIoCancellationRegistration?>()

            fun closeDeadline() {
                deadline.getAndSet(CLOSED_REGISTRATION)?.close()
            }

            val scheduled =
                scheduler.schedule(timeoutMillis) {
                    if (completed.compareAndSet(false, true)) {
                        closeDeadline()
                        cancellation.cancel()
                        continuation.resumeWith(Result.failure(ProviderIoTimeoutException()))
                    }
                }
            if (!deadline.compareAndSet(null, scheduled)) scheduled.close()

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    closeDeadline()
                    cancellation.cancel()
                }
            }
            scope.launch {
                val result = runCatching { operation(cancellation) }
                if (completed.compareAndSet(false, true)) {
                    closeDeadline()
                    continuation.resumeWith(result)
                }
            }
        }
    }

    private fun throwIfCancelled(cancellation: ProviderIoCancellation) {
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
    }

    private fun cancelSafely(cancel: () -> Unit) {
        try {
            cancel()
            // instrumentation: silent — operation cleanup re-observes stored close failure
        } catch (_: Exception) {
            // The operation thread re-observes stored resource-close failures during cleanup.
        }
    }

    private inline fun <T> withCleanup(
        cleanup: Array<out () -> Unit>,
        block: () -> T,
    ): T {
        var primaryFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            cleanup.forEach { action ->
                try {
                    action()
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure
                    } else if (cleanupFailure !== failure) {
                        cleanupFailure?.addSuppressed(failure)
                    }
                }
            }
            cleanupFailure?.let { failure ->
                if (primaryFailure == null) {
                    throw failure
                }
                if (primaryFailure !== failure) {
                    primaryFailure?.addSuppressed(failure)
                }
            }
        }
    }

    private fun cancellationFailure(
        cancellation: ProviderIoCancellation,
        failure: Throwable,
    ): Throwable =
        if (
            cancellation.isCancelled() &&
            failure !is ProviderIoCancelledException &&
            failure !is ProviderIoTimeoutException
        ) {
            ProviderIoCancelledException(failure)
        } else if (failure is OperationCanceledException) {
            ProviderIoCancelledException(failure)
        } else {
            failure
        }

    private val CLOSED_REGISTRATION = ProviderIoCancellationRegistration { }

    private class CloseOnce<T : Closeable>(
        val value: T,
    ) {
        private val closed = AtomicBoolean(false)
        private val closeFailure = AtomicReference<Throwable?>()

        fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    value.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                    throw failure
                }
            } else {
                closeFailure.get()?.let { throw it }
            }
        }
    }

    private class CloseOnceSlot<T : Closeable> {
        private val monitor = Any()
        private var resource: CloseOnce<T>? = null
        private var cancelled = false

        fun install(opened: CloseOnce<T>) {
            val closeNow =
                synchronized(monitor) {
                    check(resource == null)
                    resource = opened
                    cancelled
                }
            if (closeNow) opened.close()
        }

        fun cancel() {
            val active =
                synchronized(monitor) {
                    cancelled = true
                    resource
                }
            active?.close()
        }

        fun cancelSafely() {
            try {
                cancel()
                // instrumentation: silent — operation thread re-observes stored close failure
            } catch (_: Exception) {
                // close() re-observes the stored failure on the operation thread.
            }
        }

        fun publish(cancellation: ProviderIoCancellation): T? =
            synchronized(monitor) {
                if (cancelled || cancellation.isCancelled()) return null
                resource?.value.also { resource = null }
            }

        fun close() {
            val active = synchronized(monitor) { resource.also { resource = null } }
            active?.close()
        }
    }
}
