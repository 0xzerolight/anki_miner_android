package com.ankiminer.android.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsManifestTest {
    @Test
    fun `a disabled log sink is named in the manifest, its message is not`() {
        val healthy = DiagnosticsManifest.logSinkEntries(null)
        val disabled =
            DiagnosticsManifest.logSinkEntries(
                IOException("ENOSPC: /data/user/0/com.ankiminer.android/files/logs"),
            )

        assertEquals(
            mapOf("log.sink_disabled" to "false", "log.sink_disabled_by" to "none"),
            healthy,
        )
        assertEquals(
            mapOf("log.sink_disabled" to "true", "log.sink_disabled_by" to "java.io.IOException"),
            disabled,
        )
        // manifest.txt never passes through the export redactor, so the failure message — which
        // carries the path it failed on — must not reach it.
        assertFalse(DiagnosticsManifest.render(disabled).contains("/data"))
    }

    @Test
    fun `build identity entries contain complete tester provenance`() {
        val identity =
            TesterBuildIdentity(
                applicationId = "com.example.miner",
                versionName = "1.2.3-rc1",
                versionCode = 42,
                sourceCommit = "0123456789abcdef",
                sdkInt = 35,
                supportedAbis = listOf("arm64-v8a", "x86_64"),
                pythonVersion = "3.13.5",
                runtimeWheelBuildKey = "wheel-key",
                tokenizerPublicationBuildKey = "tokenizer-key",
            )

        assertEquals(
            linkedMapOf(
                "app.id" to "com.example.miner",
                "app.version_name" to "1.2.3-rc1",
                "app.version_code" to "42",
                "source.commit" to "0123456789abcdef",
                "android.sdk" to "35",
                "android.abis" to "arm64-v8a,x86_64",
                "runtime.python" to "3.13.5",
                "runtime.wheel" to "wheel-key",
                "runtime.tokenizer_publication" to "tokenizer-key",
            ),
            DiagnosticsManifest.buildIdentityEntries(identity),
        )
    }
}
