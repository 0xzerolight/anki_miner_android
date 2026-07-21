package com.ankiminer.android.data.resources

import java.io.IOException

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

enum class KnownWordsSourceFormat(
    val wireValue: String,
    val fileSuffix: String,
) {
    JSON("json", ".json"),
    CSV("csv", ".csv"),
    TSV("tsv", ".tsv"),
    TEXT("txt", ".txt"),
}

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

data class ImportedPitchAccent(
    val sourceName: String,
    val sourceRevision: String,
    val sourceFormat: String,
    val entryCount: Long,
    val skippedDisplayOnly: Long,
    val skippedMalformed: Long,
    val fileSha256: String,
) : LocalResourceImportResult

data class InstalledPitchAccent(
    val sourceName: String,
    val sourceRevision: String,
    val sourceFormat: String,
    val entryCount: Long,
    val fileSizeBytes: Long,
    val schemaOk: Boolean,
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
    val pitchAccent: InstalledPitchAccent?,
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
    REFRESHING,
    CANCELLING,
}

enum class ResourceStartupReadiness {
    PENDING,
    RECOVERING,
    READY,
    FAILED,
}

data class ResourceOperationProgress(
    val operationId: String,
    val label: String,
    val phase: ResourceOperationPhase,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val fraction: Float?
        get() = if (totalBytes <= 0L) null else (completedBytes.toDouble() / totalBytes).toFloat()
}

data class ResourceFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

data class ResourceManagerState(
    val startupReadiness: ResourceStartupReadiness = ResourceStartupReadiness.PENDING,
    val catalog: ResourceCatalog? = null,
    val installedUniDic: InstalledUniDic? = null,
    val dictionaries: List<InstalledDictionary> = emptyList(),
    val frequencySources: List<InstalledFrequencySource> = emptyList(),
    val pitchAccent: InstalledPitchAccent? = null,
    val audioPacks: List<InstalledAudioPack> = emptyList(),
    val knownWords: KnownWordsInventory = KnownWordsInventory(0, 0, 0, 0, schemaOk = true),
    val wordsets: List<BundledWordset> = emptyList(),
    val lastLocalImport: LocalResourceImportResult? = null,
    val knownWordsImportPreview: KnownWordsImportPreview? = null,
    val knownWordsPage: KnownWordsPage? = null,
    val activeOperation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lastLookup: DictionaryLookup? = null,
) {
    val hasUniDic: Boolean
        get() = installedUniDic != null

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
) : IllegalStateException(message)

class ResourceDownloadException(
    val stableCode: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ResourceStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IOException("Not enough private storage for the resource operation")
