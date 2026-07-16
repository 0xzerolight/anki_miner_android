package com.ankiminer.android.anki.provider

import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.RemediationDraft
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.StagingDraft
import com.ankiminer.android.anki.journal.StagingRecord
import com.ankiminer.android.anki.journal.StagingState
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Production adapter over the existing durable mutation journal. */
internal class StoreAnkiMediaStagingJournal(
    private val store: AnkiMutationStore,
) : AnkiMediaStagingJournal {
    override fun record(draft: StagingDraft): StagingRecord = store.recordStaging(draft)

    override fun transition(
        stagingId: Long,
        state: StagingState,
        compactEvidence: String,
    ): StagingRecord = store.transitionStaging(stagingId, state, compactEvidence)

    override fun recoveryRecords(): List<StagingRecord> = store.stagingForRecovery()

    override fun addQuarantineRemediation(stagingId: Long) {
        store.addRemediation(
            RemediationDraft(
                stagingId = stagingId,
                kind = RemediationKind.STAGING_QUARANTINED,
                summary = "Anki media staging cleanup requires retry",
                compactEvidence = "private staging cleanup did not complete",
            ),
        )
    }

    override fun completeCleanup(
        stagingId: Long,
        compactEvidence: String,
    ) = store.completeStagingCleanup(stagingId, compactEvidence)
}

