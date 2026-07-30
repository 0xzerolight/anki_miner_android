package com.ankiminer.android.data.resources

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafArchiveStagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun cancellationInterruptsBlockedProviderOpenAndRemovesDestination() {
        val root = temporary.newFolder("blocked-open")
        val openStarted = CountDownLatch(1)
        val openCancelled = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val stager =
            testStager(
                root = root,
                scope = scope,
                opener =
                    ResourceInputOpener { _, cancellation ->
                        openStarted.countDown()
                        val registration =
                            cancellation.invokeOnCancellation {
                                openCancelled.countDown()
                            }
                        try {
                            check(openCancelled.await(5, TimeUnit.SECONDS)) {
                                "provider open did not receive cancellation"
                            }
                            throw IOException("provider open cancelled")
                        } finally {
                            registration.close()
                        }
                    },
            )
        val cancellation = ResourceCancellationSignal()
        try {
            val staged =
                executor.submit<StagedArchive> {
                    stager.stage(INPUT_URI, "blocked-open", cancellation) { _, _ -> }
                }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    staged.get(1, TimeUnit.SECONDS)
                }
            assertEquals(
                "resource_operation_cancelled",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun cancellationClosesBlockedProviderReadOnceAndRemovesPartial() {
        val root = temporary.newFolder("blocked-read")
        val source = StalledInputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val stager =
            testStager(
                root = root,
                scope = scope,
                opener = ResourceInputOpener { _, _ -> source },
            )
        val cancellation = ResourceCancellationSignal()
        try {
            val staged =
                executor.submit<StagedArchive> {
                    stager.stage(INPUT_URI, "blocked-read", cancellation) { _, _ -> }
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    staged.get(1, TimeUnit.SECONDS)
                }
            assertEquals(
                "resource_operation_cancelled",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertEquals(1, source.closeCalls.get())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun providerDeadlineReturnsWhenOpenIgnoresCancellation() {
        val root = temporary.newFolder("blocked-timeout")
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val stager =
            testStager(
                root = root,
                scope = scope,
                timeoutMillis = 50,
                opener =
                    ResourceInputOpener { _, _ ->
                        openStarted.countDown()
                        check(releaseOpen.await(5, TimeUnit.SECONDS)) {
                            "test provider open was not released"
                        }
                        throw IOException("late provider open")
                    },
            )
        try {
            val staged =
                executor.submit<StagedArchive> {
                    stager.stage(INPUT_URI, "blocked-timeout", ResourceCancellationSignal()) { _, _ -> }
                }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            val failure =
                assertThrows(ExecutionException::class.java) {
                    staged.get(1, TimeUnit.SECONDS)
                }

            assertEquals(
                "import_source_unavailable",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            releaseOpen.countDown()
            executor.shutdownNow()
            scope.cancel()
        }
    }

    private fun testStager(
        root: java.io.File,
        scope: CoroutineScope,
        opener: ResourceInputOpener,
        timeoutMillis: Long = 5_000,
    ) = SafArchiveStager(
        inputOpener = opener,
        stagingRoot = root,
        availableBytes = { Long.MAX_VALUE / 2 },
        providerIoScope = scope,
        providerIoTimeoutMillis = timeoutMillis,
    )

    private class StalledInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val closed = CountDownLatch(1)
        private val suppliedFirstChunk = AtomicBoolean()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (suppliedFirstChunk.compareAndSet(false, true)) {
                buffer[offset] = 1
                return 1
            }
            readStarted.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "provider read was not cancelled" }
            throw IOException("provider descriptor closed")
        }

        override fun close() {
            if (closeCalls.incrementAndGet() == 1) {
                closed.countDown()
            }
        }
    }

    private companion object {
        const val INPUT_URI = "content://fixtures/resource.zip"
    }
}
