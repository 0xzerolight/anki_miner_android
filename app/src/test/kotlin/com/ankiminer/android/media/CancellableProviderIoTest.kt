package com.ankiminer.android.media

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableProviderIoTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, failure -> uncaught.add(failure) },
        )

    @After
    fun awaitCancelledProviderWorker() = awaitProviderIoWorkerRelease()

    @Test
    fun deadlineFailsAnOperationThatNeverReportsProgress() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                execute(scheduler, executor) { deadline ->
                    deadline.invokeOnCancellation { cancelled.countDown() }
                    started.countDown()
                    check(cancelled.await(5, TimeUnit.SECONDS)) { "operation was never cancelled" }
                    throw IOException("provider stream closed")
                }
            assertTrue(started.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun progressRearmsTheDeadlineAndTheOperationCompletes() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val reported = CountDownLatch(1)
        val windowElapsed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                execute(scheduler, executor) { deadline ->
                    deadline.rearm()
                    reported.countDown()
                    check(windowElapsed.await(5, TimeUnit.SECONDS)) { "deadline never fired" }
                    "done"
                }
            assertTrue(reported.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()
            windowElapsed.countDown()

            assertEquals("done", result.get(1, TimeUnit.SECONDS))
            // Entry window, start window, and the rearm after reported progress.
            assertTrue("deadline was not rearmed", scheduler.armCount.get() >= 3)
            assertTrue(uncaught.isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun deadlineStillFiresOnceProgressStops() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val reported = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                execute(scheduler, executor) { deadline ->
                    deadline.invokeOnCancellation { cancelled.countDown() }
                    deadline.rearm()
                    reported.countDown()
                    check(cancelled.await(5, TimeUnit.SECONDS)) { "operation was never cancelled" }
                    throw IOException("provider stream closed")
                }
            assertTrue(reported.await(1, TimeUnit.SECONDS))

            // Progress happened inside the first window, so it only rearms; the second window
            // sees nothing and must fail the operation.
            scheduler.fireArmedDeadline()
            scheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun lateOperationResultAfterTheDeadlineIsDiscarded() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                execute(scheduler, executor) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "operation was never released" }
                    "late"
                }
            assertTrue(started.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)

            release.countDown()
            runBlocking { scope.coroutineContext.job.children.forEach { it.join() } }
            assertTrue("late operation result resumed the caller twice", uncaught.isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun timedOutWorkerPreventsASecondProviderWorkerFromStarting() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val retryScheduler = ManualProviderIoDeadlineScheduler()
        val started = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                execute(scheduler, executor) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "operation was never released" }
                    "late"
                }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val worker = scope.coroutineContext.job.children.single()

            scheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            // The worker is still parked inside the wedged provider call, so the deadline must
            // have cancelled it rather than leaving it running and unreferenced on the scope.
            assertTrue("worker job outlived the deadline uncancelled", worker.isCancelled)

            val retry =
                execute(retryScheduler, executor) {
                    retryStarted.countDown()
                    "retry"
                }
            assertFalse(
                "retry consumed another provider thread while the first was still blocked",
                retryStarted.await(100, TimeUnit.MILLISECONDS),
            )
            val retryFailure =
                assertThrows(ExecutionException::class.java) {
                    retry.get(1, TimeUnit.SECONDS)
                }
            assertTrue(retryFailure.cause is ProviderIoTimeoutException)
            assertEquals(0, retryScheduler.armCount.get())

            release.countDown()
            runBlocking { worker.join() }
            assertTrue("late operation result resumed the caller twice", uncaught.isEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun callerCancellationCancelsTheWorkerJob() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val caller =
                callerScope.launch {
                    CancellableProviderIo.execute(
                        scope = scope,
                        timeoutMillis = 5_000L,
                        scheduler = scheduler,
                    ) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "operation was never released" }
                        "late"
                    }
                }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val worker = scope.coroutineContext.job.children.single()

            runBlocking {
                caller.cancelAndJoin()
            }

            assertTrue("worker job outlived caller cancellation", worker.isCancelled)
            release.countDown()
            runBlocking { worker.join() }
            assertTrue("late operation result resumed the caller twice", uncaught.isEmpty())
        } finally {
            callerScope.cancel()
            scope.cancel()
        }
    }

    @Test
    fun anOperationQueuedBehindAProgressingCopyWaitsInsteadOfTimingOut() {
        // A pick made while a long SAF copy holds the single provider lane used to fail after
        // one window of pure queue time, though the copy ahead of it never stalled.
        val copyScheduler = ManualProviderIoDeadlineScheduler()
        val pickScheduler = ManualProviderIoDeadlineScheduler()
        val copyStarted = CountDownLatch(1)
        val chunks = LinkedBlockingQueue<Boolean>()
        val delivered = Semaphore(0)
        val pickRan = AtomicBoolean(false)
        val copyExecutor = Executors.newSingleThreadExecutor()
        val pickExecutor = Executors.newSingleThreadExecutor()
        try {
            val copy =
                execute(copyScheduler, copyExecutor) { deadline ->
                    copyStarted.countDown()
                    while (checkNotNull(chunks.poll(5, TimeUnit.SECONDS)) { "copy was never released" }) {
                        deadline.rearm()
                        delivered.release()
                    }
                    "copied"
                }
            assertTrue(copyStarted.await(1, TimeUnit.SECONDS))
            val pick =
                execute(pickScheduler, pickExecutor) {
                    pickRan.set(true)
                    "picked"
                }
            pickScheduler.awaitArmCount(1)

            repeat(2) {
                chunks.put(true)
                assertTrue(delivered.tryAcquire(1, TimeUnit.SECONDS))
                pickScheduler.fireArmedDeadline()
                val early = runCatching { pick.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull()
                assertTrue("queued pick ended behind a progressing copy: $early", early is TimeoutException)
            }
            assertFalse(pickRan.get())

            chunks.put(false)
            assertEquals("copied", copy.get(1, TimeUnit.SECONDS))
            assertEquals("picked", pick.get(1, TimeUnit.SECONDS))
            assertTrue(uncaught.isEmpty())
        } finally {
            chunks.put(false)
            copyExecutor.shutdownNow()
            pickExecutor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun anOperationAbandonedBeforeItStartsDoesNotBlockLaterOperations() {
        // A queued pick that was cancelled was recorded as a blocked provider worker, so every
        // later SAF call failed with "still blocked" until the copy ahead of it finished.
        val copyScheduler = ManualProviderIoDeadlineScheduler()
        val pickScheduler = ManualProviderIoDeadlineScheduler()
        val laterScheduler = ManualProviderIoDeadlineScheduler()
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val pickRan = AtomicBoolean(false)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val copyExecutor = Executors.newSingleThreadExecutor()
        val laterExecutor = Executors.newSingleThreadExecutor()
        try {
            val copy =
                execute(copyScheduler, copyExecutor) {
                    copyStarted.countDown()
                    check(releaseCopy.await(5, TimeUnit.SECONDS)) { "copy was never released" }
                    "copied"
                }
            assertTrue(copyStarted.await(1, TimeUnit.SECONDS))
            val pick =
                callerScope.launch {
                    CancellableProviderIo.execute(
                        scope = scope,
                        timeoutMillis = 5_000L,
                        scheduler = pickScheduler,
                    ) { pickRan.set(true) }
                }
            awaitWorkerCount(2)
            runBlocking { pick.cancelAndJoin() }

            val later = execute(laterScheduler, laterExecutor) { "later" }
            // Arming happens only after the blocked-worker gate admits the operation.
            laterScheduler.awaitArmCount(1)
            releaseCopy.countDown()

            assertEquals("copied", copy.get(1, TimeUnit.SECONDS))
            assertEquals("later", later.get(1, TimeUnit.SECONDS))
            assertFalse("abandoned pick still reached the provider", pickRan.get())
        } finally {
            releaseCopy.countDown()
            callerScope.cancel()
            copyExecutor.shutdownNow()
            laterExecutor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun anOperationQueuedBehindAStalledWorkerStillTimesOut() {
        // Queue time behind a healthy copy is not a stall, but queue time behind a wedged
        // provider is: the queued caller must still fail within its window instead of waiting
        // forever for a lane that never frees.
        val stalledScheduler = ManualProviderIoDeadlineScheduler()
        val pickScheduler = ManualProviderIoDeadlineScheduler()
        val stalledStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pickRan = AtomicBoolean(false)
        val stalledExecutor = Executors.newSingleThreadExecutor()
        val pickExecutor = Executors.newSingleThreadExecutor()
        try {
            execute(stalledScheduler, stalledExecutor) {
                stalledStarted.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "stalled worker was never released" }
                "late"
            }
            assertTrue(stalledStarted.await(1, TimeUnit.SECONDS))
            val pick =
                execute(pickScheduler, pickExecutor) {
                    pickRan.set(true)
                    "picked"
                }

            pickScheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { pick.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            release.countDown()
            awaitProviderIoWorkerRelease()
            assertFalse("timed-out pick still reached the provider", pickRan.get())
        } finally {
            release.countDown()
            stalledExecutor.shutdownNow()
            pickExecutor.shutdownNow()
            scope.cancel()
        }
    }

    private fun awaitWorkerCount(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (scope.coroutineContext.job.children.count() < count) {
            check(System.nanoTime() < deadline) { "provider worker was never launched" }
            Thread.sleep(1)
        }
    }

    private fun <T> execute(
        scheduler: ProviderIoDeadlineScheduler,
        executor: ExecutorService,
        operation: (ProviderIoDeadline) -> T,
    ): Future<T> =
        executor.submit<T> {
            runBlocking {
                CancellableProviderIo.execute(
                    scope = scope,
                    timeoutMillis = 5_000L,
                    scheduler = scheduler,
                    operation = operation,
                )
            }
        }
}
