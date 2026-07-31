package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.compactFaultToken
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

    /**
     * A missing ABI wheel, a broken Chaquopy asset set, a home mismatch and an OOM are the same
     * catastrophic state to the UI but completely different bugs, and the tester report is the only
     * place a person ever sees this. [fault] is a [compactFaultToken], never the throwable: this
     * value is compared by the JVM tests and a `Throwable` field would give the data class identity
     * equality.
     */
    data class Failed(
        val stage: PythonBootstrapStage,
        val fault: String,
    ) : PythonRuntimeReadiness
}

/** How far Chaquopy startup got before it failed. */
internal enum class PythonBootstrapStage {
    ENQUEUE,
    START,
    DISPATCH,
    HANDSHAKE,
}

/**
 * Stage tag for a failure raised inside the Android initializer, which the generic
 * [PythonRuntimeBootstrapGate] cannot know on its own.
 *
 * Never carries an [Error]: see [pythonBootstrapStage].
 */
internal class PythonBootstrapFailure(
    val stage: PythonBootstrapStage,
    cause: Throwable,
) : Exception(cause)

/**
 * Tags whatever [block] throws with [stage].
 *
 * Catches [Exception] rather than [Throwable] on purpose. `Python.start` raises
 * `UnsatisfiedLinkError` when the ABI has no native wheel — the very failure this tagging exists to
 * identify — and [PythonRuntimeBootstrapGate.enqueueFirst] rethrows an [Error] out of the bootstrap
 * runnable so the process does not carry on over a half-loaded runtime. Reboxing an [Error] in an
 * [Exception] subclass would make that rethrow unreachable.
 */
internal inline fun <T> pythonBootstrapStage(
    stage: PythonBootstrapStage,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: Exception) {
        throw PythonBootstrapFailure(stage, failure)
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
                    // Unwrapped: the stage tag is for the readiness state only. await() rethrows
                    // ExecutionException.cause, so completing with the wrapper would leave every
                    // caller's PythonRuntimeUnavailableException.cause pointing at
                    // PythonBootstrapFailure instead of the failure itself.
                    val origin = (failure as? PythonBootstrapFailure)?.cause ?: failure
                    // An untagged throw is one the initializer's stage blocks did not cover, which
                    // on this path means startup: nothing later runs outside one.
                    val stage = (failure as? PythonBootstrapFailure)?.stage ?: PythonBootstrapStage.START
                    // The readiness state carries no stack, so this record is the only full account
                    // of why the engine never came up — and Python's own log handler does not exist
                    // yet at this point.
                    AppLog.e(LogComponent.BOOTSTRAP, "python.initialize", origin, "stage" to stage.name)
                    mutableReadiness.value = PythonRuntimeReadiness.Failed(stage, compactFaultToken(origin))
                    completion.completeExceptionally(origin)
                    if (origin is Error) throw origin
                }
            }
        } catch (failure: RuntimeException) {
            AppLog.e(LogComponent.BOOTSTRAP, "python.enqueue", failure)
            mutableReadiness.value =
                PythonRuntimeReadiness.Failed(PythonBootstrapStage.ENQUEUE, compactFaultToken(failure))
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
