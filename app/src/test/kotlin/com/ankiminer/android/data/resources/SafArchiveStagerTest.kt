package com.ankiminer.android.data.resources

import com.ankiminer.android.media.ManualProviderIoDeadlineScheduler
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoDeadlineScheduler
import com.ankiminer.android.media.RealProviderIoDeadlineScheduler
import com.ankiminer.android.media.awaitProviderIoWorkerRelease
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafArchiveStagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @After
    fun awaitCancelledProviderWorker() = awaitProviderIoWorkerRelease()

    @Test
    fun audioArchivePrefersRawBytesAndClassifiesZip() {
        val root = temporary.newFolder("audio-raw-zip")
        val rawBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val assetBytes = "provider preview".encodeToByteArray()
        var rawOpens = 0
        var assetOpens = 0
        val opener =
            object : ResourceInputOpener {
                override fun open(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream {
                    assetOpens++
                    return ByteArrayInputStream(assetBytes)
                }

                override fun openRaw(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream? {
                    rawOpens++
                    return ByteArrayInputStream(rawBytes)
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val result =
                testStager(root, scope, opener).stageAudioArchive(
                    INPUT_URI,
                    "audio-raw-zip",
                    ResourceCancellationSignal(),
                ) { _, _ -> }

            assertEquals(1, rawOpens)
            assertEquals(0, assetOpens)
            assertEquals(AudioArchiveReadMode.RAW, result.readMode)
            assertEquals(AudioArchiveContainer.ZIP, result.container)
            assertArrayEquals(rawBytes, result.archive.file.readBytes())
            assertEquals(rawBytes.size.toLong(), result.archive.sizeBytes)
            assertEquals(sha256(rawBytes), result.archive.sha256)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun audioArchiveRecognizesEverySupportedContainerSignature() {
        val tar = ByteArray(300) { 7 }.also { "ustar".encodeToByteArray().copyInto(it, 257) }
        val fixtures =
            listOf(
                byteArrayOf(0x50, 0x4b, 0x03, 0x04) to AudioArchiveContainer.ZIP,
                byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00) to AudioArchiveContainer.XZ,
                byteArrayOf(0x1f, 0x8b.toByte()) to AudioArchiveContainer.GZIP,
                tar to AudioArchiveContainer.TAR,
            )

        fixtures.forEachIndexed { index, (signature, expected) ->
            val root = temporary.newFolder("audio-signature-$index")
            val bytes = signature + ByteArray(400) { (it % 251).toByte() }
            val opener = RecordingAudioOpener(raw = { ByteArrayInputStream(bytes) })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val result =
                    testStager(root, scope, opener).stageAudioArchive(
                        INPUT_URI,
                        "audio-signature-$index",
                        ResourceCancellationSignal(),
                    ) { _, _ -> }

                assertEquals(expected, result.container)
                assertEquals(AudioArchiveReadMode.RAW, result.readMode)
                assertArrayEquals(bytes, result.archive.file.readBytes())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun audioArchiveRejectsShortTorrentHtmlAndRandomRawBytesBeforeCopyingRemainder() {
        val fixtures =
            listOf(
                byteArrayOf(0x1f),
                ByteArray(261).also { "usta".encodeToByteArray().copyInto(it, 257) },
                "d8:announce".encodeToByteArray() + ByteArray(1_024),
                "<!doctype html>".encodeToByteArray() + ByteArray(1_024),
                ByteArray(1_024) { (it % 239).toByte() },
            )

        fixtures.forEachIndexed { index, bytes ->
            val root = temporary.newFolder("audio-unrecognized-$index")
            val source = CountingInputStream(bytes)
            val opener = RecordingAudioOpener(raw = { source })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val failure =
                    assertThrows(ResourceDownloadException::class.java) {
                        testStager(root, scope, opener).stageAudioArchive(
                            INPUT_URI,
                            "audio-unrecognized-$index",
                            ResourceCancellationSignal(),
                        ) { _, _ -> }
                    }

                assertEquals("resource_archive_unrecognized", failure.stableCode)
                assertEquals(1, opener.rawOpens)
                assertEquals(0, opener.assetOpens)
                assertTrue(source.bytesRead <= 265)
                assertTrue(root.listFiles().orEmpty().isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun audioArchiveFallsBackToAssetOnlyWhenRawOpenReturnsNullOrNotFound() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
        val rawBehaviors =
            listOf<() -> InputStream?>(
                { null },
                { throw FileNotFoundException("raw representation unavailable") },
            )

        rawBehaviors.forEachIndexed { index, raw ->
            val root = temporary.newFolder("audio-asset-fallback-$index")
            val opener = RecordingAudioOpener(raw = raw, asset = { ByteArrayInputStream(bytes) })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val result =
                    testStager(root, scope, opener).stageAudioArchive(
                        INPUT_URI,
                        "audio-asset-fallback-$index",
                        ResourceCancellationSignal(),
                    ) { _, _ -> }

                assertEquals(AudioArchiveReadMode.ASSET_FALLBACK, result.readMode)
                assertEquals(AudioArchiveContainer.GZIP, result.container)
                assertEquals(1, opener.rawOpens)
                assertEquals(1, opener.assetOpens)
                assertArrayEquals(bytes, result.archive.file.readBytes())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun unrecognizedAssetFallbackReportsProviderRepresentation() {
        val root = temporary.newFolder("audio-provider-representation")
        val opener =
            RecordingAudioOpener(
                raw = { null },
                asset = { ByteArrayInputStream("provider preview".encodeToByteArray()) },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val failure =
                assertThrows(ResourceDownloadException::class.java) {
                    testStager(root, scope, opener).stageAudioArchive(
                        INPUT_URI,
                        "audio-provider-representation",
                        ResourceCancellationSignal(),
                    ) { _, _ -> }
                }

            assertEquals("resource_archive_provider_representation", failure.stableCode)
            assertEquals(1, opener.rawOpens)
            assertEquals(1, opener.assetOpens)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun audioArchiveNeverFallsBackAfterUnrecognizedOrFailedRawRead() {
        val rawInputs =
            listOf<() -> InputStream?>(
                { ByteArrayInputStream("not an archive".encodeToByteArray()) },
                {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("raw read failed for $INPUT_URI")

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int = throw IOException("raw read failed for $INPUT_URI")
                    }
                },
            )

        rawInputs.forEachIndexed { index, raw ->
            val root = temporary.newFolder("audio-no-fallback-$index")
            val opener =
                RecordingAudioOpener(
                    raw = raw,
                    asset = { ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val failure =
                    assertThrows(ResourceDownloadException::class.java) {
                        testStager(root, scope, opener).stageAudioArchive(
                            INPUT_URI,
                            "audio-no-fallback-$index",
                            ResourceCancellationSignal(),
                        ) { _, _ -> }
                    }

                assertEquals(1, opener.rawOpens)
                assertEquals(0, opener.assetOpens)
                assertTrue(root.listFiles().orEmpty().isEmpty())
                assertEquals(
                    if (index == 0) "resource_archive_unrecognized" else "import_source_unavailable",
                    failure.stableCode,
                )
                assertFalse(failure.toString().contains(INPUT_URI))
                if (index == 1) assertEquals(null, failure.cause)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun audioArchiveChecksLimitAfterSignatureAndRemovesPartial() {
        val root = temporary.newFolder("audio-limit")
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(512)
        val opener = RecordingAudioOpener(raw = { ByteArrayInputStream(bytes) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val failure =
                assertThrows(ResourceDownloadException::class.java) {
                    testStager(root, scope, opener).stageAudioArchive(
                        INPUT_URI,
                        "audio-limit",
                        ResourceCancellationSignal(),
                        maximumBytes = 128,
                    ) { _, _ -> }
                }

            assertEquals("resource_archive_too_large", failure.stableCode)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun audioArchiveCancellationInterruptsRawOpenWithoutAssetFallback() {
        val root = temporary.newFolder("audio-cancelled-open")
        val openStarted = CountDownLatch(1)
        val openCancelled = CountDownLatch(1)
        val assetOpens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val opener =
            object : ResourceInputOpener {
                override fun open(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream {
                    assetOpens.incrementAndGet()
                    return ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
                }

                override fun openRaw(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream? {
                    openStarted.countDown()
                    val registration = cancellation.invokeOnCancellation(openCancelled::countDown)
                    try {
                        check(openCancelled.await(5, TimeUnit.SECONDS)) {
                            "raw provider open did not receive cancellation"
                        }
                        throw IOException("raw provider open cancelled")
                    } finally {
                        registration.close()
                    }
                }
            }
        val cancellation = ResourceCancellationSignal()
        try {
            val staged =
                executor.submit<StagedAudioArchive> {
                    testStager(root, scope, opener).stageAudioArchive(
                        INPUT_URI,
                        "audio-cancelled-open",
                        cancellation,
                    ) { _, _ -> }
                }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure = assertThrows(ExecutionException::class.java) { staged.get(1, TimeUnit.SECONDS) }
            assertEquals(
                "resource_operation_cancelled",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertEquals(0, assetOpens.get())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun audioArchiveTimeoutDuringRawOpenNeverFallsBackToAsset() {
        val root = temporary.newFolder("audio-timeout-open")
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val assetOpens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val opener =
            object : ResourceInputOpener {
                override fun open(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream {
                    assetOpens.incrementAndGet()
                    return ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
                }

                override fun openRaw(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ): InputStream? {
                    openStarted.countDown()
                    check(releaseOpen.await(5, TimeUnit.SECONDS)) {
                        "test raw provider open was not released"
                    }
                    throw IOException("late raw provider open")
                }
            }
        try {
            val staged =
                executor.submit<StagedAudioArchive> {
                    testStager(root, scope, opener, timeoutMillis = 50).stageAudioArchive(
                        INPUT_URI,
                        "audio-timeout-open",
                        ResourceCancellationSignal(),
                    ) { _, _ -> }
                }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            val failure = assertThrows(ExecutionException::class.java) { staged.get(1, TimeUnit.SECONDS) }
            assertEquals(
                "import_source_unavailable",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertEquals(0, assetOpens.get())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            releaseOpen.countDown()
            executor.shutdownNow()
            scope.cancel()
        }
    }

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
            if (!openStarted.await(1, TimeUnit.SECONDS)) {
                staged.get(1, TimeUnit.SECONDS)
                throw AssertionError("provider open never started")
            }

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

    @Test
    fun slowProviderTransferSurvivesRepeatedDeadlineWindows() {
        val root = temporary.newFolder("slow-transfer")
        val scheduler = ManualProviderIoDeadlineScheduler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = SlowInputStream(chunks = 4) { scheduler.fireArmedDeadline() }
        val stager =
            testStager(
                root = root,
                scope = scope,
                opener = ResourceInputOpener { _, _ -> source },
                scheduler = scheduler,
            )
        try {
            val staged =
                stager.stage(INPUT_URI, "slow-transfer", ResourceCancellationSignal()) { _, _ -> }

            assertEquals(4L, staged.sizeBytes)
            assertEquals(4L, staged.file.length())
            assertTrue("deadline was not rearmed per chunk", scheduler.armCount.get() >= 5)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun providerDeadlineStillFiresWhenProgressStops() {
        val root = temporary.newFolder("stalled-after-progress")
        val scheduler = ManualProviderIoDeadlineScheduler()
        val source = StalledInputStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val stager =
            testStager(
                root = root,
                scope = scope,
                opener = ResourceInputOpener { _, _ -> source },
                scheduler = scheduler,
            )
        try {
            val staged =
                executor.submit<StagedArchive> {
                    stager.stage(INPUT_URI, "stalled-after-progress", ResourceCancellationSignal()) { _, _ -> }
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            // The first chunk rearmed the deadline, so one window only rearms it again; the
            // window after it sees no progress and must abort the stalled provider.
            scheduler.fireArmedDeadline()
            scheduler.fireArmedDeadline()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    staged.get(1, TimeUnit.SECONDS)
                }
            assertEquals(
                "import_source_unavailable",
                (failure.cause as ResourceDownloadException).stableCode,
            )
            assertEquals(1, source.closeCalls.get())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    private fun testStager(
        root: java.io.File,
        scope: CoroutineScope,
        opener: ResourceInputOpener,
        timeoutMillis: Long = 5_000,
        scheduler: ProviderIoDeadlineScheduler = RealProviderIoDeadlineScheduler,
    ) = SafArchiveStager(
        inputOpener = opener,
        stagingRoot = root,
        availableBytes = { Long.MAX_VALUE / 2 },
        providerIoScope = scope,
        providerIoTimeoutMillis = timeoutMillis,
        providerIoScheduler = scheduler,
    )

    private class RecordingAudioOpener(
        private val raw: () -> InputStream?,
        private val asset: () -> InputStream = { error("asset fallback was not expected") },
    ) : ResourceInputOpener {
        var rawOpens = 0
            private set
        var assetOpens = 0
            private set

        override fun open(
            uri: String,
            cancellation: ProviderIoCancellation,
        ): InputStream {
            assetOpens++
            return asset()
        }

        override fun openRaw(
            uri: String,
            cancellation: ProviderIoCancellation,
        ): InputStream? {
            rawOpens++
            return raw()
        }
    }

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
            private set

        override fun read(): Int =
            super.read().also { if (it >= 0) bytesRead++ }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Delivers one byte per read and lets the test elapse a deadline window between chunks. */
    private class SlowInputStream(
        private val chunks: Int,
        private val onWindowElapsed: () -> Unit,
    ) : InputStream() {
        private var delivered = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            // The first read has no progress to report yet, so its window must stay armed.
            if (delivered > 0) onWindowElapsed()
            if (delivered >= chunks) return -1
            delivered++
            buffer[offset] = 1
            return 1
        }
    }

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
