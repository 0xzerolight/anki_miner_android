package com.ankiminer.android.ui.community

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CommunityLinksTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun starAndDiscordOpenTheProjectLinks() {
        val opened = mutableListOf<String>()
        val recordingUriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }

        composeRule.setContent {
            AnkiMinerTheme {
                CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                    CommunityLinks()
                }
            }
        }

        composeRule.onNodeWithTag(CommunityLinksTestTags.STAR).performClick()
        composeRule.onNodeWithTag(CommunityLinksTestTags.DISCORD).performClick()

        composeRule.runOnIdle {
            // Literals, not AppLinks: the point is to catch a typo in the constants themselves.
            assertEquals(
                listOf(
                    "https://github.com/0xzerolight/anki_miner_android",
                    "https://discord.com/invite/aDtQyZzUVP",
                ),
                opened,
            )
        }
    }
}
