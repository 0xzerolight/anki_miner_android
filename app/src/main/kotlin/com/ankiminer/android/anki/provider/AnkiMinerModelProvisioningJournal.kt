package com.ankiminer.android.anki.provider

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

internal enum class AnkiMinerModelProvisioningPhase {
    PREPARED,
    MODEL_CREATE_ENTERED,
    MODEL_BASE_VERIFIED,
    TEMPLATE_UPDATE_ENTERED,
    COMPLETE,
}

internal data class AnkiMinerModelProvisioningRecord(
    val contractSha256: String,
    val phase: AnkiMinerModelProvisioningPhase,
    val modelId: Long? = null,
    val snapshotSha256: String? = null,
) {
    init {
        require(SHA256.matches(contractSha256)) { "Provisioning contract digest is invalid" }
        snapshotSha256?.let { require(SHA256.matches(it)) { "Provisioning snapshot digest is invalid" } }
        when (phase) {
            AnkiMinerModelProvisioningPhase.PREPARED,
            AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED,
            -> require(modelId == null && snapshotSha256 == null) {
                "A pre-reconciliation provisioning record cannot identify a model"
            }
            AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
            AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
            AnkiMinerModelProvisioningPhase.COMPLETE,
            -> require(modelId != null && modelId > 0L && snapshotSha256 != null) {
                "A reconciled provisioning record requires model identity and snapshot evidence"
            }
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal interface AnkiMinerModelProvisioningJournal {
    fun read(): AnkiMinerModelProvisioningRecord?

    /** Atomic compare-and-replace. The expected record prevents a second owner entering the provider. */
    fun replace(
        expected: AnkiMinerModelProvisioningRecord?,
        updated: AnkiMinerModelProvisioningRecord,
    )
}

internal open class AnkiMinerModelJournalException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class AnkiMinerModelJournalStateChangedException :
    AnkiMinerModelJournalException("The provisioning journal changed concurrently")

/**
 * A small no-backup, fsync-backed journal dedicated to first-party model provisioning.
 * The main mutation journal is intentionally not widened with a non-engine operation.
 */
internal class AtomicFileAnkiMinerModelProvisioningJournal private constructor(
    private val file: AtomicFile,
) : AnkiMinerModelProvisioningJournal {
    constructor(context: Context) :
        this(AtomicFile(File(context.noBackupFilesDir, JOURNAL_FILE_NAME)))

    internal constructor(baseFile: File) : this(AtomicFile(baseFile))

    @Synchronized
    override fun read(): AnkiMinerModelProvisioningRecord? = readCurrent()

    @Synchronized
    override fun replace(
        expected: AnkiMinerModelProvisioningRecord?,
        updated: AnkiMinerModelProvisioningRecord,
    ) {
        val current = readCurrent()
        if (current != expected) throw AnkiMinerModelJournalStateChangedException()
        requireAllowedTransition(current, updated)
        val encoded = AnkiMinerModelProvisioningJournalCodec.encode(updated)
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(encoded)
            file.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let(file::failWrite)
            throw AnkiMinerModelJournalException("Could not persist the model provisioning journal", error)
        }
    }

    private fun readCurrent(): AnkiMinerModelProvisioningRecord? {
        val bytes =
            try {
                file.readFully()
            } catch (_: FileNotFoundException) {
                return null
            } catch (error: IOException) {
                throw AnkiMinerModelJournalException("Could not read the model provisioning journal", error)
            }
        if (bytes.size > MAX_JOURNAL_BYTES) {
            throw AnkiMinerModelJournalException("The model provisioning journal is oversized")
        }
        return try {
            AnkiMinerModelProvisioningJournalCodec.decode(bytes)
        } catch (error: IllegalArgumentException) {
            throw AnkiMinerModelJournalException("The model provisioning journal is corrupt", error)
        }
    }

    private companion object {
        const val JOURNAL_FILE_NAME = "anki-miner-model-provisioning-v1.journal"
        const val MAX_JOURNAL_BYTES = 512
    }
}

internal object AnkiMinerModelProvisioningJournalCodec {
    fun encode(record: AnkiMinerModelProvisioningRecord): ByteArray =
        buildString(256) {
            append(HEADER)
            append('\n')
            append(record.contractSha256)
            append('\n')
            append(record.phase.name)
            append('\n')
            append(record.modelId?.toString().orEmpty())
            append('\n')
            append(record.snapshotSha256.orEmpty())
            append('\n')
        }.toByteArray(StandardCharsets.US_ASCII)

    fun decode(bytes: ByteArray): AnkiMinerModelProvisioningRecord {
        require(bytes.all { byte -> byte.toInt() in 0..0x7f }) { "Journal is not ASCII" }
        val lines = String(bytes, StandardCharsets.US_ASCII).split('\n')
        require(lines.size == FIELD_COUNT_WITH_TERMINATOR && lines.last().isEmpty()) {
            "Journal field count is invalid"
        }
        require(lines[0] == HEADER) { "Journal version is unsupported" }
        val phase =
            AnkiMinerModelProvisioningPhase.entries.singleOrNull { it.name == lines[2] }
                ?: throw IllegalArgumentException("Journal phase is invalid")
        val modelId =
            lines[3].takeIf(String::isNotEmpty)?.let { raw ->
                require(CANONICAL_POSITIVE_LONG.matches(raw)) { "Journal model ID is invalid" }
                raw.toLongOrNull()?.takeIf { it > 0L }
                    ?: throw IllegalArgumentException("Journal model ID is invalid")
            }
        val snapshot = lines[4].takeIf(String::isNotEmpty)
        return AnkiMinerModelProvisioningRecord(
            contractSha256 = lines[1],
            phase = phase,
            modelId = modelId,
            snapshotSha256 = snapshot,
        )
    }

    private const val HEADER = "anki-miner-model-provisioning-journal-v1"
    private const val FIELD_COUNT_WITH_TERMINATOR = 6
    private val CANONICAL_POSITIVE_LONG = Regex("[1-9][0-9]*")
}

internal fun requireAllowedTransition(
    current: AnkiMinerModelProvisioningRecord?,
    updated: AnkiMinerModelProvisioningRecord,
) {
    if (
        current?.phase == AnkiMinerModelProvisioningPhase.COMPLETE &&
            updated.phase == AnkiMinerModelProvisioningPhase.PREPARED
    ) {
        // A completed generation has no entered provider mutation. Explicit setup may retire it
        // after the exact model was deleted or the app's owned contract advanced.
        return
    }
    require(current == null || current.contractSha256 == updated.contractSha256) {
        "A provisioning journal contract cannot be replaced"
    }
    val allowed =
        when (current?.phase) {
            null -> updated.phase == AnkiMinerModelProvisioningPhase.PREPARED
            AnkiMinerModelProvisioningPhase.PREPARED ->
                updated.phase == AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED
            AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED ->
                updated.phase == AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED ||
                    updated.phase == AnkiMinerModelProvisioningPhase.COMPLETE
            AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED ->
                updated.phase == AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED ||
                    updated.phase == AnkiMinerModelProvisioningPhase.COMPLETE
            AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED ->
                updated.phase == AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED ||
                    updated.phase == AnkiMinerModelProvisioningPhase.COMPLETE
            AnkiMinerModelProvisioningPhase.COMPLETE -> false
        }
    require(allowed) { "Invalid model provisioning journal transition" }
    if (current?.modelId != null && updated.modelId != null) {
        require(current.modelId == updated.modelId) { "Provisioned model identity cannot change" }
    }
    if (
        current?.phase == AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED &&
            updated.phase == AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED
    ) {
        require(current == updated) { "Template entry evidence cannot be rewritten" }
    }
}
