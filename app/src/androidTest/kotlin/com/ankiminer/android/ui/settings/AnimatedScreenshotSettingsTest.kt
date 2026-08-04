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
import org.junit.Rule
import org.junit.Test

/**
 * The animated-screenshot controls follow the same shape as the tags override above them: the two
 * tuning fields stay visible and go disabled, rather than appearing and disappearing.
 *
 * Nothing else composes [mediaSettings], so this builds its own host. Every assertion scrolls
 * first — the CI emulator is 320x640 @ 160dpi while the local AVD is a Pixel 6, so a control that
 * is on screen locally sits below the fold in CI.
 */
class AnimatedScreenshotSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setMediaSettings(enabled: Boolean) {
        composeRule.setContent {
            // remember, or the toggle test's state resets on every recomposition.
            var draft by remember {
                mutableStateOf(
                    SettingsDraft.from(
                        AppSettings(
                            animatedScreenshotsEnabled = enabled,
                            animatedScreenshotDurationSeconds = 2.0,
                            animatedScreenshotQuality = 30,
                        ),
                        ResourceManagerState(),
                    ),
                )
            }
            AnkiMinerTheme {
                LazyColumn(Modifier.testTag(SettingsCategoryTestTags.LIST)) {
                    mediaSettings(draft) { draft = it }
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
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_QUALITY)
            .assertIsNotEnabled()
    }

    @Test
    fun tuningIsEnabledWhenTheToggleIsOn() {
        setMediaSettings(enabled = true)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
            .assertIsEnabled()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_QUALITY)
            .assertIsEnabled()
    }

    @Test
    fun togglingTheSwitchEnablesTheTuningFields() {
        setMediaSettings(enabled = false)

        scrollTo(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
        composeRule.onNodeWithText("Animated screenshots").performClick()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION)
            .assertIsEnabled()
    }
}
