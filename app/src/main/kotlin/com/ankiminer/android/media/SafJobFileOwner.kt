package com.ankiminer.android.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A path whose backing resource is owned by a [SafJobFileOwner]. */
@ConsistentCopyVisibility
data class PythonMediaInput internal constructor(
    val path: String,
)

internal enum class SafCopyRole {
    VIDEO,
    SUBTITLE,
}

internal data class SafCopyProgress(
    val role: SafCopyRole,
    val copiedBytes: Long,
    val expectedBytes: Long?,
)

internal fun interface SafCopyProgressListener {
    fun onProgress(progress: SafCopyProgress)

    companion object {
        val NONE = SafCopyProgressListener { }
    }
}

/**
 * Owns every SAF descriptor opened for one Python mining job.
 *
 * Keep this object alive around the complete parked Python call, including curation and every
 * ffmpeg child process. All inputs are copied once into app cache: a SAF grant lives in the
 * provider IPC layer, so an ffmpeg child that re-opens `/proc/self/fd/N` is denied by the
 * FUSE-backed shared-storage permission check (the app deliberately holds no READ_MEDIA_*
 * permission). Subtitles keep a filename suffix because the engine's parser dispatches on it.
 * The original [ParcelFileDescriptor] remains open until [close], so no backing resource can
 * change ownership underneath the engine.
 *
 * Opening and copying may block and must run on the mining worker, never the main thread.
 */
