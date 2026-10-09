package com.ankiminer.android.data.resources

import com.ankiminer.android.anki.generated.UnicodeContractV151
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The word-list file format, mirroring `anki_miner/services/word_list_service.WordListService`: one
 * word per line, blank lines and lines starting with `#` ignored.
 *
 * Every installed list is BOM-less NFC UTF-8. A Japanese import must already be UTF-8: a Japanese
 * run reads lists with the engine's UTF-8 default, not the ja profile's ladder. Any other language's
 * import reaches [normalizeForInstall] after the bridge has rewritten it as UTF-8 through that
 * language's encoding ladder. A file this gate let through unreadable would fail every later run
 * instead of the one that chose it.
 */
internal object WordListFileFormat {
    /** Words the engine would load from [text]. */
    fun entryCount(text: String): Int =
        text.lineSequence().count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
        }

    /**
     * [entryCount] for [file], read the way the engine reads it.
     *
     * @throws CharacterCodingException when the bytes are not UTF-8.
     */
    fun entryCount(file: File): Int {
        return entryCount(decode(file))
    }

    /**
     * Validates staged UTF-8, removes its optional leading encoding signature and composes it to NFC
     * before publish. A kept U+FEFF would make [entryCount] read a leading `#` comment as a word.
     */
    fun normalizeForInstall(file: File): Int {
        val text = decode(file)
        val normalized =
            requireNotNull(UnicodeContractV151.normalizeNfc(text.removePrefix(UTF8_BOM))) {
                "Word-list text contains an invalid Unicode scalar"
            }
        if (normalized != text) {
            FileOutputStream(file, false).use { output ->
                output.write(normalized.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
        }
        return entryCount(normalized)
    }

    private fun decode(file: File): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = file.inputStream().use { input -> decoder.decode(java.nio.ByteBuffer.wrap(input.readBytes())) }
        return text.toString()
    }

    private const val UTF8_BOM = "\uFEFF"
}
