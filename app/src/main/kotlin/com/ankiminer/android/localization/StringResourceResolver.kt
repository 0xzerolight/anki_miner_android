package com.ankiminer.android.localization

import android.content.Context
import androidx.annotation.StringRes

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
    ): String = resources.getString(resourceId, *formatArguments.toTypedArray())
}
