package com.ankiminer.android.timing

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.ProviderIoCancellationController
import com.ankiminer.android.media.ProviderIoCancellationRegistration
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafJobFileOwner
import com.ankiminer.android.mining.TokenizerConfigurator
import com.ankiminer.android.subtitles.SubtitleCueLookupService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TimingPreviewBusyException :
    IllegalStateException("Another runtime task is already running")

/**
 * Pre-run subtitle staging. The runtime lease and staged input remain owned by the returned
 * session, so neither resource publication nor mining can race the workbench.
 */
internal class TimingPreviewLoader(
    private val coordinator: RuntimeWorkCoordinator,
    private val ownerFactory: (FileCopyCancellation) -> SafJobFileOwner,
    private val tokenizer: TokenizerConfigurator,
    private val cueLookup: SubtitleCueLookupService,
    private val io: CoroutineDispatcher,
    private val resourceDispatcher: CoroutineDispatcher = io,
) : TimingPreviewOpener {
    override suspend fun open(subtitle: SafDocument): Result<TimingPreviewSession> {
        val lease =
            coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
                ?: return Result.failure(TimingPreviewBusyException())
        val copyCancellation = TimingPreviewCopyCancellation()
        val owner =
            try {
                ownerFactory(copyCancellation)
            } catch (failure: Throwable) {
                lease.close()
                return Result.failure(failure)
            }
        try {
            val subtitlePath =
                stageSubtitle(
                    owner = owner,
                    subtitle = subtitle,
                    cancellation = copyCancellation,
                )
            withContext(resourceDispatcher) {
                tokenizer.configureInstalled()
            }
            val cues = cueLookup.cues(runId = null, subtitlePath = subtitlePath).getOrThrow()
            return Result.success(
                TimingPreviewSession(
                    cues = cues,
                ) {
                    withContext(NonCancellable + io) {
                        try {
                            owner.close()
                        } finally {
                            lease.close()
                        }
                    }
                },
            )
        } catch (failure: CancellationException) {
            copyCancellation.cancel()
            closeAfterFailure(owner, lease, failure)
            throw failure
        } catch (failure: Throwable) {
            closeAfterFailure(owner, lease, failure)
            return Result.failure(failure)
        }
    }

    private suspend fun stageSubtitle(
        owner: SafJobFileOwner,
        subtitle: SafDocument,
        cancellation: TimingPreviewCopyCancellation,
    ): String =
        coroutineScope {
            val stagingFinished = AtomicBoolean(false)
            // Unconfined makes the cancellation hook run immediately even while provider I/O is
            // blocking the injected IO dispatcher.
            val cancellationWatcher =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!stagingFinished.get()) cancellation.cancel()
                    }
                }
            try {
                withContext(io) {
                    owner.materializeSubtitleUri(subtitle.uri, subtitle.displayName).path
                }
            } finally {
                stagingFinished.set(true)
                cancellationWatcher.cancel()
            }
        }

    private suspend fun closeAfterFailure(
        owner: SafJobFileOwner,
        lease: RuntimeWorkCoordinator.Lease,
        failure: Throwable,
    ) {
        try {
            TimingPreviewSession(emptyList()) {
                withContext(NonCancellable + io) {
                    try {
                        owner.close()
                    } finally {
                        lease.close()
                    }
                }
            }.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }
}

internal fun interface TimingPreviewOpener {
    suspend fun open(subtitle: SafDocument): Result<TimingPreviewSession>
}

/** Owns the staged subtitle and runtime lease until the workbench leaves the window. */
internal class TimingPreviewSession internal constructor(
    val cues: List<SubtitleCue>,
    private val closeAction: suspend () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeAction()
    }
}

private class TimingPreviewCopyCancellation : FileCopyCancellation {
    private val delegate = ProviderIoCancellationController()

    override fun isCancelled(): Boolean = delegate.isCancelled()

    override fun invokeOnCancellation(
        listener: () -> Unit,
    ): ProviderIoCancellationRegistration = delegate.invokeOnCancellation(listener)

    fun cancel() {
        delegate.cancel()
    }
}
