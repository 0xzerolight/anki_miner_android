package com.ankiminer.android.data.settings

import android.content.ContentResolver
import android.net.Uri
import com.ankiminer.android.data.resources.ResourceDocumentWriter
import com.ankiminer.android.media.CancellableProviderIo
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancelledException
import com.ankiminer.android.media.ProviderIoDeadlineScheduler
import com.ankiminer.android.media.RealProviderIoDeadlineScheduler
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal fun interface SettingsDocumentReader {
    /**
     * Reads [uri], a `content://` string. Kept as a String rather than a parsed [Uri] so callers can
     * be exercised by JVM unit tests, where `Uri.parse` is a throwing stub.
     */
    suspend fun read(uri: String): String
}

internal fun interface SettingsDocumentInputOpener {
    fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream
}

internal fun interface SettingsDocumentOutputOpener {
    fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): OutputStream
}

private class AndroidSettingsDocumentInputOpener(
    private val resolver: ContentResolver,
) : SettingsDocumentInputOpener {
    override fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream =
        CancellableProviderIo.open(cancellation) { signal ->
            val descriptor =
                resolver.openAssetFileDescriptor(Uri.parse(uri), "r", signal)
                    ?: throw IOException("DocumentsProvider returned no stream for $uri")
            try {
                descriptor.createInputStream()
            } catch (failure: Throwable) {
                try {
                    descriptor.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
}

private class AndroidSettingsDocumentOutputOpener(
    private val writer: ResourceDocumentWriter,
) : SettingsDocumentOutputOpener {
    override fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): OutputStream =
        CancellableProviderIo.open(cancellation) { signal ->
            writer.open(uri, signal)
                ?: throw IOException("DocumentsProvider returned no stream for $uri")
        }
}

internal class AndroidSettingsDocumentReader internal constructor(
    private val inputOpener: SettingsDocumentInputOpener,
    private val providerIoScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val providerIoTimeoutMillis: Long = PROVIDER_IO_TIMEOUT_MILLIS,
    private val providerIoScheduler: ProviderIoDeadlineScheduler =
        RealProviderIoDeadlineScheduler,
) : SettingsDocumentReader {
    constructor(resolver: ContentResolver) : this(
        inputOpener = AndroidSettingsDocumentInputOpener(resolver),
    )

    override suspend fun read(uri: String): String =
        CancellableProviderIo.execute(
            scope = providerIoScope,
            timeoutMillis = providerIoTimeoutMillis,
            scheduler = providerIoScheduler,
        ) { deadline ->
            CancellableProviderIo.useResource(
                cancellation = deadline,
                open = { inputOpener.open(uri, deadline) },
            ) { stream ->
                deadline.rearm()
                val bytes =
                    stream.readAtMost(
                        SettingsBackupCodec.MAX_DOCUMENT_BYTES + 1,
                        cancellation = deadline,
                        onProgress = deadline::rearm,
                    )
                if (bytes.size > SettingsBackupCodec.MAX_DOCUMENT_BYTES) {
                    throw SettingsBackupException(SettingsBackupFailure.TOO_LARGE)
                }
                bytes.decodeStrictUtf8()
            }
        }

    private companion object {
        const val PROVIDER_IO_TIMEOUT_MILLIS = 30_000L
    }
}

internal fun interface SettingsBackupWriter {
    suspend fun write(
        uri: String,
        bytes: ByteArray,
    )
}

internal class SettingsDocumentWriter internal constructor(
    private val outputOpener: SettingsDocumentOutputOpener,
    private val providerIoScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val providerIoTimeoutMillis: Long = PROVIDER_IO_TIMEOUT_MILLIS,
    private val providerIoScheduler: ProviderIoDeadlineScheduler =
        RealProviderIoDeadlineScheduler,
) : SettingsBackupWriter {
    constructor(writer: ResourceDocumentWriter) : this(
        outputOpener = AndroidSettingsDocumentOutputOpener(writer),
    )

    override suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Unit =
        CancellableProviderIo.execute(
            scope = providerIoScope,
            timeoutMillis = providerIoTimeoutMillis,
            scheduler = providerIoScheduler,
        ) { deadline ->
            CancellableProviderIo.useResource(
                cancellation = deadline,
                open = { outputOpener.open(uri, deadline) },
            ) { stream ->
                var offset = 0
                while (offset < bytes.size) {
                    deadline.throwIfCancelled()
                    val count = minOf(WRITE_CHUNK_BYTES, bytes.size - offset)
                    stream.write(bytes, offset, count)
                    deadline.throwIfCancelled()
                    deadline.rearm()
                    offset += count
                }
            }
        }

    private companion object {
        const val PROVIDER_IO_TIMEOUT_MILLIS = 30_000L
        const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}

/**
 * Read at most [maximumBytes], stopping at end of stream.
 *
 * Hand-rolled because `InputStream.readNBytes` is API 33 and this app supports API 26; lint's
 * `NewApi` check is what catches the mistake. Shared with the update client, which bounds an
 * untrusted HTTP body the same way.
 */
internal fun InputStream.readAtMost(
    maximumBytes: Int,
    cancellation: ProviderIoCancellation = ProviderIoCancellation.NONE,
    onProgress: () -> Unit = {},
): ByteArray {
    val buffer = ByteArray(maximumBytes)
    var total = 0
    while (total < buffer.size) {
        cancellation.throwIfCancelled()
        when (val count = read(buffer, total, buffer.size - total)) {
            -1 -> {
                cancellation.throwIfCancelled()
                onProgress()
                break
            }
            0 -> {
                cancellation.throwIfCancelled()
                val next = read()
                cancellation.throwIfCancelled()
                onProgress()
                if (next == -1) break
                buffer[total] = next.toByte()
                total += 1
            }
            else -> {
                cancellation.throwIfCancelled()
                onProgress()
                total += count
            }
        }
    }
    return buffer.copyOf(total)
}

private fun ProviderIoCancellation.throwIfCancelled() {
    if (isCancelled()) throw ProviderIoCancelledException()
}

private fun ByteArray.decodeStrictUtf8(): String =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw SettingsBackupException(SettingsBackupFailure.MALFORMED).also {
            it.initCause(failure)
        }
    }
