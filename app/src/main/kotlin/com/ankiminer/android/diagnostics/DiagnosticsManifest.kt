package com.ankiminer.android.diagnostics

internal object DiagnosticsManifest {
    fun buildIdentityEntries(identity: TesterBuildIdentity): Map<String, String> =
        linkedMapOf(
            "app.id" to identity.applicationId,
            "app.version_name" to identity.versionName,
            "app.version_code" to identity.versionCode.toString(),
            "source.commit" to identity.sourceCommit,
            "android.sdk" to identity.sdkInt.toString(),
            "android.abis" to identity.supportedAbis.joinToString(","),
            "runtime.python" to identity.pythonVersion,
            "runtime.wheel" to identity.runtimeWheelBuildKey,
            "runtime.tokenizer_publication" to identity.tokenizerPublicationBuildKey,
            "runtime.device_accepted" to identity.deviceRuntimeAccepted.toString(),
        )

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
