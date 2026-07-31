package com.ankiminer.android.reading

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.media.BoundedFileCopier
import com.ankiminer.android.media.BoundedFileCopyPolicy
import com.ankiminer.android.media.BoundedFileCopyProgress
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.FileCopyLimitExceededException
import com.ankiminer.android.media.FileCopyProgressListener
import com.ankiminer.android.media.FileCopyStorageException
import com.ankiminer.android.media.SafDocument
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal enum class ReadingSourceStageRole {
    TEXT,
    EPUB,
    SUBTITLE,
    MOKURO_SIDECAR,
    MOKURO_ARCHIVE,
}

internal enum class StagedReadingSourceKind(
    val wireValue: String,
) {
    TXT("txt"),
    EPUB("epub"),
    SUBTITLE("subtitle"),
    MOKURO("mokuro"),
}

internal data class ReadingSourceStageProgress(
    val role: ReadingSourceStageRole,
    val copiedBytes: Long,
    val expectedBytes: Long?,
)

internal fun interface ReadingSourceStageProgressListener {
    fun onProgress(progress: ReadingSourceStageProgress)

    companion object {
        val NONE = ReadingSourceStageProgressListener { }
    }
}

internal sealed interface ReadingSourceSelection {
    data class Single(
        val document: SafDocument,
    ) : ReadingSourceSelection

    data class MokuroArchivePair(
        val sidecar: SafDocument,
        val archive: SafDocument,
    ) : ReadingSourceSelection
}

internal enum class ReadingSourceSelectionFailure {
    INVALID_URI,
    INVALID_DISPLAY_NAME,
    UNSUPPORTED_EXTENSION,
    INVALID_MOKURO_PAIR,
}

internal enum class EmbeddedSidecarFailure {
    NO_MOKURO_MEMBER,
    MULTIPLE_MOKURO_MEMBERS,
    UNREADABLE_ARCHIVE,
}

