package com.ankiminer.android.reading

import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.FileCopyLimitExceededException
import com.ankiminer.android.media.FileCopySizeMismatchException
import com.ankiminer.android.media.FileCopyStorageException
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
                ReadingSourceSelection.Single(document("content://reading/archive", "volume.cbz", 1L)) to
                    ReadingSourceSelectionFailure.ARCHIVE_REQUIRES_MOKURO_SIDECAR,
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

        assertEquals(4, removed)
        listOf(empty, single, pair, linkedFileStage).forEach { assertFalse(it.exists()) }
        listOf(nearName, invalidFile, archiveOnly, noncanonical, mismatch, inexactPair, nested, linked, outside)
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

    private fun stager(
        root: File,
        opener: FakeReadingSourceOpener,
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
    )

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

private class FakeReadingSourceOpener(
    private val content: Map<String, ByteArray>,
) : ReadingSourceInputOpener {
    val openedUris = mutableListOf<String>()

    override fun open(document: SafDocument): InputStream {
        openedUris += document.uri
        return ByteArrayInputStream(content.getValue(document.uri))
    }
}

private class QueueNonceSource(
    vararg nonces: String,
) : ReadingSourceStageNonceSource {
    private val values = ArrayDeque(nonces.toList())

    override fun nextNonce(): String = values.removeFirst()
}