/** FileProvider, private-cache, and exact-package grant implementation used on device. */
internal class AndroidAnkiMediaStagingPlatform(
    context: Context,
) : AnkiMediaStagingPlatform {
    private val appContext = context.applicationContext
    private val cacheRoot = appContext.cacheDir.toPath().toAbsolutePath().normalize()
    private val filesRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
    private val stagingRoot = cacheRoot.resolve(ANKI_MEDIA_STAGING_ROOT).normalize()
    private val versionDirectory = stagingRoot.resolve(STAGING_VERSION).normalize()
    private val approvedSourceRoots =
        listOf(
            cacheRoot,
            filesRoot.resolve(DICTIONARY_MEDIA_ROOT).normalize(),
            filesRoot.resolve(LOCAL_AUDIO_CACHE_ROOT).normalize(),
        )

    override val authority: String = "${appContext.packageName}.anki-media"

    init {
        check(stagingRoot.parent == cacheRoot) { "Unsafe media staging root" }
        check(versionDirectory.parent == stagingRoot) { "Unsafe media staging version directory" }
    }

    override fun contentUriFor(relativePath: String): String {
        ensureStagingDirectories()
        return FileProvider.getUriForFile(
            appContext,
            authority,
            resolveDestination(relativePath).toFile(),
        ).toString()
    }

    override fun destinationExists(relativePath: String): Boolean {
        if (!safeExistingDirectories()) return false
        return Files.exists(resolveDestination(relativePath), LinkOption.NOFOLLOW_LINKS)
    }

    override fun openSource(absolutePath: String): FileInputStream {
        val source = Paths.get(absolutePath).toAbsolutePath().normalize()
        require(source !in listOf(cacheRoot, filesRoot)) { "Media source must be a file" }
        require(!source.startsWith(stagingRoot)) { "Media staging cannot read its own private copies" }
        val approvedRoot =
            approvedSourceRoots.firstOrNull(source::startsWith)
                ?: throw IllegalArgumentException("Media source is outside approved app storage")
        validateSourcePath(approvedRoot, source)

        val descriptor =
            openCloseOnExec(
                source,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
            )
        try {
            check(OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) { "Media source is not a regular file" }
            return FileInputStream(descriptor)
        } catch (error: Exception) {
            Os.close(descriptor)
            throw error
        }
    }

    override fun createDestination(relativePath: String): AnkiMediaStagingOutput {
        ensureStagingDirectories()
        val destination = resolveDestination(relativePath)
        val channel =
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        return FileChannelStagingOutput(channel, versionDirectory)
    }

    override fun grantRead(
        packageName: String,
        contentUri: String,
    ) {
        require(packageName == ANKIDROID_PACKAGE) { "Unexpected media grant package" }
        appContext.grantUriPermission(
            ANKIDROID_PACKAGE,
            contentUri.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    override fun revokeRead(
        packageName: String,
        contentUri: String,
    ) {
        require(packageName == ANKIDROID_PACKAGE) { "Unexpected media revoke package" }
        appContext.revokeUriPermission(
            ANKIDROID_PACKAGE,
            contentUri.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    override fun deleteDestination(relativePath: String) {
        if (!safeExistingDirectories()) return
        Files.deleteIfExists(resolveDestination(relativePath))
        syncDirectory(versionDirectory)
    }

    override fun sweepUnjournaled(journaledRelativePaths: Set<String>): Int {
        if (!safeExistingDirectories()) return 0
        val retained =
            journaledRelativePaths
                .map(::resolveDestination)
                .toSet()
        var removed = 0
        Files.walkFileTree(
            stagingRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    _attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (file !in retained) {
                        Files.delete(file)
                        removed += 1
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    if (directory != stagingRoot) {
                        try {
                            Files.delete(directory)
                            removed += 1
                        } catch (_: DirectoryNotEmptyException) {
                            // A retained journaled file still owns this generated directory.
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        if (Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeDirectory(versionDirectory, "media staging version directory")
            syncDirectory(versionDirectory)
        }
        syncDirectory(stagingRoot)
        return removed
    }

    private fun ensureStagingDirectories() {
        if (!Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(stagingRoot)
            syncDirectory(cacheRoot)
        } else {
            requireSafeDirectory(stagingRoot, "media staging root")
        }
        if (!Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(versionDirectory)
            syncDirectory(stagingRoot)
        } else {
            requireSafeDirectory(versionDirectory, "media staging version directory")
        }
    }

    private fun safeExistingDirectories(): Boolean {
        if (!Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) return false
        requireSafeDirectory(stagingRoot, "media staging root")
        if (!Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) return false
        requireSafeDirectory(versionDirectory, "media staging version directory")
        return true
    }

    private fun validateSourcePath(
        approvedRoot: Path,
        source: Path,
    ) {
        requireSafeDirectory(approvedRoot, "approved media root")
        val relative = approvedRoot.relativize(source)
        check(relative.nameCount > 0) { "Media source must be below an approved root" }
        var current = approvedRoot
        relative.forEachIndexed { index, component ->
            current = current.resolve(component)
            val attributes =
                Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            check(!attributes.isSymbolicLink) { "Media source path contains a symbolic link" }
            if (index == relative.nameCount - 1) {
                check(attributes.isRegularFile) { "Media source is not a regular file" }
            } else {
                check(attributes.isDirectory) { "Media source parent is not a directory" }
            }
        }
    }

    private fun resolveDestination(relativePath: String): Path {
        require(relativePath.matches(Regex("v1/[0-9a-f]{64}\\.stage"))) {
            "Unsafe media staging relative path"
        }
        val resolved = stagingRoot.resolve(relativePath).normalize()
        require(resolved.startsWith(stagingRoot) && resolved.parent?.parent == stagingRoot) {
            "Unsafe media staging destination"
        }
        return resolved
    }
}

private class FileChannelStagingOutput(
    private val channel: FileChannel,
    private val directory: Path,
) : AnkiMediaStagingOutput {
    override val stream: OutputStream = Channels.newOutputStream(channel)

    override fun sync() {
        channel.force(true)
        syncDirectory(directory)
    }

    override fun close() {
        stream.close()
    }
}

private fun requireSafeDirectory(
    directory: Path,
    label: String,
) {
    check(
        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory),
    ) { "Unsafe $label" }
}

private fun syncDirectory(directory: Path) {
    val descriptor = openCloseOnExec(directory, OsConstants.O_RDONLY)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private fun openCloseOnExec(
    path: Path,
    flags: Int,
): java.io.FileDescriptor = Os.open(path.toString(), flags or O_CLOEXEC_LINUX, 0)

private const val STAGING_VERSION = "v1"
private const val DICTIONARY_MEDIA_ROOT = "dicts"
private const val LOCAL_AUDIO_CACHE_ROOT = "audio_cache/local_packs"

// Android is Linux on every supported ABI. The NDK UAPI exposes O_CLOEXEC as 02000000;
// the Java SDK did not expose the named constant until API 27, one level above our minimum.
private const val O_CLOEXEC_LINUX = 0x00080000
