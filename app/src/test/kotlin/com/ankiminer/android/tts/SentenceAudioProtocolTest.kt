package com.ankiminer.android.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentenceAudioProtocolTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun dispatcherDecodesStrictRequestAndCorrelatesReadyResult() {
        val cache = temporary.newFolder("sentence-audio-v1")
        val audio =
            File(cache, "android_tts_v1_${"a".repeat(64)}.wav")
                .also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        var received: String? = null
        val dispatcher =
            SentenceAudioCallbackDispatcher(
                SentenceAudioSynthesizer { sentence, cancellationCheck ->
                    assertTrue(!cancellationCheck())
                    received = sentence
                    SentenceAudioSynthesis.ready(audio)
                },
            )

        val result = dispatcher.synthesizeSentenceAudio(REQUEST, RUN_ID) { false }

        assertEquals("猫だ。", received)
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"tts.sentence.result\",\"payload\":{" +
                "\"runId\":\"$RUN_ID\",\"requestId\":\"$REQUEST_ID\",\"outcome\":\"ready\"," +
                "\"path\":\"${audio.canonicalPath}\",\"errorCode\":null}}",
            result,
        )
    }

    @Test
    fun cancellationDoesNotEnterSynthesizer() {
        val dispatcher =
            SentenceAudioCallbackDispatcher(
                SentenceAudioSynthesizer { _, _ -> error("synthesizer must not run") },
            )

        val result = dispatcher.synthesizeSentenceAudio(REQUEST, RUN_ID) { true }

        assertTrue(result.contains("\"outcome\":\"cancelled\""))
        assertTrue(result.contains("\"errorCode\":\"cancelled\""))
    }

    @Test
    fun synthesizerExceptionBecomesBoundedOptionalFailure() {
        val dispatcher =
            SentenceAudioCallbackDispatcher(
                SentenceAudioSynthesizer { _, _ -> throw IllegalStateException("engine died") },
            )

        val result = dispatcher.synthesizeSentenceAudio(REQUEST, RUN_ID) { false }

        assertTrue(result.contains("\"outcome\":\"failed\""))
        assertTrue(result.contains("\"errorCode\":\"internal_error\""))
        assertTrue(result.length < SentenceAudioBridgeCodec.MAX_RESULT_UTF8_BYTES)
    }

    @Test
    fun strictDecoderRejectsUnknownDuplicateStaleAndInvalidUnicodeInputs() {
        val unknown = REQUEST.replace("\"sentence\":\"猫だ。\"", "\"sentence\":\"猫だ。\",\"extra\":1")
        val duplicate = REQUEST.replace("\"sentence\":", "\"sentence\":\"犬\",\"sentence\":")
        val stale = REQUEST.replace(RUN_ID, "run_11111111111111111111111111111111")
        val invalidUnicode = REQUEST.replace("猫だ。", "\ud800")

        listOf(unknown, duplicate, stale, invalidUnicode).forEach { raw ->
            assertThrows(SentenceAudioProtocolException::class.java) {
                SentenceAudioBridgeCodec.decodeRequest(raw, RUN_ID)
            }
        }
    }

    @Test
    fun strictDecoderRejectsOversizedSentence() {
        val oversized = REQUEST.replace("猫だ。", "猫".repeat(6_000))

        assertThrows(SentenceAudioProtocolException::class.java) {
            SentenceAudioBridgeCodec.decodeRequest(oversized, RUN_ID)
        }
    }

    @Test
    fun strictDecoderCountsSupplementaryCharactersAsTwoUtf16Units() {
        val atLimit = REQUEST.replace("猫だ。", "\uD83D\uDE00".repeat(2_000))
        val overLimit = REQUEST.replace("猫だ。", "\uD83D\uDE00".repeat(2_001))

        assertEquals(4_000, SentenceAudioBridgeCodec.decodeRequest(atLimit, RUN_ID).sentence.length)
        assertThrows(SentenceAudioProtocolException::class.java) {
            SentenceAudioBridgeCodec.decodeRequest(overLimit, RUN_ID)
        }
    }

    private companion object {
        const val RUN_ID = "run_00000000000000000000000000000000"
        const val REQUEST_ID = "tts_00000000000000000000000000000000"
        const val REQUEST =
            "{\"schemaVersion\":1,\"type\":\"tts.sentence.request\",\"payload\":{" +
                "\"runId\":\"$RUN_ID\",\"requestId\":\"$REQUEST_ID\",\"sentence\":\"猫だ。\"}}"
    }
}
