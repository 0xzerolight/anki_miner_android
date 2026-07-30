package com.ankiminer.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineJapaneseVoiceSelectorTest {
    @Test
    fun exactEmbeddedJapaneseVoiceWinsDeterministically() {
        val voices =
            listOf(
                voice("network", "ja-JP", network = true, quality = 500),
                voice("ja-general", "ja", quality = 500),
                voice("z-exact", "ja-JP", quality = 400),
                voice("a-exact", "ja-JP", quality = 400),
                voice("exact-not-embedded", "ja-JP", quality = 500),
                voice("english", "en-US", quality = 500),
            )

        assertEquals("exact-not-embedded", selectOfflineJapaneseVoice(voices))
        assertEquals("exact-not-embedded", selectOfflineJapaneseVoice(voices.reversed()))
    }

    @Test
    fun networkOnlyOrNonJapaneseVoicesAreUnavailable() {
        assertNull(
            selectOfflineJapaneseVoice(
                listOf(
                    voice("network", "ja-JP", network = true),
                    voice("english", "en-US"),
                ),
            ),
        )
    }

    @Test
    fun notInstalledExactVoiceDoesNotMaskInstalledJapaneseFallback() {
        val voices =
            listOf(
                voice("exact-not-installed", "ja-JP", installed = false, quality = 500),
                voice("installed-general", "ja", quality = 400),
            )

        assertEquals("installed-general", selectOfflineJapaneseVoice(voices))
    }

    @Test
    fun rejectedTopRankedVoiceFallsThroughInDeterministicOrder() {
        val attempts = mutableListOf<String>()
        val voices =
            listOf(
                voice("best", "ja-JP", quality = 500),
                voice("fallback", "ja-JP", quality = 400),
            )

        val selected =
            selectOfflineJapaneseVoice(voices) { id ->
                attempts += id
                id == "fallback"
            }

        assertEquals("fallback", selected)
        assertEquals(listOf("best", "fallback"), attempts)
    }

    private fun voice(
        id: String,
        tag: String,
        network: Boolean = false,
        installed: Boolean = true,
        quality: Int = 300,
    ) =
        OfflineJapaneseVoiceCandidate(
            id = id,
            languageTag = tag,
            quality = quality,
            latency = 300,
            networkRequired = network,
            installed = installed,
        )
}
