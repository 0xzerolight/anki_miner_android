package com.ankiminer.android.localization

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

internal data class StringResourceArgument(
    @StringRes val resourceId: Int,
)

internal data class ByteSizeArgument(
    val bytes: Long,
)

internal fun formatByteSize(
    bytes: Long,
    locale: Locale,
): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return String.format(locale, "%.1f %s", value, units[unit])
}

internal fun localizeFormatArguments(
    arguments: List<Any>,
    locale: Locale,
    resolveString: (Int) -> String,
): List<Any> =
    arguments.map { argument ->
        when (argument) {
            is StringResourceArgument -> resolveString(argument.resourceId)
            is ByteSizeArgument -> formatByteSize(argument.bytes, locale)
            else -> argument
        }
    }

internal fun interface StringResourceResolver {
    fun resolve(
        @StringRes resourceId: Int,
        formatArguments: List<Any>,
    ): String

    fun resolve(@StringRes resourceId: Int): String = resolve(resourceId, emptyList())
}

internal data class LocalizedStringResource(
    @StringRes val resourceId: Int,
    val formatArguments: List<Any> = emptyList(),
)

internal class AndroidStringResourceResolver(
    context: Context,
) : StringResourceResolver {
    private val resources = context.applicationContext.resources

    override fun resolve(
        resourceId: Int,
        formatArguments: List<Any>,
    ): String {
        val locale = resources.configuration.locales[0]
        val localized =
            localizeFormatArguments(formatArguments, locale) { nestedResourceId ->
                resources.getString(nestedResourceId)
            }
        return resources.getString(resourceId, *localized.toTypedArray())
    }
}
