package com.ankiminer.android.data.settings

/**
 * The engine values that take effect when an Android setting is left unset.
 *
 * [AppSettings] models most processing fields as nullable, and
 * [EngineSettingsSnapshotMapper] omits every null from the snapshot, so the engine falls back to
 * the `AnkiMinerConfig` dataclass default. Those defaults were previously invisible: the settings
 * UI rendered an empty field, and the resolved values were re-typed as literals at each
 * `NullableToggle` / `NullableChoice` call site and once more in `MiningModels.kt`.
 *
 * This object is the one place Kotlin states them. Every constant here mirrors a field of
 * `anki_miner/config/config.py`; `tools/engine-sync/tests/test_engine_defaults_mirror.py` fails CI
 * when the two drift apart, because a stale mirror would replace an honest blank field with a
 * confidently wrong number.
 *
 * The Android-side name is paired with its wire key in the mirror test — rename either side and the
 * test tells you which.
 */
internal object EngineDefaults {
    // anki_deck_name / anki_tags
    const val DECK_NAME: String = "Anki Miner"
    const val TAGS: String = "auto-mined"

    // Media capture.
    const val AUDIO_PADDING_SECONDS: Double = 0.3
    const val SCREENSHOT_OFFSET_SECONDS: Double = 1.0
    const val SUBTITLE_OFFSET_SECONDS: Double = 0.0
    const val AUDIO_BITRATE_KBPS: Int = 192
    val AUDIO_FORMAT: AudioFormat = AudioFormat.MP3

    // Animated screenshots.
    const val ANIMATED_SCREENSHOTS_ENABLED: Boolean = false
    const val ANIMATED_SCREENSHOT_MATCH_AUDIO: Boolean = false
    const val ANIMATED_SCREENSHOT_DURATION_SECONDS: Double = 2.0
    const val ANIMATED_SCREENSHOT_QUALITY: Int = 30

    // Subtitle text handling.
    const val SUBTITLE_REGEX_FILTER: String = ""
    const val SUBTITLE_REGEX_REPLACEMENT: String = ""
    const val USE_SUBTITLE_REGEX_FILTER: Boolean = false

    // Candidate filtering.
    const val USE_KNOWN_WORDS_DATABASE: Boolean = false
    const val USE_BLACKLIST: Boolean = false
    const val USE_WHITELIST: Boolean = false
    const val EXCLUDE_HIRAGANA_ONLY: Boolean = false
    const val EXCLUDE_KATAKANA_ONLY: Boolean = false
    const val BOLD_TARGET_IN_SENTENCE: Boolean = false

    /**
     * The engine deduplicates by default. Android stores `false` instead — see
     * [AppSettings.deduplicateSentences] — but this constant states the engine's own value, which
     * is what an unset field would inherit.
     */
    const val DEDUPLICATE_SENTENCES: Boolean = true
    const val USE_I_PLUS_ONE_FILTER: Boolean = false
    const val USE_SENTENCE_LENGTH_FILTER: Boolean = false

    /** Both caps use zero for "no limit". */
    const val MAX_SENTENCE_DURATION_SECONDS: Double = 0.0
    const val MAX_SENTENCE_CHARACTERS: Int = 0
    const val MAX_FREQUENCY_RANK: Int = 0

    /** One occurrence, i.e. no minimum. */
    const val READING_MINIMUM_OCCURRENCE: Int = 1
    const val MAX_PARALLEL_WORKERS: Int = 6

    val PITCH_CATEGORY_FORMAT: PitchCategoryFormat = PitchCategoryFormat.JAPANESE
}
