package com.ankiminer.android.data.settings

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

internal fun interface SettingsDocumentReader {
    /**
     * Reads [uri], a `content://` string. Kept as a String rather than a parsed [Uri] so callers can
     * be exercised by JVM unit tests, where `Uri.parse` is a throwing stub.
     */
    fun read(uri: String): String
}

internal class AndroidSettingsDocumentReader(
    private val resolver: ContentResolver,
) : SettingsDocumentReader {
    override fun read(uri: String): String {
        val bytes =
            resolver.openInputStream(Uri.parse(uri))?.use { stream ->
                stream.readAtMost(SettingsBackupCodec.MAX_DOCUMENT_BYTES + 1)
            } ?: throw IOException("DocumentsProvider returned no stream for $uri")
        if (bytes.size > SettingsBackupCodec.MAX_DOCUMENT_BYTES) {
            throw SettingsBackupException(SettingsBackupFailure.TOO_LARGE)
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

/**
 * Read at most [maximumBytes], stopping at end of stream.
 *
 * Hand-rolled because `InputStream.readNBytes` is API 33 and this app supports API 26; lint's
 * `NewApi` check is what catches the mistake. Shared with the update client, which bounds an
 * untrusted HTTP body the same way.
 */
internal fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
    val buffer = ByteArray(maximumBytes)
    var total = 0
    while (total < buffer.size) {
        when (val count = read(buffer, total, buffer.size - total)) {
            -1 -> break
            0 -> {
                val next = read()
                if (next == -1) break
                buffer[total] = next.toByte()
                total += 1
            }
            else -> total += count
        }
    }
    return buffer.copyOf(total)
}
