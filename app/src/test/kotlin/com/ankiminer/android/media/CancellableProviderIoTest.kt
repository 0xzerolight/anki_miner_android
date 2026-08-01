package com.ankiminer.android.media

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            assertTrue("deadline was not rearmed", scheduler.armCount.get() >= 2)
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
    fun deadlineCancelsTheWorkerJobAndStillDiscardsItsLateResult() {
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
            val worker = scope.coroutineContext.job.children.single()

            scheduler.fireArmedDeadline()

            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            // The worker is still parked inside the wedged provider call, so the deadline must
            // have cancelled it rather than leaving it running and unreferenced on the scope.
            assertTrue("worker job outlived the deadline uncancelled", worker.isCancelled)

            release.countDown()
            runBlocking { worker.join() }
            assertTrue("late operation result resumed the caller twice", uncaught.isEmpty())
        } finally {
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
