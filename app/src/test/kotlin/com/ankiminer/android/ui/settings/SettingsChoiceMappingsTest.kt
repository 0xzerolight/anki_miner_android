package com.ankiminer.android.ui.settings

import androidx.compose.ui.state.ToggleableState
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.LanguageDefaults
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.vm.SettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The desktop v3.8.0 combos and their mapping onto the fields Android already stores. */
class SettingsChoiceMappingsTest {
    private fun draft(settings: AppSettings = AppSettings()) = SettingsDraft.from(settings, ResourceManagerState())

    // Script Type (desktop _SCRIPT_TYPE_VALUES).

    @Test
    fun `each script type is one pair of the kana booleans`() {
        assertEquals(ScriptType.KEEP, ScriptType.of(hiragana = false, katakana = false))
        assertEquals(ScriptType.HIRAGANA, ScriptType.of(hiragana = true, katakana = false))
        assertEquals(ScriptType.KATAKANA, ScriptType.of(hiragana = false, katakana = true))
        assertEquals(ScriptType.ALL_KANA, ScriptType.of(hiragana = true, katakana = true))
        ScriptType.entries.forEach { type ->
            assertEquals(type, ScriptType.of(type.excludeHiragana, type.excludeKatakana))
        }
    }

    @Test
    fun `an unset pair shows the language default and a pick writes both booleans`() {
        val inherited = LanguageDefaults.JAPANESE.copy(excludeHiraganaOnly = true)
        val unset = draft()

        assertEquals(ScriptType.HIRAGANA, unset.scriptType(inherited))
        val picked = unset.withScriptType(ScriptType.ALL_KANA)
        assertEquals(true, picked.hiragana)
        assertEquals(true, picked.katakana)
        assertEquals(ScriptType.ALL_KANA, picked.scriptType(inherited))
    }

    // Korean script filters (the ko profile's filter_options in languages/ko/script.py).

    @Test
    fun `the Korean script filters write the kana flags the ko profile binds them to`() {
        assertEquals(R.string.settings_exclude_hangul_only, HangulFilter.HANGUL_ONLY.label)
        assertEquals(R.string.settings_exclude_hanja, HangulFilter.HANJA_CONTAINING.label)
        val korean = draft(AppSettings(language = "ko"))

        fun engineFlags(filter: HangulFilter): List<BridgeJsonValue?> {
            val saved = filter.write(korean, true).toSettings(AppSettings())
            val engine = EngineSettingsSnapshotMapper.map(saved, emptyList()).settings
            return listOf(engine["exclude_hiragana_only_words"], engine["exclude_katakana_only_words"])
        }
        // hangul_only -> exclude_hiragana_only_words; hanja_containing -> exclude_katakana_only_words.
        assertEquals(listOf(BridgeJsonValue.Bool(true), null), engineFlags(HangulFilter.HANGUL_ONLY))
        assertEquals(listOf(null, BridgeJsonValue.Bool(true)), engineFlags(HangulFilter.HANJA_CONTAINING))

        // Each row shows the flag it writes, and that flag's language default while unset.
        val stored = draft(AppSettings(language = "ko", excludeHiraganaOnly = true, excludeKatakanaOnly = false))
        assertEquals(true, HangulFilter.HANGUL_ONLY.value(stored))
        assertEquals(false, HangulFilter.HANJA_CONTAINING.value(stored))
        val inherited = LanguageDefaults.JAPANESE.copy(excludeHiraganaOnly = true, excludeKatakanaOnly = false)
        assertTrue(HangulFilter.HANGUL_ONLY.default(inherited))
        assertFalse(HangulFilter.HANJA_CONTAINING.default(inherited))
    }

    // Sentence Rule (desktop _SENTENCE_RULE_VALUES and set_sentence_rule).

    @Test
    fun `each sentence rule is one pair of dedup and i+1`() {
        assertEquals(false to false, SentenceRule.ALL.let { it.deduplicate to it.iPlusOne })
        assertEquals(true to false, SentenceRule.ONE_PER_SENTENCE.let { it.deduplicate to it.iPlusOne })
        assertEquals(false to true, SentenceRule.I_PLUS_ONE.let { it.deduplicate to it.iPlusOne })
        assertEquals(SentenceRule.ALL, SentenceRule.of(deduplicate = false, iPlusOne = false))
        assertEquals(SentenceRule.ONE_PER_SENTENCE, SentenceRule.of(deduplicate = true, iPlusOne = false))
        assertEquals(SentenceRule.I_PLUS_ONE, SentenceRule.of(deduplicate = false, iPlusOne = true))
    }

