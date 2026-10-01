package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.LanguageScope
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.vm.SettingsDraft
import java.text.Collator
import java.util.Locale

internal object LanguageSettingsTestTags {
    const val PICKER = "language-picker"
    const val CHOOSE_NOTE_TYPE = "language-choose-note-type"
    const val SCRIPT_VARIANT = "language-script-variant"
    const val TONE_COLOR = "language-tone-color"
    const val SWITCH_BLOCKED = "language-switch-blocked"

    fun option(code: String) = "language-option-$code"

    fun reason(code: String) = "language-reason-$code"

    fun download(code: String) = "language-download-$code"
}

/** Card keys of the Language tab, in emission order. */
internal const val MINING_LANGUAGE_KEY = "mining-language"
internal const val LANGUAGE_VARIANT_KEY = "language-variant"
internal const val LANGUAGE_TONE_COLOR_KEY = "language-tone-color"

/** Everything the Language tab and the language-dependent cards on other tabs read. */
internal data class LanguageSettingsState(
    val activeCode: String = LanguageScope.JAPANESE,
    /** `language.profiles`, registry order; empty until the bridge answers. */
    val profiles: List<LanguageProfileInfo> = emptyList(),
    /** The language whose data is downloading for a switch, if any. */
    val downloadingCode: String? = null,
    /** A known-words import preview is open: its words would land in the other language's DB. */
    val knownWordsPreviewOpen: Boolean = false,
    /** A resource or Anki operation, or a run, holds the runtime. */
    val busy: Boolean = false,
    /** The active language has no note type yet, so no run can start. */
    val noteTypeMissing: Boolean = false,
) {
    val activeProfile: LanguageProfileInfo?
        get() = profiles.firstOrNull { it.code == activeCode }

    /** Pitch sources and the pitch format exist only for a language with pitch (ja). */
    val showsPitch: Boolean
        get() = activeProfile?.let { PITCH_CAPABILITY in it.capabilities } ?: (activeCode == LanguageScope.JAPANESE)

    /** Jisho is a Japanese dictionary; the declared network egress is Japanese lookups. */
    val offersJisho: Boolean
        get() = activeCode == LanguageScope.JAPANESE

    val showsToneColor: Boolean
        get() = activeProfile?.let { TONE_COLOR_CAPABILITY in it.capabilities } == true

    val scriptVariants: List<String>
        get() = activeProfile?.scriptVariants.orEmpty()

    /** A switch can start: nothing holds the runtime and no preview would cross languages. */
    val switchAllowed: Boolean
        get() = !busy && !knownWordsPreviewOpen && downloadingCode == null

    companion object {
        const val PITCH_CAPABILITY = "pitch"
        const val TONE_COLOR_CAPABILITY = "tone_color"
    }
}

internal data class LanguageSettingsActions(
    val onSwitch: (String) -> Unit = {},
    val onDownloadAndSwitch: (String) -> Unit = {},
    val onChooseNoteType: () -> Unit = {},
)

/**
 * The name a language is listed under: the interface language's own name for it ("Hebrew",
 * "Hebräisch", "ヘブライ語"), the profile's English name when the platform has none.
 */
internal fun languageDisplayName(
    profile: LanguageProfileInfo,
    uiLocale: Locale,
): String {
    val localized = Locale.forLanguageTag(profile.code).getDisplayLanguage(uiLocale)
    return if (localized.isBlank() || localized.equals(profile.code, ignoreCase = true)) {
        profile.englishName
    } else {
        localized.replaceFirstChar { it.titlecase(uiLocale) }
    }
}

/** The picker's order: by the name the user reads, in their own collation. */
internal fun orderedLanguageChoices(
    profiles: List<LanguageProfileInfo>,
    uiLocale: Locale,
): List<LanguageProfileInfo> {
    val collator = Collator.getInstance(uiLocale)
    return profiles.sortedWith { left, right ->
        collator.compare(languageDisplayName(left, uiLocale), languageDisplayName(right, uiLocale))
    }
}

/** What the note-type route does once a requested switch lands. */
internal enum class NoteTypeRoute {
    /** The switch has not landed yet. */
    WAIT,

    /** The new language has no note type: open the Anki tab's target card. */
    ROUTE,

    /** The new language already has one (a return visit): nothing to do. */
    DONE,
}

/**
 * Every non-Japanese profile starts with no note type, which config_map refuses, so a switch into
 * one is only half done until the user picks it. [pending] is the language a switch was asked for
 * from the picker; [language] and [noteType] are what the settings now hold.
 */
internal fun noteTypeRouteAfterSwitch(
    pending: String?,
    language: String,
    noteType: String?,
): NoteTypeRoute =
    when {
        pending == null || pending != language -> NoteTypeRoute.WAIT
        noteType.isNullOrEmpty() -> NoteTypeRoute.ROUTE
        else -> NoteTypeRoute.DONE
    }

