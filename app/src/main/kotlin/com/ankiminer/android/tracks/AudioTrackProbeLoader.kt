package com.ankiminer.android.tracks

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.ProviderIoCancellationController
import com.ankiminer.android.media.ProviderIoCancellationRegistration
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafJobFileOwner
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

internal class AudioTrackProbeBusyException :
    IllegalStateException("Another runtime task is already running")

internal fun interface AudioTrackProbeOpener {
    suspend fun probe(video: SafDocument): Result<AudioTrackList>
}

internal class AudioTrackProbeLoader(
    private val coordinator: RuntimeWorkCoordinator,
    private val ownerFactory: (FileCopyCancellation) -> SafJobFileOwner,
    private val lookup: AudioTrackLookupService,
    private val io: CoroutineDispatcher,
) : AudioTrackProbeOpener {
    override suspend fun probe(video: SafDocument): Result<AudioTrackList> {
        val lease =
            coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
                ?: return Result.failure(AudioTrackProbeBusyException())
        val copyCancellation = AudioTrackProbeCopyCancellation()
        val owner =
            try {
                ownerFactory(copyCancellation)
            } catch (failure: Throwable) {
                lease.close()
                return Result.failure(failure)
            }
        var cancellation: CancellationException? = null
        var outcome: Result<AudioTrackList>? = null
        try {
            val videoPath =
                stageVideo(
                    owner = owner,
                    video = video,
                    cancellation = copyCancellation,
                )
            outcome = lookup.tracks(videoPath)
        } catch (failure: CancellationException) {
            copyCancellation.cancel()
            cancellation = failure
        } catch (failure: Throwable) {
            outcome = Result.failure(failure)
        }

        // A cleanup failure must never mask the outcome decided above by simply being the last
        // thing to throw: cancellation always wins over it (structured concurrency must not lose
        // the cancellation signal), and a resolved outcome absorbs it deliberately, as a failure,
        // with whatever it replaced attached via addSuppressed.
        val cleanupFailure = closeQuietly(owner, lease)
        cancellation?.let { pending ->
            cleanupFailure?.let(pending::addSuppressed)
            throw pending
        }
        if (cleanupFailure != null) {
            outcome?.exceptionOrNull()?.let(cleanupFailure::addSuppressed)
            return Result.failure(cleanupFailure)
        }
        return checkNotNull(outcome) { "audio track probe completed without an outcome" }
    }

    private suspend fun closeQuietly(
        owner: SafJobFileOwner,
        lease: RuntimeWorkCoordinator.Lease,
    ): Throwable? =
        withContext(NonCancellable + io) {
            val ownerFailure =
                try {
                    owner.close()
                    null
                } catch (failure: Throwable) {
                    failure
                }
            val leaseFailure =
                try {
                    lease.close()
                    null
                } catch (failure: Throwable) {
                    failure
                }
            when {
                ownerFailure == null -> leaseFailure
                leaseFailure == null -> ownerFailure
                else -> ownerFailure.also { it.addSuppressed(leaseFailure) }
            }
        }

    private suspend fun stageVideo(
        owner: SafJobFileOwner,
        video: SafDocument,
        cancellation: AudioTrackProbeCopyCancellation,
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
                    owner.openVideoUri(video.uri).path
                }
            } finally {
                stagingFinished.set(true)
                cancellationWatcher.cancel()
            }
        }
}

private class AudioTrackProbeCopyCancellation : FileCopyCancellation {
    private val delegate = ProviderIoCancellationController()

    override fun isCancelled(): Boolean = delegate.isCancelled()

    override fun invokeOnCancellation(
        listener: () -> Unit,
    ): ProviderIoCancellationRegistration = delegate.invokeOnCancellation(listener)

    fun cancel() {
        delegate.cancel()
    }
}
