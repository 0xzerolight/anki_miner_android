package com.ankiminer.android.data.resources

import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The word-list file format, mirroring `anki_miner/services/word_list_service.WordListService`: one
 * word per line, UTF-8, blank lines and lines starting with `#` ignored.
 *
 * The engine opens the file with a strict UTF-8 decode and raises on failure, so an import that gets
 * this wrong would fail every later run instead of the one that chose the file.
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
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = file.inputStream().use { input -> decoder.decode(java.nio.ByteBuffer.wrap(input.readBytes())) }
        return entryCount(text.toString())
    }
}
