package com.ankiminer.android.data.resources

import java.io.IOException
import java.util.Locale

data class ResourceAttribution(
    val name: String,
    val copyright: String,
    val license: String,
    val url: String,
)

data class ResourceArchive(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: String,
)

sealed interface CatalogResource {
    val resourceId: String
    val displayName: String
    val archive: ResourceArchive
    val attribution: List<ResourceAttribution>
}

data class UniDicInstallIdentity(
    val memberPrefix: String,
    val treeSha256: String,
    val fileCount: Long,
    val sizeBytes: Long,
    val archiveMemberLimit: Long,
)

data class UniDicCatalogResource(
    override val resourceId: String,
    override val displayName: String,
    override val archive: ResourceArchive,
    val install: UniDicInstallIdentity,
    override val attribution: List<ResourceAttribution>,
) : CatalogResource

data class YomitanDictionaryIdentity(
    val title: String,
    val revision: String,
    val format: Long,
    val memberCount: Long,
    val uncompressedBytes: Long,
    val archiveMemberLimit: Long,
    val uncompressedBytesLimit: Long,
    val fileBytesLimit: Long,
)

data class YomitanCatalogResource(
    override val resourceId: String,
    override val displayName: String,
    val slotId: String,
    override val archive: ResourceArchive,
    val dictionary: YomitanDictionaryIdentity,
    override val attribution: List<ResourceAttribution>,
) : CatalogResource

data class ResourceCatalog(
    val schemaVersion: Long,
    val resources: List<CatalogResource>,
) {
    val unidic: UniDicCatalogResource
        get() = resources.filterIsInstance<UniDicCatalogResource>().single()

    val dictionaries: List<YomitanCatalogResource>
        get() = resources.filterIsInstance<YomitanCatalogResource>()

    fun dictionary(resourceId: String): YomitanCatalogResource? =
        dictionaries.singleOrNull { it.resourceId == resourceId }
}

data class InstalledUniDic(
    val resourceId: String,
    val dicDir: String,
    val treeSha256: String,
    val fileCount: Long,
    val sizeBytes: Long,
    val alreadyInstalled: Boolean,
    val attribution: List<ResourceAttribution>,
)

data class ImportedDictionary(
    val slotId: String,
    val catalogResourceId: String?,
    val sourceName: String,
    val sourceRevision: String,
    val entryCount: Long,
    val skippedMalformed: Long,
    val mediaWarnings: List<String>,
    val archiveSha256: String,
    val attribution: List<ResourceAttribution>,
)

data class InstalledDictionary(
    val slotId: String,
    /** A private-storage entry currently owns this stable slot. */
    val occupied: Boolean,
    /** The index is readable and uses the current engine schema. */
    val valid: Boolean,
    val sourceName: String,
    val sourceRevision: String,
    val format: String,
    val entryCount: Long,
    val schemaOk: Boolean,
    val embeddedAttribution: Map<String, String>,
    val catalogResourceId: String?,
    val attribution: List<ResourceAttribution>,
) {
    val isUsable: Boolean
        get() = occupied && valid && schemaOk
}

enum class FrequencySourceFormat(
    val wireValue: String,
    val fileSuffix: String,
) {
    YOMITAN_ZIP("zip", ".zip"),
    CSV("csv", ".csv"),
    TSV("tsv", ".tsv"),
    TEXT("txt", ".txt"),
}

enum class PitchAccentSourceFormat(
    val wireValue: String,
    val fileSuffix: String,
) {
    YOMITAN_ZIP("zip", ".zip"),
    CSV("csv", ".csv"),
    TSV("tsv", ".tsv"),
}

enum class ResourceImportFileKind {
    YOMITAN_ZIP,
    JSON,
    CSV,
    TSV,
    TEXT,
}

data class RetainedResourceImport(
    val uri: String,
    val displayName: String,
    val fileKind: ResourceImportFileKind,
)

