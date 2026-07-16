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

    val recommendedDictionary: YomitanCatalogResource
        get() = resources.filterIsInstance<YomitanCatalogResource>().single()
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
    val sourceName: String,
    val sourceRevision: String,
    val format: Long,
    val entryCount: Long,
    val schemaOk: Boolean,
    val embeddedAttribution: Map<String, String>,
    val catalogResourceId: String?,
    val attribution: List<ResourceAttribution>,
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
    val activeOperation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lastLookup: DictionaryLookup? = null,
) {
    val hasUniDic: Boolean
        get() = installedUniDic != null

    val hasRecommendedDictionary: Boolean
        get() = dictionaries.any { it.catalogResourceId == catalog?.recommendedDictionary?.resourceId }
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
