package com.ankiminer.android.mining

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

internal enum class MiningRunKind(
    val wireValue: String,
    private val foregroundPrefix: String,
) {
    VIDEO("video", "video-"),
    READING("reading", "reading-"),
    ;

    fun foregroundRunId(cancellationToken: MiningCancellationToken): String =
        foregroundPrefix + cancellationToken.value

    companion object {
        fun fromForegroundRunId(runId: String): MiningRunKind? =
            entries.singleOrNull { kind ->
                runId.startsWith(kind.foregroundPrefix) &&
                    runCatching {
                        MiningCancellationToken(runId.removePrefix(kind.foregroundPrefix))
                    }.isSuccess
            }
    }
}

internal fun MiningCancellationToken.foregroundRunId(kind: MiningRunKind): String =
    kind.foregroundRunId(this)

internal data class InterruptedMiningRun(
    val kind: MiningRunKind,
    val ownerId: String,
    val runId: String?,
) {
    init {
        require(OWNER_ID.matches(ownerId))
        require(runId == null || RUN_ID.matches(runId))
    }

    private companion object {
        val OWNER_ID = Regex("cancel_[0-9a-f]{32}")
        val RUN_ID = Regex("run_[0-9a-f]{32}")
    }
}

internal interface MiningRunInterruptionStore {
    fun current(): InterruptedMiningRun?

    fun begin(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean

    fun registered(
        kind: MiningRunKind,
        ownerId: String,
        runId: String,
    ): Boolean

    fun complete(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean
}

internal data object NoOpMiningRunInterruptionStore : MiningRunInterruptionStore {
    override fun current(): InterruptedMiningRun? = null

    override fun begin(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean = true

    override fun registered(
        kind: MiningRunKind,
        ownerId: String,
        runId: String,
    ): Boolean = true

    override fun complete(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean = true
}

internal class AndroidMiningRunInterruptionStore(
    root: File,
) : MiningRunInterruptionStore {
    private val lock = Any()
    private val file = AtomicFile(File(root, FILE_NAME))

    override fun current(): InterruptedMiningRun? =
        synchronized(lock) {
            readLocked()
        }

    override fun begin(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean =
        synchronized(lock) {
            if (readLocked() != null || file.baseFile.exists()) return@synchronized false
            writeLocked(InterruptedMiningRun(kind, ownerId, runId = null))
        }

    override fun registered(
        kind: MiningRunKind,
        ownerId: String,
        runId: String,
    ): Boolean =
        synchronized(lock) {
            val expected = InterruptedMiningRun(kind, ownerId, runId = null)
            if (readLocked() != expected) return@synchronized false
            writeLocked(InterruptedMiningRun(kind, ownerId, runId))
        }

    override fun complete(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean =
        synchronized(lock) {
            val current = readLocked() ?: return@synchronized !file.baseFile.exists()
            if (current.kind != kind || current.ownerId != ownerId) return@synchronized false
            try {
                file.delete()
                !file.baseFile.exists()
            } catch (_: RuntimeException) {
                false
            }
        }

    private fun readLocked(): InterruptedMiningRun? {
        return try {
            val bytes =
                file.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(256)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            if (bytes.isEmpty()) return null
            val lines = bytes.toString(StandardCharsets.UTF_8).split('\n')
            if (lines.size != 4 || lines[0] != VERSION) return null
            val kind = MiningRunKind.entries.singleOrNull { it.wireValue == lines[1] } ?: return null
            InterruptedMiningRun(
                kind = kind,
                ownerId = lines[2],
                runId = lines[3].takeUnless { it == NO_RUN_ID },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLocked(record: InterruptedMiningRun): Boolean {
        val payload =
            listOf(
                VERSION,
                record.kind.wireValue,
                record.ownerId,
                record.runId ?: NO_RUN_ID,
            ).joinToString("\n")
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size !in 1..MAX_BYTES.toInt()) return false
        var output: FileOutputStream? = null
        return try {
            output = file.startWrite()
            output.write(bytes)
            output.flush()
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            runCatching { file.failWrite(output) }
            false
        }
    }

    private companion object {
        const val FILE_NAME = "mining-run-interruption-v1"
        const val VERSION = "1"
        const val NO_RUN_ID = "-"
        const val MAX_BYTES = 1024L
    }
}
