package com.ankiminer.android.diagnostics

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads the tail of the engine's rotating log file for the opt-in share action.
 *
 * The raw log may contain user file names, so it is never embedded in the coded
 * [TesterDiagnostics] report; sharing it is a separate, explicitly-labeled step.
 * Reading may block and must run off the main thread.
 */
class EngineLogReader(
    private val logFile: File,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    fun tail(): String {
        val bytes =
            try {
                if (!logFile.isFile) return ""
                RandomAccessFile(logFile, "r").use { file ->
                    val length = file.length()
                    val count = minOf(length, maxBytes.toLong()).toInt()
                    val buffer = ByteArray(count)
                    file.seek(length - count)
                    file.readFully(buffer)
                    buffer
                }
            } catch (_: IOException) {
                return ""
            }
        val decoder =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16 * 1024
    }
}
