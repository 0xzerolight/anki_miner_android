package com.ankiminer.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ThemePalette
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.ui.theme.radioActionColors
import com.ankiminer.android.ui.theme.toColorScheme

internal object ThemePickerTestTags {
    const val LIST = "theme-picker-list"

    fun row(key: String): String = "theme-picker-row-$key"
}

@Composable
internal fun ThemePickerDialog(
    title: String,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ThemePickerTestTags.LIST)
                        .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
            ) {
                ThemePalettes.grouped().forEach { (family, palettes) ->
                    family?.let {
                        item(key = "theme-picker-family-$it") {
                            Text(
                                text = it,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .semantics { heading() },
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    palettes.forEach { palette ->
                        item(key = palette.key) {
                            ThemePickerRow(
                                palette = palette,
                                label =
                                    if (family == null) {
                                        palette.displayName
                                    } else {
                                        palette.variantName
                                    },
                                selected = palette.key == selectedKey,
                                onSelect = { onSelect(palette.key) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_theme_picker_close))
            }
        },
    )
}

@Composable
private fun ThemePickerRow(
    palette: ThemePalette,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    // Deriving a scheme runs several binary searches, so cache it per palette rather than
    // recomputing for every visible row on each recomposition while the list scrolls.
    val colors = remember(palette) { palette.toColorScheme() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ).testTag(ThemePickerTestTags.row(palette.key))
                .padding(
                    horizontal = AnkiMinerTokens.Space.group,
                    vertical = AnkiMinerTokens.Space.line,
                ),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = radioActionColors(),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro)) {
            ThemeSwatch(colors.background)
            ThemeSwatch(colors.surfaceContainerHigh)
            ThemeSwatch(colors.primary)
        }
    }
}

@Composable
private fun ThemeSwatch(color: Color) {
    Box(
        modifier =
            Modifier
                .size(16.dp)
                .background(
                    color = color,
                    shape = RoundedCornerShape(4.dp),
                ),
    )
}
