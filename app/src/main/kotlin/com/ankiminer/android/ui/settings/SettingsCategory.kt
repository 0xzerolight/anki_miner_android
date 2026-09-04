package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.mining.boundedSaveableQuery
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.CompactOutlinedTextField

internal enum class SettingsCategory(
    @param:StringRes val label: Int,
) {
    ANKI(R.string.b3_settings_category_anki),
    MEDIA(R.string.b3_settings_category_media),
    RESOURCES(R.string.b3_settings_category_resources),
    FILTERING(R.string.b3_settings_category_filtering),
    UI(R.string.b3_settings_category_ui),
    DIAGNOSTICS(R.string.b3_settings_category_diagnostics),
}

internal object SettingsCategoryTestTags {
    const val LIST = "settings-category-list"
    const val SEARCH = "settings-search"
    const val ANIMATED_SCREENSHOT_DURATION = "settings-animated-screenshot-duration"
    const val ANIMATED_SCREENSHOT_QUALITY = "settings-animated-screenshot-quality"
}

/** Width of the fade drawn over each scrollable edge of the category tab strip. */
private val SettingsTabEdgeFade = 24.dp

internal fun settingsCategoryFor(origin: ResourceFailureOrigin): SettingsCategory =
    when (origin) {
        // SETUP renders in the shared header, so any category works; DIAGNOSTICS is where the
        // resource-startup status it contradicts is printed. UNIDIC owns a card there.
        ResourceFailureOrigin.SETUP,
        ResourceFailureOrigin.UNIDIC,
        -> SettingsCategory.DIAGNOSTICS
        ResourceFailureOrigin.CATALOG_DICTIONARY,
        ResourceFailureOrigin.CUSTOM_DICTIONARY,
        ResourceFailureOrigin.RECOMMENDED_SET,
        ResourceFailureOrigin.PITCH,
        ResourceFailureOrigin.DICTIONARY_LOOKUP,
        ResourceFailureOrigin.AUDIO,
        ResourceFailureOrigin.FREQUENCY,
        -> SettingsCategory.RESOURCES
        ResourceFailureOrigin.KNOWN_WORDS,
        ResourceFailureOrigin.WORD_LIST,
        -> SettingsCategory.FILTERING
    }

internal fun settingsCategoryFor(origin: AnkiSetupFailureOrigin): SettingsCategory =
    when (origin) {
        AnkiSetupFailureOrigin.TARGET -> SettingsCategory.ANKI
    }

/**
 * Lazy-list index for the card that owns a linked failure (header and tabs occupy 0 and 1).
 *
 * These stay constants only because each category emits its conditional cards *after* the last
 * card any failure origin deep-links to. Adding a conditional card ahead of a target, or
 * reordering one behind it, silently sends the deep link to the wrong card. A collapsed panel is
 * not such a case: the card still emits its own lazy item whether it is open or closed.
 */
internal fun settingsCardIndexFor(origin: ResourceFailureOrigin): Int =
    when (origin) {
        // Rendered in the header, which is item 0.
        ResourceFailureOrigin.SETUP -> 0
        // Resources: dictionary-sources(2) owns all three dictionary origins, pitch-sources(3),
        // audio-sources(4), frequency-sources(5), dictionary-lookup(6).
        ResourceFailureOrigin.CATALOG_DICTIONARY,
        ResourceFailureOrigin.CUSTOM_DICTIONARY,
        ResourceFailureOrigin.RECOMMENDED_SET,
        -> 2
        ResourceFailureOrigin.PITCH -> 3
        ResourceFailureOrigin.AUDIO -> 4
        ResourceFailureOrigin.FREQUENCY -> 5
        ResourceFailureOrigin.DICTIONARY_LOOKUP -> 6
        // Diagnostics: diagnostic-runtime(2), unidic(3).
        ResourceFailureOrigin.UNIDIC -> 3
        // Filtering: filtering-options(2), known-words-import(3), word-lists(4).
        ResourceFailureOrigin.KNOWN_WORDS -> 3
        ResourceFailureOrigin.WORD_LIST -> 4
    }

internal fun settingsCardIndexFor(origin: AnkiSetupFailureOrigin): Int =
    when (origin) {
        AnkiSetupFailureOrigin.TARGET -> 3
    }

@Composable
internal fun rememberSettingsCategoryListStates(): Map<SettingsCategory, LazyListState> =
    buildMap {
        SettingsCategory.entries.forEach { category ->
            put(
                category,
                rememberSaveable(
                    category.name,
                    saver = LazyListState.Saver,
                ) {
                    LazyListState()
                },
            )
        }
    }

