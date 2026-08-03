package com.ankiminer.android.ui.video

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.player.FakeCurationPreviewPlayer
import com.ankiminer.android.ui.mining.TimingPreviewState
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimingPreviewOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cueNudgeAndAbToggleSeekWithTheDisplayedOffset() {
        val fake = FakeCurationPreviewPlayer()
        var state by mutableStateOf(state())
        setOverlay(
            fake = fake,
            state = { state },
            update = { state = it },
        )

        scrollTo(VideoMiningTestTags.timingPreviewCue(0))
        composeRule.onNodeWithTag(VideoMiningTestTags.timingPreviewCue(0)).performClick()
        composeRule.runOnIdle { assertEquals(1.5, fake.seekAndPlayCalls.last(), 0.000_001) }

        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_NUDGE_LATER)
        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_NUDGE_LATER).performClick()
        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_READOUT)
        composeRule.onNodeWithText("Offset +0.60 s").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1.6, fake.seekAndPlayCalls.last(), 0.000_001) }

        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_TOGGLE)
        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_TOGGLE).performClick()
        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_READOUT)
        composeRule.onNodeWithText("Unshifted").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1.0, fake.seekAndPlayCalls.last(), 0.000_001) }
    }

    @Test
    fun typingChangesWorkingValueWithoutSeeking() {
        val fake = FakeCurationPreviewPlayer()
        var state by mutableStateOf(state())
        setOverlay(
            fake = fake,
            state = { state },
            update = { state = it },
        )
        scrollTo(VideoMiningTestTags.timingPreviewCue(0))
        composeRule.onNodeWithTag(VideoMiningTestTags.timingPreviewCue(0)).performClick()
        val callsBeforeTyping = fake.seekAndPlayCalls.size

        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_OFFSET_FIELD)
        composeRule
            .onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_OFFSET_FIELD)
            .performTextReplacement("1.25")

        composeRule.runOnIdle {
            assertEquals(1.25, state.workingOffset, 0.0)
            assertEquals(callsBeforeTyping, fake.seekAndPlayCalls.size)
        }
    }

    @Test
    fun applyPropagatesWorkingValueClosesOverlayAndReleasesPlayer() {
        val fake = FakeCurationPreviewPlayer()
        var state by mutableStateOf(state())
        var visible by mutableStateOf(true)
        var applied: Double? = null
        composeRule.setContent {
            AnkiMinerTheme {
                if (visible) {
                    TimingPreviewOverlay(
                        state = state,
                        videoUri = VIDEO_URI,
                        onSelectCue = { index -> state = state.selectCue(index) },
                        onNudge = { delta -> state = state.nudge(delta) },
                        onSetWorking = { value -> state = state.setWorking(value) },
                        onToggleUnshifted = { state = state.toggleUnshifted() },
                        onApply = {
                            applied = state.workingOffset
                            visible = false
                        },
                        onCancel = { visible = false },
                        playerFactory = { fake },
                        seekabilityProbe = { _, _ -> true },
                    )
                }
            }
        }
        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_OFFSET_FIELD)
        composeRule
            .onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_OFFSET_FIELD)
            .performTextReplacement("1.75")

        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_APPLY).performClick()

        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1.75, applied ?: error("not applied"), 0.0)
            assertEquals(1, fake.releaseCount)
        }
    }

    @Test
    fun systemBackClosesOverlayAndReleasesPlayer() {
        val fake = FakeCurationPreviewPlayer()
        var state by mutableStateOf(state())
        var visible by mutableStateOf(true)
        composeRule.setContent {
            AnkiMinerTheme {
                if (visible) {
                    TimingPreviewOverlay(
                        state = state,
                        videoUri = VIDEO_URI,
                        onSelectCue = { index -> state = state.selectCue(index) },
                        onNudge = { delta -> state = state.nudge(delta) },
                        onSetWorking = { value -> state = state.setWorking(value) },
                        onToggleUnshifted = { state = state.toggleUnshifted() },
                        onApply = { visible = false },
                        onCancel = { visible = false },
                        playerFactory = { fake },
                        seekabilityProbe = { _, _ -> true },
                    )
                }
            }
        }

        pressBack()

        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, fake.releaseCount) }
    }

    @Test
    fun nonSeekableSourceKeepsTimingControlsUsableWithoutBindingPlayer() {
        val fake = FakeCurationPreviewPlayer()
        var state by mutableStateOf(state())
        setOverlay(
            fake = fake,
            state = { state },
            update = { state = it },
            seekable = false,
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_UNAVAILABLE)
            .assertIsDisplayed()
        scrollTo(VideoMiningTestTags.timingPreviewCue(0))
        composeRule.onNodeWithTag(VideoMiningTestTags.timingPreviewCue(0)).performClick()
        scrollTo(VideoMiningTestTags.TIMING_PREVIEW_NUDGE_LATER)
        composeRule.onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_NUDGE_LATER).performClick()

        composeRule.runOnIdle {
            assertEquals(0.6, state.workingOffset, 0.000_001)
            assertFalse(fake.boundUris.isNotEmpty())
            assertTrue(fake.seekAndPlayCalls.isEmpty())
        }
    }

    private fun setOverlay(
        fake: FakeCurationPreviewPlayer,
        state: () -> TimingPreviewState,
        update: (TimingPreviewState) -> Unit,
        seekable: Boolean = true,
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                val current = state()
                TimingPreviewOverlay(
                    state = current,
                    videoUri = VIDEO_URI,
                    onSelectCue = { index -> update(current.selectCue(index)) },
                    onNudge = { delta -> update(current.nudge(delta)) },
                    onSetWorking = { value -> update(current.setWorking(value)) },
                    onToggleUnshifted = { update(current.toggleUnshifted()) },
                    onApply = {},
                    onCancel = {},
                    playerFactory = { fake },
                    seekabilityProbe = { _, _ -> seekable },
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule
            .onNodeWithTag(VideoMiningTestTags.TIMING_PREVIEW_CONTENT)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun state(): TimingPreviewState =
        TimingPreviewState(
            initialOffset = 0.5,
            workingOffset = 0.5,
            previewingUnshifted = false,
            cues = listOf(SubtitleCue(1.0, 2.0, "猫だ。")),
            selectedCueIndex = null,
        )

    private companion object {
        val VIDEO_URI: Uri = Uri.parse("content://test/video.mkv")
    }
}
