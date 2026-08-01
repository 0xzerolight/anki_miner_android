package com.ankiminer.android.localization

import com.ankiminer.android.R

/**
 * Picks the string for a byte-counted progress line so the scale suits the size being copied.
 *
 * A single mebibyte string renders "0.0 of 0.0 MiB" for anything under about 50 KiB, and that is
 * the ordinary reading source rather than an edge: subtitle tracks, plain text and many EPUBs are
 * well under a megabyte. [total] alone chooses the scale, so the unit cannot change part-way
 * through one copy.
 */
internal fun byteProgressResource(
    completed: Long,
    total: Long,
): LocalizedStringResource =
    when {
        total >= MEBIBYTE ->
            LocalizedStringResource(
                R.string.progress_mebibytes,
                listOf(completed / MEBIBYTE_F, total / MEBIBYTE_F),
            )

        total >= KIBIBYTE ->
            LocalizedStringResource(
                R.string.progress_kibibytes,
                listOf(completed / KIBIBYTE_F, total / KIBIBYTE_F),
            )

        else -> LocalizedStringResource(R.string.progress_bytes, listOf(completed, total))
    }

private const val KIBIBYTE = 1024L
private const val MEBIBYTE = 1024L * 1024L
private const val KIBIBYTE_F = 1024f
private const val MEBIBYTE_F = 1024f * 1024f