    @Test
    fun `a stored dedup and i+1 pair shows i+1 and is not rewritten`() {
        // i+1 already overrides dedup in the engine, so (T, T) mines the same as (F, T).
        val both = draft(AppSettings(deduplicateSentences = true, useIPlusOneFilter = true))

        assertEquals(SentenceRule.I_PLUS_ONE, SentenceRule.of(deduplicate = true, iPlusOne = true))
        assertEquals(SentenceRule.I_PLUS_ONE, both.sentenceRule)
        // Picking the rule already shown is no edit: the old pair stays, nothing migrates.
        assertEquals(both, both.withSentenceRule(SentenceRule.I_PLUS_ONE))
        val moved = both.withSentenceRule(SentenceRule.ONE_PER_SENTENCE)
        assertEquals(true, moved.deduplicate)
        assertEquals(false, moved.iPlusOne)
    }

    @Test
    fun `an unset sentence rule reads the engine defaults`() {
        val expected =
            SentenceRule.of(EngineDefaults.DEDUPLICATE_SENTENCES, EngineDefaults.USE_I_PLUS_ONE_FILTER)

        assertEquals(expected, draft().sentenceRule)
    }

    // Animated Size (desktop ANIMATED_SIZE_PRESETS).

    @Test
    fun `the size presets are desktop's triples`() {
        assertEquals(
            listOf(Triple(12, 480, 30), Triple(20, 720, 30), Triple(24, 1080, 50)),
            AnimatedSizePreset.entries.map { Triple(it.fps, it.height, it.quality) },
        )
        // Desktop: "balanced" == the defaults from before the presets.
        assertEquals(
            Triple(
                EngineDefaults.ANIMATED_SCREENSHOT_FPS,
                EngineDefaults.ANIMATED_SCREENSHOT_HEIGHT,
                EngineDefaults.ANIMATED_SCREENSHOT_QUALITY,
            ),
            AnimatedSizePreset.BALANCED.let { Triple(it.fps, it.height, it.quality) },
        )
    }

    @Test
    fun `absent frame rate and height read as Balanced`() {
        // An old backup carries no animated fps or height at all.
        val old = draft(AppSettings(animatedScreenshotFps = null, animatedScreenshotHeight = null))

        assertEquals(AnimatedSize.Preset(AnimatedSizePreset.BALANCED), old.animatedSize)
        assertEquals(
            AnimatedSize.Preset(AnimatedSizePreset.BALANCED),
            animatedSizeOf(fps = null, height = null, quality = null),
        )
    }

    @Test
    fun `a triple matching no preset is custom`() {
        val custom = draft(AppSettings(animatedScreenshotFps = 15, animatedScreenshotQuality = 30))

        assertEquals(AnimatedSize.Custom(15, 720, 30), custom.animatedSize)
        // Quality alone moves a preset off its triple too.
        assertEquals(
            AnimatedSize.Custom(24, 1080, 30),
            animatedSizeOf(fps = 24, height = 1080, quality = 30),
        )
        assertEquals(AnimatedSize.Preset(AnimatedSizePreset.HIGH), animatedSizeOf(24, 1080, 50))
    }

    @Test
    fun `picking a size writes all three and Balanced saves as inherited`() {
        val high = draft().withAnimatedSize(AnimatedSizePreset.HIGH)

        assertEquals(24, high.animatedScreenshotFps)
        assertEquals(1080, high.animatedScreenshotHeight)
        assertEquals("50", high.animatedScreenshotQuality)
        val saved = high.withAnimatedSize(AnimatedSizePreset.BALANCED).toSettings(AppSettings())
        assertNull(saved.animatedScreenshotFps)
        assertNull(saved.animatedScreenshotHeight)
        assertNull(saved.animatedScreenshotQuality)
    }

    // Clip length (desktop _ClipLengthSpinBox).

    @Test
    fun `the lowest clip length is the sentence audio and stepping up returns the stored length`() {
        val stepper = ClipLengthStepper()
        val atMinimum = ClipLength(sameAsAudio = false, seconds = 0.5)

        val matched = stepper.step(atMinimum, up = false)
        assertEquals(ClipLength(sameAsAudio = true, seconds = 0.5), matched)
        assertEquals(ClipLength(sameAsAudio = false, seconds = 0.5), stepper.step(matched, up = true))
        // Nothing lies below it.
        assertEquals(matched, ClipLengthStepper().step(matched, up = false))
    }

    @Test
    fun `stepping down to the sentence audio keeps the length the user had set`() {
        // Desktop Edge1: the values passed on the way down are not remembered.
        val stepper = ClipLengthStepper()
        var current = ClipLength(sameAsAudio = false, seconds = 2.0)
        val seen = mutableListOf<Double>()
        repeat(4) {
            current = stepper.step(current, up = false)
            seen += current.seconds
        }

        assertEquals(listOf(1.5, 1.0, 0.5, 2.0), seen)
        assertTrue(current.sameAsAudio)
        assertEquals(ClipLength(sameAsAudio = false, seconds = 2.0), stepper.step(current, up = true))
    }

