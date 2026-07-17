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

    private fun voice(
        id: String,
        tag: String,
        network: Boolean = false,
        quality: Int = 300,
    ) =
        OfflineJapaneseVoiceCandidate(
            id = id,
            languageTag = tag,
            quality = quality,
            latency = 300,
            networkRequired = network,
        )
}
