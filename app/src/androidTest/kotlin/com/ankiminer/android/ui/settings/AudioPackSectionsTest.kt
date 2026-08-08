package com.ankiminer.android.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioPackSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun audioPackImportCardOffersRemoveForEveryInstalledPack() {
        val removed = mutableListOf<String>()
        composeRule.setContent {
            AnkiMinerTheme {
                AudioPackImportCard(
                    state =
                        SetupUiState(
                            // Default startup readiness is PENDING, which makes the state busy and
                            // renders every Remove disabled; a click on one would be a silent no-op.
                            resourceStartup = ResourceStartupReadiness.READY,
                            audioPacks =
                                listOf(
                                    installedPack("nhk16", usable = true),
                                    installedPack("broken", usable = false),
                                ),
                        ),
                    onImport = {},
                    onRemove = removed::add,
                )
            }
        }

        // The card sits in a plain OutlinedCard with no scrollable ancestor, so
        // performScrollTo() would throw here (09af5e0a) -- click nodes directly.
        composeRule.onAllNodesWithText("Remove").assertCountEquals(2)
        composeRule.onAllNodesWithText("Remove")[0].performClick()

        assertEquals(listOf("nhk16"), removed)
    }

    private fun installedPack(packId: String, usable: Boolean) =
        InstalledAudioPack(
            packId = packId,
            sourceName = packId.replaceFirstChar(Char::uppercase),
            format = "nhk16",
            entryCount = if (usable) 100 else 0,
            contentAvailable = usable,
        )
}
