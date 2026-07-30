package com.ankiminer.android.anki.journal

internal data class JournalRetentionBoundary(
    val finalizedAtMs: Long,
    val parentId: Long,
)

/**
 * Internal journal retention. Finalized cohorts have no replay or recovery role after explicit
 * acknowledgement/abandonment; live, RESULT_READY, unresolved, and remediated state is excluded.
 */
internal class JournalRetentionPolicy private constructor(
    val completedCohortLimit: Int,
    val resolvedRemediationLimit: Int,
    val maxAgeMillis: Long,
) {
    init {
        require(completedCohortLimit > 0)
        require(resolvedRemediationLimit > 0)
        require(maxAgeMillis > 0)
    }

    fun ageCutoff(nowEpochMillis: Long): Long {
        require(nowEpochMillis >= 0)
        return if (nowEpochMillis <= maxAgeMillis) 0 else nowEpochMillis - maxAgeMillis
    }

    fun shouldPrune(
        finalizedAtMs: Long,
        parentId: Long,
        nowEpochMillis: Long,
        countBoundary: JournalRetentionBoundary?,
    ): Boolean {
        require(finalizedAtMs >= 0 && parentId > 0)
        val tooOld = finalizedAtMs < ageCutoff(nowEpochMillis)
        val beyondCount =
            countBoundary?.let { boundary ->
                finalizedAtMs < boundary.finalizedAtMs ||
                    (finalizedAtMs == boundary.finalizedAtMs && parentId < boundary.parentId)
            } ?: false
        return tooOld || beyondCount
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

        /** Keep the newest 512 completed cohorts, but never beyond 30 days when safely prunable. */
        val PRODUCTION =
            JournalRetentionPolicy(
                completedCohortLimit = 512,
                resolvedRemediationLimit = 512,
                maxAgeMillis = 30L * DAY_MILLIS,
            )

        internal fun forTests(
            completedCohortLimit: Int,
            resolvedRemediationLimit: Int,
            maxAgeMillis: Long,
        ) = JournalRetentionPolicy(completedCohortLimit, resolvedRemediationLimit, maxAgeMillis)
    }
}
