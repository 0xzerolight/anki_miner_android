package com.ankiminer.android.diagnostics

internal object DiagnosticsManifest {
    fun render(values: Map<String, String>): String =
        buildString {
            values.forEach { (key, value) ->
                append(safe(key))
                append('=')
                append(safe(value))
                append('\n')
            }
        }

    private fun safe(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                append(if (character == '\n' || character == '\r' || character == '\u0000') ' ' else character)
            }
        }
}
