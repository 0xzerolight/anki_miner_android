package com.ankiminer.android.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Word filters and Sentences rows that mirror desktop, composed from the real card builders.
 *
 * Every assertion scrolls first: the CI emulator is 320x640 @ 160dpi, so a row that is on screen
 * on a phone sits below the fold there.
 */
class WordFilterAndSentenceSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var latest: SettingsDraft

    /** False takes the cards off the screen, as a tab switch does. */
    private var shown by mutableStateOf(true)

    private fun setContent(
        settings: AppSettings = AppSettings(),
        cards: LazyListScope.(SettingsDraft, SettingsCardIndexRecorder, (SettingsDraft) -> Unit) -> Unit,
    ) {
        latest = SettingsDraft.from(settings, ResourceManagerState())
        composeRule.setContent {
            var draft by remember { mutableStateOf(latest) }
            val recorder = remember { SettingsCardIndexRecorder() }
            AnkiMinerTheme {
                LazyColumn(Modifier.testTag(SettingsCategoryTestTags.LIST)) {
                    if (shown) {
                        cards(draft, recorder) {
                            draft = it
                            latest = it
                        }
                    }
                }
            }
        }
    }

    private fun setWordFilters(settings: AppSettings = AppSettings()) =
        setContent(settings) { draft, recorder, onChange ->
            recorder.begin(SettingsCategory.WORD_FILTERS)
            wordFilterOptions(draft, ResourceManagerState(), emptyList(), recorder, onChange)
        }

    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToNode(matcher)
    }

    /**
     * Desktop raises the other end; here it happens once the user leaves the field, by moving focus
     * on or by the field leaving the screen while it still has focus (Back closes the keyboard but
     * keeps focus, so a tab switch after it reports no focus change).
     */
    @Test
    fun leavingAMinimumAboveTheMaximumRaisesTheMaximum() {
        setWordFilters(AppSettings(maxFrequencyRank = 5000))

        scrollTo(hasTestTag(SettingsCategoryTestTags.MAX_FREQUENCY))
        composeRule.onNodeWithTag(SettingsCategoryTestTags.MIN_FREQUENCY).performClick()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.MIN_FREQUENCY)
            .performTextReplacement("8000")
        // Still typing: the maximum has not moved yet.
        composeRule.runOnIdle { assertEquals("5000", latest.maxFrequency) }

        composeRule.onNodeWithTag(SettingsCategoryTestTags.MAX_FREQUENCY).performClick()

        composeRule.runOnIdle {
            assertEquals("8000", latest.minFrequency)
            assertEquals("8000", latest.maxFrequency)
        }

        composeRule.onNodeWithTag(SettingsCategoryTestTags.MIN_FREQUENCY).performClick()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.MIN_FREQUENCY)
            .performTextReplacement("9000")
        composeRule.runOnIdle { shown = false }

        composeRule.runOnIdle {
            assertEquals("9000", latest.minFrequency)
            assertEquals("9000", latest.maxFrequency)
        }
    }

    @Test
    fun keepingUnrankedWordsIsDisabledUntilABandIsSet() {
        setWordFilters()
        val keepUnranked = "Include words missing from the frequency list"

        scrollTo(hasText(keepUnranked))
        composeRule.onNodeWithText(keepUnranked).assertIsNotEnabled()

        scrollTo(hasTestTag(SettingsCategoryTestTags.MAX_FREQUENCY))
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.MAX_FREQUENCY)
            .performTextReplacement("5000")

        scrollTo(hasText(keepUnranked))
        composeRule.onNodeWithText(keepUnranked).assertIsEnabled()
    }

    /** Android-only, so nothing but this row ever writes it. */
    @Test
    fun theSecondarySubtitleRowWritesItsField() {
        setContent { draft, recorder, onChange ->
            recorder.begin(SettingsCategory.SENTENCES)
            sentencesSettings(draft, recorder, onChange)
        }

        scrollTo(hasText("Secondary subtitles"))
        composeRule.onNodeWithText("Secondary subtitles").performClick()

        composeRule.runOnIdle { assertTrue(latest.secondarySubtitleEnabled) }
    }
}
