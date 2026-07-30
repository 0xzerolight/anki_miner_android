package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface PythonRuntimeReadiness {
    data object Pending : PythonRuntimeReadiness

    data object Starting : PythonRuntimeReadiness

    data class Ready(val home: String) : PythonRuntimeReadiness

    data object Failed : PythonRuntimeReadiness
}
internal class PythonRuntimeUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The embedded Python runtime is unavailable", cause)

/** Pure concurrency primitive behind the Android-specific Chaquopy initializer. */
internal class PythonRuntimeBootstrapGate<T>(
    private val homeOf: (T) -> String,
) {
    private val enqueued = AtomicBoolean(false)
    private val completion = CompletableFuture<T>()
    private val mutableReadiness =
        MutableStateFlow<PythonRuntimeReadiness>(PythonRuntimeReadiness.Pending)
    val readiness: StateFlow<PythonRuntimeReadiness> = mutableReadiness.asStateFlow()

    fun enqueueFirst(
        executor: Executor,
        initialize: () -> T,
    ) {
        check(enqueued.compareAndSet(false, true)) { "Python bootstrap was already enqueued" }
        try {
            executor.execute {
                mutableReadiness.value = PythonRuntimeReadiness.Starting
                try {
                    val runtime = initialize()
                    completion.complete(runtime)
                    mutableReadiness.value = PythonRuntimeReadiness.Ready(homeOf(runtime))
                } catch (failure: Throwable) {
                    // The readiness state carries no payload, so this record is the only account of
                    // why the engine never came up — and Python's own log handler does not exist
                    // yet at this point.
                    AppLog.e(LogComponent.BOOTSTRAP, "python.initialize", failure)
                    mutableReadiness.value = PythonRuntimeReadiness.Failed
                    completion.completeExceptionally(failure)
                    if (failure is Error) throw failure
                }
            }
        } catch (failure: RuntimeException) {
            AppLog.e(LogComponent.BOOTSTRAP, "python.enqueue", failure)
            mutableReadiness.value = PythonRuntimeReadiness.Failed
            completion.completeExceptionally(failure)
            throw failure
        }
    }

    fun await(checkWorkerThread: () -> Unit): T {
        checkWorkerThread()
        check(enqueued.get()) { "Python bootstrap has not been enqueued" }
        return try {
            completion.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PythonRuntimeUnavailableException(failure)
        } catch (failure: ExecutionException) {
            throw PythonRuntimeUnavailableException(failure.cause ?: failure)
        }
    }
}
