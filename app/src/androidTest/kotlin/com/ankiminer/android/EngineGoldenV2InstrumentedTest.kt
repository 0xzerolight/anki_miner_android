package com.ankiminer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_GOLDEN_V2_ARGUMENT = "ankiMinerRunGoldenV2"

@RunWith(AndroidJUnit4::class)
class EngineGoldenV2InstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun asset(path: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(path)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun allCompleteSectionsReplayThroughPackagedEngine() {
        assumeTrue(
            "v2 parity runs only through its fresh-process selector",
            InstrumentationRegistry.getArguments().getString(RUN_GOLDEN_V2_ARGUMENT) == "true",
        )
        assertTrue("v2 parity requires the selected S1a publication", BuildConfig.S1A_SPIKE_ENABLED)
        val fixtureJson = asset("engine-v2.json")
        val corpusJson = asset("corpus/tokenizer-v1.json")
        val inputJson = asset("corpus/engine-v2-input.json")
        val fixture = JSONObject(fixtureJson)
        val expectedDictionaryHash =
            fixture.getJSONObject("provenance").getJSONObject("data")
                .getJSONObject("unidic").getJSONObject("tree").getString("sha256")
        val dicdir = PythonInstrumentationRuntime.stageExternalUniDic(expectedDictionaryHash)
        val result =
            JSONObject(
                PythonInstrumentationRuntime.awaitReady()
                    .getModule("engine_golden_v2_instrumented")
                    .callAttr(
                        "run",
                        fixtureJson,
                        corpusJson,
                        inputJson,
                        dicdir.absolutePath,
                        context.filesDir.absolutePath,
                    ).toString(),
            )
        assertEquals("s1a", result.getString("backend"))
        assertEquals("engine_shared_tagger", result.getString("tagger_path"))
        assertEquals(expectedDictionaryHash, result.getString("dictionary_sha256"))
        assertEquals(
            "6ddc4371bf99f751f6db2cd6aac9c81b9262575edb0128777950672ee9a192d3",
            result.getString("fixture_sha256"),
        )
        val expectedSections =
            setOf(
                "tokenization",
                "morphology",
                "filtering",
                "deinflection",
                "compounds",
                "dictionaries",
                "frequency",
                "pitch",
                "cards",
            )
        val counts = result.getJSONObject("case_counts")
        val hashes = result.getJSONObject("section_hashes")
        assertEquals(expectedSections, counts.keys().asSequence().toSet())
        assertEquals(expectedSections, hashes.keys().asSequence().toSet())
        expectedSections.forEach { section ->
            assertTrue("v2 section is empty: $section", counts.getInt(section) > 0)
            assertTrue(
                "v2 section hash is malformed: $section",
                hashes.getString(section).matches(Regex("[0-9a-f]{64}")),
            )
        }
    }
}
