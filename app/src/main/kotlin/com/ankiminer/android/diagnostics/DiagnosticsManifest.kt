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

    /**
     * Whether the app log sink is still writing, and what stopped it.
     *
     * Without this a log that died at 09:00 exports at 09:30 as a file that simply ends at 09:00,
     * with `entry.logs/app.log.truncated=false` and a manifest asserting nothing is wrong.
     *
     * The class name only: an IOException message routinely carries the path it failed on
     * (`ENOSPC` on a staging file), and manifest.txt is written straight into the archive without
     * going through the export redactor.
     */
    fun logSinkEntries(disabledBy: Throwable?): Map<String, String> =
        linkedMapOf(
            "log.sink_disabled" to (disabledBy != null).toString(),
            "log.sink_disabled_by" to (disabledBy?.javaClass?.name ?: "none"),
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
