package com.ankiminer.android.reading

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.FileCopyLimitExceededException
import com.ankiminer.android.media.FileCopySizeMismatchException
import com.ankiminer.android.media.FileCopyStorageException
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancellationController
import com.ankiminer.android.media.ProviderIoCancellationRegistration
import com.ankiminer.android.media.SafDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReadingSourceStagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `default staging caps match the mobile reading boundary`() {
        val limits = ReadingSourceStageLimits()

        assertEquals(8L * 1024 * 1024, limits.textMaxBytes)
        assertEquals(256L * 1024 * 1024, limits.epubMaxBytes)
        assertEquals(8L * 1024 * 1024, limits.subtitleMaxBytes)
        assertEquals(16L * 1024 * 1024, limits.mokuroSidecarMaxBytes)
        assertEquals(1024L * 1024 * 1024, limits.mokuroArchiveMaxBytes)
        assertEquals(
            limits.mokuroSidecarMaxBytes + limits.mokuroArchiveMaxBytes,
            limits.jobMaxBytes,
        )
    }

    @Test
    fun `single reading sources preserve safe stems and canonicalize supported extensions`() {
        val cases =
            listOf(
                SingleCase("Novel.TXT", ReadingSourceStageRole.TEXT, StagedReadingSourceKind.TXT, "Novel.txt"),
                SingleCase("Book.EPUB", ReadingSourceStageRole.EPUB, StagedReadingSourceKind.EPUB, "Book.epub"),
                SingleCase(
                    "Episode.SRT",
                    ReadingSourceStageRole.SUBTITLE,
                    StagedReadingSourceKind.SUBTITLE,
                    "Episode.srt",
                ),
                SingleCase(
                    "Volume.MOKURO",
                    ReadingSourceStageRole.MOKURO_SIDECAR,
                    StagedReadingSourceKind.MOKURO,
                    "Volume.mokuro",
                ),
            )

        cases.forEachIndexed { index, case ->
            val root = File(temporary.root, "single-$index")
            val document = document("content://reading/$index", case.displayName, 4L)
            val opener = FakeReadingSourceOpener(mapOf(document.uri to "data".toByteArray()))
            val staged = stager(root, opener).stage(ReadingSourceSelection.Single(document))
            val detector = File(staged.detectorPath)

            assertTrue(detector.isAbsolute)
            assertEquals(case.outputName, detector.name)
            assertEquals(case.sourceKind, staged.sourceKind)
            assertEquals(case.sourceKind.wireValue, staged.sourceKind.wireValue)
            assertEquals(case.role, staged.files.single().role)
            assertEquals(4L, staged.files.single().sizeBytes)
            assertTrue(staged.imageArchivePath == null)
            assertArrayEquals("data".toByteArray(), detector.readBytes())

            staged.close()
            staged.close()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `mokuro pair uses one NFC sidecar stem so detector sibling matching is exact`() {
        val root = File(temporary.root, "pair")
        val sidecar = document("content://reading/sidecar", "Cafe\u0301.MOKURO", 3L)
        val archive = document("content://reading/archive", "CAFÉ.CBZ", 4L)
        val opener =
            FakeReadingSourceOpener(
                mapOf(
                    sidecar.uri to "ocr".toByteArray(),
                    archive.uri to "page".toByteArray(),
                ),
            )
        val progress = mutableListOf<ReadingSourceStageProgress>()

        val staged =
            stager(root, opener).stage(
                ReadingSourceSelection.MokuroArchivePair(sidecar, archive),
                progressListener = ReadingSourceStageProgressListener(progress::add),
            )

        val files = staged.files.associateBy(StagedReadingFile::role)
        val stagedSidecar = checkNotNull(files[ReadingSourceStageRole.MOKURO_SIDECAR]).file
        val stagedArchive = checkNotNull(files[ReadingSourceStageRole.MOKURO_ARCHIVE]).file
        assertEquals("Café.mokuro", stagedSidecar.name)
        assertEquals("Café.cbz", stagedArchive.name)
        assertEquals(stagedSidecar.nameWithoutExtension, stagedArchive.nameWithoutExtension)
        assertEquals(StagedReadingSourceKind.MOKURO, staged.sourceKind)
        assertEquals(stagedSidecar.absolutePath, staged.detectorPath)
        assertEquals(stagedArchive.absolutePath, staged.imageArchivePath)
        assertEquals(
            listOf(
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_SIDECAR, 0L, 3L),
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_SIDECAR, 2L, 3L),
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_SIDECAR, 3L, 3L),
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_ARCHIVE, 0L, 4L),
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_ARCHIVE, 2L, 4L),
                ReadingSourceStageProgress(ReadingSourceStageRole.MOKURO_ARCHIVE, 4L, 4L),
            ),
            progress,
        )

        staged.close()
        assertFalse(stagedSidecar.exists())
        assertFalse(stagedArchive.exists())
    }

    @Test
    fun `invalid SAF metadata and unsafe source combinations fail before opening a source`() {
        val root = File(temporary.root, "invalid")
        val opener = FakeReadingSourceOpener(emptyMap())
        val stager = stager(root, opener)
        val invalidCases =
            listOf(
                ReadingSourceSelection.Single(document("file:///tmp/book.txt", "book.txt", 1L)) to
                    ReadingSourceSelectionFailure.INVALID_URI,
                ReadingSourceSelection.Single(document("content://reading/unsafe", "../book.txt", 1L)) to
                    ReadingSourceSelectionFailure.INVALID_DISPLAY_NAME,
                ReadingSourceSelection.Single(document("content://reading/pdf", "book.pdf", 1L)) to
                    ReadingSourceSelectionFailure.UNSUPPORTED_EXTENSION,
                ReadingSourceSelection.MokuroArchivePair(
                    document("content://reading/a", "one.mokuro", 1L),
                    document("content://reading/b", "two.cbz", 1L),
                ) to ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
                ReadingSourceSelection.MokuroArchivePair(
                    document("content://reading/same", "same.mokuro", 1L),
                    document("content://reading/same", "same.zip", 1L),
                ) to ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
                ReadingSourceSelection.MokuroArchivePair(
                    document("content://reading/text", "same.txt", 1L),
                    document("content://reading/zip", "same.zip", 1L),
                ) to ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
            )

        invalidCases.forEach { (selection, expected) ->
            val failure =
                assertThrows(ReadingSourceSelectionException::class.java) {
                    stager.stage(selection)
                }
            assertEquals(expected, failure.failure)
        }
        assertEquals(0, opener.openedUris.size)
        assertFalse(root.exists())
    }

    @Test
    fun `every known size and the aggregate storage requirement are preflighted before source open`() {
        val sidecar = document("content://reading/preflight-sidecar", "volume.mokuro", 4L)
        val archive = document("content://reading/preflight-archive", "volume.cbz", 7L)
        val selection = ReadingSourceSelection.MokuroArchivePair(sidecar, archive)

        val roleRoot = File(temporary.root, "role-limit")
        val roleOpener = FakeReadingSourceOpener(emptyMap())
        val roleFailure =
            assertThrows(FileCopyLimitExceededException::class.java) {
                stager(
                    roleRoot,
                    roleOpener,
                    limits = limits(mokuroArchiveMaxBytes = 6L, jobMaxBytes = 20L),
                ).stage(selection)
            }
        assertEquals(6L, roleFailure.maxBytes)
        assertEquals(7L, roleFailure.observedBytes)
        assertFalse(roleRoot.exists())
        assertTrue(roleOpener.openedUris.isEmpty())

        val totalRoot = File(temporary.root, "total-limit")
        val totalOpener = FakeReadingSourceOpener(emptyMap())
        val totalFailure =
            assertThrows(ReadingSourceTotalLimitExceededException::class.java) {
                stager(
                    totalRoot,
                    totalOpener,
                    limits = limits(mokuroArchiveMaxBytes = 10L, jobMaxBytes = 10L),
                ).stage(selection)
            }
        assertEquals(10L, totalFailure.maxBytes)
        assertEquals(11L, totalFailure.observedBytes)
        assertFalse(totalRoot.exists())
        assertTrue(totalOpener.openedUris.isEmpty())

        val storageRoot = File(temporary.root, "storage")
        val storageOpener = FakeReadingSourceOpener(emptyMap())
        val storageFailure =
            assertThrows(FileCopyStorageException::class.java) {
                stager(
                    storageRoot,
                    storageOpener,
                    limits = limits(mokuroArchiveMaxBytes = 10L, jobMaxBytes = 20L, reserveBytes = 3L),
                    availableBytes = { 13L },
                ).stage(selection)
            }
        assertEquals(14L, storageFailure.requiredBytes)
        assertEquals(13L, storageFailure.availableBytes)
        assertTrue(storageRoot.listFiles().orEmpty().isEmpty())
        assertTrue(storageOpener.openedUris.isEmpty())
    }

    @Test
    fun `unknown sizes stay streaming bounded and a failed archive removes the complete pair`() {
        val root = File(temporary.root, "stream-limit")
        val sidecar = document("content://reading/unknown-sidecar", "volume.mokuro", null)
        val archive = document("content://reading/unknown-archive", "volume.zip", null)
        val opener =
            FakeReadingSourceOpener(
                mapOf(
                    sidecar.uri to "side".toByteArray(),
                    archive.uri to "1234567".toByteArray(),
                ),
            )

        val failure =
            assertThrows(FileCopyLimitExceededException::class.java) {
                stager(
                    root,
                    opener,
                    limits = limits(mokuroSidecarMaxBytes = 5L, mokuroArchiveMaxBytes = 5L),
                ).stage(ReadingSourceSelection.MokuroArchivePair(sidecar, archive))
            }

        assertEquals(5L, failure.maxBytes)
        assertEquals(6L, failure.observedBytes)
        assertEquals(listOf(sidecar.uri, archive.uri), opener.openedUris)
        assertTrue(root.listFiles().orEmpty().isEmpty())

        val totalRoot = File(temporary.root, "stream-total")
        val totalOpener =
            FakeReadingSourceOpener(
                mapOf(
                    sidecar.uri to "side".toByteArray(),
                    archive.uri to "page".toByteArray(),
                ),
            )
        val totalFailure =
            assertThrows(ReadingSourceTotalLimitExceededException::class.java) {
                stager(
                    totalRoot,
                    totalOpener,
                    limits =
                        limits(
                            mokuroSidecarMaxBytes = 5L,
                            mokuroArchiveMaxBytes = 5L,
                            jobMaxBytes = 6L,
                        ),
                ).stage(ReadingSourceSelection.MokuroArchivePair(sidecar, archive))
            }
        assertEquals(6L, totalFailure.maxBytes)
        assertEquals(8L, totalFailure.observedBytes)
        assertTrue(totalFailure.cause is FileCopyLimitExceededException)
        assertTrue(totalRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `streaming storage checks preserve the reserve and clean partial bytes`() {
        val root = File(temporary.root, "stream-storage")
        val source = document("content://reading/storage-stream", "book.txt", null)
        val opener = FakeReadingSourceOpener(mapOf(source.uri to "four".toByteArray()))
        val availability = ArrayDeque(listOf(100L, 100L, 100L, 4L))

        val failure =
            assertThrows(FileCopyStorageException::class.java) {
                stager(
                    root,
                    opener,
                    limits = limits(reserveBytes = 3L),
                    availableBytes = { availability.removeFirst() },
                ).stage(ReadingSourceSelection.Single(source))
            }

        assertEquals(5L, failure.requiredBytes)
        assertEquals(4L, failure.availableBytes)
        assertTrue(availability.isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `known provider size mismatch removes the whole private job directory`() {
        val root = File(temporary.root, "size-mismatch")
        val source = document("content://reading/changing", "changing.txt", 5L)
        val opener = FakeReadingSourceOpener(mapOf(source.uri to "four".toByteArray()))

        val failure =
            assertThrows(FileCopySizeMismatchException::class.java) {
                stager(root, opener).stage(ReadingSourceSelection.Single(source))
            }

        assertEquals(5L, failure.expectedBytes)
        assertEquals(4L, failure.actualBytes)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `progress cancellation leaves no staged source or job directory`() {
        val root = File(temporary.root, "cancel")
        val source = document("content://reading/cancel", "cancel.txt", null)
        val opener = FakeReadingSourceOpener(mapOf(source.uri to "abcdef".toByteArray()))
        val cancelled = AtomicBoolean(false)
        val progress = mutableListOf<ReadingSourceStageProgress>()

        assertThrows(FileCopyCancelledException::class.java) {
            stager(root, opener).stage(
                selection = ReadingSourceSelection.Single(source),
                cancellation = FileCopyCancellation(cancelled::get),
                progressListener =
                    ReadingSourceStageProgressListener {
                        progress += it
                        if (it.copiedBytes == 2L) cancelled.set(true)
                    },
            )
        }

        assertEquals(
            listOf(
                ReadingSourceStageProgress(ReadingSourceStageRole.TEXT, 0L, null),
                ReadingSourceStageProgress(ReadingSourceStageRole.TEXT, 2L, null),
            ),
            progress,
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation interrupts a blocked provider open and removes the stage`() {
        val root = File(temporary.root, "cancel-blocked-open")
        val source = document("content://reading/blocked-open", "blocked.txt", null)
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val controller = ProviderIoCancellationController()
        val cancellation =
            object : FileCopyCancellation {
                override fun isCancelled(): Boolean = controller.isCancelled()

                override fun invokeOnCancellation(
                    listener: () -> Unit,
                ): ProviderIoCancellationRegistration =
                    controller.invokeOnCancellation(listener)
            }
        val opener =
            ReadingSourceInputOpener { _, providerCancellation ->
                val registration =
                    providerCancellation.invokeOnCancellation {
                        releaseOpen.countDown()
                    }
                try {
                    openStarted.countDown()
                    assertTrue(releaseOpen.await(1, TimeUnit.SECONDS))
                    throw IOException("provider open interrupted")
                } finally {
                    registration.close()
                }
            }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staging =
                executor.submit<StagedReadingSource> {
                    stager(root, opener).stage(
                        ReadingSourceSelection.Single(source),
                        cancellation = cancellation,
                    )
                }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            controller.cancel()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    staging.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is FileCopyCancelledException)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `empty sources fail before open when known and after bounded copy when unknown`() {
        val knownRoot = File(temporary.root, "known-empty")
        val known = document("content://reading/known-empty", "empty.txt", 0L)
        val knownOpener = FakeReadingSourceOpener(emptyMap())
        val knownFailure =
            assertThrows(EmptyReadingSourceException::class.java) {
                stager(knownRoot, knownOpener).stage(ReadingSourceSelection.Single(known))
            }
        assertEquals(ReadingSourceStageRole.TEXT, knownFailure.role)
        assertFalse(knownRoot.exists())
        assertTrue(knownOpener.openedUris.isEmpty())

        val unknownRoot = File(temporary.root, "unknown-empty")
        val unknown = document("content://reading/unknown-empty", "empty.epub", null)
        val unknownOpener = FakeReadingSourceOpener(mapOf(unknown.uri to byteArrayOf()))
        val unknownFailure =
            assertThrows(EmptyReadingSourceException::class.java) {
                stager(unknownRoot, unknownOpener).stage(ReadingSourceSelection.Single(unknown))
            }
        assertEquals(ReadingSourceStageRole.EPUB, unknownFailure.role)
        assertEquals(listOf(unknown.uri), unknownOpener.openedUris)
        assertTrue(unknownRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `nonce collision is retried without touching the existing directory`() {
        val root = File(temporary.root, "collision").apply { mkdirs() }
        val firstNonce = "1".repeat(32)
        val secondNonce = "2".repeat(32)
        val collision = File(root, "reading-job-v1-$firstNonce").apply { mkdir() }
        val marker = File(collision, "keep.bin").apply { writeText("keep") }
        val source = document("content://reading/collision", "book.txt", 4L)
        val opener = FakeReadingSourceOpener(mapOf(source.uri to "book".toByteArray()))

        val staged =
            stager(
                root,
                opener,
                nonceSource = QueueNonceSource(firstNonce, secondNonce),
            ).stage(ReadingSourceSelection.Single(source))

        assertEquals(
            "reading-job-v1-$secondNonce",
            requireNotNull(File(staged.detectorPath).parentFile).name,
        )
        assertTrue(marker.isFile)
        staged.close()
        assertTrue(marker.isFile)
    }

    @Test
    fun `orphan janitor removes only owned directory names with owned flat source shapes`() {
        val root = File(temporary.root, "janitor").apply { mkdirs() }
        val empty = ownedDirectory(root, '1')
        val single = ownedDirectory(root, '2').apply { File(this, "Novel.txt").writeText("text") }
        val pair =
            ownedDirectory(root, '3').apply {
                File(this, "Volume.mokuro").writeText("ocr")
                File(this, "Volume.cbz").writeText("archive")
            }
        val nearName = File(root, "reading-job-v1-${"4".repeat(31)}z").apply { mkdir() }
        val invalidFile = ownedDirectory(root, '5').apply { File(this, "unowned.bin").writeText("keep") }
        val archiveOnly = ownedDirectory(root, '6').apply { File(this, "Volume.zip").writeText("keep") }
        val noncanonical = ownedDirectory(root, 'c').apply { File(this, "Novel.TXT").writeText("keep") }
        val mismatch =
            ownedDirectory(root, '7').apply {
                File(this, "One.mokuro").writeText("keep")
                File(this, "Two.cbz").writeText("keep")
            }
        val inexactPair =
            ownedDirectory(root, 'd').apply {
                File(this, "Volume.mokuro").writeText("keep")
                File(this, "volume.cbz").writeText("keep")
            }
        val nested = ownedDirectory(root, '8').apply { File(this, "nested").mkdir() }
        val outside = File(temporary.root, "outside").apply { mkdir() }
        val outsideFile = File(outside, "outside.txt").apply { writeText("outside") }
        val linkedFileStage = ownedDirectory(root, 'b')
        val linkedFile = File(linkedFileStage, "Linked.txt")
        Files.createSymbolicLink(linkedFile.toPath(), outsideFile.toPath())
        val linked = File(root, "reading-job-v1-${"9".repeat(32)}")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())

        val removed = ReadingSourceStageJanitor(root).removeOrphans()

        // archiveOnly is now an owned orphan: a crash between the archive copy
        // and the embedded-sidecar extraction legitimately leaves a lone archive.
        assertEquals(5, removed)
        listOf(empty, single, pair, archiveOnly, linkedFileStage).forEach { assertFalse(it.exists()) }
        listOf(nearName, invalidFile, noncanonical, mismatch, inexactPair, nested, linked, outside)
            .forEach { assertTrue(it.exists()) }
        assertTrue(outsideFile.isFile)
    }

    @Test
    fun `janitor rejects a symlink staging root without touching its target`() {
        val target = File(temporary.root, "target").apply { mkdir() }
        val marker = File(target, "reading-job-v1-${"a".repeat(32)}").apply { mkdir() }
        val link = File(temporary.root, "root-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        assertThrows(IOException::class.java) {
            ReadingSourceStageJanitor(link).removeOrphans()
        }

        assertTrue(marker.isDirectory)
    }

    @Test
    fun `lone archive stages a sidecar extracted from its single embedded mokuro member`() {
        listOf("cbz", "zip").forEachIndexed { index, extension ->
            val root = File(temporary.root, "lone-archive-$extension")
            val archiveBytes =
                zipBytes(
                    "manga_volume/001.jpg" to "jpeg-one".toByteArray(),
                    "manga_volume/002.jpg" to "jpeg-two".toByteArray(),
                    "manga_volume.mokuro" to "{\"pages\":[]}".toByteArray(),
                )
            val document =
                document("content://reading/lone-$index", "Manga Volume.$extension", archiveBytes.size.toLong())
            val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

            val staged =
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))

            val files = staged.files.associateBy(StagedReadingFile::role)
            val sidecar = checkNotNull(files[ReadingSourceStageRole.MOKURO_SIDECAR]).file
            val archive = checkNotNull(files[ReadingSourceStageRole.MOKURO_ARCHIVE]).file
            assertEquals(StagedReadingSourceKind.MOKURO, staged.sourceKind)
            assertEquals("Manga Volume.mokuro", sidecar.name)
            assertEquals("Manga Volume.$extension", archive.name)
            assertEquals(sidecar.absolutePath, staged.detectorPath)
            assertEquals(archive.absolutePath, staged.imageArchivePath)
            assertArrayEquals("{\"pages\":[]}".toByteArray(), sidecar.readBytes())
            assertEquals(2, checkNotNull(archive.parentFile).listFiles().orEmpty().size)

            staged.close()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `lone archive rejects a generated sidecar name over 255 UTF-8 bytes before open`() {
        val root = File(temporary.root, "generated-name-limit")
        val stem = "a".repeat(251)
        val document = document("content://reading/long-name", "$stem.cbz", 1L)
        val opener = FakeReadingSourceOpener(emptyMap())

        val failure =
            assertThrows(ReadingSourceSelectionException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(ReadingSourceSelectionFailure.INVALID_DISPLAY_NAME, failure.failure)
        assertFalse(root.exists())
        assertTrue(opener.openedUris.isEmpty())
    }

    @Test
    fun `EOCD member cap is checked before archive parser allocation`() {
        val root = File(temporary.root, "eocd-member-limit")
        val archiveBytes =
            zipBytes("volume.mokuro" to "{\"pages\":[]}".toByteArray()).also { bytes ->
                val eocd = bytes.lastIndexOfSignature(0x50, 0x4b, 0x05, 0x06)
                bytes.writeLittleEndianU16(eocd + 8, 4_097)
                bytes.writeLittleEndianU16(eocd + 10, 4_097)
            }
        val document = document("content://reading/eocd-limit", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val failure =
            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(EmbeddedSidecarFailure.UNREADABLE_ARCHIVE, failure.failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `embedded sidecar member stem may differ from the archive stem`() {
        val root = File(temporary.root, "stem-mismatch")
        val archiveBytes =
            zipBytes(
                "scans/whatever.mokuro" to "ocr".toByteArray(),
                "scans/001.jpg" to "jpeg".toByteArray(),
            )
        val document = document("content://reading/stem", "本.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val staged =
            stager(root, opener, limits = archiveLimits())
                .stage(ReadingSourceSelection.Single(document))

        assertEquals("本.mokuro", File(staged.detectorPath).name)
        staged.close()
    }

    @Test
    fun `top level embedded sidecar wins over nested duplicates`() {
        val root = File(temporary.root, "top-level-wins")
        val archiveBytes =
            zipBytes(
                "volume.mokuro" to "top".toByteArray(),
                "backup/volume.mokuro" to "nested".toByteArray(),
            )
        val document = document("content://reading/top", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val staged =
            stager(root, opener, limits = archiveLimits())
                .stage(ReadingSourceSelection.Single(document))

        assertArrayEquals("top".toByteArray(), File(staged.detectorPath).readBytes())
        staged.close()
    }

    @Test
    fun `archive without any mokuro member fails with a no-member reason and removes the stage`() {
        val root = File(temporary.root, "no-member")
        val archiveBytes = zipBytes("manga_volume/001.jpg" to "jpeg".toByteArray())
        val document = document("content://reading/none", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val failure =
            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(EmbeddedSidecarFailure.NO_MOKURO_MEMBER, failure.failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive with multiple ambiguous mokuro members fails with a multiple-member reason`() {
        val root = File(temporary.root, "multi-member")
        val archiveBytes =
            zipBytes(
                "a/volume.mokuro" to "one".toByteArray(),
                "b/volume.mokuro" to "two".toByteArray(),
            )
        val document = document("content://reading/multi", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val failure =
            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(EmbeddedSidecarFailure.MULTIPLE_MOKURO_MEMBERS, failure.failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `zip slip and junk members are never candidates and never escape the stage`() {
        val root = File(temporary.root, "junk-members")
        val archiveBytes =
            zipBytes(
                "../evil.mokuro" to "evil".toByteArray(),
                "__MACOSX/resource.mokuro" to "junk".toByteArray(),
                ".hidden.mokuro" to "hidden".toByteArray(),
                "nested/.also-hidden.mokuro" to "hidden".toByteArray(),
            )
        val document = document("content://reading/junk", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val failure =
            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(EmbeddedSidecarFailure.NO_MOKURO_MEMBER, failure.failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertFalse(File(temporary.root, "evil.mokuro").exists())
    }

    @Test
    fun `oversized embedded sidecar member fails the sidecar byte cap and removes the stage`() {
        val root = File(temporary.root, "oversized-member")
        val archiveBytes =
            zipBytes("volume.mokuro" to ByteArray(64) { 'x'.code.toByte() })
        val document = document("content://reading/oversized", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        assertThrows(FileCopyLimitExceededException::class.java) {
            stager(
                root,
                opener,
                limits = archiveLimits(mokuroSidecarMaxBytes = 16L),
            ).stage(ReadingSourceSelection.Single(document))
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `empty embedded sidecar member fails as an empty source and removes the stage`() {
        val root = File(temporary.root, "empty-member")
        val archiveBytes = zipBytes("volume.mokuro" to ByteArray(0))
        val document = document("content://reading/empty-member", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

        val failure =
            assertThrows(EmptyReadingSourceException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(ReadingSourceStageRole.MOKURO_SIDECAR, failure.role)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `corrupt archive fails with an unreadable-archive reason and removes the stage`() {
        val root = File(temporary.root, "corrupt-archive")
        val document = document("content://reading/corrupt", "volume.cbz", 24L)
        val opener =
            FakeReadingSourceOpener(mapOf(document.uri to "this is not a zip file..").mapValues { it.value.toByteArray() })

        val failure =
            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

        assertEquals(EmbeddedSidecarFailure.UNREADABLE_ARCHIVE, failure.failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `corrupt embedded sidecar warning omits the member path at default verbosity`() {
        val recorded = RecordingLogSink()
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
        try {
            val root = File(temporary.root, "private-corrupt-member")
            val privateMember = "秘密/本.mokuro"
            val archiveBytes = corruptedDeflatedZipBytes(privateMember, "ocr-data".toByteArray())
            val document =
                document(
                    "content://reading/private-corrupt-member",
                    "volume.cbz",
                    archiveBytes.size.toLong(),
                )
            val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))

            assertThrows(EmbeddedSidecarException::class.java) {
                stager(root, opener, limits = archiveLimits())
                    .stage(ReadingSourceSelection.Single(document))
            }

            val warning = recorded.records.single { it.contains(" op=embedded_sidecar.copy ") }
            assertFalse(warning, warning.contains(privateMember))
            assertFalse(warning, warning.contains("秘密"))
            assertTrue(warning, warning.contains(" extension=mokuro"))
            assertTrue(warning, warning.contains(" nameBytes="))
        } finally {
            AppLog.install(NoOpSink)
        }
    }

    @Test
    fun `cancellation during embedded sidecar extraction removes the whole stage`() {
        val root = File(temporary.root, "cancel-extraction")
        val archiveBytes =
            zipBytes(
                "volume.mokuro" to "ocr-data".toByteArray(),
                "001.jpg" to "jpeg".toByteArray(),
            )
        val document = document("content://reading/cancel", "volume.cbz", archiveBytes.size.toLong())
        val opener = FakeReadingSourceOpener(mapOf(document.uri to archiveBytes))
        val cancelled = AtomicBoolean(false)

        assertThrows(FileCopyCancelledException::class.java) {
            stager(root, opener, limits = archiveLimits()).stage(
                ReadingSourceSelection.Single(document),
                cancellation = FileCopyCancellation(cancelled::get),
                progressListener =
                    ReadingSourceStageProgressListener { progress ->
                        if (progress.role == ReadingSourceStageRole.MOKURO_SIDECAR) {
                            cancelled.set(true)
                        }
                    },
            )
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun archiveLimits(mokuroSidecarMaxBytes: Long = 64L) =
        limits(
            mokuroSidecarMaxBytes = mokuroSidecarMaxBytes,
            mokuroArchiveMaxBytes = 4096L,
            jobMaxBytes = 8192L,
        )

    private fun zipBytes(vararg members: Pair<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            members.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun corruptedDeflatedZipBytes(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return bytes.toByteArray().also { archive ->
            val nameBytes = archive.readLittleEndianU16(26)
            val extraBytes = archive.readLittleEndianU16(28)
            val dataOffset = 30 + nameBytes + extraBytes
            // DEFLATE BTYPE=3 is reserved, so the member stream must raise ZipException.
            archive[dataOffset] = 0x07
        }
    }

    private fun stager(
        root: File,
        opener: ReadingSourceInputOpener,
        limits: ReadingSourceStageLimits = limits(),
        availableBytes: (File) -> Long = { Long.MAX_VALUE },
        nonceSource: ReadingSourceStageNonceSource = QueueNonceSource("1".repeat(32)),
    ) = ReadingSourceStager(
        stagingRoot = root,
        inputOpener = opener,
        limits = limits,
        availableBytes = availableBytes,
        nonceSource = nonceSource,
    )

    private fun limits(
        textMaxBytes: Long = 32L,
        epubMaxBytes: Long = 32L,
        subtitleMaxBytes: Long = 32L,
        mokuroSidecarMaxBytes: Long = 32L,
        mokuroArchiveMaxBytes: Long = 32L,
        jobMaxBytes: Long = 64L,
        reserveBytes: Long = 2L,
    ) = ReadingSourceStageLimits(
        textMaxBytes = textMaxBytes,
        epubMaxBytes = epubMaxBytes,
        subtitleMaxBytes = subtitleMaxBytes,
        mokuroSidecarMaxBytes = mokuroSidecarMaxBytes,
        mokuroArchiveMaxBytes = mokuroArchiveMaxBytes,
        jobMaxBytes = jobMaxBytes,
        freeSpaceReserveBytes = reserveBytes,
        bufferBytes = 2,
        // Byte-scale fixtures assert per-buffer progress; production coalesces onto megabytes.
        checkpointIntervalBytes = 1,
    )

    /**
     * Android's `Context.getCacheDir()` traverses the `/data/user/0 -> /data/data` app-data symlink,
     * while the bridge sends `cacheDir.canonicalPath`. The staging root has to resolve the same way
     * or every staged reading path lands outside the cacheDir the codec compares against.
     */
    @Test
    fun `staging root resolves a cache directory reached through a symlinked ancestor`() {
        val root = temporary.newFolder("app-storage").toPath()
        val real = Files.createDirectory(root.resolve("real"))
        Files.createDirectory(real.resolve("cache"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        val cacheDir = link.resolve("cache").toFile()

        val stagingRoot = readingSourceStagingRoot(cacheDir)

        assertEquals(real.resolve("cache").toFile().canonicalFile, requireNotNull(stagingRoot.parentFile))
        assertEquals(stagingRoot.canonicalFile, stagingRoot)
    }

    private fun document(
        uri: String,
        displayName: String,
        sizeBytes: Long?,
    ) = SafDocument(uri, displayName, mimeType = null, sizeBytes = sizeBytes)

    private fun ownedDirectory(
        root: File,
        token: Char,
    ) = File(root, "reading-job-v1-${token.toString().repeat(32)}").apply { mkdir() }

    private data class SingleCase(
        val displayName: String,
        val role: ReadingSourceStageRole,
        val sourceKind: StagedReadingSourceKind,
        val outputName: String,
    )
}

private fun ByteArray.readLittleEndianU16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private class FakeReadingSourceOpener(
    private val content: Map<String, ByteArray>,
) : ReadingSourceInputOpener {
    val openedUris = mutableListOf<String>()

    override fun open(
        document: SafDocument,
        cancellation: ProviderIoCancellation,
    ): InputStream {
        openedUris += document.uri
        return ByteArrayInputStream(content.getValue(document.uri))
    }
}

private fun ByteArray.lastIndexOfSignature(
    first: Int,
    second: Int,
    third: Int,
    fourth: Int,
): Int {
    for (index in size - 4 downTo 0) {
        if (
            (this[index].toInt() and 0xff) == first &&
                (this[index + 1].toInt() and 0xff) == second &&
                (this[index + 2].toInt() and 0xff) == third &&
                (this[index + 3].toInt() and 0xff) == fourth
        ) {
            return index
        }
    }
    error("ZIP signature not found")
}

private fun ByteArray.writeLittleEndianU16(
    offset: Int,
    value: Int,
) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = (value ushr 8 and 0xff).toByte()
}

private class QueueNonceSource(
    vararg nonces: String,
) : ReadingSourceStageNonceSource {
    private val values = ArrayDeque(nonces.toList())

    override fun nextNonce(): String = values.removeFirst()
}
