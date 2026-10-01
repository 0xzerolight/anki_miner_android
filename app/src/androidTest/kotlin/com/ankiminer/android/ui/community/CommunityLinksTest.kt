package com.ankiminer.android.ui.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test
    fun starAndDiscordShareOneRowAtLargeTextOnASmallPhone() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(baseDensity, 2f)) {
                AnkiMinerTheme {
                    Box(Modifier.requiredWidth(288.dp)) { CommunityLinks() }
                }
            }
        }

        val star = composeRule.onNodeWithTag(CommunityLinksTestTags.STAR).getUnclippedBoundsInRoot()
        val discord = composeRule.onNodeWithTag(CommunityLinksTestTags.DISCORD).getUnclippedBoundsInRoot()
        assertEquals(star.top, discord.top)
        assertEquals(star.bottom, discord.bottom)
    }
}
