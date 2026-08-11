package com.ankiminer.android.data.resources

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One crash marker for the process-wide resource mutation lease.
 *
 * Download bytes already survive restart. This marker preserves the operation needed to interpret
 * those bytes and to offer the existing Retry/Choose another action after startup reconciliation.
 */
internal data class PersistedResourceOperation(
    val origin: ResourceFailureOrigin,
    val retry: ResourceFailureRetry,
    val knownWordsOperation: KnownWordsFailureOperation? = null,
    val resourceImportUri: String? = null,
    val resourceImportOwnership: ResourceImportOwnershipPhase? = null,
) {
    init {
        require((resourceImportUri == null) == (resourceImportOwnership == null))
    }
}

internal enum class ResourceImportOwnershipPhase {
    INVENTORY_RETAINED,
}

internal class ResourceOperationJournal(
    private val root: File,
    private val syncDirectory: (File) -> Unit = ::syncResourceDirectory,
) {
    private val record = File(root, RECORD_NAME)
    private val candidate = File(root, CANDIDATE_NAME)

    fun exists(): Boolean = record.isFile

    fun write(operation: PersistedResourceOperation) {
        if (!root.exists() && !root.mkdirs()) {
            throw ResourceDownloadException(
                "import_staging_failed",
                "Could not persist the resource operation",
            )
        }
        val target = operation.retry.targetId.orEmpty()
        val resourceImportUri = operation.resourceImportUri.orEmpty()
        require('\n' !in target && '\r' !in target)
        require('\n' !in resourceImportUri && '\r' !in resourceImportUri)
        val bytes =
            listOf(
                FORMAT,
                operation.origin.name,
                operation.retry.action.name,
                target,
                operation.retry.replace.toString(),
                operation.knownWordsOperation?.name.orEmpty(),
                resourceImportUri,
                operation.resourceImportOwnership?.name.orEmpty(),
            ).joinToString(separator = "\n", postfix = "\n")
                .toByteArray(Charsets.UTF_8)
        candidate.delete()
        FileOutputStream(candidate).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            try {
                Files.move(
                    candidate.toPath(),
                    record.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                // instrumentation: silent — unsupported atomic move uses durable replace fallback
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    candidate.toPath(),
                    record.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            syncDirectory(root)
        } finally {
            candidate.delete()
        }
    }

    fun read(): PersistedResourceOperation? {
        if (!record.isFile) return null
        val lines =
            try {
                record.readLines(Charsets.UTF_8)
                // instrumentation: silent — unreadable records enter malformed-record recovery
            } catch (_: Exception) {
                return malformedRecord()
            }
        return try {
            val format = lines.firstOrNull()
            require(
                (format == FORMAT && lines.size == FIELD_COUNT) ||
                    (format == LEGACY_FORMAT && lines.size == LEGACY_FIELD_COUNT),
            )
            val resourceImportUri =
                if (format == FORMAT) lines[6].takeIf { it.isNotEmpty() } else null
            val resourceImportOwnership =
                if (format == FORMAT) {
                    lines[7]
                        .takeIf { it.isNotEmpty() }
                        ?.let(ResourceImportOwnershipPhase::valueOf)
                } else {
                    null
                }
            PersistedResourceOperation(
                origin = ResourceFailureOrigin.valueOf(lines[1]),
                retry =
                    ResourceFailureRetry(
                        action = ResourceFailureAction.valueOf(lines[2]),
                        targetId = lines[3].takeIf { it.isNotEmpty() },
                        replace =
                            when (lines[4]) {
                                "true" -> true
                                "false" -> false
                                else -> error("invalid replace flag")
                            },
                    ),
                knownWordsOperation =
                    lines[5]
                        .takeIf { it.isNotEmpty() }
                        ?.let(KnownWordsFailureOperation::valueOf),
                resourceImportUri = resourceImportUri,
                resourceImportOwnership = resourceImportOwnership,
            )
            // instrumentation: silent — invalid fields enter malformed-record recovery
        } catch (_: Exception) {
            malformedRecord()
        }
    }

    fun clear() {
        candidate.delete()
        record.delete()
        if (root.isDirectory) {
            try {
                syncDirectory(root)
                // instrumentation: silent — absent record remains safe if directory sync fails
            } catch (_: Exception) {
                // The record is already absent from this process's view. A later recovery safely
                // treats any crash-surviving directory entry as interrupted work.
            }
        }
    }

    private fun malformedRecord(): PersistedResourceOperation {
        clear()
        return PersistedResourceOperation(
            origin = ResourceFailureOrigin.SETUP,
            retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
        )
    }

    private companion object {
        const val FORMAT = "resource-operation-v2"
        const val FIELD_COUNT = 8
        const val LEGACY_FORMAT = "resource-operation-v1"
        const val LEGACY_FIELD_COUNT = 6
        const val RECORD_NAME = "resource-operation-v1.pending"
        const val CANDIDATE_NAME = "resource-operation-v1.candidate"
    }
}