    @Test
    fun `a length set elsewhere is the one a later step returns to`() {
        val stepper = ClipLengthStepper()
        stepper.step(ClipLength(sameAsAudio = false, seconds = 2.0), up = false)

        // The stored length changed without this stepper (settings loaded, a backup imported).
        val loaded = ClipLength(sameAsAudio = false, seconds = 0.5)
        val matched = stepper.step(loaded, up = false)
        assertEquals(ClipLength(sameAsAudio = true, seconds = 0.5), matched)
    }

    @Test
    fun `clip length steps by half a second within the engine's range`() {
        val stepper = ClipLengthStepper()

        assertEquals(2.5, stepper.step(ClipLength(false, 2.0), up = true).seconds, 0.0)
        assertEquals(10.0, ClipLengthStepper().step(ClipLength(false, 10.0), up = true).seconds, 0.0)
        assertEquals(0.5, ClipLengthStepper().step(ClipLength(false, 0.7), up = false).seconds, 0.0)
        assertEquals(2.8, ClipLengthStepper().step(ClipLength(false, 2.3), up = true).seconds, 0.0)
    }

    @Test
    fun `the draft round-trips through the clip length`() {
        val matched = draft(AppSettings(animatedScreenshotMatchAudio = true, animatedScreenshotDurationSeconds = 3.0))

        assertEquals(ClipLength(sameAsAudio = true, seconds = 3.0), matched.clipLength)
        val stepped = matched.withClipLength(ClipLength(sameAsAudio = false, seconds = 3.5))
        assertFalse(stepped.animatedScreenshotMatchAudio)
        assertEquals(3.5, stepped.toSettings(AppSettings()).animatedScreenshotDurationSeconds!!, 0.0)
        // Unparseable text reads as the engine default rather than failing the screen.
        assertEquals(
            EngineDefaults.ANIMATED_SCREENSHOT_DURATION_SECONDS,
            draft().copy(animatedScreenshotDuration = "").clipLength.seconds,
            0.0,
        )
    }

    // Name wordsets (desktop names_checkbox, D15 item 2).

    @Test
    fun `the names box is checked for every list, clear for none and partial otherwise`() {
        val catalog = listOf("people", "places", "companies", "other")

        assertEquals(ToggleableState.On, nameWordsetsState(catalog, catalog))
        assertEquals(ToggleableState.Off, nameWordsetsState(emptyList(), catalog))
        assertEquals(ToggleableState.Indeterminate, nameWordsetsState(listOf("people"), catalog))
        // An id the catalog no longer has does not count.
        assertEquals(ToggleableState.Off, nameWordsetsState(listOf("retired"), catalog))
        assertEquals(ToggleableState.On, nameWordsetsState(catalog + "retired", catalog))
        assertEquals(ToggleableState.Off, nameWordsetsState(catalog, emptyList()))
    }

    @Test
    fun `a click on a partial names box means every list`() {
        val catalog = listOf("people", "places")

        assertEquals(catalog, nameWordsetsAfterClick(listOf("people"), catalog))
        assertEquals(emptyList<String>(), nameWordsetsAfterClick(catalog, catalog))
        assertEquals(catalog, nameWordsetsAfterClick(emptyList(), catalog))
    }

    // Subtitle cleanup (desktop builtin_cleanup_pieces, add_missing_pieces, cleanup_state).

    @Test
    fun `cleanup pieces are the presets unless the language ships its own pattern`() {
        assertEquals(SUBTITLE_REGEX_PRESETS.map { it.pattern }, subtitleCleanupPieces(""))
        assertEquals(listOf("""\(.*?\)"""), subtitleCleanupPieces("""\(.*?\)"""))
    }

    @Test
    fun `missing pieces are appended and present ones kept in place`() {
        assertEquals("a|b", addMissingCleanupPieces("", listOf("a", "b")))
        assertEquals("b|mine|a", addMissingCleanupPieces("b|mine", listOf("a", "b")))
        assertEquals("a|b", addMissingCleanupPieces("  a|b ", listOf("a", "b")))
    }

    @Test
    fun `the cleanup box is derived from the toggle and the pattern`() {
        val pieces = listOf("a", "b")

        assertEquals(ToggleableState.Off, subtitleCleanupState(use = false, pattern = "a|b", pieces = pieces))
        assertEquals(ToggleableState.On, subtitleCleanupState(use = true, pattern = "a|b|mine", pieces = pieces))
        assertEquals(ToggleableState.Indeterminate, subtitleCleanupState(use = true, pattern = "a", pieces = pieces))
    }

