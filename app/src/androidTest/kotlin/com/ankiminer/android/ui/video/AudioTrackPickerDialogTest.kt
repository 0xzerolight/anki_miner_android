package com.ankiminer.android.ui.video

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.engine.AudioTrackInfo
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioTrackPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun zeroTracksShowNoneTextAndCloseOnlyClosingFiresOnDismiss() {
        var dismissed = false
        setDialog(
            state = AudioTrackPickerState(tracks = emptyList(), autoAudioIndex = null, selectedAudioIndex = null),
            onDismiss = { dismissed = true },
        )

        composeRule.onNodeWithText("No audio tracks found in this file.").assertIsDisplayed()
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_APPLY).assertDoesNotExist()
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_CANCEL).assertDoesNotExist()

        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_CLOSE).performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun singleTrackShowsSingleTextAndFormattedLabelWithNoApplyButton() {
        val track = track(audioIndex = 0, languageTag = "jpn", codec = "aac", channels = 2)
        setDialog(
            state = AudioTrackPickerState(tracks = listOf(track), autoAudioIndex = 0, selectedAudioIndex = null),
        )

        composeRule.onNodeWithText("This file has only one audio track.").assertIsDisplayed()
        composeRule.onNodeWithText("Track 1 — jpn · AAC stereo").assertIsDisplayed()
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_APPLY).assertDoesNotExist()
    }

    @Test
    fun multiTrackAutoRowIsPreselectedWithRadioButtonSemantics() {
        val tracks = listOf(track(audioIndex = 0, languageTag = "jpn"), track(audioIndex = 1, languageTag = "eng"))
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = 0, selectedAudioIndex = null),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.audioTrackRow(null))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun multiTrackRowTapFiresOnSelectWithTrackAudioIndex() {
        val tracks = listOf(track(audioIndex = 0, languageTag = "jpn"), track(audioIndex = 1, languageTag = "eng"))
        var selected: Long? = -1L
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = 0, selectedAudioIndex = null),
            onSelect = { selected = it },
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.audioTrackRow(1L)).performClick()

        assertEquals(1L, selected)
    }

    @Test
    fun multiTrackApplyAndCancelFireTheirCallbacks() {
        val tracks = listOf(track(audioIndex = 0, languageTag = "jpn"), track(audioIndex = 1, languageTag = "eng"))
        var applied = false
        var dismissed = false
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = 0, selectedAudioIndex = null),
            onApply = { applied = true },
            onDismiss = { dismissed = true },
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_APPLY).performClick()
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_CANCEL).performClick()

        assertEquals(true, applied)
        assertEquals(true, dismissed)
    }

    @Test
    fun autoLabelShowsCurrentlyDetectedTrackWhenPresent() {
        val tracks = listOf(track(audioIndex = 0, languageTag = "eng"), track(audioIndex = 1, languageTag = "jpn"))
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = 1, selectedAudioIndex = null),
        )

        composeRule.onNodeWithText("Auto-detect (currently: Track 2 — jpn)").assertIsDisplayed()
    }

    @Test
    fun autoLabelShowsNoJapaneseVariantWhenAutoTrackAbsent() {
        val tracks = listOf(track(audioIndex = 0, languageTag = "eng"), track(audioIndex = 1, languageTag = "fra"))
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = null, selectedAudioIndex = null),
        )

        composeRule
            .onNodeWithText("Auto-detect (no Japanese track found — will use the first track)")
            .assertIsDisplayed()
    }

    @Test
    fun labelRendersNullMetadataAndTitleSuffix() {
        val tracks =
            listOf(
                track(
                    audioIndex = 0,
                    languageTag = null,
                    codec = null,
                    channels = null,
                    title = "Director's Commentary",
                ),
            )
        setDialog(
            state = AudioTrackPickerState(tracks = tracks, autoAudioIndex = 0, selectedAudioIndex = null),
        )

        composeRule.onNodeWithText("Track 1 — und · ? (Director's Commentary)").assertIsDisplayed()
    }

    private fun setDialog(
        state: AudioTrackPickerState,
        onSelect: (Long?) -> Unit = {},
        onApply: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                AudioTrackPickerDialog(
                    state = state,
                    onSelect = onSelect,
                    onApply = onApply,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    private fun track(
        audioIndex: Long,
        languageTag: String?,
        title: String? = null,
        codec: String? = "aac",
        channels: Long? = 2,
        isDefault: Boolean = false,
    ): AudioTrackInfo =
        AudioTrackInfo(
            audioIndex = audioIndex,
            globalIndex = audioIndex,
            languageTag = languageTag,
            title = title,
            codec = codec,
            channels = channels,
            isDefault = isDefault,
        )
}