internal class EmbeddedSidecarException(
    val failure: EmbeddedSidecarFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class ReadingSourceSelectionException(
    val failure: ReadingSourceSelectionFailure,
    message: String,
) : IllegalArgumentException(message)

internal class ReadingSourceTotalLimitExceededException(
    val maxBytes: Long,
    val observedBytes: Long,
    cause: Throwable? = null,
) : IOException("Selected reading sources exceed the private staging limit", cause)

internal class EmptyReadingSourceException(
    val role: ReadingSourceStageRole,
) : IOException("Selected reading source is empty")

internal data class ReadingSourceStageLimits(
    val textMaxBytes: Long = DEFAULT_TEXT_MAX_BYTES,
    val epubMaxBytes: Long = DEFAULT_EPUB_MAX_BYTES,
    val subtitleMaxBytes: Long = DEFAULT_SUBTITLE_MAX_BYTES,
    val mokuroSidecarMaxBytes: Long = DEFAULT_MOKURO_SIDECAR_MAX_BYTES,
    val mokuroArchiveMaxBytes: Long = DEFAULT_MOKURO_ARCHIVE_MAX_BYTES,
    val jobMaxBytes: Long = DEFAULT_JOB_MAX_BYTES,
    val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
) {
    init {
        require(textMaxBytes > 0L)
        require(epubMaxBytes > 0L)
        require(subtitleMaxBytes > 0L)
        require(mokuroSidecarMaxBytes > 0L)
        require(mokuroArchiveMaxBytes > 0L)
        require(jobMaxBytes > 0L)
        require(freeSpaceReserveBytes >= 0L)
        require(bufferBytes in 1..MAX_BUFFER_BYTES)
    }

    fun maxBytes(role: ReadingSourceStageRole): Long =
        when (role) {
            ReadingSourceStageRole.TEXT -> textMaxBytes
            ReadingSourceStageRole.EPUB -> epubMaxBytes
            ReadingSourceStageRole.SUBTITLE -> subtitleMaxBytes
            ReadingSourceStageRole.MOKURO_SIDECAR -> mokuroSidecarMaxBytes
            ReadingSourceStageRole.MOKURO_ARCHIVE -> mokuroArchiveMaxBytes
        }

    internal companion object {
        // These staged-byte caps mirror android_bridge.reading_limits. Python
        // separately validates archive expansion and retained text; keeping the
        // copy gate equally strict avoids spending private storage on a source
        // the 384 MiB runtime target cannot safely process.
        const val DEFAULT_TEXT_MAX_BYTES = 8L * 1024 * 1024
        const val DEFAULT_EPUB_MAX_BYTES = 256L * 1024 * 1024
        const val DEFAULT_SUBTITLE_MAX_BYTES = 8L * 1024 * 1024
        const val DEFAULT_MOKURO_SIDECAR_MAX_BYTES = 16L * 1024 * 1024
        const val DEFAULT_MOKURO_ARCHIVE_MAX_BYTES = 1024L * 1024 * 1024
        const val DEFAULT_JOB_MAX_BYTES =
            DEFAULT_MOKURO_SIDECAR_MAX_BYTES + DEFAULT_MOKURO_ARCHIVE_MAX_BYTES
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024
        const val DEFAULT_BUFFER_BYTES = 256 * 1024
        const val MAX_BUFFER_BYTES = 1024 * 1024
    }
}

internal fun interface ReadingSourceInputOpener {
    @Throws(IOException::class)
    fun open(document: SafDocument): InputStream
}

internal fun interface ReadingSourceStageNonceSource {
    fun nextNonce(): String
}

private object SecureReadingSourceStageNonceSource : ReadingSourceStageNonceSource {
    private val random = SecureRandom()

    override fun nextNonce(): String =
        ByteArray(16)
            .also(random::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
}

internal data class StagedReadingFile(
    val role: ReadingSourceStageRole,
    val file: File,
    val sizeBytes: Long,
)

/** Owns one complete, flat private directory handed to the Python reading detector. */
internal class StagedReadingSource internal constructor(
    val sourceKind: StagedReadingSourceKind,
    val detectorPath: String,
    val imageArchivePath: String?,
    val files: List<StagedReadingFile>,
    private val stageDirectory: File,
) : Closeable {
    private val monitor = Any()
    private var closed = false

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            var failure: IOException? = null
            files.asReversed().forEach { staged ->
                try {
                    deleteOwnedFile(staged.file)
                } catch (error: IOException) {
                    failure = collectCleanupFailure(failure, error)
                }
            }
            try {
                deleteOwnedStageDirectory(stageDirectory)
            } catch (error: IOException) {
                failure = collectCleanupFailure(failure, error)
            }
            if (failure == null) {
                closed = true
            } else {
                throw checkNotNull(failure)
            }
        }
    }
}

/**
 * Materializes one SAF reading selection into a private, collision-resistant, flat job directory.
 *
 * Every byte copy is delegated to [BoundedFileCopier], including streaming limits, storage reserve
 * checks, cancellation checkpoints, exact known-size verification, and destination fsync. A
 * failed pair is removed as one unit. The returned owner must stay open through the Python call.
 * Opening and copying block, so callers must run [stage] on a worker dispatcher.
 */
