package com.ankiminer.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared breakpoint used by action groups and other width-sensitive controls. */
const val CompactLayoutWidthDp = 360

/**
 * Named layout and motion values. Spacing mirrors the desktop app's 4/8/12/16/24 scale so the two
 * clients read as one product. Stroke widths, corner radii, elevations, and indicator dimensions
 * are component dimensions and deliberately stay outside the spacing scale.
 */
internal object AnkiMinerTokens {
    object Space {
        /** Furigana and ruby pairs only. */
        val micro = 2.dp

        /** Between lines of a single thought. */
        val line = 4.dp

        /** Between rows, and inside a control's own internals. */
        val related = 8.dp

        /** Title to body, and between grouped actions. */
        val group = 12.dp

        /** Screen inset, and padding inside a container. */
        val content = 16.dp

        /** Between distinct sections. */
        val section = 24.dp
    }

    object Layout {
        val minTouchTarget = 48.dp
        val rowContentInset = 12.dp
    }

    /** One motion vocabulary. Compose still applies the system duration scale on top of these. */
    object Motion {
        const val ExitMs = 90
        const val ProgressMs = 100
        const val StateMs = 150
        const val LayoutMs = 250
    }
}

/**
 * Container fill for a selected list row.
 *
 * Opaque on purpose. The previous `primaryContainer.copy(alpha = 0.45f)` composited against
 * whatever it happened to be drawn over — `surface`, not the `surfaceContainerLow` the value was
 * tuned against — and an alpha color cannot be contrast-checked, because a relative-luminance
 * calculation reads only the RGB channels.
 */
internal fun ColorScheme.selectedRowContainer(): Color =
    lerp(surfaceContainerLow, primaryContainer, 0.45f)

/** Readable disabled colors. Fill remains distinct from every enabled action fill. */
internal data class DisabledActionColors(
    val content: Color,
    val border: Color,
    val container: Color,
    val enabledContainer: Color,
)

/**
 * Set by [AnkiMinerTheme] from the active scheme. Derived rather than mapped onto scheme roles: the
 * nearest candidates measure below the 4.5:1 that `DesignSystemTest` holds them to, and with 29
 * palettes plus a runtime Material You scheme there is nothing to hand-tune against.
 */
internal val LocalDisabledActionColors =
    staticCompositionLocalOf { disabledActionColorsFor(ThemePalettes.Dark.toColorScheme()) }

private val BaseFontFamily = FontFamily.SansSerif

/** Explicit type scale. System font scaling remains uncapped. */
internal val AnkiMinerTypography =
    Typography(
        displayLarge = textStyle(57, 64, FontWeight.Normal, -0.25),
        displayMedium = textStyle(45, 52),
        displaySmall = textStyle(36, 44),
        headlineLarge = textStyle(32, 40),
        headlineMedium = textStyle(28, 36),
        headlineSmall = textStyle(24, 32, FontWeight.SemiBold),
        titleLarge = textStyle(22, 28, FontWeight.SemiBold),
        titleMedium = textStyle(16, 24, FontWeight.SemiBold, 0.15),
        titleSmall = textStyle(14, 20, FontWeight.SemiBold, 0.1),
        bodyLarge = textStyle(16, 24, FontWeight.Normal, 0.5),
        bodyMedium = textStyle(14, 20, FontWeight.Normal, 0.25),
        bodySmall = textStyle(12, 16, FontWeight.Normal, 0.4),
        labelLarge = textStyle(14, 20, FontWeight.SemiBold, 0.1),
        labelMedium = textStyle(12, 16, FontWeight.SemiBold, 0.5),
        labelSmall = textStyle(11, 16, FontWeight.SemiBold, 0.5),
    )

/** Shape scale shared by cards, fields, dialogs, and large containers. */
internal val AnkiMinerShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

private fun textStyle(
    sizeSp: Int,
    lineHeightSp: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacingSp: Double = 0.0,
): TextStyle =
    TextStyle(
        fontFamily = BaseFontFamily,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = lineHeightSp.sp,
        letterSpacing = letterSpacingSp.toFloat().sp,
    )

/** Page-level heading for content that is not already named by app chrome. */
@Composable
internal fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
}

/**
 * Heading for a mining phase. Replaces six copies of `headlineMedium` overridden inline to Bold,
 * which both fought the type scale and set the phase above the app bar that already names the
 * screen.
 */
@Composable
internal fun PhaseTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

/** Section heading with consistent hierarchy and accessibility semantics. */
@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

/** Low-emphasis explanatory text used beneath controls and section labels. */
@Composable
internal fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Compact result/status metric for later mining result layouts. */
@Composable
internal fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            SupportingText(label)
        }
    }
}

/**
 * Places the primary action first and full-width when width is compact or text scale is large.
 * Wider layouts keep actions on one row with the primary action in the trailing position.
 */
@Composable
internal fun AdaptiveActionGroup(
    primary: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    secondary: (@Composable (Modifier) -> Unit)? = null,
    stackWidthThreshold: Dp = CompactLayoutWidthDp.dp,
) {
    BoxWithConstraints(modifier) {
        val stack =
            maxWidth < stackWidthThreshold || LocalDensity.current.fontScale >= 1.3f
        if (stack) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                primary(Modifier.fillMaxWidth())
                secondary?.invoke(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                secondary?.invoke(Modifier.weight(1f))
                primary(Modifier.weight(1f))
            }
        }
    }
}