/**
 * Runs [noteTypeRouteAfterSwitch]: once the requested language is in force, [onRoute] when it needs
 * a note type, then [onConsumed] either way.
 */
@Composable
internal fun LanguageSwitchNoteTypeRoute(
    pending: String?,
    language: String,
    noteType: String?,
    onRoute: () -> Unit,
    onConsumed: () -> Unit,
) {
    val route by rememberUpdatedState(onRoute)
    val consumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(pending, language, noteType) {
        when (noteTypeRouteAfterSwitch(pending, language, noteType)) {
            NoteTypeRoute.WAIT -> Unit
            NoteTypeRoute.ROUTE -> {
                route()
                consumed()
            }
            NoteTypeRoute.DONE -> consumed()
        }
    }
}

/**
 * Requests to jump to the note-type card. Each request is taken once: the counters are saved, so a
 * return to Settings or a recreated activity restores them already handled instead of jumping again.
 */
@Stable
internal class NoteTypeJumpRequests(
    requested: Int = 0,
    private var handled: Int = 0,
) {
    var requested by mutableIntStateOf(requested)
        private set

    fun request() {
        requested += 1
    }

    fun take(): Boolean {
        if (handled == requested) return false
        handled = requested
        return true
    }

    internal companion object {
        val Saver =
            listSaver<NoteTypeJumpRequests, Int>(
                save = { listOf(it.requested, it.handled) },
                restore = { NoteTypeJumpRequests(it[0], it[1]) },
            )
    }
}

@Composable
internal fun rememberNoteTypeJumpRequests(): NoteTypeJumpRequests =
    rememberSaveable(saver = NoteTypeJumpRequests.Saver) { NoteTypeJumpRequests() }

/** Slots installed for languages other than [language], by panel, for the cross-language note. */
internal data class OtherLanguageSlots(
    val dictionaries: List<Pair<String, String>> = emptyList(),
    val frequencies: List<Pair<String, String>> = emptyList(),
    val pitch: List<Pair<String, String>> = emptyList(),
    val audio: List<Pair<String, String>> = emptyList(),
)

/** (name, language code) of each occupied slot another language owns. */
internal fun ResourceManagerState.otherLanguageSlots(language: String): OtherLanguageSlots =
    OtherLanguageSlots(
        dictionaries =
            dictionaries.filter { it.occupied && it.language != language }.map { it.sourceName to it.language },
        frequencies = frequencySources.filter { it.language != language }.map { it.sourceName to it.language },
        pitch = pitchSources.filter { it.language != language }.map { it.sourceName to it.language },
        audio = audioPacks.filter { it.language != language }.map { it.sourceName to it.language },
    )

/**
 * The note under a resource panel naming what other languages hold: those slots are hidden from
 * this language's chain (the engine would skip them), and without the note they read as lost.
 */
@Composable
internal fun OtherLanguageSlotsNote(slots: List<Pair<String, String>>) {
    if (slots.isEmpty()) return
    val uiLocale = currentUiLocale()
    val names =
        slots.joinToString { (name, code) ->
            val language = Locale.forLanguageTag(code).getDisplayLanguage(uiLocale).ifBlank { code }
            "$name ($language)"
        }
    SupportingText(stringResource(R.string.resource_panel_other_language_slots, names))
}

@Composable
internal fun currentUiLocale(): Locale =
    LocalConfiguration.current.locales[0]

/**
 * The Language tab: the mining-language picker, then the language's own variant and tone-colour
 * settings when its profile has them. Mirrors desktop's Mining Language panel: a language that
 * needs its data offers "Download and switch" instead of a switch; one this build cannot mine says
 * why and offers nothing.
 *
 * Internal rather than private so the instrumented tests compose the real cards.
 */