internal class ReadingSourceStager(
    private val stagingRoot: File,
    private val inputOpener: ReadingSourceInputOpener,
    private val limits: ReadingSourceStageLimits = ReadingSourceStageLimits(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val fileCopier: BoundedFileCopier = BoundedFileCopier(availableBytes),
    private val nonceSource: ReadingSourceStageNonceSource = SecureReadingSourceStageNonceSource,
) {
    fun stage(
        selection: ReadingSourceSelection,
        cancellation: FileCopyCancellation = FileCopyCancellation.NONE,
        progressListener: ReadingSourceStageProgressListener = ReadingSourceStageProgressListener.NONE,
    ): StagedReadingSource {
        checkCancellation(cancellation)
        val plan = buildPlan(selection)
        preflightKnownSizes(plan)
        checkCancellation(cancellation)

        ensureRoot()
        val stageDirectory = createStageDirectory()
        val stagedFiles = mutableListOf<StagedReadingFile>()
        try {
            preflightStorage(stageDirectory, plan)
            var copiedForJob = 0L
            plan.entries.forEach { entry ->
                checkCancellation(cancellation)
                val destination = File(stageDirectory, entry.outputName)
                require(destination.parentFile == stageDirectory && !destination.exists()) {
                    "Reading stage destination is not a new direct child"
                }
                val remaining = limits.jobMaxBytes - copiedForJob
                if (remaining <= 0L) {
                    throw ReadingSourceTotalLimitExceededException(
                        limits.jobMaxBytes,
                        checkedAdd(copiedForJob, 1L),
                    )
                }
                val roleMaxBytes = limits.maxBytes(entry.role)
                val effectiveMaxBytes = minOf(roleMaxBytes, remaining)
                val policy =
                    BoundedFileCopyPolicy(
                        maxBytes = effectiveMaxBytes,
                        freeSpaceReserveBytes = limits.freeSpaceReserveBytes,
                        bufferBytes = limits.bufferBytes,
                    )
                val copied =
                    try {
                        fileCopier.copy(
                            openSource = { inputOpener.open(entry.document) },
                            destination = destination,
                            knownSizeBytes = entry.document.sizeBytes,
                            policy = policy,
                            cancellation = cancellation,
                            progressListener =
                                FileCopyProgressListener { progress ->
                                    progressListener.onProgress(progress.forRole(entry.role))
                                },
                        )
                    } catch (failure: FileCopyLimitExceededException) {
                        if (effectiveMaxBytes == remaining && remaining < roleMaxBytes) {
                            throw ReadingSourceTotalLimitExceededException(
                                maxBytes = limits.jobMaxBytes,
                                observedBytes = checkedAdd(copiedForJob, failure.observedBytes),
                                cause = failure,
                            )
                        }
                        throw failure
                    }
                if (copied == 0L) throw EmptyReadingSourceException(entry.role)
                copiedForJob = checkedAdd(copiedForJob, copied)
                if (copiedForJob > limits.jobMaxBytes) {
                    throw ReadingSourceTotalLimitExceededException(limits.jobMaxBytes, copiedForJob)
                }
                stagedFiles += StagedReadingFile(entry.role, destination, copied)
            }
            if (plan.extractEmbeddedSidecar) {
                checkCancellation(cancellation)
                val archiveStaged =
                    stagedFiles.single { it.role == ReadingSourceStageRole.MOKURO_ARCHIVE }
                val destination = File(stageDirectory, plan.detectorName)
                require(destination.parentFile == stageDirectory && !destination.exists()) {
                    "Reading stage destination is not a new direct child"
                }
                val remaining = limits.jobMaxBytes - copiedForJob
                if (remaining <= 0L) {
                    throw ReadingSourceTotalLimitExceededException(
                        limits.jobMaxBytes,
                        checkedAdd(copiedForJob, 1L),
                    )
                }
                val copied =
                    extractEmbeddedSidecar(
                        archive = archiveStaged.file,
                        destination = destination,
                        remainingJobBytes = remaining,
                        cancellation = cancellation,
                        progressListener = progressListener,
                    )
                copiedForJob = checkedAdd(copiedForJob, copied)
                if (copiedForJob > limits.jobMaxBytes) {
                    throw ReadingSourceTotalLimitExceededException(limits.jobMaxBytes, copiedForJob)
                }
                stagedFiles +=
                    StagedReadingFile(ReadingSourceStageRole.MOKURO_SIDECAR, destination, copied)
            }
            checkCancellation(cancellation)
            val detectorFile = File(stageDirectory, plan.detectorName)
            check(detectorFile.isFile) { "Reading detector entry was not materialized" }
            val archiveFile = plan.imageArchiveName?.let { name -> File(stageDirectory, name) }
            check(archiveFile == null || archiveFile.isFile) {
                "Reading image archive was not materialized"
            }
            return StagedReadingSource(
                sourceKind = plan.sourceKind,
                detectorPath = detectorFile.absolutePath,
                imageArchivePath = archiveFile?.absolutePath,
                files = stagedFiles.toList(),
                stageDirectory = stageDirectory,
            )
        } catch (failure: Throwable) {
            cleanupFailedStage(
                stageDirectory,
                buildList {
                    plan.entries.forEach { entry -> add(File(stageDirectory, entry.outputName)) }
                    if (plan.extractEmbeddedSidecar) add(File(stageDirectory, plan.detectorName))
                },
                failure,
            )
            throw failure
        }
    }

    private fun extractEmbeddedSidecar(
        archive: File,
        destination: File,
        remainingJobBytes: Long,
        cancellation: FileCopyCancellation,
        progressListener: ReadingSourceStageProgressListener,
    ): Long {
        val zip =
            try {
                ZipFile(archive)
            } catch (failure: IOException) {
                throw embeddedSidecarUnreadable(failure)
            } catch (failure: RuntimeException) {
                throw embeddedSidecarUnreadable(failure)
            }
        zip.use { open ->
            val members = Collections.list(open.entries())
            if (members.size > MAX_ARCHIVE_MEMBER_SCAN) {
                throw EmbeddedSidecarException(
                    EmbeddedSidecarFailure.UNREADABLE_ARCHIVE,
                    "Mokuro image archive lists too many members",
                )
            }
            val candidates =
                members.filter { member ->
                    !member.isDirectory && isSafeSidecarMemberName(member.name)
                }
            val selected =
                when {
                    candidates.isEmpty() ->
                        throw EmbeddedSidecarException(
                            EmbeddedSidecarFailure.NO_MOKURO_MEMBER,
                            "Mokuro image archive contains no .mokuro member",
                        )
                    candidates.size == 1 -> candidates.single()
                    else ->
                        candidates.singleOrNull { member -> '/' !in member.name }
                            ?: throw EmbeddedSidecarException(
                                EmbeddedSidecarFailure.MULTIPLE_MOKURO_MEMBERS,
                                "Mokuro image archive contains multiple .mokuro members",
                            )
                }
            if (selected.size == 0L) {
                throw EmptyReadingSourceException(ReadingSourceStageRole.MOKURO_SIDECAR)
            }
            val policy =
                BoundedFileCopyPolicy(
                    maxBytes = minOf(limits.mokuroSidecarMaxBytes, remainingJobBytes),
                    freeSpaceReserveBytes = limits.freeSpaceReserveBytes,
                    bufferBytes = limits.bufferBytes,
                )
            val copied =
                try {
                    fileCopier.copy(
                        openSource = { open.getInputStream(selected) },
                        destination = destination,
                        knownSizeBytes = selected.size.takeIf { it >= 0L },
                        policy = policy,
                        cancellation = cancellation,
                        progressListener =
                            FileCopyProgressListener { progress ->
                                progressListener.onProgress(
                                    progress.forRole(ReadingSourceStageRole.MOKURO_SIDECAR),
                                )
                            },
                    )
                } catch (failure: ZipException) {
                    AppLog.w(
                        LogComponent.READING,
                        "embedded_sidecar.copy",
                        failure,
                        "entry" to selected.name,
                        "outcome" to "fail",
                    )
                    throw embeddedSidecarUnreadable(failure)
                }
            if (copied == 0L) throw EmptyReadingSourceException(ReadingSourceStageRole.MOKURO_SIDECAR)
            return copied
        }
    }

    private fun preflightKnownSizes(plan: StagePlan) {
        var knownTotal = 0L
        plan.entries.forEach { entry ->
            val known = entry.document.sizeBytes ?: return@forEach
            if (known == 0L) throw EmptyReadingSourceException(entry.role)
            val roleMax = limits.maxBytes(entry.role)
            if (known > roleMax) throw FileCopyLimitExceededException(roleMax, known)
            knownTotal = checkedAdd(knownTotal, known)
            if (knownTotal > limits.jobMaxBytes) {
                throw ReadingSourceTotalLimitExceededException(limits.jobMaxBytes, knownTotal)
            }
        }
    }

    private fun preflightStorage(
        stageDirectory: File,
        plan: StagePlan,
    ) {
        val knownBytes =
            plan.entries.fold(0L) { total, entry ->
                checkedAdd(total, entry.document.sizeBytes ?: 0L)
            }
        val required = checkedAdd(knownBytes, limits.freeSpaceReserveBytes)
        val available = availableBytes(stageDirectory).coerceAtLeast(0L)
        if (available < required) throw FileCopyStorageException(required, available)
    }

    private fun ensureRoot() {
        if (!stagingRoot.isAbsolute) {
            throw IOException("Private reading staging root must be absolute")
        }
        if (stagingRoot.exists()) {
            if (!stagingRoot.isDirectory || Files.isSymbolicLink(stagingRoot.toPath())) {
                throw IOException("Private reading staging root is unsafe")
            }
            return
        }
        if (!stagingRoot.mkdirs() || !stagingRoot.isDirectory) {
            throw IOException("Could not create private reading staging root")
        }
    }

    private fun createStageDirectory(): File {
        repeat(STAGE_DIRECTORY_ATTEMPTS) {
            val nonce = nonceSource.nextNonce()
            if (!NONCE_PATTERN.matches(nonce)) {
                throw IOException("Reading stage nonce is invalid")
            }
            val directory = File(stagingRoot, "$STAGE_DIRECTORY_PREFIX$nonce")
            if (directory.mkdir()) return directory
            if (!directory.exists()) {
                throw IOException("Could not create private reading stage directory")
            }
        }
        throw IOException("Could not allocate a private reading stage directory")
    }

    private fun buildPlan(selection: ReadingSourceSelection): StagePlan =
        when (selection) {
            is ReadingSourceSelection.Single -> singlePlan(selection.document)
            is ReadingSourceSelection.MokuroArchivePair -> pairPlan(selection.sidecar, selection.archive)
        }

    private fun singlePlan(document: SafDocument): StagePlan {
        validateContentUri(document)
        val name = parseDisplayName(document.displayName)
        if (name.extension in ARCHIVE_EXTENSIONS) {
            // A lone archive is a self-contained Mokuro volume: its .mokuro
            // sidecar is extracted from the archive after the copy, restoring
            // the exact two-file layout the Python detector requires.
            val archiveOutput = name.outputName
            return StagePlan(
                sourceKind = StagedReadingSourceKind.MOKURO,
                entries =
                    listOf(
                        StageEntry(document, ReadingSourceStageRole.MOKURO_ARCHIVE, archiveOutput),
                    ),
                detectorName = "${name.normalizedStem}.mokuro",
                imageArchiveName = archiveOutput,
                extractEmbeddedSidecar = true,
            )
        }
        val role =
            when (name.extension) {
                "txt" -> ReadingSourceStageRole.TEXT
                "epub" -> ReadingSourceStageRole.EPUB
                in SUBTITLE_EXTENSIONS -> ReadingSourceStageRole.SUBTITLE
                "mokuro" -> ReadingSourceStageRole.MOKURO_SIDECAR
                else -> throw unsupportedExtension()
            }
        val outputName = name.outputName
        return StagePlan(
            sourceKind =
                when (role) {
                    ReadingSourceStageRole.TEXT -> StagedReadingSourceKind.TXT
                    ReadingSourceStageRole.EPUB -> StagedReadingSourceKind.EPUB
                    ReadingSourceStageRole.SUBTITLE -> StagedReadingSourceKind.SUBTITLE
                    ReadingSourceStageRole.MOKURO_SIDECAR -> StagedReadingSourceKind.MOKURO
                    ReadingSourceStageRole.MOKURO_ARCHIVE -> error("Archive cannot be a single source")
                },
            entries = listOf(StageEntry(document, role, outputName)),
            detectorName = outputName,
            imageArchiveName = null,
        )
    }

    private fun pairPlan(
        sidecar: SafDocument,
        archive: SafDocument,
    ): StagePlan {
        validateContentUri(sidecar)
        validateContentUri(archive)
        if (sidecar.uri == archive.uri) {
            throw selectionFailure(
                ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
                "Mokuro sidecar and archive must be different documents",
            )
        }
        val sidecarName = parseDisplayName(sidecar.displayName)
        val archiveName = parseDisplayName(archive.displayName)
        if (sidecarName.extension != "mokuro" || archiveName.extension !in ARCHIVE_EXTENSIONS) {
            throw selectionFailure(
                ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
                "Mokuro pairing requires one .mokuro sidecar and one .cbz or .zip archive",
            )
        }
        if (sidecarName.canonicalStem != archiveName.canonicalStem) {
            throw selectionFailure(
                ReadingSourceSelectionFailure.INVALID_MOKURO_PAIR,
                "Mokuro sidecar and archive filenames must have the same stem",
            )
        }

        val outputStem = sidecarName.normalizedStem
        val sidecarOutput = "$outputStem.mokuro"
        val archiveOutput = "$outputStem.${archiveName.extension}"
        return StagePlan(
            sourceKind = StagedReadingSourceKind.MOKURO,
            entries =
                listOf(
                    StageEntry(sidecar, ReadingSourceStageRole.MOKURO_SIDECAR, sidecarOutput),
                    StageEntry(archive, ReadingSourceStageRole.MOKURO_ARCHIVE, archiveOutput),
                ),
            detectorName = sidecarOutput,
            imageArchiveName = archiveOutput,
        )
    }

    private fun validateContentUri(document: SafDocument) {
        val parsed =
            try {
                URI(document.uri)
            } catch (_: URISyntaxException) {
                throw invalidUri()
            }
        if (
            !parsed.scheme.equals("content", ignoreCase = true) ||
                parsed.rawAuthority.isNullOrBlank() ||
                parsed.rawFragment != null
        ) {
            throw invalidUri()
        }
    }

    private fun checkCancellation(cancellation: FileCopyCancellation) {
        if (cancellation.isCancelled()) throw FileCopyCancelledException()
    }

    private data class StagePlan(
        val sourceKind: StagedReadingSourceKind,
        val entries: List<StageEntry>,
        val detectorName: String,
        val imageArchiveName: String?,
        val extractEmbeddedSidecar: Boolean = false,
    )

    private data class StageEntry(
        val document: SafDocument,
        val role: ReadingSourceStageRole,
        val outputName: String,
    )

    private companion object {
        const val STAGE_DIRECTORY_ATTEMPTS = 16

        // Cheap defense-in-depth mirroring android_bridge.reading_limits
        // MOKURO_ARCHIVE_LIMITS.max_members; Python re-validates the archive.
        const val MAX_ARCHIVE_MEMBER_SCAN = 4096
    }
}

private fun isSafeSidecarMemberName(name: String): Boolean {
    if (name.isEmpty() || !name.lowercase(Locale.ROOT).endsWith(".mokuro")) return false
    if (name.contains('\\') || name.startsWith("/")) return false
    if (name.any { Character.isISOControl(it) }) return false
    val segments = name.split('/')
    return segments.none { segment ->
        segment.isEmpty() || segment.startsWith(".") || segment == "__MACOSX"
    }
}

private fun embeddedSidecarUnreadable(cause: Exception) =
    EmbeddedSidecarException(
        EmbeddedSidecarFailure.UNREADABLE_ARCHIVE,
        "Mokuro image archive could not be read",
        cause,
    )

/**
 * Removes only direct, structurally valid orphan directories created by [ReadingSourceStager].
 * Run during startup, before admitting a reading stage; it is not an active-job sweeper.
 */
internal class ReadingSourceStageJanitor(
    private val stagingRoot: File,
) {
    @Throws(IOException::class)
    fun removeOrphans(): Int {
        if (!stagingRoot.exists()) return 0
        if (!stagingRoot.isDirectory || Files.isSymbolicLink(stagingRoot.toPath())) {
            throw IOException("Private reading staging root is unsafe")
        }
        val entries = stagingRoot.listFiles()
            ?: throw IOException("Could not inspect private reading staging root")
        var removed = 0
        entries.forEach { entry ->
            if (!isOwnedDirectory(entry)) return@forEach
            val children = entry.listFiles()
                ?: throw IOException("Could not inspect orphaned reading stage")
            if (!isOwnedStageShape(children.toList())) return@forEach
            children.forEach(::deleteOwnedFile)
            deleteOwnedStageDirectory(entry)
            removed += 1
        }
        return removed
    }

    private fun isOwnedDirectory(entry: File): Boolean =
        OWNED_STAGE_DIRECTORY.matches(entry.name) &&
            entry.isDirectory &&
            !Files.isSymbolicLink(entry.toPath())

    private fun isOwnedStageShape(entries: List<File>): Boolean {
        if (entries.size > 2) return false
        if (entries.any { !it.isFile && !Files.isSymbolicLink(it.toPath()) }) return false
        val names =
            try {
                entries.map { entry -> parseDisplayName(entry.name) }
            } catch (_: ReadingSourceSelectionException) {
                return false
            }
        if (entries.zip(names).any { (entry, parsed) -> entry.name != parsed.outputName }) {
            return false
        }
        return when (names.size) {
            0 -> true
            // A lone staged archive is a legitimate orphan: a crash between the
            // archive copy and the embedded-sidecar extraction leaves it behind.
            1 ->
                names.single().extension in SINGLE_EXTENSIONS ||
                    names.single().extension in ARCHIVE_EXTENSIONS
            2 -> {
                val sidecars = names.filter { it.extension == "mokuro" }
                val archives = names.filter { it.extension in ARCHIVE_EXTENSIONS }
                sidecars.size == 1 &&
                    archives.size == 1 &&
                    sidecars.single().normalizedStem == archives.single().normalizedStem
            }
            else -> false
        }
    }
}

private data class ParsedDisplayName(
    val normalizedStem: String,
    val canonicalStem: String,
    val extension: String,
) {
    val outputName: String
        get() = "$normalizedStem.$extension"
}

private fun parseDisplayName(displayName: String): ParsedDisplayName {
    val normalized = Normalizer.normalize(displayName, Normalizer.Form.NFC)
    val invalid =
        normalized.isBlank() ||
            normalized == "." ||
            normalized == ".." ||
            normalized.contains('/') ||
            normalized.contains('\\') ||
            normalized.any { Character.isISOControl(it) } ||
            normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_DISPLAY_NAME_UTF8_BYTES
    val separator = normalized.lastIndexOf('.')
    if (invalid || separator <= 0 || separator == normalized.lastIndex) {
        throw selectionFailure(
            ReadingSourceSelectionFailure.INVALID_DISPLAY_NAME,
            "Reading source display name is unsafe",
        )
    }
    val stem = normalized.substring(0, separator)
    if (stem.isBlank()) {
        throw selectionFailure(
            ReadingSourceSelectionFailure.INVALID_DISPLAY_NAME,
            "Reading source display name has no filename stem",
        )
    }
    val extension = normalized.substring(separator + 1).lowercase(Locale.ROOT)
    if (extension !in ALL_READING_EXTENSIONS) throw unsupportedExtension()
    return ParsedDisplayName(
        normalizedStem = stem,
        canonicalStem = stem.lowercase(Locale.ROOT),
        extension = extension,
    )
}

private fun BoundedFileCopyProgress.forRole(role: ReadingSourceStageRole) =
    ReadingSourceStageProgress(
        role = role,
        copiedBytes = copiedBytes,
        expectedBytes = expectedBytes,
    )

private fun cleanupFailedStage(
    stageDirectory: File,
    files: List<File>,
    primaryFailure: Throwable,
) {
    files.asReversed().forEach { file ->
        try {
            deleteOwnedFile(file)
        } catch (cleanupFailure: IOException) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
    try {
        deleteOwnedStageDirectory(stageDirectory)
    } catch (cleanupFailure: IOException) {
        primaryFailure.addSuppressed(cleanupFailure)
    }
}

private fun deleteOwnedFile(file: File) {
    try {
        val symbolicLink = Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !symbolicLink) return
        if (!file.isFile && !symbolicLink) {
            throw IOException("Private staged reading path is not a file")
        }
        if (!file.delete()) throw IOException("Could not remove private staged reading file")
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Could not inspect private staged reading file", error)
    }
}

private fun deleteOwnedStageDirectory(directory: File) {
    try {
        val symbolicLink = Files.isSymbolicLink(directory.toPath())
        if (!directory.exists() && !symbolicLink) return
        if (!directory.isDirectory && !symbolicLink) {
            throw IOException("Private reading stage path is not a directory")
        }
        if (!directory.delete()) throw IOException("Could not remove private reading stage directory")
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Could not inspect private reading stage directory", error)
    }
}

private fun collectCleanupFailure(
    current: IOException?,
    next: IOException,
): IOException {
    if (current == null) return next
    current.addSuppressed(next)
    return current
}

private fun checkedAdd(
    left: Long,
    right: Long,
): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

private fun invalidUri() =
    selectionFailure(
        ReadingSourceSelectionFailure.INVALID_URI,
        "Reading source must be a durable content URI",
    )

private fun unsupportedExtension() =
    selectionFailure(
        ReadingSourceSelectionFailure.UNSUPPORTED_EXTENSION,
        "Reading source has an unsupported filename extension",
    )

private fun selectionFailure(
    failure: ReadingSourceSelectionFailure,
    message: String,
) = ReadingSourceSelectionException(failure, message)

/**
 * Resolves the `/data/user/0 -> /data/data` app-data symlink that `Context.getCacheDir()` returns,
 * so every staged reading path matches the canonical `cacheDir` the bridge sends and the codec's
 * lexical containment check ([com.ankiminer.android.engine.BridgeJsonCodec]) holds. Only the
 * framework-created parent is resolved: the staging directory itself stays unresolved so the
 * stager's and janitor's symlink guards keep rejecting a tampered root.
 */
internal fun readingSourceStagingRoot(cacheDirectory: File): File =
    File(cacheDirectory.canonicalFile, READING_SOURCE_STAGING_ROOT)

private const val READING_SOURCE_STAGING_ROOT = "reading-sources-v1"
private const val STAGE_DIRECTORY_PREFIX = "reading-job-v1-"
private val NONCE_PATTERN = Regex("[0-9a-f]{32}")
private val OWNED_STAGE_DIRECTORY = Regex("${STAGE_DIRECTORY_PREFIX}[0-9a-f]{32}")
private const val MAX_DISPLAY_NAME_UTF8_BYTES = 255
private val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "ssa", "vtt")
private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")
private val SINGLE_EXTENSIONS = setOf("txt", "epub", "mokuro") + SUBTITLE_EXTENSIONS
private val ALL_READING_EXTENSIONS = SINGLE_EXTENSIONS + ARCHIVE_EXTENSIONS