internal suspend fun detectResourceImportFileKind(
    displayName: String,
    mimeType: String?,
    readLeadingBytes: suspend () -> ByteArray,
): ResourceImportFileKind {
    val extension =
        displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (
        extension == "json" ||
            mime?.let { it in JSON_MIME_TYPES || it.endsWith("+json") } == true
    ) {
        return ResourceImportFileKind.JSON
    }
    if (
        extension == "zip" ||
            mime?.let { it in ZIP_MIME_TYPES || it.endsWith("+zip") } == true
    ) {
        return ResourceImportFileKind.YOMITAN_ZIP
    }
    when (extension) {
        "csv" -> return ResourceImportFileKind.CSV
        "tsv" -> return ResourceImportFileKind.TSV
        "txt", "text" -> return ResourceImportFileKind.TEXT
    }
    when {
        mime?.let { it in CSV_MIME_TYPES } == true -> return ResourceImportFileKind.CSV
        mime?.let { it in TSV_MIME_TYPES } == true -> return ResourceImportFileKind.TSV
        mime?.startsWith("text/") == true -> return ResourceImportFileKind.TEXT
    }
    if (extension.isEmpty() || mime == OCTET_STREAM_MIME_TYPE) {
        val leadingBytes = readLeadingBytes()
        if (
            leadingBytes.size >= ZIP_MAGIC.size &&
                ZIP_MAGIC.indices.all { leadingBytes[it] == ZIP_MAGIC[it] }
        ) {
            return ResourceImportFileKind.YOMITAN_ZIP
        }
    }
    return ResourceImportFileKind.TEXT
}

enum class KnownWordsSourceFormat(
    val wireValue: String,
    val fileSuffix: String,
) {
    JSON("json", ".json"),
    CSV("csv", ".csv"),
    TSV("tsv", ".tsv"),
    TEXT("txt", ".txt"),
}

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

// Internal rather than private so the SAF picker allowlists can be tested against them. A
// classifier that accepts a MIME type the picker does not offer is a file the user cannot select.
internal val ZIP_MIME_TYPES =
    setOf("application/zip", "application/x-zip", "application/x-zip-compressed")
internal val CSV_MIME_TYPES = setOf("text/csv", "application/csv")
internal val TSV_MIME_TYPES = setOf("text/tab-separated-values", "text/tsv")
internal val JSON_MIME_TYPES = setOf("application/json", "text/json")
private const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"

/**
 * A plain-text word list the engine reads by path at the start of every run.
 *
 * Unlike dictionaries and known words, Python never ingests these into a store of its own, so the
 * imported file is kept rather than deleted once the operation ends.
 */
enum class WordListKind(val fileName: String) {
    BLACKLIST("blacklist.txt"),
    WHITELIST("whitelist.txt"),
}

data class InstalledWordList(
    val kind: WordListKind,
    val entryCount: Int,
    val sizeBytes: Long,
)

sealed interface LocalResourceImportResult

data class ImportedFrequencySource(
    val sourceId: String,
    val sourceName: String,
    val sourceRevision: String,
    val format: String,
    val entryCount: Long,
    val skippedDisplayOnly: Long,
    val skippedMalformed: Long,
    val convertedToRanks: Boolean,
    val isCategorical: Boolean,
    val archiveSha256: String,
) : LocalResourceImportResult

data class InstalledFrequencySource(
    val sourceId: String,
    val sourceName: String,
    val format: String,
    val entryCount: Long,
    val schemaOk: Boolean,
    val schemaVersion: Long,
    val isCategorical: Boolean,
)

data class ImportedPitchSource(
    val sourceId: String,
    val sourceName: String,
    val sourceRevision: String,
    val sourceFormat: String,
    val entryCount: Long,
    val skippedDisplayOnly: Long,
    val skippedMalformed: Long,
    val archiveSha256: String,
) : LocalResourceImportResult

data class InstalledPitchSource(
    val sourceId: String,
    val sourceName: String,
    val sourceRevision: String,
    val format: String,
    val entryCount: Long,
    val schemaOk: Boolean,
    val schemaVersion: Long,
)

