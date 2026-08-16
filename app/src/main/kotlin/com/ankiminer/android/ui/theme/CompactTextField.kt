package com.ankiminer.android.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Compact variant of [androidx.compose.material3.OutlinedTextField].
 *
 * Stock M3 fields are 56dp tall with 16dp horizontal and 16dp vertical content padding, which on a
 * settings screen full of fields costs a row of vertical space per field for nothing. This variant
 * is the same component assembled from the same M3 parts — `OutlinedTextFieldDefaults.DecorationBox`
 * over a `BasicTextField`, so the floating label, error and disabled colors, placeholder and
 * supporting-text slots all behave exactly as they do in the stock field — with 12/8dp content
 * padding and a rectangular [androidx.compose.material3.Shapes.small] container matching the
 * desktop app's controls.
 *
 * Height is a `defaultMinSize` floor, never a fixed `height`: at large system font scales the field
 * must be free to grow past [CompactFieldMinHeight]. `minimumInteractiveComponentSize` keeps the
 * touch target at 48dp while the drawn box shrinks to 44dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val focused by resolvedInteractionSource.collectIsFocusedAsState()
    val textColor =
        when {
            !enabled -> colors.disabledTextColor
            isError -> colors.errorTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                modifier
                    .minimumInteractiveComponentSize()
                    .defaultMinSize(
                        minWidth = OutlinedTextFieldDefaults.MinWidth,
                        minHeight = CompactFieldMinHeight,
                    ),
            enabled = enabled,
            textStyle = textStyle.merge(TextStyle(color = textColor)),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            interactionSource = resolvedInteractionSource,
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = resolvedInteractionSource,
                    isError = isError,
                    label = label,
                    placeholder = placeholder,
                    trailingIcon = trailingIcon,
                    supportingText = supportingText,
                    colors = colors,
                    contentPadding = CompactFieldContentPadding,
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = resolvedInteractionSource,
                            colors = colors,
                            shape = MaterialTheme.shapes.small,
                            focusedBorderThickness = 2.dp,
                            unfocusedBorderThickness = 1.dp,
                        )
                    },
                )
            },
        )
    }
}

/** Drawn height floor. The 48dp touch target comes from `minimumInteractiveComponentSize`. */
private val CompactFieldMinHeight = 44.dp

/** Against the stock field's 16dp/16dp. */
private val CompactFieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
