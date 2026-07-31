package com.ankiminer.android.diagnostics

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex

internal class DiagnosticsCaptureGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

internal class PythonLogSnapshotter(
    private val sourceDirectory: File,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val afterCopy: (name: String, attempt: Int) -> Unit = { _, _ -> },
) {
    init {
        require(maxAttempts > 0)
    }

    fun snapshot(workspace: File): Map<String, File> {
        val outputDirectory = File(workspace, "python")
        repeat(maxAttempts) { index ->
            clear(outputDirectory)
            val attempt = index + 1
            val output = linkedMapOf<String, File>()
            val stable =
                try {
                    NAMES.forEach { name ->
                        val source = File(sourceDirectory, name)
                        if (source.isFile) {
                            val copy = File(outputDirectory, name)
                            source.copyTo(copy)
                            output[name] = copy
                            afterCopy(name, attempt)
                        }
                    }
                    backupMatches(output[BACKUP_NAME])
                } catch (_: IOException) {
                    false
                }
            if (stable) return output
        }
        deleteOutputs(outputDirectory)
        throw IOException("Python log files changed during capture; retry limit reached")
    }

    private fun backupMatches(copiedBackup: File?): Boolean {
        val sourceBackup = File(sourceDirectory, BACKUP_NAME)
        val sourceExists = sourceBackup.isFile
        val copyExists = copiedBackup?.isFile == true
        if (sourceExists != copyExists) return false
        return !sourceExists || sameContents(sourceBackup, copiedBackup!!)
    }

    private fun sameContents(
        first: File,
        second: File,
    ): Boolean {
        if (first.length() != second.length()) return false
        first.inputStream().buffered().use { left ->
            second.inputStream().buffered().use { right ->
                val leftBuffer = ByteArray(COMPARE_BUFFER_BYTES)
                val rightBuffer = ByteArray(COMPARE_BUFFER_BYTES)
                while (true) {
                    val leftCount = left.readChunk(leftBuffer)
                    val rightCount = right.readChunk(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount == -1) return true
                    for (index in 0 until leftCount) {
                        if (leftBuffer[index] != rightBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = read(buffer, count, buffer.size - count)
            if (read == -1) return if (count == 0) -1 else count
            if (read > 0) count += read
        }
        return count
    }

    private fun clear(outputDirectory: File) {
        deleteOutputs(outputDirectory)
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            throw IOException("Python log snapshot directory is unavailable")
        }
    }

    private fun deleteOutputs(outputDirectory: File) {
        if (outputDirectory.exists() && !outputDirectory.deleteRecursively()) {
            throw IOException("Python log snapshot outputs could not be cleared")
        }
    }

    internal companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        private const val BACKUP_NAME = "anki_miner.log.1"
        private const val CURRENT_NAME = "anki_miner.log"
        private const val COMPARE_BUFFER_BYTES = 32 * 1024
        private val NAMES = listOf(BACKUP_NAME, CURRENT_NAME)
    }
}
