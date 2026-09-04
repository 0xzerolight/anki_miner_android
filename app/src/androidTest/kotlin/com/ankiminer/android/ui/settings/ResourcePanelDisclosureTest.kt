package com.ankiminer.android.ui.settings

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResourcePanelDisclosureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val rows =
        listOf(
            row("alpha", "Alpha pack", enabled = true),
            row("beta", "Beta pack", enabled = true),
            row("gamma", "Gamma pack", enabled = false),
        )

    private val header = context.getString(R.string.settings_disclosure_summary, HEADING, 2, 3)

    @Test
    fun aPanelArrivesClosedAndSaysHowManySourcesAreActive() {
        setDisclosure(SettingsPanelExpansion())

        composeRule.onNodeWithText(header).assertIsDisplayed()
        composeRule.onNodeWithText(PANEL_BODY).assertDoesNotExist()
    }

    @Test
    fun tappingTheHeaderOpensOnlyThatPanel() {
        val expansion = SettingsPanelExpansion()
        setDisclosure(expansion)

        composeRule.onNodeWithText(header).performClick()
        composeRule.waitForIdle()

        assertTrue(expansion.isExpanded(AUDIO_KEY))
        assertFalse(expansion.isExpanded("frequency-sources"))
        composeRule.onNodeWithText(PANEL_BODY).assertIsDisplayed()
    }

    @Test
    fun aDeepLinkedPanelIsAlreadyOpenWhenItIsScrolledTo() {
        val expansion = SettingsPanelExpansion()
        expansion.expand(AUDIO_KEY)

        setDisclosure(expansion)

        composeRule.onNodeWithText(PANEL_BODY).assertIsDisplayed()
    }

    @Test
    fun aFailureReportedForThePanelOpensIt() {
        val expansion = SettingsPanelExpansion()

        setDisclosure(expansion, failed = true)

        composeRule.waitForIdle()
        assertTrue(expansion.isExpanded(AUDIO_KEY))
        composeRule.onNodeWithText(PANEL_BODY).assertIsDisplayed()
    }

    @Test
    fun anEmptyPanelStillReportsItsCounts() {
        composeRule.setContent {
            AnkiMinerTheme {
                ResourcePanelDisclosure(
                    cardKey = AUDIO_KEY,
                    heading = HEADING,
                    rows = emptyList(),
                    expansion = SettingsPanelExpansion(),
                    failed = false,
                ) {
                    Text(PANEL_BODY)
                }
            }
        }

        val empty = context.getString(R.string.settings_disclosure_summary, HEADING, 0, 0)
        composeRule.onNodeWithText(empty).assertIsDisplayed()
    }

    @Test
    fun closingAPanelAgainDropsItFromTheOpenSet() {
        val expansion = SettingsPanelExpansion()
        expansion.expand(AUDIO_KEY)
        setDisclosure(expansion)

        composeRule.onNodeWithText(header).performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), expansion.expandedKeys())
        composeRule.onNodeWithText(PANEL_BODY).assertDoesNotExist()
    }

    private fun setDisclosure(
        expansion: SettingsPanelExpansion,
        failed: Boolean = false,
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                ResourcePanelDisclosure(
                    cardKey = AUDIO_KEY,
                    heading = HEADING,
                    rows = rows,
                    expansion = expansion,
                    failed = failed,
                ) {
                    Text(PANEL_BODY)
                }
            }
        }
    }

    private fun row(
        id: String,
        title: String,
        enabled: Boolean,
    ) = ResourceRowSpec(
        id = id,
        title = title,
        metadata = emptyList(),
        enabled = enabled,
        onToggle = {},
    )

    private companion object {
        const val AUDIO_KEY = "audio-sources"
        const val HEADING = "Active audio sources"
        const val PANEL_BODY = "panel body"
    }
}
