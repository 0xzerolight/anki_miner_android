package com.ankiminer.android.data.resources

/**
 * One device's inventory after a Japanese user has also set up Hebrew: every family holds slots
 * of both languages. The Japanese slots carry no stamp, as every slot installed before languages
 * existed does.
 */
internal object LanguageInventoryFixtures {
    val japaneseOnly: ResourceManagerState =
        ResourceManagerState(
            startupReadiness = ResourceStartupReadiness.READY,
            dictionaries = listOf(dictionary("jitendex"), dictionary("jmdict")),
            frequencySources = listOf(frequency("jpdb")),
            pitchSources = listOf(pitch("kanjium")),
            audioPacks = listOf(audioPack("jpod")),
        )

    val mixed: ResourceManagerState =
        japaneseOnly.copy(
            dictionaries =
                (japaneseOnly.dictionaries + dictionary("wty-he-en", language = "he"))
                    .sortedBy { it.slotId },
            frequencySources =
                (japaneseOnly.frequencySources + frequency("opensubtitles-he", language = "he"))
                    .sortedBy { it.sourceId },
            pitchSources = japaneseOnly.pitchSources + pitch("he-stress", language = "he"),
            audioPacks =
                (japaneseOnly.audioPacks + audioPack("forvo-he", language = "he"))
                    .sortedBy { it.packId },
        )

    val japaneseIds = setOf("jitendex", "jmdict", "jpdb", "kanjium", "jpod")

    val hebrewIds = setOf("wty-he-en", "opensubtitles-he", "he-stress", "forvo-he")

    fun dictionary(
        id: String,
        language: String = JAPANESE,
    ): InstalledDictionary =
        InstalledDictionary(
            slotId = id,
            occupied = true,
            valid = true,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
            rebuildSourcePath = null,
            language = language,
        )

    fun frequency(
        id: String,
        language: String = JAPANESE,
    ): InstalledFrequencySource =
        InstalledFrequencySource(
            sourceId = id,
            sourceName = id,
            format = "csv",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 1,
            isCategorical = false,
            rebuildSourcePath = null,
            language = language,
        )

    fun pitch(
        id: String,
        language: String = JAPANESE,
    ): InstalledPitchSource =
        InstalledPitchSource(
            sourceId = id,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 1,
            rebuildSourcePath = null,
            language = language,
        )

    fun audioPack(
        id: String,
        language: String = JAPANESE,
    ): InstalledAudioPack =
        InstalledAudioPack(
            packId = id,
            sourceName = id,
            format = "jpod_legacy",
            entryCount = 1,
            contentAvailable = true,
            language = language,
        )
}
