package com.ankiminer.android.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val TEST_HIGHLIGHT_MILLIS = 1_200L

class SettingsSearchJumpTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchingFromAnotherCategoryJumpsToTheOwningCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val katakanaLabel = context.getString(R.string.settings_exclude_katakana)
        var resolvedJumpIndex: Int? = null

        composeRule.setContent {
            AnkiMinerTheme {
                SettingsSearchJumpFixture(onJumpIndexResolved = { resolvedJumpIndex = it })
            }
        }

        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        composeRule.onNodeWithTag(SettingsCategoryTestTags.SEARCH).performTextInput("katakana")
        list.performScrollToNode(hasText(katakanaLabel))
        composeRule.onNodeWithText(katakanaLabel).performClick()

        composeRule.runOnIdle {
            assertEquals(SettingsCardIndexRecorder.FIRST_CARD_INDEX, resolvedJumpIndex)
        }
        list.performScrollToNode(hasText(katakanaLabel))
        composeRule.onNodeWithText(katakanaLabel).assertIsDisplayed()
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        // EditableText, not assertTextEquals: a text field's merged Text also carries its label,
        // so assertTextEquals("") fails against an empty field labelled "Search settings".
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.SEARCH)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )
    }

    @Test
    fun aQueryThatMatchesNothingShowsTheEmptyLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val noResults = context.getString(R.string.settings_search_no_results)

        composeRule.setContent {
            AnkiMinerTheme { SettingsSearchJumpFixture() }
        }

        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.SEARCH)
            .performTextInput("no-setting-can-match-this-query")
        list.performScrollToNode(hasText(noResults))
        composeRule.onNodeWithText(noResults).assertIsDisplayed()
    }
}

@Composable
private fun SettingsSearchJumpFixture(onJumpIndexResolved: (Int?) -> Unit = {}) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.DIAGNOSTICS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listStates = rememberSettingsCategoryListStates()
    val recorder =
        remember {
            SettingsCardIndexRecorder().apply {
                begin(SettingsCategory.FILTERING)
                record(SettingsCategory.FILTERING, "stale-leading-card")
                record(SettingsCategory.FILTERING, "filtering-options")
            }
        }
    val title = stringResource(R.string.settings_exclude_katakana)
    val breadcrumb = stringResource(SettingsCategory.FILTERING.label)
    val entries =
        listOf(
            ResolvedSettingsEntry(
                id = "filtering.exclude_katakana",
                category = SettingsCategory.FILTERING,
                cardKey = "filtering-options",
                title = title,
                breadcrumb = breadcrumb,
                haystack = listOf(normalizeSettingsText(title), normalizeSettingsText(breadcrumb)),
            ),
        )
    var pendingJump by remember { mutableStateOf<ResolvedSettingsEntry?>(null) }

    LaunchedEffect(pendingJump) {
        val entry = pendingJump ?: return@LaunchedEffect
        recorder.begin(entry.category)
        selectedCategory = entry.category
        searchQuery = ""
        val index =
            withTimeoutOrNull(2_000) {
                snapshotFlow { recorder.indexOf(entry.category, entry.cardKey) }
                    .filterNotNull()
                    .first()
            }
        onJumpIndexResolved(index)
        listStates.getValue(entry.category)
            .scrollToItem(index ?: SettingsCardIndexRecorder.FIRST_CARD_INDEX)
        recorder.highlightedKey = entry.cardKey
        delay(TEST_HIGHLIGHT_MILLIS)
        recorder.highlightedKey = null
        pendingJump = null
    }

    SettingsCategoryLayout(
        selectedCategory = selectedCategory,
        onSelectedCategory = { selectedCategory = it },
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        results = searchSettings(entries, searchQuery),
        onResultChosen = { pendingJump = it },
        recorder = recorder,
        listStates = listStates,
        header = {},
    ) { category ->
        if (category == SettingsCategory.FILTERING) {
            settingsCard(category, recorder, "filtering-options") {
                Text(title)
            }
        } else {
            settingsCard(category, recorder, "${category.name}-fixture") {
                Text(stringResource(category.label))
            }
        }
    }
}
