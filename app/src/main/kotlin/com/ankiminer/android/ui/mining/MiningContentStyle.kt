package com.ankiminer.android.ui.mining

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import com.ankiminer.android.engine.ContentDirection
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.player.JAPANESE_AUDIO_TRACK_CODES

/**
 * How mined text renders: the active profile's `content_style`.
 *
 * The locale picks the glyphs (a Han character is drawn the Japanese way for ja, not the way the
 * phone's own locale draws it) and the script's shaping; an RTL language (ar, fa, he) lays its
 * text out right to left. Only the mined text takes this style — the app's own labels stay in the
 * interface language.
 */
@Immutable
internal data class MiningContentStyle(
    /** BCP-47 tag of the mined content (`profile.contentLanguage`). */
    val contentLanguage: String,
    val rtl: Boolean,
    /** The container language tags a track in this language carries (`audio_track_codes`). */
    val audioTrackCodes: List<String>,
) {
    /** Merged into a text style: locale always, direction only for an RTL language. */
    val textStyle: TextStyle
        get() =
            TextStyle(
                localeList = LocaleList(contentLanguage),
                textDirection = if (rtl) TextDirection.Rtl else TextDirection.Unspecified,
            )

    companion object {
        /** Japanese before the profiles load: the codes `audio_track_detector` matches. */
        val JAPANESE =
            MiningContentStyle(
                contentLanguage = "ja",
                rtl = false,
                audioTrackCodes = JAPANESE_AUDIO_TRACK_CODES,
            )

        /**
         * The style for [language]: its profile's when loaded; Japanese's for ja; otherwise the bare
         * code, left to right, until the profile arrives.
         */
        fun forLanguage(
            language: String,
            profiles: List<LanguageProfileInfo>,
        ): MiningContentStyle {
            val profile = profiles.firstOrNull { it.code == language }
            return when {
                profile != null ->
                    MiningContentStyle(
                        contentLanguage = profile.contentLanguage,
                        rtl = profile.contentDirection == ContentDirection.RTL,
                        audioTrackCodes = profile.audioTrackCodes,
                    )
                language == JAPANESE.contentLanguage -> JAPANESE
                else -> MiningContentStyle(language, rtl = false, audioTrackCodes = listOf(language))
            }
        }
    }
}

internal val LocalMiningContentStyle = staticCompositionLocalOf { MiningContentStyle.JAPANESE }

/** [this] style for mined text in the active mining language. */
@Composable
@ReadOnlyComposable
internal fun TextStyle.minedText(): TextStyle = merge(LocalMiningContentStyle.current.textStyle)

/** Definition HTML wrapped so the WebView picks the content's glyphs and direction. */
internal fun MiningContentStyle.wrapDefinitionHtml(html: String): String {
    val direction = if (rtl) " dir=\"rtl\"" else ""
    return "<div lang=\"$contentLanguage\"$direction>$html</div>"
}
