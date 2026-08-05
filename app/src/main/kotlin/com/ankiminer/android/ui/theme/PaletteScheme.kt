package com.ankiminer.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

internal const val ReadableContrast = 4.5
internal const val SeparationContrast = 3.0

private const val ContainerBandContrast = SeparationContrast
private const val SelectionSeparationContrast = 1.20
private const val FixedDimAccentFraction = 0.30f
private const val SelectedRowFraction = 0.45f
private const val DisabledContainerTintFraction = 0.09f
private const val ContainerClampIterations = 32
private const val SelectionSearchSteps = 1_000

private val SurfaceContainerFractions = listOf(0.00f, 0.04f, 0.07f, 0.11f, 0.15f)

/** WCAG 2.x relative luminance for an sRGB [Color]. */
internal fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val normalized = value.toDouble()
        return if (normalized <= 0.04045) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }

    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

internal fun contrastRatio(a: Color, b: Color): Double {
    val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
    val darker = minOf(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}

/** True when white gives a more readable result than black on this surface. */
internal fun Color.isDarkSurface(): Boolean =
    contrastRatio(Color.White, this) > contrastRatio(Color.Black, this)

/** Chooses the first preferred color readable on every background. */
internal fun readableOn(
    backgrounds: List<Color>,
    candidates: List<Color>,
    minimum: Double = ReadableContrast,
): Color {
    require(backgrounds.isNotEmpty()) { "At least one background is required" }
    require(candidates.isNotEmpty()) { "At least one candidate is required" }

    candidates.firstOrNull { candidate ->
        backgrounds.all { background -> contrastRatio(candidate, background) >= minimum }
    }?.let { return it }

    val pushed = pushedToContrast(candidates.first(), backgrounds, minimum)
    if (backgrounds.all { background -> contrastRatio(pushed, background) >= minimum }) {
        return pushed
    }

    return blackOrWhiteWithBestWorstCaseContrast(backgrounds)
}

/**
 * The candidate nudged the smallest distance toward black or white that clears [minimum] on every
 * background. This preserves the palette hue instead of collapsing a muted theme to white or
 * black.
 */
internal fun pushedToContrast(
    candidate: Color,
    backgrounds: List<Color>,
    minimum: Double,
): Color {
    require(backgrounds.isNotEmpty()) { "At least one background is required" }
    if (backgrounds.all { background -> contrastRatio(candidate, background) >= minimum }) {
        return candidate
    }

    val target = blackOrWhiteWithBestWorstCaseContrast(backgrounds)
    var failingFraction = 0f
    var passingFraction = 1f
    repeat(ContainerClampIterations) {
        val fraction = (failingFraction + passingFraction) / 2f
        val pushed = lerp(candidate, target, fraction)
        if (backgrounds.all { background -> contrastRatio(pushed, background) >= minimum }) {
            passingFraction = fraction
        } else {
            failingFraction = fraction
        }
    }
    return lerp(candidate, target, passingFraction)
}

/** Dims a readable color as far as possible toward the page [background] without losing contrast. */
private fun dimmedToContrast(
    candidate: Color,
    background: Color,
    backgrounds: List<Color>,
    minimum: Double,
): Color {
    var passingFraction = 0f
    var failingFraction = 1f
    repeat(ContainerClampIterations) {
        val fraction = (passingFraction + failingFraction) / 2f
        val dimmed = lerp(candidate, background, fraction)
        if (backgrounds.all { fill -> contrastRatio(dimmed, fill) >= minimum }) {
            passingFraction = fraction
        } else {
            failingFraction = fraction
        }
    }
    return lerp(candidate, background, passingFraction)
}

private fun blackOrWhiteWithBestWorstCaseContrast(backgrounds: List<Color>): Color =
    listOf(Color.White, Color.Black).maxBy { candidate ->
        backgrounds.minOf { background -> contrastRatio(candidate, background) }
    }

/**
 * Maps the desktop palette roles onto Material3 roles while preserving an ordered elevation ramp
 * and readable on-colors for each fill used by the Android UI.
 */
internal fun ThemePalette.toColorScheme(): ColorScheme {
    val background = color(ThemeSlots.BACKGROUND)
    val text = color(ThemeSlots.TEXT)
    val textMuted = color(ThemeSlots.TEXT_MUTED)
    val textOnPrimary = color(ThemeSlots.TEXT_ON_PRIMARY)
    val isDarkSurface = background.isDarkSurface()
    val surfaceTint = if (isDarkSurface) Color.White else Color.Black
    val surfaceContainers = SurfaceContainerFractions.map { fraction ->
        lerp(background, surfaceTint, fraction)
    }
    val surfaceContainerLowest = surfaceContainers[0]
    val surfaceContainerLow = surfaceContainers[1]
    val surfaceContainer = surfaceContainers[2]
    val surfaceContainerHigh = surfaceContainers[3]
    val surfaceContainerHighest = surfaceContainers[4]

    val primary = color(ThemeSlots.PRIMARY)
    val primaryContainer =
        primaryContainerWithSelectionSeparation(
            initial = color(ThemeSlots.PRIMARY_LIGHT),
            primary = primary,
            background = background,
            surfaceContainerLow = surfaceContainerLow,
        )
    val secondary = color(ThemeSlots.SECONDARY)
    val secondaryContainer = clampTonalContainer(color(ThemeSlots.TABLE_SELECTED_BG), background)
    val tertiary = color(ThemeSlots.INFO)
    val tertiaryContainer = clampTonalContainer(color(ThemeSlots.BADGE_INFO_BG), background)
    val error = color(ThemeSlots.ERROR)
    val errorContainer = clampTonalContainer(color(ThemeSlots.BADGE_ERROR_BG), background)
    val surfaceVariant = clampTonalContainer(color(ThemeSlots.BORDER_SUBTLE), background)
    val inverseSurface = color(ThemeSlots.TOOLTIP_BG)

    val primaryFixedDim = lerp(primaryContainer, primary, FixedDimAccentFraction)
    val secondaryFixedDim = lerp(secondaryContainer, secondary, FixedDimAccentFraction)
    val tertiaryFixedDim = lerp(tertiaryContainer, tertiary, FixedDimAccentFraction)
    val selectedRow = lerp(surfaceContainerLow, primaryContainer, SelectedRowFraction)
    val surfaceFills =
        listOf(
            background,
            surfaceContainerLowest,
            surfaceContainerLow,
            surfaceContainer,
            surfaceContainerHigh,
            surfaceContainerHighest,
            surfaceVariant,
            selectedRow,
        )
    val onSurface = readableOn(surfaceFills, listOf(text))
    val initialOnSurfaceVariant = readableOn(surfaceFills, listOf(textMuted))
    val onSurfaceVariant =
        if (contrastRatio(initialOnSurfaceVariant, background) >= contrastRatio(onSurface, background)) {
            dimmedToContrast(
                candidate = onSurface,
                background = background,
                backgrounds = surfaceFills,
                minimum = ReadableContrast,
            )
        } else {
            initialOnSurfaceVariant
        }

    val scheme = if (isDarkSurface) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = primary,
        onPrimary = readableOn(listOf(primary), listOf(textOnPrimary, text)),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(listOf(primaryContainer), listOf(text, textMuted, textOnPrimary)),
        inversePrimary = color(ThemeSlots.PRIMARY_DARK),
        secondary = secondary,
        onSecondary = readableOn(listOf(secondary), listOf(textOnPrimary, text)),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(listOf(secondaryContainer), listOf(text, textMuted, textOnPrimary)),
        tertiary = tertiary,
        onTertiary = readableOn(listOf(tertiary), listOf(textOnPrimary, text)),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readableOn(listOf(tertiaryContainer), listOf(text, textMuted, textOnPrimary)),
        background = background,
        onBackground = readableOn(listOf(background), listOf(text, textMuted)),
        surface = background,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = readableOn(listOf(inverseSurface), listOf(color(ThemeSlots.TOOLTIP_TEXT), text, textMuted)),
        error = error,
        onError = readableOn(listOf(error), listOf(textOnPrimary, text)),
        errorContainer = errorContainer,
        onErrorContainer = readableOn(listOf(errorContainer), listOf(text, textMuted, textOnPrimary)),
        outline =
            readableOn(
                backgrounds = listOf(background, surfaceContainer),
                candidates = listOf(color(ThemeSlots.BORDER), textMuted, text),
                minimum = SeparationContrast,
            ),
        outlineVariant = surfaceVariant,
        scrim = Color.Black,
        surfaceBright = if (isDarkSurface) surfaceContainerHighest else background,
        surfaceDim = if (isDarkSurface) background else surfaceContainerHighest,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        primaryFixed = primaryContainer,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = readableOn(listOf(primaryContainer, primaryFixedDim), listOf(text, textMuted, textOnPrimary)),
        onPrimaryFixedVariant = readableOn(listOf(primaryContainer, primaryFixedDim), listOf(textMuted, text, textOnPrimary)),
        secondaryFixed = secondaryContainer,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = readableOn(listOf(secondaryContainer, secondaryFixedDim), listOf(text, textMuted, textOnPrimary)),
        onSecondaryFixedVariant = readableOn(listOf(secondaryContainer, secondaryFixedDim), listOf(textMuted, text, textOnPrimary)),
        tertiaryFixed = tertiaryContainer,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = readableOn(listOf(tertiaryContainer, tertiaryFixedDim), listOf(text, textMuted, textOnPrimary)),
        onTertiaryFixedVariant = readableOn(listOf(tertiaryContainer, tertiaryFixedDim), listOf(textMuted, text, textOnPrimary)),
    )
}

