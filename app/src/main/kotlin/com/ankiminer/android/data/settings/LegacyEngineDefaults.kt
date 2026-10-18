package com.ankiminer.android.data.settings

/**
 * Desktop v3.8.0 defaults kept for upgraders (desktop `keep_legacy_feature_switches`).
 *
 * The re-pin to desktop `6ca809fe` changed these engine defaults, and desktop gives the new ones to
 * fresh installs only: its config file holds every value, so an existing install keeps what it had.
 * Android stores an unset processing setting as null, which follows the engine default, so a store
 * or backup from before the re-pin would move to the new defaults without anyone choosing it. The
 * schema 4 store migration and the import of an older backup write these values where the setting
 * is unset instead. Everything here is keyed by preference name.
 */
internal object LegacyEngineDefaults {
    const val AUDIO_BITRATE_KBPS: Int = 192
    const val BOLD_TARGET_IN_SENTENCE: Boolean = false
    const val STRICT_CARD_ORDER: Boolean = false

    /** Desktop `_LEGACY_OFF_FIELDS`: on for a fresh install, off for anyone who had the app before. */
    const val FEATURE_SWITCH: Boolean = false
    const val USE_SUBTITLE_REGEX_FILTER: Boolean = false

    const val USE_SUBTITLE_REGEX_FILTER_KEY = "use_subtitle_regex_filter"
    private const val SUBTITLE_REGEX_FILTER_KEY = "subtitle_regex_filter"

    val FEATURE_SWITCHES: Set<String> =
        setOf("pick_best_sentence", "skip_opening_ending", "normalize_audio_loudness", "crop_manga_to_text")

    /**
     * The [GLOBAL] keys an older backup can lack because its app never exported them, so a missing
     * one means unset rather than "keep the current value": the four switches, and strict card
     * order, which no export before v2.0.0 carries. Every backup-writing release (v0.5.0 on) exports
     * the other flipped keys.
     */
    val UNSET_WHEN_ABSENT: Set<String> = FEATURE_SWITCHES + "strict_card_order"

    /** The global settings an upgrader keeps, each with the value it keeps. */
    val GLOBAL: Map<String, Any> =
        mapOf(
            "audio_bitrate_kbps" to AUDIO_BITRATE_KBPS,
            "bold_target" to BOLD_TARGET_IN_SENTENCE,
            "strict_card_order" to STRICT_CARD_ORDER,
        ) + FEATURE_SWITCHES.associateWith { FEATURE_SWITCH }

    /**
     * The languages whose subtitle cleanup default was empty before the re-pin and is now desktop's
     * built-in pattern. Every other profile already shipped a pattern of its own.
     */
    private val CLEANUP_LANGUAGES: Set<String> = setOf("ja", "zh", "yue")

    /**
     * Whether [language]'s subtitle cleanup has to be switched off to keep filtering nothing, given
     * which of its two settings [isUnset] reports unset: an unset pattern now resolves to the
     * built-in one, and an unset toggle now resolves to on.
     *
     * The fix is always the toggle, set to [USE_SUBTITLE_REGEX_FILTER]. An empty pattern would read
     * the same to the engine, but the settings draft turns a blank pattern back into null on its
     * next save, which would bring the built-in cleanup back.
     */
    fun pinsCleanupOff(
        language: String,
        isUnset: (String) -> Boolean,
    ): Boolean =
        language in CLEANUP_LANGUAGES &&
            (isUnset(USE_SUBTITLE_REGEX_FILTER_KEY) || isUnset(SUBTITLE_REGEX_FILTER_KEY))
}