internal fun LazyListScope.languageSettings(
    language: LanguageSettingsState,
    draft: SettingsDraft,
    recorder: SettingsCardIndexRecorder,
    onDraftChange: (SettingsDraft) -> Unit,
    actions: LanguageSettingsActions,
    inlineFailure: @Composable () -> Unit = {},
) {
    settingsCard(SettingsCategory.LANGUAGE, recorder, MINING_LANGUAGE_KEY) {
        SettingsSection(stringResource(R.string.language_settings_title)) {
            SupportingText(stringResource(R.string.language_settings_help))
            MiningLanguagePicker(language, actions)
            if (language.knownWordsPreviewOpen) {
                Text(
                    stringResource(R.string.language_switch_blocked_known_words),
                    modifier = Modifier.testTag(LanguageSettingsTestTags.SWITCH_BLOCKED),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            inlineFailure()
            if (language.noteTypeMissing) {
                val name =
                    language.activeProfile?.let { languageDisplayName(it, currentUiLocale()) }
                        ?: language.activeCode
                SupportingText(stringResource(R.string.language_note_type_needed, name))
                PrimaryActionButton(
                    onClick = actions.onChooseNoteType,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(LanguageSettingsTestTags.CHOOSE_NOTE_TYPE),
                ) {
                    Text(stringResource(R.string.language_choose_note_type))
                }
            }
        }
    }
    val variants = language.scriptVariants
    if (variants.isNotEmpty()) {
        settingsCard(SettingsCategory.LANGUAGE, recorder, LANGUAGE_VARIANT_KEY) {
            // zh offers character sets, pt regional varieties; the profile's own list decides which.
            val regional = variants.any { it in REGIONAL_VARIANTS }
            Column(Modifier.testTag(LanguageSettingsTestTags.SCRIPT_VARIANT)) {
                NullableChoice(
                    label =
                        stringResource(
                            if (regional) R.string.language_regional_variety else R.string.language_script_variant,
                        ),
                    value = draft.scriptVariant?.takeIf { it in variants },
                    engineDefault = variants.first(),
                    values = variants,
                    optionLabel = { stringResource(scriptVariantLabel(it)) },
                    onChange = { onDraftChange(draft.copy(scriptVariant = it)) },
                )
            }
        }
    }
    if (language.showsToneColor) {
        settingsCard(SettingsCategory.LANGUAGE, recorder, LANGUAGE_TONE_COLOR_KEY) {
            Column(Modifier.testTag(LanguageSettingsTestTags.TONE_COLOR)) {
                NullableToggle(
                    stringResource(R.string.settings_reading_tone_color),
                    draft.readingToneColor,
                    false,
                ) { onDraftChange(draft.copy(readingToneColor = it)) }
            }
        }
    }
}

private val REGIONAL_VARIANTS = setOf("br", "pt")

@StringRes
private fun scriptVariantLabel(variant: String): Int =
    when (variant) {
        "simplified" -> R.string.language_script_variant_simplified
        "traditional" -> R.string.language_script_variant_traditional
        "br" -> R.string.language_variety_br
        "pt" -> R.string.language_variety_pt
        else -> R.string.language_script_variant_as_written
    }

@Composable
private fun MiningLanguagePicker(
    language: LanguageSettingsState,
    actions: LanguageSettingsActions,
) {
    val uiLocale = currentUiLocale()
    // Before the bridge answers only the active language is known; it stays listed and selected.
    val profiles = orderedLanguageChoices(language.profiles, uiLocale)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .testTag(LanguageSettingsTestTags.PICKER),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
    ) {
        profiles.forEach { profile ->
            LanguageOption(profile, language, uiLocale, actions)
        }
    }
}

@Composable
private fun LanguageOption(
    profile: LanguageProfileInfo,
    language: LanguageSettingsState,
    uiLocale: Locale,
    actions: LanguageSettingsActions,
) {
    val code = profile.code
    val selected = code == language.activeCode
    val reason = profile.unavailableReason
    val selectable = reason == null && !selected && language.switchAllowed
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
                    .selectable(
                        selected = selected,
                        enabled = selectable,
                        role = Role.RadioButton,
                        onClick = { actions.onSwitch(code) },
                    ).testTag(LanguageSettingsTestTags.option(code))
                    .padding(vertical = AnkiMinerTokens.Space.line),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = selected || selectable)
            Column(Modifier.weight(1f)) {
                Text(languageDisplayName(profile, uiLocale))
                // The native name is the one a learner of the language looks for; it is laid out in
                // its own direction (עברית, العربية) rather than the interface's.
                if (profile.displayName != languageDisplayName(profile, uiLocale)) {
                    Text(
                        profile.displayName,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                textDirection = TextDirection.Content,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (reason != null) {
            Text(
                stringResource(
                    when (reason) {
                        LanguageUnavailableReason.DATA_REQUIRED -> R.string.language_unavailable_data_required
                        LanguageUnavailableReason.UNSUPPORTED -> R.string.language_unavailable_unsupported
                    },
                ),
                modifier =
                    Modifier
                        .padding(start = AnkiMinerTokens.Layout.minTouchTarget)
                        .testTag(LanguageSettingsTestTags.reason(code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only data a download can supply is offered; a language this build cannot mine is not.
        if (reason == LanguageUnavailableReason.DATA_REQUIRED) {
            if (language.downloadingCode == code) {
                SupportingText(
                    stringResource(R.string.language_downloading),
                    modifier = Modifier.padding(start = AnkiMinerTokens.Layout.minTouchTarget),
                )
            } else {
                SecondaryActionButton(
                    onClick = { actions.onDownloadAndSwitch(code) },
                    enabled = language.switchAllowed,
                    modifier =
                        Modifier
                            .padding(start = AnkiMinerTokens.Layout.minTouchTarget)
                            .testTag(LanguageSettingsTestTags.download(code)),
                ) {
                    Text(stringResource(R.string.language_download_and_switch))
                }
            }
        }
    }
}
