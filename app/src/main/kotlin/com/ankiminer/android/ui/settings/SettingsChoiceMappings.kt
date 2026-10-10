package com.ankiminer.android.ui.settings

import androidx.compose.ui.state.ToggleableState
import com.ankiminer.android.data.settings.AnimatedScreenshotLimits
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.LanguageDefaults
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.vm.SettingsDraft
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Desktop v3.8.0 folds several settings rows into one choice each. Every choice here maps onto
 * fields Android already stores, the way desktop maps its own combos: no field changes meaning and
 * nothing new is persisted.
 */

/**
 * Desktop's Script Type combo (`_SCRIPT_TYPE_VALUES`): the four states of the two kana booleans.
 * Both on also skips words that mix the two kana scripts, which two checkboxes never said.
 */
internal enum class ScriptType(val excludeHiragana: Boolean, val excludeKatakana: Boolean) {
    KEEP(false, false),
    HIRAGANA(true, false),
    KATAKANA(false, true),
    ALL_KANA(true, true),
    ;

    companion object {
        fun of(
            hiragana: Boolean,
            katakana: Boolean,
        ): ScriptType = entries.single { it.excludeHiragana == hiragana && it.excludeKatakana == katakana }
    }
}

/** The script type in force: an unset boolean is the language's own default. */
internal fun SettingsDraft.scriptType(inherited: LanguageDefaults): ScriptType =
    ScriptType.of(hiragana ?: inherited.excludeHiraganaOnly, katakana ?: inherited.excludeKatakanaOnly)

internal fun SettingsDraft.withScriptType(type: ScriptType): SettingsDraft =
    copy(hiragana = type.excludeHiragana, katakana = type.excludeKatakana)

/**
 * Desktop's Sentence Rule combo (`_SENTENCE_RULE_VALUES`). i+1 already overrides dedup in the
 * engine, so a stored pair with both on shows i+1 and mines exactly as i+1 alone.
 */
internal enum class SentenceRule(val deduplicate: Boolean, val iPlusOne: Boolean) {
    ALL(false, false),
    ONE_PER_SENTENCE(true, false),
    I_PLUS_ONE(false, true),
    ;

    companion object {
        fun of(
            deduplicate: Boolean,
            iPlusOne: Boolean,
        ): SentenceRule =
            when {
                iPlusOne -> I_PLUS_ONE
                deduplicate -> ONE_PER_SENTENCE
                else -> ALL
            }
    }
}

internal val SettingsDraft.sentenceRule: SentenceRule
    get() =
        SentenceRule.of(
            deduplicate ?: EngineDefaults.DEDUPLICATE_SENTENCES,
            iPlusOne ?: EngineDefaults.USE_I_PLUS_ONE_FILTER,
        )

/** Picking the rule already shown is no edit, so a stored (dedup, i+1) pair is never migrated. */
internal fun SettingsDraft.withSentenceRule(rule: SentenceRule): SettingsDraft =
    if (rule == sentenceRule) this else copy(deduplicate = rule.deduplicate, iPlusOne = rule.iPlusOne)

/** Desktop's `ANIMATED_SIZE_PRESETS`: frame rate, height in pixels and quality (0–100). */
internal enum class AnimatedSizePreset(val fps: Int, val height: Int, val quality: Int) {
    SMALL(12, 480, 30),
    BALANCED(20, 720, 30),
    HIGH(24, 1080, 50),
}

/** The Size row's value: a preset, or the stored triple when it matches none. */
internal sealed interface AnimatedSize {
    data class Preset(val preset: AnimatedSizePreset) : AnimatedSize

    data class Custom(val fps: Int, val height: Int, val quality: Int) : AnimatedSize
}

/** An unset value inherits the engine default, so absent fps and height read as Balanced. */
internal fun animatedSizeOf(
    fps: Int?,
    height: Int?,
    quality: Int?,
): AnimatedSize {
    val resolvedFps = fps ?: EngineDefaults.ANIMATED_SCREENSHOT_FPS
    val resolvedHeight = height ?: EngineDefaults.ANIMATED_SCREENSHOT_HEIGHT
    val resolvedQuality = quality ?: EngineDefaults.ANIMATED_SCREENSHOT_QUALITY
    return AnimatedSizePreset.entries
        .firstOrNull { it.fps == resolvedFps && it.height == resolvedHeight && it.quality == resolvedQuality }
        ?.let(AnimatedSize::Preset)
        ?: AnimatedSize.Custom(resolvedFps, resolvedHeight, resolvedQuality)
}

