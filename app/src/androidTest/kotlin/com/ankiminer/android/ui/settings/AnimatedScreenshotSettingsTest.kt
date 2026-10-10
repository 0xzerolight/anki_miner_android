package com.ankiminer.android.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The animated-screenshot rows (Clip length stepper, Size, Animated format) stay visible and go
 * disabled while the feature is off, rather than appearing and disappearing.
 *
 * Nothing else composes [mediaSettings], so this builds its own host. Every assertion scrolls
 * first — the CI emulator is 320x640 @ 160dpi while the local AVD is a Pixel 6, so a control that
 * is on screen locally sits below the fold in CI.
 */
class AnimatedScreenshotSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var latest: SettingsDraft

    private fun setMediaSettings(
        enabled: Boolean,
        matchAudio: Boolean = false,
        duration: Double = 2.0,
    ) {
        latest =
            SettingsDraft.from(
                AppSettings(
                    animatedScreenshotsEnabled = enabled,
                    animatedScreenshotDurationSeconds = duration,
                    animatedScreenshotQuality = 30,
                    animatedScreenshotMatchAudio = matchAudio,
                ),
                ResourceManagerState(),
            )
        composeRule.setContent {
            // remember, or the toggle test's state resets on every recomposition.
            var draft by remember { mutableStateOf(latest) }
            val recorder = remember { SettingsCardIndexRecorder() }
            AnkiMinerTheme {
                LazyColumn(Modifier.testTag(SettingsCategoryTestTags.LIST)) {
                    recorder.begin(SettingsCategory.MEDIA)
                    mediaSettings(draft, recorder) {
                        draft = it
                        latest = it
                    }
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun tuningIsDisabledUntilTheToggleIsOn() {
        setMediaSettings(enabled = false)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_SHORTER).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_LONGER).assertIsNotEnabled()
    }

    @Test
    fun togglingTheSwitchEnablesTheTuningRows() {
        setMediaSettings(enabled = false)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        composeRule.onNodeWithText("Animated screenshots").performClick()
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_SHORTER).assertIsEnabled()
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_LONGER).assertIsEnabled()
    }

    /**
     * Desktop's Clip length: its lowest value is "Same as sentence audio", and stepping back up
     * returns the length the user had set rather than the last value passed on the way down.
     */
    @Test
    fun steppingBelowTheShortestClipMatchesTheAudioAndSteppingUpRestoresTheLength() {
        setMediaSettings(enabled = true, duration = 1.0)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        val shorter = composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_SHORTER)
        shorter.performClick()
        composeRule.runOnIdle { assertEquals("0.5", latest.animatedScreenshotDuration) }
        shorter.performClick()

        composeRule.onNodeWithText("Same as sentence audio").assertExists()
        shorter.assertIsNotEnabled()
        composeRule.runOnIdle {
            assertTrue(latest.animatedScreenshotMatchAudio)
            assertEquals("1.0", latest.animatedScreenshotDuration)
        }

        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_LONGER).performClick()
        composeRule.runOnIdle {
            assertFalse(latest.animatedScreenshotMatchAudio)
            assertEquals("1.0", latest.animatedScreenshotDuration)
        }
    }

    @Test
    fun aStoredMatchAudioShowsAsTheLowestClipLength() {
        setMediaSettings(enabled = true, matchAudio = true)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        composeRule.onNodeWithText("Same as sentence audio").assertExists()
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_SHORTER).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsCategoryTestTags.ANIMATED_CLIP_LONGER).assertIsEnabled()
    }

    @Test
    fun theSizeShowsBalancedForTheDefaultsAndAPickSetsAllThree() {
        setMediaSettings(enabled = true)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SIZE)
        composeRule.onNodeWithText("Balanced").performClick()
        composeRule.onNodeWithText("High").performClick()

        composeRule.runOnIdle {
            assertEquals(24, latest.animatedScreenshotFps)
            assertEquals(1080, latest.animatedScreenshotHeight)
            assertEquals("50", latest.animatedScreenshotQuality)
        }
    }
}
