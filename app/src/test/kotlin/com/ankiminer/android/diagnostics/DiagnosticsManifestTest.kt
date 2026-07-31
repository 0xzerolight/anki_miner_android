package com.ankiminer.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsManifestTest {
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
                deviceRuntimeAccepted = true,
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
                "runtime.device_accepted" to "true",
            ),
            DiagnosticsManifest.buildIdentityEntries(identity),
        )
    }
}
