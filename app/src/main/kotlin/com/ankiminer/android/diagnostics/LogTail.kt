package com.ankiminer.android.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal data class LogTailResult(
    val text: String,
    val omittedBytes: Long,
    val omittedLines: Long,
    val totalBytes: Long,
)

internal object LogTail {
    fun of(
        file: File,
        maxBytes: Long,
    ): LogTailResult {
        if (!file.isFile) return EMPTY
        return try {
            FileInputStream(file).use { source -> of(source, maxBytes) }
        } catch (_: IOException) {
            EMPTY
        }
    }

    fun of(
        source: InputStream,
        maxBytes: Long,
    ): LogTailResult {
        require(maxBytes in 0 until Int.MAX_VALUE.toLong()) {
            "maxBytes must fit in a JVM byte array"
        }
        if (maxBytes == 0L) return consumeWithoutRetaining(source)

        val capacity = maxBytes.toInt() + 1
        val ring = ByteArray(capacity)
        val input = ByteArray(BUFFER_BYTES)
        var writeAt = 0
        var stored = 0
        var totalBytes = 0L
        var totalLines = 0L
        while (true) {
            val count = source.read(input)
            if (count < 0) break
            if (count == 0) continue
            for (index in 0 until count) {
                val byte = input[index]
                ring[writeAt] = byte
                writeAt = (writeAt + 1) % capacity
                if (stored < capacity) stored++
                totalBytes++
                if (byte == NEWLINE) totalLines++
            }
        }

        val window = chronologicalBytes(ring, writeAt, stored)
        val retained = minOf(totalBytes, maxBytes).toInt()
        val candidateStart = window.size - retained
        val outputStart =
            if (totalBytes <= maxBytes || isLineBoundary(window, candidateStart)) {
                candidateStart
            } else {
                firstCompleteLineStart(window, candidateStart)
            }
        val output = window.copyOfRange(outputStart, window.size)
        val outputLines = output.count { it == NEWLINE }.toLong()
        return LogTailResult(
            text = String(output, StandardCharsets.UTF_8),
            omittedBytes = totalBytes - output.size,
            omittedLines = totalLines - outputLines,
            totalBytes = totalBytes,
        )
    }

    private fun consumeWithoutRetaining(source: InputStream): LogTailResult {
        val input = ByteArray(BUFFER_BYTES)
        var bytes = 0L
        var lines = 0L
        while (true) {
            val count = source.read(input)
            if (count < 0) break
            if (count == 0) continue
            bytes += count
            for (index in 0 until count) {
                if (input[index] == NEWLINE) lines++
            }
        }
        return LogTailResult("", bytes, lines, bytes)
    }

    private fun chronologicalBytes(
        ring: ByteArray,
        writeAt: Int,
        stored: Int,
    ): ByteArray {
        if (stored < ring.size) return ring.copyOf(stored)
        return ByteArray(stored) { index -> ring[(writeAt + index) % ring.size] }
    }

    private fun isLineBoundary(
        window: ByteArray,
        candidateStart: Int,
    ): Boolean = candidateStart > 0 && window[candidateStart - 1] == NEWLINE

    private fun firstCompleteLineStart(
        window: ByteArray,
        candidateStart: Int,
    ): Int {
        for (index in candidateStart until window.size) {
            if (window[index] == NEWLINE) return index + 1
        }
        return window.size
    }

    private val EMPTY = LogTailResult("", 0, 0, 0)
    private const val BUFFER_BYTES = 8 * 1024
    private const val NEWLINE: Byte = '\n'.code.toByte()
}