    @Test
    fun `ticking cleanup turns the filter on with every built-in piece`() {
        val ticked = draft().withSubtitleCleanupClicked(LanguageDefaults.JAPANESE)

        assertEquals(true, ticked.useSubtitleRegex)
        assertEquals(SUBTITLE_REGEX_PRESETS.joinToString("|") { it.pattern }, ticked.subtitleRegex)
        val cleared = ticked.withSubtitleCleanupClicked(LanguageDefaults.JAPANESE)
        assertEquals(false, cleared.useSubtitleRegex)
        // Off keeps the pattern, so turning it back on finds it.
        assertEquals(ticked.subtitleRegex, cleared.subtitleRegex)
    }

    @Test
    fun `a partial cleanup box completes the user's own pattern`() {
        val own = draft().copy(subtitleRegex = "mine", useSubtitleRegex = true)

        assertEquals(ToggleableState.Indeterminate, own.subtitleCleanupState(LanguageDefaults.JAPANESE))
        val completed = own.withSubtitleCleanupClicked(LanguageDefaults.JAPANESE)
        assertTrue(completed.subtitleRegex.startsWith("mine|"))
        assertEquals(ToggleableState.On, completed.subtitleCleanupState(LanguageDefaults.JAPANESE))
    }

    @Test
    fun `a language pattern the field inherits stays inherited`() {
        val korean =
            LanguageDefaults.JAPANESE.copy(code = "ko", useSubtitleRegexFilter = false, subtitleRegexFilter = "x")
        val ticked = draft().withSubtitleCleanupClicked(korean)

        assertEquals(true, ticked.useSubtitleRegex)
        assertEquals("", ticked.subtitleRegex)
        assertFalse(subtitlePatternIsCustom(ticked.effectiveSubtitleRegex(korean), subtitleCleanupPieces("x")))
        assertTrue(subtitlePatternIsCustom("x|y", subtitleCleanupPieces("x")))
    }

    // Card type (desktop "Default Card Type" + "Customize marker field names").

    @Test
    fun `picking a card type preselects each profile's own default marker field`() {
        // SetupViewModel.selectCardType preselects CardType.conventionalField when the note type
        // has it; every profile's scoped card_type_marker_fields default is that same name, so the
        // pick lands on the profile's default without reading the profile.
        LanguageProfileFixtures.all.forEach { profile ->
            val markers = profile.scopedDefaults.getValue("card_type_marker_fields") as BridgeJsonValue.ObjectValue
            CardType.entries.forEach { type ->
                assertEquals(
                    "${profile.code} ${type.wireValue}",
                    BridgeJsonValue.Text(type.conventionalField),
                    markers.values[type.wireValue],
                )
            }
        }
    }

    // Mining-language list (desktop gui/utils/language_choices.py _sort_key).

    @Test
    fun `the 32 languages sort by native name in desktop's script groups`() {
        val shuffled = NATIVE_NAMES.shuffled(Random(7))
        val profiles = shuffled.map { (code, native) -> LanguageProfileFixtures.hebrew.copy(code = code, displayName = native) }

        assertEquals(NATIVE_NAMES.map { it.first }, nativeLanguageOrder(profiles).map { it.code })
    }

    @Test
    fun `a right-to-left name is isolated inside a sentence`() {
        assertEquals("\u2068עברית\u2069", bidiIsolated("עברית"))
        assertEquals("\u2068العربية\u2069", bidiIsolated("العربية"))
        assertEquals("Deutsch", bidiIsolated("Deutsch"))
    }

    private companion object {
        /** Desktop `mining_language_choices()` order at e7cf74e88, every language available. */
        val NATIVE_NAMES =
            listOf(
                "id" to "Bahasa Indonesia",
                "ca" to "Català",
                "da" to "Dansk",
                "de" to "Deutsch",
                "en" to "English",
                "es" to "Español",
                "fr" to "Français",
                "hr" to "Hrvatski",
                "it" to "Italiano",
                "lt" to "Lietuvių",
                "hu" to "Magyar",
                "nl" to "Nederlands",
                "nb" to "Norsk bokmål",
                "pl" to "Polski",
                "pt" to "Português",
                "ro" to "Română",
                "sl" to "Slovenščina",
                "fi" to "Suomi",
                "sv" to "Svenska",
                "vi" to "Tiếng Việt",
                "tr" to "Türkçe",
                "el" to "Ελληνικά",
                "ru" to "Русский",
                "uk" to "Українська",
                "he" to "עברית",
                "ar" to "العربية",
                "fa" to "فارسی",
                "th" to "ไทย",
                "zh" to "中文",
                "yue" to "廣東話",
                "ja" to "日本語",
                "ko" to "한국어",
            )
    }
}