internal val SettingsDraft.animatedSize: AnimatedSize
    get() =
        animatedSizeOf(
            animatedScreenshotFps,
            animatedScreenshotHeight,
            animatedScreenshotQuality.toIntOrNull(),
        )

/** All three move together; a value equal to the engine default saves as inherited. */
internal fun SettingsDraft.withAnimatedSize(preset: AnimatedSizePreset): SettingsDraft =
    copy(
        animatedScreenshotFps = preset.fps,
        animatedScreenshotHeight = preset.height,
        animatedScreenshotQuality = preset.quality.toString(),
    )

/** The Clip length row: [sameAsAudio] is its lowest value, "Same as sentence audio". */
internal data class ClipLength(val sameAsAudio: Boolean, val seconds: Double)

/** Desktop's spin step for the clip length. */
private const val CLIP_LENGTH_STEP = 0.5

/**
 * Desktop's `_ClipLengthSpinBox`: one stepper whose lowest value sets match-audio and leaves the
 * stored length alone, so stepping back up returns that length.
 *
 * Only a length the user settled on is returned to. The way down to "Same as sentence audio"
 * passes through every value above it, and each step saves; returning to the last of those left
 * 0.5 behind (desktop Edge1). So the stepper remembers the length it found, and a length that
 * changed since its own last step (loaded, imported) replaces it. One instance lives as long as the
 * row is composed: leaving the screen settles whatever the row shows.
 */
internal class ClipLengthStepper {
    private var resume: Double? = null
    private var lastWritten: Double? = null

    fun step(
        current: ClipLength,
        up: Boolean,
    ): ClipLength {
        val settled = resume?.takeIf { current.seconds == lastWritten } ?: current.seconds
        val range = AnimatedScreenshotLimits.CLIP_DURATION_SECONDS
        val next =
            when {
                current.sameAsAudio && up -> ClipLength(sameAsAudio = false, seconds = current.seconds)
                current.sameAsAudio -> current
                !up && current.seconds <= range.start -> ClipLength(sameAsAudio = true, seconds = settled)
                else -> {
                    val delta = if (up) CLIP_LENGTH_STEP else -CLIP_LENGTH_STEP
                    // Tenths, so 2.3 + 0.5 reads 2.8 rather than 2.8000000000000003.
                    val stepped = ((current.seconds + delta) * 10).roundToInt() / 10.0
                    ClipLength(sameAsAudio = false, seconds = stepped.coerceIn(range))
                }
            }
        resume = settled
        lastWritten = next.seconds
        return next
    }
}

internal val SettingsDraft.clipLength: ClipLength
    get() =
        ClipLength(
            sameAsAudio = animatedScreenshotMatchAudio,
            seconds =
                AppSettingsDraftParser.doubleOrNull(animatedScreenshotDuration)?.takeIf { it.isFinite() }
                    ?: EngineDefaults.ANIMATED_SCREENSHOT_DURATION_SECONDS,
        )

internal fun SettingsDraft.withClipLength(length: ClipLength): SettingsDraft =
    copy(
        animatedScreenshotMatchAudio = length.sameAsAudio,
        animatedScreenshotDuration = length.seconds.toString(),
    )

/**
 * Desktop's one names box (D15 item 2): checked excludes every bundled list, clear excludes none.
 * A subset saved before the box existed shows partly checked and is kept until the box is clicked.
 */
