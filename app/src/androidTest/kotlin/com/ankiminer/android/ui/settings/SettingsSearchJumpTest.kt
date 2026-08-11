package com.ankiminer.android.ui.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

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

    @Test
    fun pendingProductionJumpSurvivesStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val katakanaLabel = context.getString(R.string.settings_exclude_katakana)
        val restorationTester = StateRestorationTester(composeRule)
        var resolutions = 0
        restorationTester.setContent {
            AnkiMinerTheme {
                SettingsSearchJumpFixture(onJumpIndexResolved = { resolutions += 1 })
            }
        }

        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        composeRule.onNodeWithTag(SettingsCategoryTestTags.SEARCH).performTextInput("katakana")
        list.performScrollToNode(hasText(katakanaLabel))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(katakanaLabel).performClick()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.waitUntil(timeoutMillis = 5_000L) { resolutions == 1 }

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.waitUntil(timeoutMillis = 5_000L) { resolutions == 2 }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun settingsResetConfirmationSurvivesStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val confirmation =
            context.getString(R.string.settings_restore_mining_defaults_confirmation)
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            AnkiMinerTheme {
                SettingsResetConfirmationHost(onRestoreMiningDefaults = { true }) { request ->
                    TextButton(
                        onClick = { request(SettingsResetAction.RESTORE_MINING_DEFAULTS) },
                    ) {
                        Text("Request reset")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Request reset").performClick()
        composeRule.onNodeWithText(confirmation).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(confirmation).assertIsDisplayed()
    }

    @Test
    fun missingProductionJumpTargetDoesNotScrollToUnrelatedFirstCard() {
        val entry =
            ResolvedSettingsEntry(
                id = "filtering.missing",
                category = SettingsCategory.FILTERING,
                cardKey = "missing-card",
                title = "Missing target",
                breadcrumb = "Filtering",
                haystack = listOf("missing target", "filtering"),
            )
        var resolved = false
        var filteringListState: LazyListState? = null
        composeRule.setContent {
            var selectedCategory by rememberSaveable {
                mutableStateOf(SettingsCategory.FILTERING)
            }
            val listStates = rememberSettingsCategoryListStates()
            filteringListState = listStates.getValue(SettingsCategory.FILTERING)
            val recorder = remember { SettingsCardIndexRecorder() }
            SettingsSearchJumpHandler(
                entries = listOf(entry),
                recorder = recorder,
                listStates = listStates,
                onSelectedCategory = { selectedCategory = it },
                onClearQuery = {},
                onJumpIndexResolved = { resolved = true },
            ) { onResultChosen ->
                LaunchedEffect(entry) { onResultChosen(entry) }
                SettingsCategoryLayout(
                    selectedCategory = selectedCategory,
                    onSelectedCategory = { selectedCategory = it },
                    recorder = recorder,
                    listStates = listStates,
                    header = {},
                ) { category ->
                    settingsCard(category, recorder, "unrelated-card") {
                        Text("Unrelated card")
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { resolved }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(0, requireNotNull(filteringListState).firstVisibleItemIndex)
        }
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
    SettingsSearchJumpHandler(
        entries = entries,
        recorder = recorder,
        listStates = listStates,
        onSelectedCategory = { selectedCategory = it },
        onClearQuery = { searchQuery = "" },
        onJumpIndexResolved = onJumpIndexResolved,
    ) { onResultChosen ->
        SettingsCategoryLayout(
            selectedCategory = selectedCategory,
            onSelectedCategory = { selectedCategory = it },
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = searchSettings(entries, searchQuery),
            onResultChosen = onResultChosen,
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
}
