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

/**
 * Durable state sampled once per process, before any run in it can begin. Both lanes read the same
 * sample, so what a repository sees no longer depends on when it happens to be constructed, and a
 * run started later in this process is never mistaken for an interrupted one.
 */
internal sealed interface StartupInterruption {
    /** Nothing was left behind. */
    data object None : StartupInterruption

    /** A run of either lane that never completed. One record covers both lanes. */
    data class Interrupted(
        val record: InterruptedMiningRun,
    ) : StartupInterruption

    /** Bytes that could not be decoded; they still block admission until acknowledged. */
    data object Unrecognized : StartupInterruption
}

internal interface MiningRunInterruptionStore {
    /**
     * Durable state left behind by an earlier process. Retires as soon as this process changes the
     * durable state, so a repository constructed after the other lane acknowledged the record
     * starts idle instead of reporting a run that is already cleared.
     */
    fun startupInterruption(): StartupInterruption

    /**
     * True when no undecodable record remains. A decodable record is never removed here: it means
     * the undecodable bytes are already gone.
     */
    fun clearUnrecognizedRecord(): Boolean

    fun begin(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean

    fun registered(
        kind: MiningRunKind,
        ownerId: String,
        runId: String,
    ): Boolean

    /**
     * True when no record for this run remains, whether this call removed it or the other lane
     * already acknowledged it.
     */
    fun complete(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean
}

internal data object NoOpMiningRunInterruptionStore : MiningRunInterruptionStore {
    override fun startupInterruption(): StartupInterruption = StartupInterruption.None

    override fun clearUnrecognizedRecord(): Boolean = true

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
    private var startupState: StartupInterruption = sampleStartupState()

    override fun startupInterruption(): StartupInterruption =
        synchronized(lock) {
            startupState
        }

    override fun clearUnrecognizedRecord(): Boolean =
        synchronized(lock) {
            if (readLocked() != null) {
                // A decodable record means the undecodable bytes are gone and a run of this
                // process wrote over them, so the startup sample is spent like every other arm.
                startupState = StartupInterruption.None
                return@synchronized true
            }
            if (!file.baseFile.exists()) {
                startupState = StartupInterruption.None
                return@synchronized true
            }
            val removed =
                try {
                    file.delete()
                    !file.baseFile.exists()
                    // instrumentation: silent — false leaves the unknown record recovery-blocking
                } catch (_: RuntimeException) {
                    false
                }
            if (removed) startupState = StartupInterruption.None
            removed
        }

    override fun begin(
        kind: MiningRunKind,
        ownerId: String,
    ): Boolean =
        synchronized(lock) {
            if (readLocked() != null || file.baseFile.exists()) return@synchronized false
            val written = writeLocked(InterruptedMiningRun(kind, ownerId, runId = null))
            if (written) startupState = StartupInterruption.None
            written
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
            val current = readLocked()
            if (current == null) {
                // Undecodable bytes still block admission; an absent file means nothing remains.
                if (file.baseFile.exists()) return@synchronized false
                startupState = StartupInterruption.None
                return@synchronized true
            }
            if (current.kind != kind || current.ownerId != ownerId) {
                // This run's record is already gone: the other lane acknowledged the startup
                // record and a later run wrote its own. Nothing left for this caller to remove.
                startupState = StartupInterruption.None
                return@synchronized true
            }
            val removed =
                try {
                    file.delete()
                    !file.baseFile.exists()
                    // instrumentation: silent — false retains explicit interrupted-run recovery
                } catch (_: RuntimeException) {
                    false
                }
            if (removed) startupState = StartupInterruption.None
            removed
        }

    /**
     * Read in the constructor, before any other reference to this store exists, so no lock is
     * needed and no run of this process can have written what is being sampled.
     */
    private fun sampleStartupState(): StartupInterruption {
        val record = readLocked()
        return when {
            record != null -> StartupInterruption.Interrupted(record)
            file.baseFile.exists() -> StartupInterruption.Unrecognized
            else -> StartupInterruption.None
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
            // instrumentation: silent — null keeps malformed durable state recovery-blocking
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
            // instrumentation: silent — failWrite and false preserve prior durable state
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