internal fun nameWordsetsState(
    enabled: List<String>,
    catalog: List<String>,
): ToggleableState {
    val selected = catalog.count { it in enabled }
    return when {
        selected == 0 -> ToggleableState.Off
        selected == catalog.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** A click moves a partial box to checked, as Qt's tristate cycle does on desktop. */
internal fun nameWordsetsAfterClick(
    enabled: List<String>,
    catalog: List<String>,
): List<String> =
    if (nameWordsetsState(enabled, catalog) == ToggleableState.On) emptyList() else catalog

/**
 * Desktop's `builtin_cleanup_pieces`: a language that ships its own pattern keeps exactly that one
 * piece; the rest get every preset.
 */
internal fun subtitleCleanupPieces(languageDefault: String): List<String> =
    if (languageDefault.isNotEmpty()) listOf(languageDefault) else SUBTITLE_REGEX_PRESETS.map { it.pattern }

/** Desktop's `add_missing_pieces`: the user's own text keeps its order; missing pieces go last. */
internal fun addMissingCleanupPieces(
    pattern: String,
    pieces: List<String>,
): String =
    pieces.fold(pattern.trim()) { current, piece ->
        when {
            piece in current -> current
            current.isEmpty() -> piece
            else -> "$current|$piece"
        }
    }

/** Desktop's `cleanup_state`: derived from the two stored fields, never stored itself. */
internal fun subtitleCleanupState(
    use: Boolean,
    pattern: String,
    pieces: List<String>,
): ToggleableState =
    when {
        !use -> ToggleableState.Off
        pieces.all { it in pattern } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

/** The raw fields open by themselves when the pattern is anything but the built-ins. */
internal fun subtitlePatternIsCustom(
    pattern: String,
    pieces: List<String>,
): Boolean = pattern.trim().let { it.isNotEmpty() && it != pieces.joinToString("|") }

/** The pattern a run uses: a blank field inherits the language's own. */
internal fun SettingsDraft.effectiveSubtitleRegex(inherited: LanguageDefaults): String =
    subtitleRegex.ifEmpty { inherited.subtitleRegexFilter }

internal fun SettingsDraft.subtitleCleanupState(inherited: LanguageDefaults): ToggleableState =
    subtitleCleanupState(
        use = useSubtitleRegex ?: inherited.useSubtitleRegexFilter,
        pattern = effectiveSubtitleRegex(inherited),
        pieces = subtitleCleanupPieces(inherited.subtitleRegexFilter),
    )

/**
 * Desktop's `_on_cleanup_clicked`: a checked box turns the filter off and keeps the pattern;
 * otherwise the filter goes on with every missing piece added. A field that inherits the
 * language's pattern stays blank when that pattern already holds them.
 */
internal fun SettingsDraft.withSubtitleCleanupClicked(inherited: LanguageDefaults): SettingsDraft {
    if (subtitleCleanupState(inherited) == ToggleableState.On) return copy(useSubtitleRegex = false)
    val effective = effectiveSubtitleRegex(inherited)
    val completed = addMissingCleanupPieces(effective, subtitleCleanupPieces(inherited.subtitleRegexFilter))
    return copy(
        useSubtitleRegex = true,
        subtitleRegex = if (subtitleRegex.isEmpty() && completed == effective.trim()) "" else completed,
    )
}

/**
 * Desktop's script groups, named as `Character.UnicodeScript` names them (desktop's "CJK" is HAN).
 * A script not listed sorts last.
 */
private val SCRIPT_ORDER =
    listOf(
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.ARABIC,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HANGUL,
    )

/**
 * The mining-language list's order, shared by Settings and the setup wizard: desktop's `_sort_key`
 * (`gui/utils/language_choices.py`). The script group comes first, then the native name with its
 * accents and case folded away. The group has to lead: NFKD splits 한 into Jamo, which by code point
 * would sort 한국어 ahead of every Han name.
 */
internal fun nativeLanguageOrder(profiles: List<LanguageProfileInfo>): List<LanguageProfileInfo> =
    profiles.sortedWith(compareBy({ scriptGroup(it.displayName) }, { foldedName(it.displayName) }))

private fun scriptGroup(name: String): Int {
    if (name.isEmpty()) return SCRIPT_ORDER.size
    val index = SCRIPT_ORDER.indexOf(Character.UnicodeScript.of(name.codePointAt(0)))
    return if (index < 0) SCRIPT_ORDER.size else index
}

private fun foldedName(name: String): String =
    Normalizer
        .normalize(name, Normalizer.Form.NFKD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)

/**
 * Desktop's `bidi_isolated`: a right-to-left name inside a sentence is wrapped in FSI … PDI, or the
 * sentence takes the name's direction from its first strong character.
 */
internal fun bidiIsolated(name: String): String {
    val rightToLeft =
        name.any {
            val direction = Character.getDirectionality(it)
            direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }
    return if (rightToLeft) "$FIRST_STRONG_ISOLATE$name$POP_DIRECTIONAL_ISOLATE" else name
}

// Built from code points: lint's BidiSpoofing rightly rejects invisible controls in a literal.
private val FIRST_STRONG_ISOLATE = Char(0x2068)
private val POP_DIRECTIONAL_ISOLATE = Char(0x2069)