class SafJobFileOwner internal constructor(
    private val descriptorOpener: DescriptorOpener,
    private val cacheFileFactory: CacheFileFactory,
    private val fileCopier: BoundedFileCopier = BoundedFileCopier(),
    private val cancellation: FileCopyCancellation = FileCopyCancellation.NONE,
    private val progressListener: SafCopyProgressListener = SafCopyProgressListener.NONE,
) : Closeable {
    private data class OwnedInput(
        val descriptor: OwnedDescriptor,
        val cacheFile: File?,
    )

    constructor(context: Context) : this(
        AndroidDescriptorOpener(context.applicationContext.contentResolver),
        AndroidCacheFileFactory(context.applicationContext.cacheDir),
    )

    internal constructor(
        context: Context,
        cancellation: FileCopyCancellation,
        progressListener: SafCopyProgressListener = SafCopyProgressListener.NONE,
    ) : this(
        AndroidDescriptorOpener(context.applicationContext.contentResolver),
        AndroidCacheFileFactory(context.applicationContext.cacheDir),
        BoundedFileCopier(),
        cancellation,
        progressListener,
    )

    private val monitor = Any()
    private val ownedInputs = mutableListOf<OwnedInput>()
    private val closeCancellation = ProviderIoCancellationController()
    private val operationCancellation = cancellation.combine(closeCancellation)
    private var closed = false

    /** Compatibility alias for the S3 probe; production callers should name the video role. */
    fun open(uri: Uri): PythonMediaInput = openVideo(uri)

    fun openVideo(uri: Uri): PythonMediaInput = openVideoUri(uri.toString())

    fun materializeSubtitle(
        uri: Uri,
        displayName: String,
    ): PythonMediaInput = materializeSubtitleUri(uri.toString(), displayName)

    internal fun openUri(uri: String): PythonMediaInput = openVideoUri(uri)

    internal fun openVideoUri(uri: String): PythonMediaInput =
        openOwned(uri = uri, copySuffix = null)

    internal fun materializeSubtitleUri(
        uri: String,
        displayName: String,
    ): PythonMediaInput {
        val suffix = subtitleSuffix(displayName)
        return openOwned(uri = uri, copySuffix = suffix)
    }

    private fun openOwned(
        uri: String,
        copySuffix: String?,
    ): PythonMediaInput {
        synchronized(monitor) {
            check(!closed) { "SAF job file owner is already closed" }
        }
        require(uri.isNotBlank()) { "SAF URI must not be blank" }

        val descriptor = CloseOnceOwnedDescriptor(descriptorOpener.open(uri, operationCancellation))
        val descriptorCancellation =
            operationCancellation.invokeOnCancellation {
                try {
                    descriptor.close()
                } catch (_: Exception) {
                    // cleanupFailedOpen re-reads the close-once wrapper's stored failure.
                }
            }
        var cacheFile: File? = null
        try {
            if (operationCancellation.isCancelled()) throw FileCopyCancelledException()
            val createdCacheFile = cacheFileFactory.create(copySuffix ?: VIDEO_COPY_SUFFIX)
            cacheFile = createdCacheFile
            require(createdCacheFile.isAbsolute) { "SAF cache path must be absolute" }
            val role = if (copySuffix == null) SafCopyRole.VIDEO else SafCopyRole.SUBTITLE
            fileCopier.copy(
                openSource = descriptor::openInputStream,
                destination = createdCacheFile,
                knownSizeBytes = descriptor.knownSizeBytes,
                policy = if (role == SafCopyRole.VIDEO) VIDEO_COPY_POLICY else SUBTITLE_COPY_POLICY,
                cancellation = operationCancellation,
                progressListener =
                    FileCopyProgressListener { progress ->
                        progressListener.onProgress(
                            SafCopyProgress(
                                role = role,
                                copiedBytes = progress.copiedBytes,
                                expectedBytes = progress.expectedBytes,
                            ),
                        )
                    },
            )
            check(createdCacheFile.isFile) { "SAF provider copy did not create a file" }
            if (operationCancellation.isCancelled()) throw FileCopyCancelledException()
            val input = PythonMediaInput(path = createdCacheFile.absolutePath)

            val published =
                synchronized(monitor) {
                    if (closed) {
                        false
                    } else {
                        ownedInputs += OwnedInput(descriptor, createdCacheFile)
                        true
                    }
                }
            check(published) { "SAF job file owner closed while opening an input" }
            return input
        } catch (failure: Throwable) {
            cleanupFailedOpen(descriptor, cacheFile, failure)
            throw failure
        } finally {
            descriptorCancellation.close()
        }
    }

    private fun subtitleSuffix(displayName: String): String {
        require(displayName.isNotBlank()) { "Subtitle display name must not be blank" }
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        require(extension in SUBTITLE_EXTENSIONS) {
            "Subtitle filename must end in .srt, .ass, .ssa, or .vtt"
        }
        return ".$extension"
    }

    override fun close() {
        closeCancellation.cancel()
        val inputs =
            synchronized(monitor) {
                if (closed) {
                    emptyList()
                } else {
                    closed = true
                    ownedInputs.asReversed().toList().also { ownedInputs.clear() }
                }
            }

        var failure: IOException? = null
        for (input in inputs) {
            val cacheFile = input.cacheFile
            if (cacheFile != null) {
                try {
                    if (cacheFile.exists() && !cacheFile.delete()) {
                        throw IOException("Could not remove SAF cache copy: ${cacheFile.absolutePath}")
                    }
                } catch (cleanupError: Exception) {
                    failure = collectFailure(failure, cleanupError)
                }
            }
            try {
                input.descriptor.close()
            } catch (cleanupError: Exception) {
                failure = collectFailure(failure, cleanupError)
            }
        }
        failure?.let { throw it }
    }

    private fun cleanupFailedOpen(
        descriptor: OwnedDescriptor,
        cacheFile: File?,
        primaryFailure: Throwable,
    ) {
        if (cacheFile != null) {
            try {
                if (cacheFile.exists() && !cacheFile.delete()) {
                    primaryFailure.addSuppressed(
                        IOException("Could not remove failed SAF copy: ${cacheFile.absolutePath}"),
                    )
                }
            } catch (cleanupFailure: Exception) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
        try {
            descriptor.close()
        } catch (cleanupFailure: Exception) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun collectFailure(
        current: IOException?,
        next: Exception,
    ): IOException {
        val converted = next as? IOException ?: IOException(next.message, next)
        if (current == null) {
            return converted
        }
        current.addSuppressed(converted)
        return current
    }

    private companion object {
        const val VIDEO_COPY_SUFFIX = ".media"
        const val MAX_VIDEO_COPY_BYTES = 16L * 1024 * 1024 * 1024
        const val MAX_SUBTITLE_COPY_BYTES = 32L * 1024 * 1024
        const val FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024
        val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "ssa", "vtt")
        val VIDEO_COPY_POLICY =
            BoundedFileCopyPolicy(
                maxBytes = MAX_VIDEO_COPY_BYTES,
                freeSpaceReserveBytes = FREE_SPACE_RESERVE_BYTES,
            )
        val SUBTITLE_COPY_POLICY =
            BoundedFileCopyPolicy(
                maxBytes = MAX_SUBTITLE_COPY_BYTES,
                freeSpaceReserveBytes = FREE_SPACE_RESERVE_BYTES,
            )
    }
}

internal fun interface DescriptorOpener {
    @Throws(IOException::class)
    fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): OwnedDescriptor
}

