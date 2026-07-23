package com.ankiminer.android.ui.settings

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
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
}
