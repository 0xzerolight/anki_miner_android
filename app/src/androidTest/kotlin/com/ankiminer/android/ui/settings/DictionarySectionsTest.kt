package com.ankiminer.android.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Rule
import org.junit.Test

class DictionarySectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dictionaryLookupTitleIsHeading() {
        composeRule.setContent {
            AnkiMinerTheme {
                DictionaryLookupCard(
                    state = SetupUiState(),
                    onTermChanged = {},
                    onSelectSlot = {},
                    onLookup = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Offline dictionary test")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }
}