data class ImportedAudioPack(
    val packId: String,
    val sourceName: String,
    val format: String,
    val entryCount: Long,
    val archiveSha256: String,
) : LocalResourceImportResult

data class InstalledAudioPack(
    val packId: String,
    val sourceName: String,
    val format: String,
    val entryCount: Long,
    val contentAvailable: Boolean,
)

data class ImportedKnownWords(
    val format: String,
    val importedCount: Long,
    val newRowCount: Long,
    val totalEntries: Long,
    val isGeneric: Boolean,
) : LocalResourceImportResult

data class KnownWordsImportPreview(
    val format: String,
    val importedCount: Long,
    val totalEntries: Long,
    val isGeneric: Boolean,
    val sampleWords: List<String>,
)

data class KnownWordsPage(
    val query: String,
    val offset: Int,
    val totalCount: Long,
    val words: List<String>,
    val hasMore: Boolean,
)

enum class KnownWordsResetScope(val wireValue: String) {
    USER("user"),
    CACHE("cache"),
}

data class KnownWordsResetResult(
    val scope: KnownWordsResetScope,
    val removedCount: Long,
)

data class KnownWordsExport(
    val exportPath: String,
    val exportedCount: Long,
    val sizeBytes: Long,
)

data class KnownWordsInventory(
    val totalCount: Long,
    val userCount: Long,
    val ankiCount: Long,
    val minedCount: Long,
    val schemaOk: Boolean,
)

data class BundledWordset(
    val wordsetId: String,
    val displayName: String,
    val entryCount: Long,
)

data class LocalResourceInventory(
    val frequencies: List<InstalledFrequencySource>,
    val pitchSources: List<InstalledPitchSource>,
    val audioPacks: List<InstalledAudioPack>,
    val knownWords: KnownWordsInventory,
    val wordsets: List<BundledWordset>,
)

data class DictionaryLookup(
    val slotId: String,
    val term: String,
    val html: String,
)

enum class ResourceOperationPhase {
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    IMPORTING,

    /**
     * Commit, publish, and sidecar work after the last countable step. Reaching the final term bank
     * is not the end of an import, so the bar must stop being determinate here rather than sit full.
     */
    FINALIZING,
    REFRESHING,
    CANCELLING,
}

enum class ResourceStartupReadiness {
    PENDING,
    RECOVERING,
    READY,
    FAILED,
}

/** What [ResourceOperationProgress.completed] counts, so term banks never render as megabytes. */
enum class ResourceProgressUnit {
    BYTES,
    ITEMS,
}

/**
 * Counts are phase-local. A phase that cannot count reports `0/0` and renders as indeterminate
 * motion; carrying the previous phase's total forward is what left a full bar sitting motionless
 * over an import that had barely started.
 */
data class ResourceOperationProgress(
    val operationId: String,
    val label: String,
    val phase: ResourceOperationPhase,
    val completed: Long = 0,
    val total: Long = 0,
    val unit: ResourceProgressUnit = ResourceProgressUnit.BYTES,
) {
    val fraction: Float?
        get() = if (total <= 0L) null else (completed.toDouble() / total).toFloat()
}

/**
 * Advances an operation to [phase], keeping counts only while the phase is unchanged.
 *
 * Entering a new phase with no numbers of its own yields `0/0`, which renders as indeterminate
 * motion. Inheriting the previous phase's completed total is what produced a full, motionless bar
 * over an import that had not started.
 */
internal fun ResourceOperationProgress?.advancedTo(
    operationId: String,
    label: String,
    phase: ResourceOperationPhase,
    completed: Long? = null,
    total: Long? = null,
    unit: ResourceProgressUnit = ResourceProgressUnit.BYTES,
): ResourceOperationProgress {
    val carried = this?.takeIf { it.phase == phase && it.operationId == operationId }
    return ResourceOperationProgress(
        operationId = operationId,
        label = label,
        phase = phase,
        completed = (completed ?: carried?.completed ?: 0L).coerceAtLeast(0),
        total = (total ?: carried?.total ?: 0L).coerceAtLeast(0),
        unit = unit,
    )
}

