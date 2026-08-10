package com.ankiminer.android.ui.settings

import android.content.Context
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DictionarySectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun customDictionaryCardChoosesAZipWithoutRequestingASlot() {
        var imports = 0
        composeRule.setContent {
            AnkiMinerTheme {
                CustomDictionaryImportCard(
                    state = SetupUiState(resourceStartup = ResourceStartupReadiness.READY),
                    onImport = { imports += 1 },
                )
            }
        }

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithText("Choose Yomitan ZIP").performClick()

        assertEquals(1, imports)
    }

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

    @Test
    fun unchangedHtmlDoesNotReloadButChangedResultLoadsOnce() {
        var html by mutableStateOf("<h1>猫</h1>")
        var lookupTerm by mutableStateOf("猫")
        lateinit var countingWebView: CountingWebView

        composeRule.setContent {
            AnkiMinerTheme {
                DictionaryHtml(
                    html = html,
                    updateKey = lookupTerm,
                    webViewFactory = { context ->
                        CountingWebView(context).also { countingWebView = it }
                    },
                )
            }
        }
        composeRule.runOnIdle { assertEquals(1, countingWebView.loadCount) }

        repeat(10) { index ->
            composeRule.runOnIdle { lookupTerm = "term-$index" }
        }
        composeRule.runOnIdle { assertEquals(1, countingWebView.loadCount) }

        composeRule.runOnIdle { html = "<h1>犬</h1>" }
        composeRule.runOnIdle { assertEquals(2, countingWebView.loadCount) }
    }

    @Test
    fun renderedHtmlCarriesThemeColorsAndWebViewBackground() {
        lateinit var recordingWebView: RecordingWebView
        var expectedSurface = 0
        var expectedSurfaceHex = ""

        composeRule.setContent {
            AnkiMinerTheme {
                val surface = MaterialTheme.colorScheme.surface
                expectedSurface = surface.toArgb()
                expectedSurfaceHex = "#%06X".format(0xFFFFFF and surface.toArgb())
                DictionaryHtml(
                    html = "<p>x</p>",
                    webViewFactory = { context ->
                        RecordingWebView(context).also { recordingWebView = it }
                    },
                )
            }
        }

        composeRule.runOnIdle {
            val loaded = requireNotNull(recordingWebView.loadedData)
            assertTrue(loaded.contains("color-scheme"))
            assertTrue(loaded.contains("background-color:$expectedSurfaceHex"))
            assertTrue(loaded.endsWith("<p>x</p>"))
            assertEquals(expectedSurface, recordingWebView.backgroundColor)
        }
    }

    @Test
    fun themedHtmlEnvelopePrefixesStyleAndPreservesFragment() {
        val out =
            themedDictionaryHtml(
                fragment = "<p>x</p>",
                surface = Color(0xFF101318),
                onSurface = Color(0xFFE2E2E9),
                accent = Color(0xFFADC6FF),
            )

        assertTrue(out.contains("content=\"dark\""))
        assertTrue(out.contains("background-color:#101318"))
        assertTrue(out.contains("color:#E2E2E9"))
        assertTrue(out.contains("a{color:#ADC6FF}"))
        assertTrue(out.endsWith("<p>x</p>"))

        val light =
            themedDictionaryHtml(
                fragment = "<p>x</p>",
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1A1C20),
                accent = Color(0xFF445E91),
            )
        assertTrue(light.contains("content=\"light\""))
    }

    @Test
    fun dictionaryInventoryCardOffersReplaceAndRemoveForEveryInstalledSlot() {
        val replaced = mutableListOf<String>()
        val removed = mutableListOf<String>()
        composeRule.setContent {
            AnkiMinerTheme {
                DictionaryInventoryCard(
                    state =
                        SetupUiState(
                            // Default startup readiness is PENDING, which makes the state busy and
                            // renders every Remove disabled; a click on one would be a silent no-op.
                            resourceStartup = ResourceStartupReadiness.READY,
                            dictionaries =
                                listOf(
                                    installedDictionary("jmdict", valid = true),
                                    installedDictionary("jitendex", valid = false),
                                ),
                        ),
                    onReplace = replaced::add,
                    onRemove = removed::add,
                )
            }
        }

        composeRule.onAllNodesWithText("Replace safely").assertCountEquals(2)
        composeRule.onAllNodesWithText("Remove").assertCountEquals(2)
        composeRule.onAllNodesWithText("Replace safely")[1].performClick()
        composeRule.onAllNodesWithText("Remove")[0].performClick()

        assertEquals(listOf("jitendex"), replaced)
        assertEquals(listOf("jmdict"), removed)
    }

    private fun installedDictionary(slotId: String, valid: Boolean) =
        InstalledDictionary(
            slotId = slotId,
            occupied = true,
            valid = valid,
            sourceName = slotId.replaceFirstChar(Char::uppercase),
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 10,
            schemaOk = valid,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
        )

    private class CountingWebView(context: Context) : WebView(context) {
        var loadCount = 0
            private set

        override fun loadDataWithBaseURL(
            baseUrl: String?,
            data: String,
            mimeType: String?,
            encoding: String?,
            historyUrl: String?,
        ) {
            loadCount += 1
        }
    }

    private class RecordingWebView(context: Context) : WebView(context) {
        var loadedData: String? = null
            private set
        var backgroundColor = 1
            private set

        override fun loadDataWithBaseURL(
            baseUrl: String?,
            data: String,
            mimeType: String?,
            encoding: String?,
            historyUrl: String?,
        ) {
            loadedData = data
        }

        override fun setBackgroundColor(color: Int) {
            backgroundColor = color
            super.setBackgroundColor(color)
        }
    }
}