/** Same breakpoint as [AdaptiveActionGroup], preserving first/second order for peer actions. */
@Composable
internal fun AdaptivePairedActions(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val stack =
            maxWidth < CompactLayoutWidthDp.dp || LocalDensity.current.fontScale >= 1.3f
        if (stack) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Actions choose meaning, not a component plus a color table. Before these existed, 42 of 53
 * helper uses were outlined, so choose, import, cancel, and back all looked identical.
 *
 * All four wrappers are rectangular ([Shapes.small], 8dp) rather than the M3 pill default, matching
 * the desktop app's controls. They also drop the explicit 48dp height clamp: the clickable Surface
 * inside every Button applies `minimumInteractiveComponentSize`, so the touch target still measures
 * 48dp while the drawn button shrinks to the M3 default 40dp.
 */
@Composable
internal fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = forwardButtonColors(),
        content = content,
    )
}

/** Choose, import, install, repair, open: supporting work, not the forward step. */
@Composable
internal fun UtilityActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = tonalActionButtonColors(),
        content = content,
    )
}

/** A neutral peer of another action, never the primary one. */
@Composable
internal fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = outlinedActionButtonColors(),
        border = actionBorder(enabled = enabled),
        content = content,
    )
}

/** Back, skip, cancel, dismiss: leaving without completing. */
@Composable
internal fun ExitActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = exitActionButtonColors(isError = isError),
        content = content,
    )
}

/** Filled colors reserved for forward workflow actions. */
@Composable
internal fun forwardButtonColors(): ButtonColors {
    val disabled = disabledActionColors()
    return ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = disabled.container,
        disabledContentColor = disabled.content,
    )
}

/** Tonal colors for choose, install, and repair actions. */
@Composable
internal fun tonalActionButtonColors(): ButtonColors {
    val disabled = disabledActionColors()
    return ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        disabledContainerColor = disabled.container,
        disabledContentColor = disabled.content,
    )
}

/** Outlined colors for choose, install, and repair actions. */
@Composable
internal fun outlinedActionButtonColors(): ButtonColors {
    val disabled = disabledActionColors()
    return ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = disabled.content,
    )
}

/** Text colors for back, skip, remove, and cancel actions. */
@Composable
internal fun exitActionButtonColors(isError: Boolean = false): ButtonColors {
    val disabled = disabledActionColors()
    return ButtonDefaults.textButtonColors(
        contentColor =
            if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        disabledContentColor = disabled.content,
    )
}

/** Segmented controls retain readable content plus an explicit fill and border when disabled. */
@Composable
internal fun segmentedActionColors(): SegmentedButtonColors {
    val disabled = disabledActionColors()
    return SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        activeBorderColor = MaterialTheme.colorScheme.outline,
        inactiveContainerColor = MaterialTheme.colorScheme.surface,
        inactiveContentColor = MaterialTheme.colorScheme.onSurface,
        inactiveBorderColor = MaterialTheme.colorScheme.outline,
        disabledActiveContainerColor = disabled.container,
        disabledActiveContentColor = disabled.content,
        disabledActiveBorderColor = disabled.border,
        disabledInactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        disabledInactiveContentColor = disabled.content,
        disabledInactiveBorderColor = disabled.border,
    )
}

/** Radio shape plus these colors keeps selected/disabled state distinguishable without alpha. */
@Composable
internal fun radioActionColors(): RadioButtonColors {
    val disabled = disabledActionColors()
    return RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledSelectedColor = disabled.content,
        disabledUnselectedColor = disabled.border,
    )
}

/** Shared readable disabled icon/content token for non-button Material controls. */
@Composable
internal fun disabledActionContentColor(): Color = disabledActionColors().content

/** Border token matching [outlinedActionButtonColors] disabled contrast. */
@Composable
internal fun actionBorder(enabled: Boolean): BorderStroke =
    BorderStroke(
        1.dp,
        if (enabled) MaterialTheme.colorScheme.outline else disabledActionColors().border,
    )

@Composable
private fun disabledActionColors(): DisabledActionColors = LocalDisabledActionColors.current

/** Hand-drawn disclosure chevron shared by the player collapse and the curation tools toggle. */
@Composable
internal fun ChevronGlyph(pointsUp: Boolean) {
    val color = LocalContentColor.current
    Canvas(
        modifier =
            Modifier
                .size(ChevronGlyphSize)
                .clearAndSetSemantics {},
    ) {
        val outsideY = size.height * if (pointsUp) 0.62f else 0.38f
        val centerY = size.height * if (pointsUp) 0.36f else 0.64f
        val strokeWidth = ChevronStrokeWidth.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, outsideY),
            end = Offset(size.width * 0.50f, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.50f, centerY),
            end = Offset(size.width * 0.78f, outsideY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

private val ChevronGlyphSize = 24.dp
private val ChevronStrokeWidth = 2.dp
