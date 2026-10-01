package com.ankiminer.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineVoiceSelectorTest {
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

        assertEquals("exact-not-embedded", selectOfflineVoice(voices, "ja"))
        assertEquals("exact-not-embedded", selectOfflineVoice(voices.reversed(), "ja"))
    }

    @Test
    fun networkOnlyOrNonJapaneseVoicesAreUnavailable() {
        assertNull(
            selectOfflineVoice(
                listOf(
                    voice("network", "ja-JP", network = true),
                    voice("english", "en-US"),
                ),
                "ja",
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

        assertEquals("installed-general", selectOfflineVoice(voices, "ja"))
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
            selectOfflineVoice(voices, "ja") { id ->
                attempts += id
                id == "fallback"
            }

        assertEquals("fallback", selected)
        assertEquals(listOf("best", "fallback"), attempts)
    }

    @Test
    fun theRequestedLanguageSelectsItsOwnVoiceNeverJapanese() {
        val voices =
            listOf(
                voice("japanese", "ja-JP", quality = 500),
                voice("hebrew", "he-IL", quality = 300),
                voice("arabic", "ar", quality = 400),
            )

        assertEquals("hebrew", selectOfflineVoice(voices, "he"))
        assertEquals("arabic", selectOfflineVoice(voices, "ar"))
        assertNull(selectOfflineVoice(voices, "th"))
    }

    @Test
    fun legacyLanguageCodesMatchTheirCurrentCodesBothWays() {
        assertEquals("legacy-hebrew", selectOfflineVoice(listOf(voice("legacy-hebrew", "iw-IL")), "he"))
        assertEquals("hebrew", selectOfflineVoice(listOf(voice("hebrew", "he-IL")), "iw"))
        assertEquals("legacy-indonesian", selectOfflineVoice(listOf(voice("legacy-indonesian", "in-ID")), "id"))
        assertEquals("legacy-yiddish", selectOfflineVoice(listOf(voice("legacy-yiddish", "ji")), "yi"))
        assertEquals("he", ttsLanguage("iw"))
        assertEquals("id", ttsLanguage("in-ID"))
        assertEquals("yi", ttsLanguage("ji"))
        assertEquals("fa", ttsLanguage("fa-IR"))
    }

    @Test
    fun onlyJapaneseKeepsItsOwnUnavailableCode() {
        assertEquals("offline_japanese_voice_unavailable", offlineVoiceUnavailableCode("ja"))
        assertEquals("offline_voice_unavailable", offlineVoiceUnavailableCode("he"))
    }

    private fun voice(
        id: String,
        tag: String,
        network: Boolean = false,
        installed: Boolean = true,
        quality: Int = 300,
    ) =
        OfflineVoiceCandidate(
            id = id,
            languageTag = tag,
            quality = quality,
            latency = 300,
            networkRequired = network,
            installed = installed,
        )
}