enum class ResourceFailureOrigin {
    SETUP,
    UNIDIC,
    CATALOG_DICTIONARY,
    CUSTOM_DICTIONARY,
    PITCH,
    DICTIONARY_LOOKUP,
    AUDIO,
    FREQUENCY,
    KNOWN_WORDS,
    WORD_LIST,
}

enum class ResourceFailureAction {
    RETRY,
    CHOOSE_ANOTHER,
    RESOLVE,
}

enum class KnownWordsFailureOperation {
    IMPORT,
    PREVIEW,
    EXPORT,
}

data class ResourceFailureRetry(
    val action: ResourceFailureAction,
    val targetId: String? = null,
    val replace: Boolean = false,
)

data class ResourceFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
    /** Opaque key joining this failure to the Python traceback in the exported log. */
    val faultId: String? = null,
    val origin: ResourceFailureOrigin = ResourceFailureOrigin.SETUP,
    val retry: ResourceFailureRetry =
        ResourceFailureRetry(
            action =
                if (retryable) {
                    ResourceFailureAction.RETRY
                } else {
                    ResourceFailureAction.RESOLVE
                },
        ),
    val knownWordsOperation: KnownWordsFailureOperation? = null,
)

data class ResourceManagerState(
    val startupReadiness: ResourceStartupReadiness = ResourceStartupReadiness.PENDING,
    val catalog: ResourceCatalog? = null,
    val installedUniDic: InstalledUniDic? = null,
    val dictionaries: List<InstalledDictionary> = emptyList(),
    val frequencySources: List<InstalledFrequencySource> = emptyList(),
    val pitchSources: List<InstalledPitchSource> = emptyList(),
    val audioPacks: List<InstalledAudioPack> = emptyList(),
    val knownWords: KnownWordsInventory = KnownWordsInventory(0, 0, 0, 0, schemaOk = true),
    val wordsets: List<BundledWordset> = emptyList(),
    val wordLists: List<InstalledWordList> = emptyList(),
    val lastLocalImport: LocalResourceImportResult? = null,
    val knownWordsImportPreview: KnownWordsImportPreview? = null,
    val knownWordsPage: KnownWordsPage? = null,
    val activeOperation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lastLookup: DictionaryLookup? = null,
) {
    val hasUniDic: Boolean
        get() = installedUniDic != null

    fun wordList(kind: WordListKind): InstalledWordList? = wordLists.firstOrNull { it.kind == kind }

    val catalogDictionaries: List<CatalogDictionaryStatus>
        get() =
            catalog?.dictionaries.orEmpty().map { resource ->
                CatalogDictionaryStatus(
                    resource = resource,
                    installed =
                        dictionaries.any {
                            it.isUsable &&
                                it.slotId == resource.slotId &&
                                it.catalogResourceId == resource.resourceId
                        },
                    slotOccupied =
                        dictionaries.any { it.occupied && it.slotId == resource.slotId },
                )
            }
}

data class CatalogDictionaryStatus(
    val resource: YomitanCatalogResource,
    val installed: Boolean,
    val slotOccupied: Boolean,
) {
    val needsRepair: Boolean
        get() = slotOccupied && !installed
}

class ResourceBridgeException(
    val code: String,
    override val message: String,
    /** Opaque key joining this failure to the Python traceback in the exported log. */
    val faultId: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ResourceDownloadException(
    val stableCode: String,
    message: String,
    cause: Throwable? = null,
    val formatArguments: List<Any> = emptyList(),
    val retryAfterMillis: Long? = null,
) : IOException(message, cause)

class ResourceStorageException(
    val requiredBytes: Long?,
    val availableBytes: Long?,
    cause: Throwable? = null,
) : IOException("Not enough private storage for the resource operation", cause)
