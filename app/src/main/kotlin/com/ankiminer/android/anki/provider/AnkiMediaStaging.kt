package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.journal.StagingDraft
import com.ankiminer.android.anki.journal.StagingRecord
import com.ankiminer.android.anki.journal.StagingState
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal const val ANKIDROID_PACKAGE = "com.ichi2.anki"
internal const val ANKI_MEDIA_STAGING_ROOT = "anki-media-staging"

private const val STAGING_VERSION_DIRECTORY = "v1"
private const val STAGING_FILE_SUFFIX = ".stage"
private const val PATH_ALLOCATION_ATTEMPTS = 16

private val RUN_ID_PATTERN = Regex("run_[0-9a-f]{32}")
private val REQUEST_ID_PATTERN = Regex("anki_[0-9a-f]{32}")
private val ASSET_ID_PATTERN = Regex("asset_[0-9a-f]{32}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val NONCE_PATTERN = Regex("[0-9a-f]{32}")
private val GENERATED_PATH_PATTERN = Regex("v1/[0-9a-f]{64}\\.stage")

internal data class AnkiMediaStagingRequest(
    val runId: String,
    val requestId: String,
    val assetId: String,
    val absoluteSourcePath: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val aggregateRemainingBytes: Long,
)

internal enum class AnkiMediaStagingFailure {
    INVALID_REQUEST,
    CAPACITY_EXCEEDED,
    VERIFICATION_FAILED,
    PREPARATION_FAILED,
    PERMISSION_FAILED,
    CLEANUP_FAILED,
    UNSAFE_JOURNAL,
    JOURNAL_FAILED,
}

internal class AnkiMediaStagingException(
    val failure: AnkiMediaStagingFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal enum class AnkiMediaCleanupOutcome {
    CLEANED,
    QUARANTINED,
}

internal data class AnkiMediaRecoveryReport(
    val cleanedRecords: Int,
    val quarantinedRecords: Int,
    val sweptOrphans: Int,
) {
    val isClean: Boolean
        get() = quarantinedRecords == 0
}

/** Small durable boundary so staging tests do not need an Android database or Context. */
internal interface AnkiMediaStagingJournal {
    fun record(draft: StagingDraft): StagingRecord

    fun transition(
        stagingId: Long,
        state: StagingState,
        compactEvidence: String,
    ): StagingRecord

    fun recoveryRecords(): List<StagingRecord>

    fun addQuarantineRemediation(stagingId: Long)

    fun completeCleanup(
        stagingId: Long,
        compactEvidence: String,
    )
}

/** A destination whose bytes can be flushed and durably synchronized before publication. */
internal interface AnkiMediaStagingOutput : Closeable {
    val stream: OutputStream

    fun sync()
}

/** Android-free platform seam for filesystem and URI-grant operations. */
internal interface AnkiMediaStagingPlatform {
    val authority: String

    fun contentUriFor(relativePath: String): String

    fun destinationExists(relativePath: String): Boolean

    fun openSource(absolutePath: String): InputStream

    fun createDestination(relativePath: String): AnkiMediaStagingOutput

    fun grantRead(
        packageName: String,
        contentUri: String,
    )

    fun revokeRead(
        packageName: String,
        contentUri: String,
    )

    fun deleteDestination(relativePath: String)

    fun sweepUnjournaled(journaledRelativePaths: Set<String>): Int
}

internal fun interface AnkiMediaStagingNonceSource {
    fun nextNonce(): String
}

internal object SecureAnkiMediaStagingNonceSource : AnkiMediaStagingNonceSource {
    private val random = SecureRandom()

    override fun nextNonce(): String =
        ByteArray(16)
            .also(random::nextBytes)
            .toLowerHex()
}

/** Serializes staging and recovery across every component in this app process. */
internal class AnkiMediaStagingProcessLock internal constructor() {
    private val monitor = Any()

    fun <T> locked(block: () -> T): T = synchronized(monitor, block)

    internal companion object {
        val shared = AnkiMediaStagingProcessLock()
    }
}

/**
 * Owns the private copy which AnkiDroid may read. Source paths never cross the provider seam.
 *
 * A durable immutable draft is written before the destination is created. Recovery therefore has
 * enough identity to revoke and remove every copy after a crash at any later instruction.
 */
internal class AnkiMediaStaging(
    private val journal: AnkiMediaStagingJournal,
    private val platform: AnkiMediaStagingPlatform,
    private val nonceSource: AnkiMediaStagingNonceSource = SecureAnkiMediaStagingNonceSource,
    private val processLock: AnkiMediaStagingProcessLock = AnkiMediaStagingProcessLock.shared,
) {
    fun stage(request: AnkiMediaStagingRequest): StagingRecord =
        processLock.locked { stageLocked(request) }

    private fun stageLocked(request: AnkiMediaStagingRequest): StagingRecord {
        validateRequest(request)
        if (request.expectedSizeBytes > request.aggregateRemainingBytes) {
            throw capacityExceeded()
        }

        val relativePath = allocateRelativePath(request)
        val contentUri =
            try {
                platform.contentUriFor(relativePath)
            } catch (error: Exception) {
                throw preparationFailed(error)
            }
        if (!isStrictContentUri(relativePath, contentUri)) throw preparationFailed()

        val record =
            try {
                journal.record(
                    StagingDraft(
                        runId = request.runId,
                        requestId = request.requestId,
                        assetId = request.assetId,
                        relativePath = relativePath,
                        contentUri = contentUri,
                        packageName = ANKIDROID_PACKAGE,
                        sizeBytes = request.expectedSizeBytes,
                        sha256 = request.expectedSha256,
                    ),
                )
            } catch (error: Exception) {
                throw journalFailed(error)
            }

        try {
            copyAndVerify(request, relativePath)
        } catch (error: Exception) {
            val stable =
                when (error) {
                    is AnkiMediaStagingException -> error
                    else -> preparationFailed(error)
                }
            try {
                if (cleanup(record) == AnkiMediaCleanupOutcome.QUARANTINED) {
                    stable.addSuppressed(cleanupFailed())
                }
            } catch (cleanupError: Exception) {
                stable.addSuppressed(cleanupError)
            }
            throw stable
        }
        return record
    }

    fun grantRead(record: StagingRecord): StagingRecord =
        processLock.locked { grantReadLocked(record) }

    private fun grantReadLocked(record: StagingRecord): StagingRecord {
        validateStoredIdentity(record)
        if (record.state != StagingState.STAGED) throw unsafeJournal()
        try {
            platform.grantRead(ANKIDROID_PACKAGE, record.contentUri)
        } catch (error: Exception) {
            cleanupAfterGrantFailure(record)
            throw permissionFailed(error)
        }
        return try {
            journal.transition(
                record.id,
                StagingState.GRANTED,
                EVIDENCE_READ_GRANT_RECORDED,
            )
        } catch (error: Exception) {
            cleanupAfterGrantFailure(record)
            throw journalFailed(error)
        }
    }

    fun cleanup(record: StagingRecord): AnkiMediaCleanupOutcome =
        processLock.locked { cleanupLocked(record) }

    private fun cleanupLocked(record: StagingRecord): AnkiMediaCleanupOutcome {
        validateStoredIdentity(record)
        if (record.state == StagingState.CLEANED) throw unsafeJournal()
        val pending =
            when (record.state) {
                StagingState.STAGED, StagingState.GRANTED ->
                    try {
                        journal.transition(
                            record.id,
                            StagingState.CLEANUP_PENDING,
                            EVIDENCE_CLEANUP_SCHEDULED,
                        )
                    } catch (error: Exception) {
                        throw journalFailed(error)
                    }
                StagingState.CLEANUP_PENDING, StagingState.QUARANTINED -> record
                StagingState.CLEANED -> error("checked above")
            }

        var cleanupFailed = false
        try {
            platform.revokeRead(ANKIDROID_PACKAGE, pending.contentUri)
        } catch (_: Exception) {
            cleanupFailed = true
        }
        try {
            platform.deleteDestination(pending.relativePath)
        } catch (_: Exception) {
            cleanupFailed = true
        }
        if (cleanupFailed) {
            quarantine(pending)
            return AnkiMediaCleanupOutcome.QUARANTINED
        }

        try {
            journal.completeCleanup(pending.id, EVIDENCE_CLEANUP_COMPLETED)
        } catch (error: Exception) {
            throw journalFailed(error)
        }
        return AnkiMediaCleanupOutcome.CLEANED
    }

    fun recover(): AnkiMediaRecoveryReport = processLock.locked(::recoverLocked)

    private fun recoverLocked(): AnkiMediaRecoveryReport {
        val records =
            try {
                journal.recoveryRecords()
            } catch (error: Exception) {
                throw journalFailed(error)
            }
        validateRecoverySet(records)

        var cleaned = 0
        var quarantined = 0
        val retainedPaths = linkedSetOf<String>()
        records.forEach { record ->
            if (record.state == StagingState.CLEANED) {
                finalizeCleaned(record)
                cleaned += 1
            } else {
                when (cleanup(record)) {
                    AnkiMediaCleanupOutcome.CLEANED -> cleaned += 1
                    AnkiMediaCleanupOutcome.QUARANTINED -> {
                        quarantined += 1
                        retainedPaths += record.relativePath
                    }
                }
            }
        }
        val swept =
            try {
                platform.sweepUnjournaled(retainedPaths)
            } catch (error: Exception) {
                throw cleanupFailed(error)
            }
        return AnkiMediaRecoveryReport(cleaned, quarantined, swept)
    }

    private fun copyAndVerify(
        request: AnkiMediaStagingRequest,
        relativePath: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        platform.openSource(request.absoluteSourcePath).use { source ->
            platform.createDestination(relativePath).use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied =
                        try {
                            Math.addExact(copied, count.toLong())
                        } catch (_: ArithmeticException) {
                            throw verificationFailed()
                        }
                    if (copied > request.aggregateRemainingBytes) throw capacityExceeded()
                    if (copied > request.expectedSizeBytes) throw verificationFailed()
                    destination.stream.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                if (copied != request.expectedSizeBytes) throw verificationFailed()
                if (digest.digest().toLowerHex() != request.expectedSha256) {
                    throw verificationFailed()
                }
                destination.stream.flush()
                destination.sync()
            }
        }
    }

    private fun allocateRelativePath(request: AnkiMediaStagingRequest): String {
        repeat(PATH_ALLOCATION_ATTEMPTS) {
            val nonce = nonceSource.nextNonce()
            if (!NONCE_PATTERN.matches(nonce)) throw invalidRequest()
            val identity =
                listOf(request.runId, request.requestId, request.assetId, nonce)
                    .joinToString("\u0000")
                    .toByteArray(StandardCharsets.UTF_8)
            val token = MessageDigest.getInstance("SHA-256").digest(identity).toLowerHex()
            val relativePath = "$STAGING_VERSION_DIRECTORY/$token$STAGING_FILE_SUFFIX"
            val exists =
                try {
                    platform.destinationExists(relativePath)
                } catch (error: Exception) {
                    throw preparationFailed(error)
                }
            if (!exists) return relativePath
        }
        throw preparationFailed()
    }

    private fun validateRequest(request: AnkiMediaStagingRequest) {
        if (
            !RUN_ID_PATTERN.matches(request.runId) ||
            !REQUEST_ID_PATTERN.matches(request.requestId) ||
            !ASSET_ID_PATTERN.matches(request.assetId) ||
            !File(request.absoluteSourcePath).isAbsolute ||
            request.expectedSizeBytes !in 0L..AnkiLimitsV1.StoreMedia.MAX_ASSET_BYTES.toLong() ||
            request.aggregateRemainingBytes !in
                0L..AnkiLimitsV1.StoreMedia.MAX_TOTAL_BYTES.toLong() ||
            !SHA256_PATTERN.matches(request.expectedSha256)
        ) {
            throw invalidRequest()
        }
    }

    private fun validateRecoverySet(records: List<StagingRecord>) {
        if (
            records.map { it.id }.distinct().size != records.size ||
            records.map { it.relativePath }.distinct().size != records.size ||
            records.map { it.contentUri }.distinct().size != records.size
        ) {
            throw unsafeJournal()
        }
        records.forEach(::validateStoredIdentity)
    }

    private fun validateStoredIdentity(record: StagingRecord) {
        val exactUri =
            try {
                platform.contentUriFor(record.relativePath)
            } catch (_: Exception) {
                throw unsafeJournal()
            }
        if (
            record.id <= 0L ||
            !RUN_ID_PATTERN.matches(record.runId) ||
            !REQUEST_ID_PATTERN.matches(record.requestId) ||
            !ASSET_ID_PATTERN.matches(record.assetId) ||
            !GENERATED_PATH_PATTERN.matches(record.relativePath) ||
            record.packageName != ANKIDROID_PACKAGE ||
            record.sizeBytes < 0L ||
            !SHA256_PATTERN.matches(record.sha256) ||
            record.contentUri != exactUri ||
            !isStrictContentUri(record.relativePath, record.contentUri)
        ) {
            throw unsafeJournal()
        }
    }

    private fun isStrictContentUri(
        relativePath: String,
        contentUri: String,
    ): Boolean {
        if (!GENERATED_PATH_PATTERN.matches(relativePath)) return false
        val parsed =
            try {
                URI(contentUri)
            } catch (_: Exception) {
                return false
            }
        return parsed.scheme == "content" &&
            parsed.rawAuthority == platform.authority &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null &&
            parsed.rawUserInfo == null &&
            parsed.port == -1
    }

    private fun cleanupAfterGrantFailure(record: StagingRecord) {
        try {
            cleanup(record)
        } catch (_: Exception) {
            // The durable record remains recoverable; the primary failure stays stable.
        }
    }

    private fun finalizeCleaned(record: StagingRecord) {
        try {
            journal.completeCleanup(record.id, EVIDENCE_CLEANUP_COMPLETED)
        } catch (error: Exception) {
            throw journalFailed(error)
        }
    }

    private fun quarantine(record: StagingRecord) {
        try {
            if (record.state != StagingState.QUARANTINED) {
                journal.transition(
                    record.id,
                    StagingState.QUARANTINED,
                    EVIDENCE_CLEANUP_FAILED,
                )
            }
            journal.addQuarantineRemediation(record.id)
        } catch (error: Exception) {
            throw journalFailed(error)
        }
    }
}

private const val EVIDENCE_READ_GRANT_RECORDED = "exact AnkiDroid read grant recorded"
private const val EVIDENCE_CLEANUP_SCHEDULED = "staging revoke and cleanup scheduled"
private const val EVIDENCE_CLEANUP_COMPLETED = "staging grant revoked and private copy removed"
private const val EVIDENCE_CLEANUP_FAILED = "staging revoke or private-copy cleanup failed"

private fun ByteArray.toLowerHex(): String =
    buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(LOWER_HEX_DIGITS[value ushr 4])
            append(LOWER_HEX_DIGITS[value and 0x0f])
        }
    }

private const val LOWER_HEX_DIGITS = "0123456789abcdef"

private fun invalidRequest(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.INVALID_REQUEST,
        "Media staging request is invalid",
        cause,
    )

private fun capacityExceeded(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.CAPACITY_EXCEEDED,
        "Media staging capacity was exceeded",
        cause,
    )

private fun verificationFailed(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.VERIFICATION_FAILED,
        "Media staging verification failed",
        cause,
    )

private fun preparationFailed(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.PREPARATION_FAILED,
        "Media staging could not be prepared",
        cause,
    )

private fun permissionFailed(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.PERMISSION_FAILED,
        "Media staging permission could not be granted",
        cause,
    )

private fun cleanupFailed(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.CLEANUP_FAILED,
        "Media staging cleanup requires attention",
        cause,
    )

private fun unsafeJournal(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.UNSAFE_JOURNAL,
        "Stored media staging identity is unsafe",
        cause,
    )

private fun journalFailed(cause: Throwable? = null) =
    AnkiMediaStagingException(
        AnkiMediaStagingFailure.JOURNAL_FAILED,
        "Media staging journal operation failed",
        cause,
    )
