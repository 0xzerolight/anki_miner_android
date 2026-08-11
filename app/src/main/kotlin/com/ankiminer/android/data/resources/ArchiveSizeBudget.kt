package com.ankiminer.android.data.resources

import com.ankiminer.android.R
import com.ankiminer.android.localization.ByteSizeArgument
import com.ankiminer.android.localization.StringResourceArgument
import com.ankiminer.android.localization.formatByteSize
import java.io.File
import java.util.Locale

/** Absolute ceiling for a local audio-pack archive, independent of free space. */
internal const val AUDIO_ARCHIVE_CEILING_BYTES = 16L * 1024 * 1024 * 1024

/** Private-storage headroom kept free by every staged import. */
internal const val ARCHIVE_BUDGET_RESERVE_BYTES = 32L * 1024 * 1024

/**
 * Largest audio-pack archive this device can import right now.
 *
 * An import holds the staged ZIP and its extracted tree at the same time, and
 * local audio packs are stored (not compressed) media, so the extracted tree is
 * about as large as the archive. Everything after extraction is a rename, so
 * twice the archive size plus the reserve is the peak.
 *
 * @throws ResourceStorageException when free space is already at or under the
 * reserve. There is no budget to state then, and clamping to a positive one only
 * produces a size limit of a few bytes that reads as an absurd cap on the file
 * the user picked instead of as the out-of-space condition it is.
 */
internal fun audioArchiveBudget(usableBytes: Long): Long {
    val budget = (usableBytes - ARCHIVE_BUDGET_RESERVE_BYTES) / 2
    if (budget < 1L) throw ResourceStorageException(ARCHIVE_BUDGET_RESERVE_BYTES, usableBytes)
    return budget.coerceAtMost(AUDIO_ARCHIVE_CEILING_BYTES)
}

/**
 * Free space for a staging root that the stager has not created yet.
 *
 * [File.usableSpace] answers 0 for a path that does not exist, which would make
 * every import look impossible before the first one runs.
 */
internal fun usableSpaceForStaging(root: File): Long {
    var directory: File? = root
    while (directory != null && !directory.exists()) {
        directory = directory.parentFile
    }
    return directory?.usableSpace ?: 0L
}

/**
 * Rejection for an archive that cannot fit, carrying both numbers the user needs.
 *
 * [actualBytes] is what was measured or read so far; [limitBytes] is the budget
 * that was applied.
 */
internal fun archiveTooLarge(
    sourceLabel: String,
    actualBytes: Long,
    limitBytes: Long,
): ResourceDownloadException =
    ResourceDownloadException(
        "resource_archive_too_large",
        "The selected $sourceLabel is ${formatArchiveBytes(actualBytes)}; " +
            "the limit here is ${formatArchiveBytes(limitBytes)}",
        formatArguments =
            listOf(
                localizedSourceLabel(sourceLabel),
                ByteSizeArgument(actualBytes),
                ByteSizeArgument(limitBytes),
            ),
    )

private fun localizedSourceLabel(sourceLabel: String): Any =
    when (sourceLabel) {
        "audio-pack archive", "audio-pack ZIP" ->
            StringResourceArgument(R.string.b3_settings_category_audio)
        "dictionary archive" ->
            StringResourceArgument(R.string.wizard_dictionary_title)
        "frequency source" ->
            StringResourceArgument(R.string.b3_settings_category_frequency)
        "pitch-accent source" ->
            StringResourceArgument(R.string.pitch_import_title)
        "known-word file" ->
            StringResourceArgument(R.string.known_words_import_title)
        "word-list file" ->
            StringResourceArgument(R.string.word_lists_title)
        else -> sourceLabel
    }

/** Byte count for a user-facing message, in the units people read sizes in. */
internal fun formatArchiveBytes(bytes: Long): String = formatByteSize(bytes, Locale.ROOT)
