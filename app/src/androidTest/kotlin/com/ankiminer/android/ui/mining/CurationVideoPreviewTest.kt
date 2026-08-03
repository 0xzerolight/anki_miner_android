package com.ankiminer.android.ui.mining

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.player.FakeCurationPreviewPlayer
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Rule
import org.junit.Test

class CurationVideoPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun audioOnlyShowsTranscriptSurfaceWithoutVideoFrame() {
        setPreview(audioOnly = true)

        scrollTo(CurationPlayerTestTags.SURFACE)
        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertIsDisplayed()
        scrollTo(CurationPlayerTestTags.SURFACE)
        composeRule.onNodeWithTag(CurationPlayerTestTags.VIDEO_FRAME).assertDoesNotExist()
        scrollTo(CurationPlayerTestTags.OVERLAY)
        composeRule.onNodeWithTag(CurationPlayerTestTags.OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithText(CUE_TEXT).performScrollTo().assertIsDisplayed()
        scrollTo(CurationPlayerTestTags.PLAY_PAUSE)
        composeRule.onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE).assertIsDisplayed()
        scrollTo(CurationPlayerTestTags.PLAY_PAUSE)
        composeRule.onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE).assertIsEnabled()
    }

    @Test
    fun videoShowsVideoFrameByDefault() {
        setPreview()

        scrollTo(CurationPlayerTestTags.VIDEO_FRAME)
        composeRule.onNodeWithTag(CurationPlayerTestTags.VIDEO_FRAME).assertIsDisplayed()
    }

    @Test
    fun collapsingAudioOnlyPreviewHidesSurface() {
        setPreview(audioOnly = true)

        composeRule
            .onNodeWithTag(CurationPlayerTestTags.COLLAPSE)
            .performScrollTo()
            .performClick()
        scrollTo(CurationPlayerTestTags.COLLAPSE)
        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertDoesNotExist()
    }

    private fun setPreview(audioOnly: Boolean = false) {
        val fake = FakeCurationPreviewPlayer()
        composeRule.setContent {
            AnkiMinerTheme {
                var collapsed by remember { mutableStateOf(false) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag(SCROLL_ROOT),
                ) {
                    Spacer(Modifier.height(640.dp))
                    CurationVideoPreview(
                        player = fake,
                        videoUri = VIDEO_URI,
                        cues = listOf(SubtitleCue(0.0, 2.0, CUE_TEXT)),
                        overlayOffsetSeconds = 0.0,
                        collapsed = collapsed,
                        onToggleCollapsed = { collapsed = !collapsed },
                        audioOnly = audioOnly,
                    )
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo()
    }

    private companion object {
        const val SCROLL_ROOT = "curation_preview_test_scroll_root"
        const val CUE_TEXT = "猫だ。"
        val VIDEO_URI: Uri = Uri.parse("content://test/episode.media")
    }
}
