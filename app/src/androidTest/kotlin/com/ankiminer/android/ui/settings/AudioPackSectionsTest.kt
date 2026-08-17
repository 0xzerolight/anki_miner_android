package com.ankiminer.android.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The audio panel end to end: real [audioPanelRows] output inside a real [ResourceChainPanel].
 *
 * The per-pack Remove buttons this file used to count are gone — one toolbar action addresses the
 * selected row instead — so the contract under test is that selecting a pack arms that action and
 * that it reports the pack id the caller deletes by.
 */
class AudioPackSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingAPackArmsTheRemoveActionAndReportsItsId() {
        val removed = mutableListOf<String>()
        composeRule.setContent {
            AnkiMinerTheme {
                ResourceChainPanel(
                    heading = "Active audio sources",
                    explanation = "Tried top to bottom.",
                    rows =
                        audioPanelRows(
                            chain =
                                listOf(
                                    ResourceChainSelection("nhk16"),
                                    ResourceChainSelection("broken"),
                                ),
                            installed =
                                listOf(
                                    installedPack("nhk16", usable = true),
                                    installedPack("broken", usable = false),
                                ),
                            strings = ROW_STRINGS,
                            onChainChange = {},
                        ),
                    emptyMessage = "No packs",
                    onMove = { _, _ -> },
                    onRemove = removed::add,
                    addPrimary = ResourcePanelAction("Add audio pack…") {},
                    busy = false,
                )
            }
        }

        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()
        selectRow("broken")
        composeRule
            .onNodeWithTag(ResourcePanelTestTags.REMOVE)
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("broken"), removed)
    }

    /**
     * Clicks the row's own selectable area, as [ResourceChainPanelTest] does. The node centre falls
     * on the enable checkbox once the title column is narrower than half the row, which is the case
     * on a 320dp screen, so selection is driven from the title column instead.
     */
    private fun selectRow(id: String) {
        composeRule.onNodeWithTag(ResourcePanelTestTags.row(id)).performTouchInput {
            click(Offset(width * 0.1f, height / 2f))
        }
    }

    private fun installedPack(
        packId: String,
        usable: Boolean,
    ) = InstalledAudioPack(
        packId = packId,
        sourceName = packId.replaceFirstChar(Char::uppercase),
        format = "nhk16",
        entryCount = if (usable) 100 else 0,
        contentAvailable = usable,
    )

    private companion object {
        val ROW_STRINGS =
            ResourceRowStrings(
                entries = { count -> "$count entries" },
                notInChain = "Not in priority list",
                missingWarning = "Missing - re-import",
                repairWarning = "Re-import to repair",
            )
    }
}