internal fun interface CacheFileFactory {
    @Throws(IOException::class)
    fun create(suffix: String): File
}

internal interface OwnedDescriptor : Closeable {
    val knownSizeBytes: Long?

    @Throws(IOException::class)
    fun openInputStream(): InputStream
}

private class CloseOnceOwnedDescriptor(
    private val delegate: OwnedDescriptor,
) : OwnedDescriptor {
    private val closed = AtomicBoolean(false)
    private val closeFailure = AtomicReference<Exception?>()

    override val knownSizeBytes: Long?
        get() = delegate.knownSizeBytes

    override fun openInputStream(): InputStream {
        check(!closed.get()) { "SAF descriptor is already closed" }
        return delegate.openInputStream()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                delegate.close()
            } catch (failure: Exception) {
                closeFailure.set(failure)
                throw failure
            }
        } else {
            closeFailure.get()?.let { throw it }
        }
    }
}

private class AndroidDescriptorOpener(
    private val contentResolver: ContentResolver,
) : DescriptorOpener {
    override fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): OwnedDescriptor {
        val parsed = Uri.parse(uri)
        return CancellableProviderIo.open(cancellation) { signal ->
            val descriptor =
                contentResolver.openFileDescriptor(parsed, "r", signal)
                    ?: throw FileNotFoundException(
                        "DocumentsProvider returned no descriptor for $parsed",
                    )
            ParcelDescriptor(descriptor)
        }
    }
}

private class ParcelDescriptor(
    private val descriptor: ParcelFileDescriptor,
) : OwnedDescriptor {
    override val knownSizeBytes: Long?
        get() = descriptor.statSize.takeIf { it >= 0L }

    override fun openInputStream(): InputStream {
        // AutoCloseInputStream owns its PFD, so copy from a duplicate and retain the original.
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(duplicate)
        } catch (failure: Throwable) {
            try {
                duplicate.close()
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun close() = descriptor.close()
}

private class AndroidCacheFileFactory(
    cacheDir: File,
) : CacheFileFactory {
    private val copyRoot = safInputCacheRoot(cacheDir)

    override fun create(suffix: String): File {
        require(SAFE_SUFFIX.matches(suffix)) { "SAF cache suffix is invalid" }
        if (!copyRoot.isDirectory && !copyRoot.mkdirs()) {
            throw IOException("Could not create SAF cache directory: ${copyRoot.absolutePath}")
        }
        return File.createTempFile("input-", suffix, copyRoot)
    }

    private companion object {
        val SAFE_SUFFIX = Regex("\\.[a-z0-9]{1,16}")
    }
}

internal fun safInputCacheRoot(cacheDir: File): File = File(cacheDir, "saf-inputs")

/** Removes only recognized direct orphan files created by [SafJobFileOwner] in a prior process. */
class SafInputCacheJanitor internal constructor(
    private val copyRoot: File,
) {
    constructor(context: Context) : this(safInputCacheRoot(context.applicationContext.cacheDir))

    @Throws(IOException::class)
    fun removeOrphans(): Int {
        if (!copyRoot.exists()) return 0
        if (!copyRoot.isDirectory) {
            throw IOException("SAF input cache root is not a directory")
        }
        val entries = copyRoot.listFiles()
            ?: throw IOException("Could not inspect SAF input cache directory")
        var removed = 0
        entries.forEach { entry ->
            if (!isOwnedCacheEntry(entry)) return@forEach
            if (!entry.delete()) {
                throw IOException("Could not remove orphaned SAF cache entry: ${entry.name}")
            }
            removed += 1
        }
        return removed
    }

    private fun isOwnedCacheEntry(entry: File): Boolean {
        if (!OWNED_NAME.matches(entry.name)) return false
        return entry.isFile || Files.isSymbolicLink(entry.toPath())
    }

    private companion object {
        val OWNED_NAME = Regex("input-[A-Za-z0-9._-]+\\.(?:media|ass|srt|ssa|vtt)")
    }
}
