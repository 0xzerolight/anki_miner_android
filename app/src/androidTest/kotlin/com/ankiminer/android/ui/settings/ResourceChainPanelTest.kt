package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

        composeRule
            .onNodeWithTag(ResourcePanelTestTags.EMPTY)
            .assertTextEquals("No sources are installed.")
        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()
    }

    @Test
    fun addWithoutAMenuFiresTheAddActionDirectly() {
        var added = 0
        composeRule.setContent {
            AnkiMinerTheme {
                ResourceChainPanel(
                    heading = "Dictionary priority",
                    explanation = "Higher sources win when several define the same word.",
                    rows = chain,
                    emptyMessage = "No sources are installed.",
                    onMove = { _, _ -> },
                    onRemove = {},
                    addPrimary = ResourcePanelAction("Add") { added++ },
                    busy = false,
                )
            }
        }

        composeRule.onNodeWithTag(ResourcePanelTestTags.ADD).performClick()

        assertEquals(1, added)
    }

    @Test
    fun busyDisablesEveryRowAndToolbarControl() {
        setPanel(
            listOf(
                resourceRow(
                    id = "alpha",
                    title = "Alpha dictionary",
                    onToggle = {},
                    quietAction = ResourcePanelAction("Repair") {},
                ),
                resourceRow("beta", "Beta dictionary", onToggle = {}),
            ),
            busy = true,
        )

        selectRow("alpha")

        composeRule.onNodeWithTag(ResourcePanelTestTags.toggle("alpha")).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.QUIET_ACTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveDown("alpha")).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.moveUp("beta")).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(ResourcePanelTestTags.REMOVE).assertIsNotEnabled()
    }

    @Test
    fun quietActionRendersInTheToolbarOnlyForTheSelectedRow() {
        var repaired = 0
        setPanel(
            listOf(
                resourceRow(
                    id = "alpha",
                    title = "Alpha dictionary",
                    metadata = emptyList(),
                    quietAction = ResourcePanelAction("Repair") { repaired++ },
                ),
            ),
        )

        composeRule.onNodeWithTag(ResourcePanelTestTags.QUIET_ACTION).assertDoesNotExist()

        selectRow("alpha")
        composeRule.onNodeWithTag(ResourcePanelTestTags.QUIET_ACTION).performClick()

        assertEquals(1, repaired)
    }

    /**
     * The reason the action sits in the toolbar. On the CI emulator's 320dp screen a button beside
     * the title left it around 40dp - three characters before the ellipsis - because the title
     * column is the row's only flexible child.
     */
    @Test
    fun theSelectedRowKeepsItsTitleWidthOnANarrowScreen() {
        val title = "A dictionary whose name is far too long for a phone row"
        setPanel(
            listOf(
                resourceRow(
                    id = "alpha",
                    title = title,
                    quietAction = ResourcePanelAction("Replace\u2026") {},
                ),
            ),
            width = 320.dp,
        )

        selectRow("alpha")

        composeRule
            .onNodeWithText(title, useUnmergedTree = true)
            .assertWidthIsAtLeast(100.dp)
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

    @Test
    fun aNullHeadingLeavesTheRestOfThePanelIntact() {
        setPanel(chain, heading = null)

        composeRule.onNodeWithText("Dictionary priority").assertDoesNotExist()
        composeRule
            .onNodeWithText("Higher sources win when several define the same word.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ResourcePanelTestTags.row("alpha")).assertIsDisplayed()
        composeRule.onNodeWithTag(ResourcePanelTestTags.ADD).assertIsEnabled()
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
        quietAction: ResourcePanelAction? = null,
    ) = ResourceRowSpec(
        id = id,
        title = title,
        metadata = metadata,
        enabled = enabled,
        onToggle = onToggle,
        warning = warning,
        movable = movable,
        removable = removable,
        quietAction = quietAction,
    )

    /** [width] pins the surface; null lets the panel take whatever the test device offers. */
    private fun setPanel(
        rows: List<ResourceRowSpec>,
        addMenu: List<ResourcePanelAction> = emptyList(),
        onMove: (String, Int) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = {},
        busy: Boolean = false,
        heading: String? = "Dictionary priority",
        width: Dp? = null,
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                Box(if (width == null) Modifier else Modifier.requiredWidth(width)) {
                    ResourceChainPanel(
                        heading = heading,
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
}
