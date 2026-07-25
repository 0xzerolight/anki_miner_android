package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.theme.AnkiMinerTokens

internal enum class SettingsCategory(
    @param:StringRes val label: Int,
) {
    SETUP(R.string.b3_settings_category_setup),
    ANKI(R.string.b3_settings_category_anki),
    MEDIA(R.string.b3_settings_category_media),
    DICTIONARIES(R.string.b3_settings_category_dictionaries),
    AUDIO(R.string.b3_settings_category_audio),
    FREQUENCY(R.string.b3_settings_category_frequency),
    FILTERING(R.string.b3_settings_category_filtering),
    UI(R.string.b3_settings_category_ui),
    DIAGNOSTICS(R.string.b3_settings_category_diagnostics),
}

internal object SettingsCategoryTestTags {
    const val LIST = "settings-category-list"
}

internal fun settingsCategoryFor(origin: ResourceFailureOrigin): SettingsCategory =
    when (origin) {
        ResourceFailureOrigin.SETUP,
        ResourceFailureOrigin.UNIDIC,
        -> SettingsCategory.SETUP
        ResourceFailureOrigin.CATALOG_DICTIONARY,
        ResourceFailureOrigin.CUSTOM_DICTIONARY,
        ResourceFailureOrigin.PITCH,
        ResourceFailureOrigin.DICTIONARY_LOOKUP,
        -> SettingsCategory.DICTIONARIES
        ResourceFailureOrigin.AUDIO -> SettingsCategory.AUDIO
        ResourceFailureOrigin.FREQUENCY -> SettingsCategory.FREQUENCY
        ResourceFailureOrigin.KNOWN_WORDS -> SettingsCategory.FILTERING
    }

internal fun settingsCategoryFor(origin: AnkiSetupFailureOrigin): SettingsCategory =
    when (origin) {
        AnkiSetupFailureOrigin.TARGET,
        AnkiSetupFailureOrigin.RECOVERY,
        -> SettingsCategory.ANKI
    }

/** Lazy-list index for the card that owns a linked failure (header and tabs occupy 0 and 1). */
internal fun settingsCardIndexFor(origin: ResourceFailureOrigin): Int =
    when (origin) {
        ResourceFailureOrigin.SETUP,
        ResourceFailureOrigin.UNIDIC,
        ResourceFailureOrigin.CATALOG_DICTIONARY,
        -> 2
        ResourceFailureOrigin.CUSTOM_DICTIONARY -> 3
        ResourceFailureOrigin.PITCH -> 4
        ResourceFailureOrigin.DICTIONARY_LOOKUP -> 7
        ResourceFailureOrigin.AUDIO,
        ResourceFailureOrigin.FREQUENCY,
        ResourceFailureOrigin.KNOWN_WORDS,
        -> 3
    }

internal fun settingsCardIndexFor(origin: AnkiSetupFailureOrigin): Int =
    when (origin) {
        AnkiSetupFailureOrigin.TARGET -> 3
        AnkiSetupFailureOrigin.RECOVERY -> 4
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedCategory.ordinal,
                    edgePadding = 8.dp,
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
        content(selectedCategory)
    }
}

/**
 * One screen inset, one section gap. This wrapper used to pad and [SettingsSection] then added a
 * border plus a second inset, so setting text started at x=32dp behind two competing edges.
 */
internal fun LazyListScope.settingsCard(
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key, contentType = "card") {
        Column(
            Modifier.padding(
                horizontal = AnkiMinerTokens.Space.content,
                vertical = AnkiMinerTokens.Space.group,
            ),
        ) {
            content()
        }
    }
}
