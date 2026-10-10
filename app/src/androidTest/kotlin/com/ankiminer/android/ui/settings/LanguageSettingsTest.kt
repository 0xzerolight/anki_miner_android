package com.ankiminer.android.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.engine.ContentDirection
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.LocalMiningContentStyle
import com.ankiminer.android.ui.mining.MiningContentStyle
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Language tab and the content style a language switch brings.
 *
 * Every assertion scrolls first: the CI emulator is 320x640 @ 160dpi, so a row that is on screen on
 * a phone sits below the fold there.
 */
class LanguageSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun profile(
        code: String,
        displayName: String,
        englishName: String,
        reason: LanguageUnavailableReason? = null,
        direction: ContentDirection = ContentDirection.LTR,
        capabilities: Set<String> = emptySet(),
    ) = LanguageProfileInfo(
        code = code,
        displayName = displayName,
        englishName = englishName,
        unavailableReason = reason,
        scriptVariants = emptyList(),
        contentDirection = direction,
        contentLanguage = code,
        speechLanguage = code,
        audioTrackCodes = listOf(code),
        capabilities = capabilities,
        requiresUnidic = code == "ja",
        scopedDefaults = emptyMap(),
        extraCardFields = emptyList(),
    )

    private val japanese = profile("ja", "日本語", "Japanese", capabilities = setOf("pitch"))
    private val hebrew = profile("he", "עברית", "Hebrew", direction = ContentDirection.RTL)
    private val arabic =
        profile("ar", "العربية", "Arabic", LanguageUnavailableReason.DATA_REQUIRED, ContentDirection.RTL)
    private val thai = profile("th", "ไทย", "Thai", LanguageUnavailableReason.UNSUPPORTED)

    private val switched = mutableListOf<String>()
    private val downloaded = mutableListOf<String>()

    private fun setLanguageTab(state: LanguageSettingsState) {
        composeRule.setContent {
            val recorder = remember { SettingsCardIndexRecorder() }
            AnkiMinerTheme {
                LazyColumn(Modifier.testTag(SettingsCategoryTestTags.LIST)) {
                    recorder.begin(SettingsCategory.LANGUAGE)
                    languageSettings(
                        language = state,
                        draft = SettingsDraft.from(AppSettings(), ResourceManagerState()),
                        recorder = recorder,
                        onDraftChange = {},
                        actions =
                            LanguageSettingsActions(
                                onSwitch = { switched += it },
                                onDownloadAndSwitch = { downloaded += it },
                            ),
                    )
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(SettingsCategoryTestTags.LIST).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun aLanguageThisBuildCannotMineShowsWhyAndOffersNoDownloadOrSwitch() {
        setLanguageTab(LanguageSettingsState(profiles = listOf(japanese, thai)))

        scrollTo(LanguageSettingsTestTags.reason("th"))
        composeRule.onNodeWithTag(LanguageSettingsTestTags.reason("th")).assertIsDisplayed()
        composeRule.onNodeWithTag(LanguageSettingsTestTags.download("th")).assertDoesNotExist()
        composeRule.onNodeWithTag(LanguageSettingsTestTags.option("th")).performClick()

        composeRule.runOnIdle { assertTrue(switched.isEmpty()) }
    }

    @Test
    fun aLanguageThatNeedsItsDataOffersDownloadAndSwitchInsteadOfASwitch() {
        setLanguageTab(LanguageSettingsState(profiles = listOf(japanese, arabic, hebrew)))

        scrollTo(LanguageSettingsTestTags.option("ar"))
        composeRule.onNodeWithTag(LanguageSettingsTestTags.option("ar")).performClick()
        scrollTo(LanguageSettingsTestTags.download("ar"))
        composeRule.onNodeWithTag(LanguageSettingsTestTags.download("ar")).performClick()
        scrollTo(LanguageSettingsTestTags.option("he"))
        composeRule.onNodeWithTag(LanguageSettingsTestTags.option("he")).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("ar"), downloaded)
            assertEquals(listOf("he"), switched)
        }
    }

    /** Desktop lists each language by its native name alone; search still finds the English one. */
    @Test
    fun languagesAreListedByTheirNativeNameAlone() {
        setLanguageTab(LanguageSettingsState(profiles = listOf(japanese, hebrew)))

        scrollTo(LanguageSettingsTestTags.option("he"))
        composeRule.onNodeWithText("עברית").assertIsDisplayed()
        composeRule.onNodeWithText("Hebrew").assertDoesNotExist()
    }

    @Test
    fun aSwitchIntoALanguageWithoutANoteTypeOpensTheAnkiTab() {
        var selected by mutableStateOf(SettingsCategory.LANGUAGE)
        var activeCode by mutableStateOf("ja")
        var noteType by mutableStateOf<String?>("Lapis")
        var pending by mutableStateOf<String?>(null)
        composeRule.setContent {
            val recorder = remember { SettingsCardIndexRecorder() }
            LanguageSwitchNoteTypeRoute(
                pending = pending,
                language = activeCode,
                noteType = noteType,
                onRoute = { selected = SettingsCategory.ANKI },
                onConsumed = { pending = null },
            )
            AnkiMinerTheme {
                SettingsCategoryLayout(
                    selectedCategory = selected,
                    onSelectedCategory = { selected = it },
                    recorder = recorder,
                    header = {},
                ) { category ->
                    when (category) {
                        SettingsCategory.LANGUAGE ->
                            languageSettings(
                                LanguageSettingsState(activeCode = activeCode, profiles = listOf(japanese, hebrew)),
                                SettingsDraft.from(AppSettings(), ResourceManagerState()),
                                recorder,
                                {},
                                LanguageSettingsActions(
                                    onSwitch = { code ->
                                        // What the screen does: remember the request, then the
                                        // settings land with the language's (empty) note type.
                                        pending = code
                                        activeCode = code
                                        noteType = null
                                    },
                                ),
                            )
                        SettingsCategory.ANKI ->
                            settingsCard(category, recorder, "anki-target") { Text("note type picker") }
                        else -> Unit
                    }
                }
            }
        }

        scrollTo(LanguageSettingsTestTags.option("he"))
        composeRule.onNodeWithTag(LanguageSettingsTestTags.option("he")).performClick()

        composeRule.waitUntil(5_000) { selected == SettingsCategory.ANKI }
        composeRule.onNodeWithText("note type picker").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, pending) }
    }

    @Test
    fun hebrewSentencesAreLaidOutRightToLeft() {
        val sentence =
            CurationSentence(
                sentenceId = "s1",
                sentence = "Abc הספר על השולחן",
                sentenceFurigana = "",
                sentenceReading = "",
                startTime = 0.0,
                endTime = 1.0,
                duration = 1.0,
            )
        val candidate =
            CurationCandidate(
                candidateId = "c1",
                minedForm = "ספר",
                surface = "הספר",
                lemma = "ספר",
                reading = "",
                expressionReading = "",
                partOfSpeech = "NOUN",
                frequencyRank = null,
                occurrenceCount = 1,
                defaultSentenceId = "s1",
                sentences = listOf(sentence),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                CompositionLocalProvider(
                    LocalMiningContentStyle provides MiningContentStyle.forLanguage("he", listOf(hebrew)),
                ) {
                    CurationSentenceChoice(
                        candidate = candidate,
                        sentence = sentence,
                        containerColor = Color.Transparent,
                        selected = true,
                        enabled = true,
                        testTag = "sentence",
                        onClick = {},
                        selectable = false,
                    )
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Abc הספר על השולחן", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        // Forced, not guessed from the first strong character: the line opens with Latin letters.
        assertEquals(ResolvedTextDirection.Rtl, layouts.single().getParagraphDirection(0))
    }
}
