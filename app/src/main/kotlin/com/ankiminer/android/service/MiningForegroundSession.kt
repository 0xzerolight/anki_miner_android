package com.ankiminer.android.service

import java.util.UUID

/** Identity shared by the coordinator, foreground service, and notification actions. */
data class MiningForegroundSessionIdentity(
    val runId: String,
    val generation: Long,
    val leaseId: String,
) {
    init {
        require(runId.isNotBlank() && runId.length <= MAX_RUN_ID_LENGTH) {
            "runId must contain between 1 and $MAX_RUN_ID_LENGTH characters"
        }
        require(runId.none(Char::isISOControl)) { "runId must not contain control characters" }
        require(generation > 0) { "generation must be positive" }
        require(leaseId.matches(LEASE_ID_PATTERN)) { "leaseId must be a canonical UUID" }
    }

    companion object {
        private const val MAX_RUN_ID_LENGTH = 256
        private val LEASE_ID_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        internal fun create(
            runId: String,
            generation: Long,
        ): MiningForegroundSessionIdentity =
            MiningForegroundSessionIdentity(
                runId = runId,
                generation = generation,
                leaseId = UUID.randomUUID().toString(),
            )
    }
}

enum class MiningForegroundCancellationReason {
    USER_REQUESTED,
    SYSTEM_TIMEOUT,
    SERVICE_LOST,
    PROTOCOL_ERROR,
}

fun interface MiningForegroundSessionListener {
    /** Called away from the Android main thread and at most once for a lease. */
    fun onCancellationRequested(
        identity: MiningForegroundSessionIdentity,
        reason: MiningForegroundCancellationReason,
    )
}

data class MiningForegroundProgress(
    val completed: Int? = null,
    val total: Int? = null,
    val message: String? = null,
) {
    init {
        require((completed == null) == (total == null)) {
            "completed and total must both be set or both be absent"
        }
        if (completed != null && total != null) {
            require(total > 0) { "total must be positive" }
            require(completed in 0..total) { "completed must be between zero and total" }
        }
    }
}

interface MiningForegroundLease : AutoCloseable {
    val identity: MiningForegroundSessionIdentity

    /** Returns false once this lease is no longer the live foreground session. */
    fun updateProgress(progress: MiningForegroundProgress): Boolean

    /** Marks this as an expected shutdown before asking Android to stop the service. */
    override fun close()
}

internal fun sanitizeNotificationProgressMessage(message: String): String {
    val sanitized = StringBuilder(minOf(message.length, MAX_NOTIFICATION_MESSAGE_LENGTH))
    var offset = 0
    while (offset < message.length && sanitized.length < MAX_NOTIFICATION_MESSAGE_LENGTH) {
        val codePoint = message.codePointAt(offset)
        val replacement =
            when (Character.getType(codePoint)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> ' '.code

                else -> codePoint
            }
        val replacementWidth = Character.charCount(replacement)
        if (sanitized.length + replacementWidth > MAX_NOTIFICATION_MESSAGE_LENGTH) break
        sanitized.appendCodePoint(replacement)
        offset += Character.charCount(codePoint)
    }
    return sanitized.toString().trim()
}

private const val MAX_NOTIFICATION_MESSAGE_LENGTH = 512