/**
 * One lazy page per category. Only [selectedCategory] is composed; every category owns a
 * saveable list state so switching tabs restores its exact viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsCategoryLayout(
    selectedCategory: SettingsCategory,
    onSelectedCategory: (SettingsCategory) -> Unit,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    results: List<ResolvedSettingsEntry> = emptyList(),
    onResultChosen: (ResolvedSettingsEntry) -> Unit = {},
    recorder: SettingsCardIndexRecorder,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listStates: Map<SettingsCategory, LazyListState> = rememberSettingsCategoryListStates(),
    content: LazyListScope.(SettingsCategory) -> Unit,
) {
    LazyColumn(
        state = listStates.getValue(selectedCategory),
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .testTag(SettingsCategoryTestTags.LIST),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "settings-header", contentType = "header") {
            Column(
                Modifier.padding(horizontal = AnkiMinerTokens.Space.content, vertical = AnkiMinerTokens.Space.related),
            ) {
                header()
            }
        }
        stickyHeader(key = "settings-category-tabs", contentType = "tabs") {
            val tabScrollState = rememberScrollState()
            // Matches the Surface's own tonal elevation, so the fade reads as the strip running
            // under the edge rather than as a grey overlay.
            val edgeColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column {
                    CompactOutlinedTextField(
                        value = query,
                        onValueChange = { onQueryChange(it.boundedSaveableQuery()) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AnkiMinerTokens.Space.content,
                                    vertical = AnkiMinerTokens.Space.related,
                                )
                                .testTag(SettingsCategoryTestTags.SEARCH),
                        singleLine = true,
                        // Placeholder, not a floating label: the field sits under the header that
                        // already names the screen, and the label would eat the height the compact
                        // field buys back.
                        placeholder = { Text(stringResource(R.string.settings_search)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_clear),
                                        contentDescription =
                                            stringResource(R.string.settings_search_clear),
                                    )
                                }
                            }
                        },
                    )
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedCategory.ordinal,
                        // Six word labels still overrun a 320dp screen, so the strip scrolls.
                        // These fades are the affordance that the rest of it exists; shortening
                        // the labels would buy ~40dp and cost clarity on the two least
                        // self-evident tabs.
                        modifier =
                            Modifier.drawWithContent {
                                drawContent()
                                val fade = SettingsTabEdgeFade.toPx().coerceAtMost(size.width)
                                if (tabScrollState.canScrollBackward) {
                                    drawRect(
                                        brush =
                                            Brush.horizontalGradient(
                                                colors = listOf(edgeColor, Color.Transparent),
                                                startX = 0f,
                                                endX = fade,
                                            ),
                                        size = Size(fade, size.height),
                                    )
                                }
                                if (tabScrollState.canScrollForward) {
                                    drawRect(
                                        brush =
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, edgeColor),
                                                startX = size.width - fade,
                                                endX = size.width,
                                            ),
                                        topLeft = Offset(size.width - fade, 0f),
                                        size = Size(fade, size.height),
                                    )
                                }
                            },
                        scrollState = tabScrollState,
                        edgePadding = AnkiMinerTokens.Space.related,
                        // Default is 90.dp, which wastes ~40dp on a label as short as "UI".
                        minTabWidth = AnkiMinerTokens.Layout.minTouchTarget,
                    ) {
                        SettingsCategory.entries.forEach { category ->
                            Tab(
                                selected = selectedCategory == category,
                                onClick = { onSelectedCategory(category) },
                                text = {
                                    Text(
                                        text = stringResource(category.label),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (query.isBlank()) {
            recorder.begin(selectedCategory)
            content(selectedCategory)
        } else if (results.isEmpty()) {
            item(key = "settings-search-empty", contentType = "search-empty") {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    modifier = Modifier.padding(AnkiMinerTokens.Space.content),
                )
            }
        } else {
            results.forEach { entry ->
                item(key = "settings-search-${entry.id}", contentType = "search-result") {
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.breadcrumb) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onResultChosen(entry) },
                    )
                }
            }
        }
    }
}

/**
 * One screen inset, one section gap. This wrapper used to pad and [SettingsSection] then added a
 * border plus a second inset, so setting text started at x=32dp behind two competing edges.
 */
internal fun LazyListScope.settingsCard(
    category: SettingsCategory,
    recorder: SettingsCardIndexRecorder,
    key: String,
    content: @Composable () -> Unit,
) {
    recorder.record(category, key)
    item(key = key, contentType = "card") {
        val modifier =
            if (recorder.highlightedKey == key) {
                Modifier.background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                )
            } else {
                Modifier
            }
        Column(
            modifier.padding(
                horizontal = AnkiMinerTokens.Space.content,
                vertical = AnkiMinerTokens.Space.group,
            ),
        ) {
            content()
        }
    }
}
