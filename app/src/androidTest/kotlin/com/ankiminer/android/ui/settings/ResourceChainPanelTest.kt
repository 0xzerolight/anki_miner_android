package com.ankiminer.android.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ResourceChainPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chain =
        listOf(
            resourceRow("alpha", "Alpha dictionary"),
            resourceRow("beta", "Beta dictionary"),
            resourceRow("gamma", "Gamma dictionary"),
        )

    private val pinnedChain =
        listOf(
            resourceRow("alpha", "Alpha dictionary"),
            resourceRow("jisho", "Jisho", movable = false, removable = false),
        )

    @Test
    fun endsOfTheChainDisableTheirMoveArrows() {
        setPanel(chain)

        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("alpha")).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("alpha")).assertIsEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("gamma")).assertIsEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("gamma")).assertIsNotEnabled()
    }

    @Test
    fun movingARowReportsIdAndDelta() {
        val moves = mutableListOf<Pair<String, Int>>()
        setPanel(chain, onMove = { id, delta -> moves += id to delta })

        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("gamma")).performClick()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("alpha")).performClick()

        assertEquals(listOf("gamma" to -1, "alpha" to 1), moves)
    }

    @Test
    fun pinnedRowOmitsMoveArrows() {
        setPanel(pinnedChain)

        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("jisho")).assertDoesNotExist()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("jisho")).assertDoesNotExist()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("alpha")).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("alpha")).assertIsNotEnabled()
    }

    @Test
    fun removeStaysDisabledForAPinnedSelection() {
        var removed: String? = null
        setPanel(pinnedChain, onRemove = { removed = it })

        selectRow("jisho")

        composeRule.onNodeWithTag(ResourcePanelTestTags.row("jisho")).assertIsSelected()
        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()
        assertNull(removed)
    }

    @Test
    fun removeIsDisabledUntilARowIsSelected() {
        setPanel(chain)

        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()

        selectRow("beta")

        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsEnabled()
    }

    @Test
    fun removeReportsTheSelectedRow() {
        var removed: String? = null
        setPanel(chain, onRemove = { removed = it })

        selectRow("beta")
        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).performClick()

        assertEquals("beta", removed)
    }

    @Test
    fun enableCheckboxIsNamedAfterItsRow() {
        val toggles = mutableListOf<Boolean>()
        setPanel(
            listOf(
                resourceRow("alpha", "Alpha dictionary", onToggle = { toggles += it }),
            ),
        )

        composeRule
            .onNodeWithContentDescription("Enable Alpha dictionary", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ResourcePanelTestTags.toggle("alpha")).performClick()

        assertEquals(listOf(false), toggles)
    }

    @Test
    fun addMenuOpensItsOptions() {
        var chosen: String? = null
        setPanel(
            chain,
            addMenu =
                listOf(
                    ResourcePanelAction("From catalog") { chosen = "catalog" },
                    ResourcePanelAction("From file") { chosen = "file" },
                ),
        )

        composeRule.onNodeWithText("From catalog").assertDoesNotExist()
        composeRule.onNodeWithTag(ResourcePanelTestTags.ADD).performClick()
        composeRule.onNodeWithText("From catalog").assertIsDisplayed()
        composeRule.onNodeWithText("From file").performClick()

        assertEquals("file", chosen)
    }

    @Test
    fun warningRendersOnTheMetadataLine() {
        setPanel(
            listOf(
                resourceRow(
                    id = "alpha",
                    title = "Alpha dictionary",
                    metadata = listOf("12,345 entries"),
                    warning = "Files are missing",
                ),
            ),
        )

        composeRule.onNodeWithText("Files are missing", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("12,345 entries", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyPanelShowsItsEmptyMessage() {
        setPanel(emptyList())

        composeRule.onNodeWithText("No sources are installed.").assertIsDisplayed()
        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()
    }

    /**
     * Clicks the row's own selectable area. The node centre falls on the enable checkbox, which is
     * a separate target, so selection is driven from the title column instead.
     */
    private fun selectRow(id: String) {
        composeRule.onNodeWithTag(ResourcePanelTestTags.row(id)).performTouchInput {
            click(Offset(width * 0.1f, height / 2f))
        }
    }

    private fun resourceRow(
        id: String,
        title: String,
        metadata: List<String> = listOf("Local"),
        enabled: Boolean = true,
        onToggle: ((Boolean) -> Unit)? = null,
        warning: String? = null,
        movable: Boolean = true,
        removable: Boolean = true,
    ) = ResourceRowSpec(
        id = id,
        title = title,
        metadata = metadata,
        enabled = enabled,
        onToggle = onToggle,
        warning = warning,
        movable = movable,
        removable = removable,
    )

    private fun setPanel(
        rows: List<ResourceRowSpec>,
        addMenu: List<ResourcePanelAction> = emptyList(),
        onMove: (String, Int) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = {},
        busy: Boolean = false,
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                ResourceChainPanel(
                    heading = "Dictionary priority",
                    explanation = "Higher sources win when several define the same word.",
                    rows = rows,
                    emptyMessage = "No sources are installed.",
                    onMove = onMove,
                    onRemove = onRemove,
                    addPrimary = ResourcePanelAction("Add") {},
                    addMenu = addMenu,
                    busy = busy,
                )
            }
        }
    }
}
