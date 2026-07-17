package com.ankiminer.android.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedSentenceAudioSynthesizerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun contentAddressedCachePublishesOnceAndUsesFilesystemSafeName() {
        val backend = FakeBackend(output = byteArrayOf(1, 2, 3, 4))
        val synthesizer = synthesizer(backend)

        val first = synthesizer.synthesize("猫だ。") { false }
        val second = synthesizer.synthesize("猫だ。") { false }

        assertEquals(SentenceAudioOutcome.READY, first.outcome)
        assertEquals(first.file, second.file)
        assertEquals(1, backend.calls)
        assertTrue(first.file!!.name.matches(Regex("android_tts_v1_[0-9a-f]{64}\\.wav")))
        assertTrue(first.file!!.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(first.file!!.parentFile!!.toPath().startsWith(temporary.root.toPath()))
        assertFalse(first.file!!.name.contains("猫"))
    }

    @Test
    fun voiceAndSentenceBothParticipateInCacheIdentity() {
        val first = synthesizer(FakeBackend(voiceIdentity = "engine-a", output = byteArrayOf(1)))
        val second = synthesizer(FakeBackend(voiceIdentity = "engine-b", output = byteArrayOf(2)))

        val a = first.synthesize("猫だ。") { false }.file
        val b = second.synthesize("猫だ。") { false }.file
        val c = first.synthesize("犬だ。") { false }.file

        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun cancellationAndOversizedOutputNeverPublishPartialFiles() {
        val cancelledBackend = FakeBackend(result = OfflineTtsSynthesisResult.Cancelled)
        val cancelled = synthesizer(cancelledBackend).synthesize("猫だ。") { false }
        assertEquals(SentenceAudioOutcome.CANCELLED, cancelled.outcome)

        val oversizedBackend = FakeBackend(output = ByteArray(9))
        val oversized = synthesizer(oversizedBackend).synthesize("犬だ。") { false }
        assertEquals("invalid_audio_output", oversized.errorCode)

        assertTrue(cacheRoot().listFiles().orEmpty().none { it.name.contains(".part.") })
        assertTrue(cacheRoot().listFiles().orEmpty().none { it.length() > 8 })
    }

    @Test
    fun cacheEvictsOldestPublishedFilesWithinBudget() {
        val backend = FakeBackend(output = ByteArray(6) { 7 })
        val synthesizer = synthesizer(backend, budget = 16, maxFile = 8)

        val first = synthesizer.synthesize("一。") { false }.file!!
        Thread.sleep(2)
        val second = synthesizer.synthesize("二。") { false }.file!!
        Thread.sleep(2)
        val third = synthesizer.synthesize("三。") { false }.file!!

        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(third.exists())
        assertTrue(cacheRoot().listFiles().orEmpty().sumOf(File::length) <= 16)
    }

    @Test
    fun unavailableBackendIsMemoizedForTheRunAndCloseIsIdempotent() {
        var opens = 0
        val synthesizer =
            CachedSentenceAudioSynthesizer(
                cacheRoot(),
                backendFactory =
                    OfflineTtsBackendFactory {
                        opens += 1
                        OfflineTtsBackendOpenResult.Unavailable("offline_japanese_voice_unavailable")
                    },
                availableBytes = { Long.MAX_VALUE },
                cacheBudgetBytes = 12,
                maxFileBytes = 8,
                reserveBytes = 0,
            )

        assertEquals(SentenceAudioOutcome.UNAVAILABLE, synthesizer.synthesize("猫。") { false }.outcome)
        assertEquals(SentenceAudioOutcome.UNAVAILABLE, synthesizer.synthesize("犬。") { false }.outcome)
        assertEquals(1, opens)
        synthesizer.close()
        synthesizer.close()
        assertEquals("synthesizer_closed", synthesizer.synthesize("鳥。") { false }.errorCode)
    }

    @Test
    fun successfulBackendIsClosedExactlyOnce() {
        val backend = FakeBackend(output = byteArrayOf(1))
        val synthesizer = synthesizer(backend)
        synthesizer.synthesize("猫。") { false }

        synthesizer.close()
        synthesizer.close()

        assertEquals(1, backend.closeCalls)
    }

    private fun synthesizer(
        backend: FakeBackend,
        budget: Long = 12,
        maxFile: Long = 8,
    ): CachedSentenceAudioSynthesizer =
        CachedSentenceAudioSynthesizer(
            cacheRoot(),
            backendFactory = OfflineTtsBackendFactory { OfflineTtsBackendOpenResult.Ready(backend) },
            availableBytes = { Long.MAX_VALUE },
            cacheBudgetBytes = budget,
            maxFileBytes = maxFile,
            reserveBytes = 0,
        )

    private fun cacheRoot(): File = File(temporary.root, "tts-cache")

    private class FakeBackend(
        override val voiceIdentity: String = "engine\u0000ja-JP\u0000offline",
        private val output: ByteArray = byteArrayOf(1),
        private val result: OfflineTtsSynthesisResult = OfflineTtsSynthesisResult.Ready,
    ) : OfflineTtsBackend {
        override val maxInputUtf16Units = 4_000
        var calls = 0
        var closeCalls = 0

        override fun synthesizeToFile(
            sentence: String,
            destination: File,
            cancellationCheck: () -> Boolean,
        ): OfflineTtsSynthesisResult {
            calls += 1
            destination.writeBytes(output)
            return result
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
