package com.ankiminer.android.ui.reading

import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

enum class ReadingSourceKindUi {
    TEXT,
    EPUB,
    SUBTITLE,
    MOKURO,
    MOKURO_ARCHIVE,
}

enum class ReadingDocumentSelectionError {
    SOURCE_ACCESS,
    SOURCE_TYPE,
    ARCHIVE_ACCESS,
    ARCHIVE_TYPE,
    ARCHIVE_NAME,
}

enum class ReadingMiningCommandError {
    START,
    CURATION,
    CANCEL,
    RESET,
}

data class ReadingDocumentSlotState(
    val document: SafDocument? = null,
    val isResolving: Boolean = false,
    val error: ReadingDocumentSelectionError? = null,
)

data class ReadingCurationCandidateUiState(
    val candidate: CurationCandidate,
    val selected: Boolean,
    val sentenceId: String,
)

data class ReadingCurationUiState(
    val runId: String,
    val requestId: String,
    val candidates: List<ReadingCurationCandidateUiState>,
    val page: CurationPage? = null,
) {
    val selectedCount: Int
        get() = candidates.count { it.selected }

    val isFinalPage: Boolean
        get() = page?.let { it.pageIndex == it.pageCount - 1 } ?: true
}

data class ReadingMiningUiState(
    val source: ReadingDocumentSlotState = ReadingDocumentSlotState(),
    val archive: ReadingDocumentSlotState = ReadingDocumentSlotState(),
    val sourceKind: ReadingSourceKindUi? = null,
    val subtitleSeriesName: String = "",
    val runState: MiningRunState = MiningRunState.Idle,
    val curation: ReadingCurationUiState? = null,
    val startPending: Boolean = false,
    val curationPending: Boolean = false,
    val cancelPending: Boolean = false,
    val resetPending: Boolean = false,
    val commandError: ReadingMiningCommandError? = null,
    val runtimeConflict: RuntimeWorkConflict? = null,
) {
    val acceptsArchive: Boolean
        get() = sourceKind == ReadingSourceKindUi.MOKURO

    val archiveNamesMatch: Boolean
        get() {
            val sourceName = source.document?.displayName ?: return false
            val archiveName = archive.document?.displayName ?: return false
            return readingArchiveMatches(sourceName, archiveName)
        }

    val canStart: Boolean
        get() =
            runState == MiningRunState.Idle &&
                source.document != null &&
                sourceKind != null &&
                !source.isResolving &&
                !archive.isResolving &&
                (!acceptsArchive || archive.document == null || archiveNamesMatch) &&
                !startPending &&
                !resetPending &&
                runtimeConflict == null
}

internal fun readingSourceKind(displayName: String): ReadingSourceKindUi? =
    when (readingExtension(displayName)) {
        "txt" -> ReadingSourceKindUi.TEXT
        "epub" -> ReadingSourceKindUi.EPUB
        "ass", "srt", "ssa", "vtt" -> ReadingSourceKindUi.SUBTITLE
        "mokuro" -> ReadingSourceKindUi.MOKURO
        // A lone cbz/zip is a self-contained Mokuro volume; its .mokuro sidecar
        // is extracted from the archive during staging, so no archive slot.
        "cbz", "zip" -> ReadingSourceKindUi.MOKURO_ARCHIVE
        else -> null
    }

internal fun isReadingArchive(displayName: String): Boolean =
    readingExtension(displayName) in setOf("cbz", "zip")

internal fun readingArchiveMatches(
    sidecarDisplayName: String,
    archiveDisplayName: String,
): Boolean =
    readingSourceKind(sidecarDisplayName) == ReadingSourceKindUi.MOKURO &&
        isReadingArchive(archiveDisplayName) &&
        canonicalReadingStem(sidecarDisplayName) == canonicalReadingStem(archiveDisplayName)

private fun readingExtension(displayName: String): String =
    safeReadingDisplayName(displayName)
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()

private fun canonicalReadingStem(displayName: String): String =
    requireNotNull(safeReadingDisplayName(displayName))
        .substringBeforeLast('.', missingDelimiterValue = displayName)
        .lowercase(Locale.ROOT)

private fun safeReadingDisplayName(displayName: String): String? {
    val normalized = Normalizer.normalize(displayName, Normalizer.Form.NFC)
    val separator = normalized.lastIndexOf('.')
    val stem = normalized.takeIf { separator > 0 }?.substring(0, separator)
    return normalized.takeIf {
        it.isNotBlank() &&
            it != "." &&
            it != ".." &&
            !it.contains('/') &&
            !it.contains('\\') &&
            it.none { character -> Character.isISOControl(character) } &&
            it.toByteArray(StandardCharsets.UTF_8).size <= MAX_DISPLAY_NAME_UTF8_BYTES &&
            separator > 0 &&
            !stem.isNullOrBlank() &&
            separator < it.lastIndex
    }
}

private const val MAX_DISPLAY_NAME_UTF8_BYTES = 255
