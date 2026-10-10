package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsSearchJumpTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchingFromAnotherCategoryJumpsToTheOwningCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scriptTypeLabel = context.getString(R.string.settings_script_type)
        var resolvedJumpIndex: Int? = null

        composeRule.setContent {
            AnkiMinerTheme {
                SettingsSearchJumpFixture(onJumpIndexResolved = { resolvedJumpIndex = it })
            }
        }

        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        composeRule.onNodeWithTag(SettingsCategoryTestTags.SEARCH).performTextInput("script")
        list.performScrollToNode(hasText(scriptTypeLabel))
        composeRule.onNodeWithText(scriptTypeLabel).performClick()

        composeRule.runOnIdle {
            assertEquals(SettingsCardIndexRecorder.FIRST_CARD_INDEX, resolvedJumpIndex)
        }
        list.performScrollToNode(hasText(scriptTypeLabel))
        composeRule.onNodeWithText(scriptTypeLabel).assertIsDisplayed()
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        // EditableText, not assertTextEquals: a text field's merged Text also carries its
        // placeholder, so assertTextEquals("") fails against an empty field hinting
        // "Search settings".
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
    fun queryHidesTabsAndHeaderAndAJumpLandsBelowTheStickyStrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scriptTypeLabel = context.getString(R.string.settings_script_type)
        val ankiTab = context.getString(SettingsCategory.ANKI.label)
        composeRule.setContent {
            AnkiMinerTheme {
                SettingsSearchJumpFixture(fillerCards = 30, header = { Text("header-marker") })
            }
        }
        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        composeRule.onNodeWithText("header-marker").assertIsDisplayed()
        // Exists, not displayed: the fixture opens on Diagnostics, so the strip has scrolled the
        // first tab out of view.
        composeRule.onNodeWithText(ankiTab).assertExists()

        composeRule.onNodeWithTag(SettingsCategoryTestTags.SEARCH).performTextInput("script")
        composeRule.onNodeWithText("header-marker").assertDoesNotExist()
        composeRule.onNodeWithText(ankiTab).assertDoesNotExist()

        list.performScrollToNode(hasText(scriptTypeLabel))
        composeRule.onNodeWithText(scriptTypeLabel).performClick()
        composeRule.waitForIdle()

        val strip = composeRule.onNodeWithTag(SettingsCategoryTestTags.STICKY_HEADER).getUnclippedBoundsInRoot()
        val target = composeRule.onNodeWithText(scriptTypeLabel).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("target top ${target.top} sits under the strip ending at ${strip.bottom}", target.top >= strip.bottom)
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
        val scriptTypeLabel = context.getString(R.string.settings_script_type)
        val restorationTester = StateRestorationTester(composeRule)
        var resolutions = 0
        restorationTester.setContent {
            AnkiMinerTheme {
                SettingsSearchJumpFixture(onJumpIndexResolved = { resolutions += 1 })
            }
        }

        val list = composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST)
        list.performScrollToNode(hasTestTag(SettingsCategoryTestTags.SEARCH))
        composeRule.onNodeWithTag(SettingsCategoryTestTags.SEARCH).performTextInput("script")
        list.performScrollToNode(hasText(scriptTypeLabel))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(scriptTypeLabel).performClick()

        // Restore while the jump is still pending: the handler clears pendingJumpId as soon as a
        // jump resolves, so a resolved jump has nothing left to survive.
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000L) { resolutions >= 1 }

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
                id = "word_filters.missing",
                category = SettingsCategory.WORD_FILTERS,
                cardKey = "missing-card",
                title = "Missing target",
                breadcrumb = "Word filters",
                haystack = listOf("missing target", "word filters"),
            )
        var resolved = false
        var filteringListState: LazyListState? = null
        composeRule.setContent {
            var selectedCategory by rememberSaveable {
                mutableStateOf(SettingsCategory.WORD_FILTERS)
            }
            val listStates = rememberSettingsCategoryListStates()
            filteringListState = listStates.getValue(SettingsCategory.WORD_FILTERS)
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
private fun SettingsSearchJumpFixture(
    onJumpIndexResolved: (Int?) -> Unit = {},
    fillerCards: Int = 0,
    header: @Composable () -> Unit = {},
) {
    var stickyPx by remember { mutableIntStateOf(0) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.DIAGNOSTICS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listStates = rememberSettingsCategoryListStates()
    val recorder =
        remember {
            SettingsCardIndexRecorder().apply {
                begin(SettingsCategory.WORD_FILTERS)
                record(SettingsCategory.WORD_FILTERS, "stale-leading-card")
                record(SettingsCategory.WORD_FILTERS, "filtering-options")
            }
        }
    val title = stringResource(R.string.settings_script_type)
    val breadcrumb = stringResource(SettingsCategory.WORD_FILTERS.label)
    val entries =
        listOf(
            ResolvedSettingsEntry(
                id = "word_filters.script_type",
                category = SettingsCategory.WORD_FILTERS,
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
        stickyHeaderPx = { stickyPx },
    ) { onResultChosen ->
        SettingsCategoryLayout(
            selectedCategory = selectedCategory,
            onSelectedCategory = { selectedCategory = it },
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = searchSettings(entries, searchQuery),
            onResultChosen = onResultChosen,
            onStickyHeaderHeightChange = { stickyPx = it },
            recorder = recorder,
            listStates = listStates,
            header = header,
        ) { category ->
            if (category == SettingsCategory.WORD_FILTERS) {
                repeat(fillerCards) { index ->
                    settingsCard(category, recorder, "filler-$index") {
                        Text("Filler $index", Modifier.height(120.dp))
                    }
                }
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
