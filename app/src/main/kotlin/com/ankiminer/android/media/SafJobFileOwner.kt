package com.ankiminer.android.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** A path whose backing resource is owned by a [SafJobFileOwner]. */
data class PythonMediaInput internal constructor(
    val path: String,
    val backing: Backing,
) {
    enum class Backing {
        SEEKABLE_DESCRIPTOR,
        CACHE_COPY,
    }
}

/**
 * Owns every SAF descriptor opened for one Python mining job.
 *
 * Keep this object alive around the complete parked Python call, including curation and every
 * ffmpeg child process. Seekable inputs are passed as `/proc/self/fd/N`; non-seekable provider
 * streams are copied once into the app cache. The original [ParcelFileDescriptor] remains open in
 * both cases until [close], so neither path can change ownership underneath the engine.
 *
 * Opening and copying may block and must run on the mining worker, never the main thread.
 */
class SafJobFileOwner internal constructor(
    private val descriptorOpener: DescriptorOpener,
    private val cacheFileFactory: CacheFileFactory,
) : Closeable {
    private data class OwnedInput(
        val descriptor: OwnedDescriptor,
        val cacheFile: File?,
    )

    constructor(context: Context) : this(
        AndroidDescriptorOpener(context.applicationContext.contentResolver),
        AndroidCacheFileFactory(context.applicationContext.cacheDir),
    )

    private val monitor = Any()
    private val ownedInputs = mutableListOf<OwnedInput>()
    private var closed = false

    fun open(uri: Uri): PythonMediaInput = openUri(uri.toString())

    internal fun openUri(uri: String): PythonMediaInput =
        synchronized(monitor) {
            check(!closed) { "SAF job file owner is already closed" }
            require(uri.isNotBlank()) { "SAF URI must not be blank" }

            val descriptor = descriptorOpener.open(uri)
            var cacheFile: File? = null
            try {
                val input =
                    if (descriptor.isSeekable()) {
                        require(descriptor.rawFd >= 0) { "SAF descriptor is invalid" }
                        PythonMediaInput(
                            path = "/proc/self/fd/${descriptor.rawFd}",
                            backing = PythonMediaInput.Backing.SEEKABLE_DESCRIPTOR,
                        )
                    } else {
                        val createdCacheFile = cacheFileFactory.create()
                        cacheFile = createdCacheFile
                        require(createdCacheFile.isAbsolute) { "SAF cache path must be absolute" }
                        descriptor.copyTo(createdCacheFile)
                        check(createdCacheFile.isFile) { "SAF provider copy did not create a file" }
                        PythonMediaInput(
                            path = createdCacheFile.absolutePath,
                            backing = PythonMediaInput.Backing.CACHE_COPY,
                        )
                    }

                ownedInputs += OwnedInput(descriptor, cacheFile)
                input
            } catch (failure: Throwable) {
                cleanupFailedOpen(descriptor, cacheFile, failure)
                throw failure
            }
        }

    override fun close() {
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
}

internal fun interface DescriptorOpener {
    @Throws(IOException::class)
    fun open(uri: String): OwnedDescriptor
}

internal fun interface CacheFileFactory {
    @Throws(IOException::class)
    fun create(): File
}

internal interface OwnedDescriptor : Closeable {
    val rawFd: Int

    @Throws(IOException::class)
    fun isSeekable(): Boolean

    @Throws(IOException::class)
    fun copyTo(target: File)
}

private class AndroidDescriptorOpener(
    private val contentResolver: ContentResolver,
) : DescriptorOpener {
    override fun open(uri: String): OwnedDescriptor {
        val parsed = Uri.parse(uri)
        val descriptor =
            contentResolver.openFileDescriptor(parsed, "r")
                ?: throw FileNotFoundException("DocumentsProvider returned no descriptor for $parsed")
        return ParcelDescriptor(descriptor)
    }
}

private class ParcelDescriptor(
    private val descriptor: ParcelFileDescriptor,
) : OwnedDescriptor {
    override val rawFd: Int
        get() = descriptor.fd

    override fun isSeekable(): Boolean =
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ESPIPE) {
                false
            } else {
                throw IOException("Could not determine SAF descriptor seekability", error)
            }
        }

    override fun copyTo(target: File) {
        // AutoCloseInputStream owns its PFD, so copy from a duplicate and retain the original.
        ParcelFileDescriptor.dup(descriptor.fileDescriptor).use { duplicate ->
            ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
    }

    override fun close() = descriptor.close()
}

private class AndroidCacheFileFactory(
    cacheDir: File,
) : CacheFileFactory {
    private val copyRoot = File(cacheDir, "saf-inputs")

    override fun create(): File {
        if (!copyRoot.isDirectory && !copyRoot.mkdirs()) {
            throw IOException("Could not create SAF cache directory: ${copyRoot.absolutePath}")
        }
        return File.createTempFile("input-", ".media", copyRoot)
    }
}