/** Derives disabled action colors from a complete scheme, including runtime dynamic schemes. */
internal fun disabledActionColorsFor(scheme: ColorScheme): DisabledActionColors {
    val background = scheme.background
    val container =
        lerp(
            background,
            if (background.isDarkSurface()) Color.White else Color.Black,
            DisabledContainerTintFraction,
        )
    val content =
        readableOn(
            backgrounds = listOf(container, background, scheme.errorContainer),
            candidates = listOf(scheme.onSurfaceVariant, scheme.onSurface),
        )
    return DisabledActionColors(
        content = content,
        border = content,
        container = container,
        enabledContainer = scheme.primary,
    )
}

private fun clampTonalContainer(
    container: Color,
    background: Color,
): Color {
    if (contrastRatio(container, background) <= ContainerBandContrast) return container

    var outsideBand = 0f
    var insideBand = 1f
    repeat(ContainerClampIterations) {
        val fraction = (outsideBand + insideBand) / 2f
        if (contrastRatio(lerp(container, background, fraction), background) > ContainerBandContrast) {
            outsideBand = fraction
        } else {
            insideBand = fraction
        }
    }
    return lerp(container, background, insideBand)
}

private fun primaryContainerWithSelectionSeparation(
    initial: Color,
    primary: Color,
    background: Color,
    surfaceContainerLow: Color,
): Color {
    val container = clampTonalContainer(initial, background)
    if (selectionSeparation(container, surfaceContainerLow) >= SelectionSeparationContrast) {
        return container
    }

    for (step in 1..SelectionSearchSteps) {
        val candidate =
            clampTonalContainer(
                lerp(container, primary, step.toFloat() / SelectionSearchSteps),
                background,
            )
        if (selectionSeparation(candidate, surfaceContainerLow) >= SelectionSeparationContrast) {
            return candidate
        }
    }
    return clampTonalContainer(primary, background)
}

private fun selectionSeparation(
    primaryContainer: Color,
    surfaceContainerLow: Color,
): Double =
    contrastRatio(
        lerp(surfaceContainerLow, primaryContainer, SelectedRowFraction),
        surfaceContainerLow,
    )
