package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.LanguageExtraCardField
import com.ankiminer.android.engine.LanguageProfileInfo

/**
 * What the active language's scoped settings inherit while they are unset, and which Anki field
 * keys it maps — the settings screen's placeholders and field-map rows.
 *
 * Japanese keeps [EngineDefaults], the values a Japanese snapshot resolves to and what the screen
 * showed before mining languages existed. Any other language reads its profile's `scoped_defaults`,
 * which is what the bridge resolves an omitted key to for that language (he filters bracketed
 * captions by default and does not fold kana; Japanese does neither and does).
 */
internal data class LanguageDefaults(
    val code: String,
    val useSubtitleRegexFilter: Boolean,
    val subtitleRegexFilter: String,
    val subtitleRegexReplacement: String,
    val useBlacklist: Boolean,
    val useWhitelist: Boolean,
    val excludeHiraganaOnly: Boolean,
    val excludeKatakanaOnly: Boolean,
    val knownWordsMatchKanaVariants: Boolean,
    val minFrequencyRank: Int,
    val maxFrequencyRank: Int,
    val frequencyKeepUnranked: Boolean,
    /** [AnkiFieldKeys.ALL] in its UI order, then the language's own card fields. */
    val fieldKeys: List<String>,
    /** The language's own card fields; each placeholder is an untranslated field-name suggestion. */
    val extraCardFields: List<LanguageExtraCardField>,
) {
    companion object {
        val JAPANESE =
            LanguageDefaults(
                code = LanguageScope.JAPANESE,
                useSubtitleRegexFilter = EngineDefaults.USE_SUBTITLE_REGEX_FILTER,
                subtitleRegexFilter = EngineDefaults.SUBTITLE_REGEX_FILTER,
                subtitleRegexReplacement = EngineDefaults.SUBTITLE_REGEX_REPLACEMENT,
                useBlacklist = EngineDefaults.USE_BLACKLIST,
                useWhitelist = EngineDefaults.USE_WHITELIST,
                excludeHiraganaOnly = EngineDefaults.EXCLUDE_HIRAGANA_ONLY,
                excludeKatakanaOnly = EngineDefaults.EXCLUDE_KATAKANA_ONLY,
                knownWordsMatchKanaVariants = EngineDefaults.KNOWN_WORDS_MATCH_KANA_VARIANTS,
                minFrequencyRank = EngineDefaults.MIN_FREQUENCY_RANK,
                maxFrequencyRank = EngineDefaults.MAX_FREQUENCY_RANK,
                frequencyKeepUnranked = EngineDefaults.FREQUENCY_KEEP_UNRANKED,
                fieldKeys = AnkiFieldKeys.ALL,
                extraCardFields = emptyList(),
            )

        /**
         * The defaults for [language], or null while a non-Japanese language's profile has not
         * loaded — a guess from Japanese would show values that language never uses.
         */
        fun forLanguage(
            language: String,
            profiles: List<LanguageProfileInfo>,
        ): LanguageDefaults? =
            if (language == LanguageScope.JAPANESE) {
                JAPANESE
            } else {
                profiles.firstOrNull { it.code == language }?.let(::from)
            }

        /**
         * What a language-scoped settings row shows while its setting is unset: [defaults] once the
         * active language's profile has loaded, else [JAPANESE]. Until then the settings screen
         * treats the active language as Japanese, and passes `ja` as the mining language too.
         */
        fun orJapanese(defaults: LanguageDefaults?): LanguageDefaults = defaults ?: JAPANESE

        fun from(profile: LanguageProfileInfo): LanguageDefaults {
            val defaults = profile.scopedDefaults
            val profileFieldKeys =
                (defaults["anki_fields"] as? BridgeJsonValue.ObjectValue)?.values?.keys.orEmpty()
            return LanguageDefaults(
                code = profile.code,
                useSubtitleRegexFilter = defaults.flag("use_subtitle_regex_filter"),
                subtitleRegexFilter = defaults.text("subtitle_regex_filter"),
                subtitleRegexReplacement = defaults.text("subtitle_regex_replacement"),
                useBlacklist = defaults.flag("use_blacklist"),
                useWhitelist = defaults.flag("use_whitelist"),
                excludeHiraganaOnly = defaults.flag("exclude_hiragana_only_words"),
                excludeKatakanaOnly = defaults.flag("exclude_katakana_only_words"),
                knownWordsMatchKanaVariants = defaults.flag("known_words_match_kana_variants"),
                minFrequencyRank = defaults.rank("min_frequency_rank"),
                maxFrequencyRank = defaults.rank("max_frequency_rank"),
                frequencyKeepUnranked = defaults.flag("frequency_keep_unranked"),
                fieldKeys = AnkiFieldKeys.ALL + profileFieldKeys.filterNot(AnkiFieldKeys.ALL::contains),
                extraCardFields = profile.extraCardFields,
            )
        }

        private fun Map<String, BridgeJsonValue>.flag(key: String): Boolean =
            (get(key) as? BridgeJsonValue.Bool)?.value ?: false

        private fun Map<String, BridgeJsonValue>.text(key: String): String =
            (get(key) as? BridgeJsonValue.Text)?.value.orEmpty()

        private fun Map<String, BridgeJsonValue>.rank(key: String): Int =
            (get(key) as? BridgeJsonValue.Integer)?.value?.toInt() ?: 0
    }
}
