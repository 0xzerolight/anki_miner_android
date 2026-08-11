package com.ankiminer.android.data.settings

import android.content.ContentResolver
import android.net.Uri
import com.ankiminer.android.media.CancellableProviderIo
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancelledException
import com.ankiminer.android.media.ProviderIoDeadlineScheduler
import com.ankiminer.android.media.RealProviderIoDeadlineScheduler
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

internal fun interface SettingsDocumentReader {
    /**
     * Reads [uri], a `content://` string. Kept as a String rather than a parsed [Uri] so callers can
     * be exercised by JVM unit tests, where `Uri.parse` is a throwing stub.
     */
    fun read(uri: String): String
}

internal fun interface SettingsDocumentInputOpener {
    fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream
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

    override fun read(uri: String): String =
        runBlocking {
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
                    bytes.toString(Charsets.UTF_8)
                }
            }
        }

    private companion object {
        const val PROVIDER_IO_TIMEOUT_MILLIS = 30_000L
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
