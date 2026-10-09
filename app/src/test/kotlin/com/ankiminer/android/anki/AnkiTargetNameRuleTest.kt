package com.ankiminer.android.anki

import com.ankiminer.android.anki.provider.DeckSnapshot
import com.ankiminer.android.anki.provider.InvalidTargetSnapshotException
import com.ankiminer.android.anki.provider.ProviderSnapshotValidation
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.data.settings.InvalidAppSettingException
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.BridgeProtocolException
import com.ankiminer.android.engine.MiningConfigSnapshot
import com.ankiminer.android.engine.VideoMiningWireRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deck and note-type names may carry ZWNJ (Persian orthography) and ZWJ (emoji sequences), the two
 * joiners AnkiDroid's own deck and note-type lists hold. Every other category-C code point stays
 * refused, and field and marker names keep the full rule (AU-020). The wire decoders are pinned by
 * the shared `golden/bridge/anki-protocol-v1.jsonl` cases.
 */
class AnkiTargetNameRuleTest {
    @Test
    fun `settings accept joiners in deck excluded deck and note type names`() {
        val settings =
            AppSettings(deckName = PERSIAN_DECK, excludedDecks = listOf(EMOJI_NAME), noteType = EMOJI_NAME)

        assertEquals(settings, AppSettingsValidator.validate(settings))
    }

    @Test
    fun `settings still refuse every other format character in those names`() {
        for (name in REFUSED_NAMES) {
            assertThrows(name, InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(AppSettings(deckName = name))
            }
            assertThrows(name, InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(AppSettings(excludedDecks = listOf(name)))
            }
            assertThrows(name, InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(AppSettings(noteType = name))
            }
        }
    }

    @Test
    fun `field names and the card type marker keep the full category C rule`() {
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(fieldMap = mapOf("word" to "Ex‌pression")))
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(cardTypeMarkerField = "Mark‍er"))
        }
    }

    @Test
    fun `provider snapshots accept joiners in deck and model names only`() {
        ProviderSnapshotValidation.validateDeck(DeckSnapshot(1L, PERSIAN_DECK, dynamic = false))
        for (name in REFUSED_NAMES) {
            assertThrows(name, InvalidTargetSnapshotException::class.java) {
                ProviderSnapshotValidation.validateDeck(DeckSnapshot(1L, name, dynamic = false))
            }
        }
        ProviderSnapshotValidation.validateModelBase(
            id = 1L,
            name = EMOJI_NAME,
            type = 0,
            rawFieldNames = "Expression",
            cardCount = 1,
            sortFieldIndex = 0,
            effectiveDefaultDeckId = 1L,
            css = "",
            latexPre = null,
            latexPost = null,
        )
        assertThrows(InvalidTargetSnapshotException::class.java) {
            ProviderSnapshotValidation.validateModelBase(
                id = 1L,
                name = "Lapis",
                type = 0,
                rawFieldNames = "Ex‌pression",
                cardCount = 1,
                sortFieldIndex = 0,
                effectiveDefaultDeckId = 1L,
                css = "",
                latexPre = null,
                latexPost = null,
            )
        }
        // The known-vocabulary note-type table lists a Persian note type instead of dropping it.
        assertEquals(listOf("Expression"), ProviderSnapshotValidation.noteTypeFieldNames(PERSIAN_DECK, "Expression"))
        assertNull(ProviderSnapshotValidation.noteTypeFieldNames("Lapis", "Ex‌pression"))
    }

    @Test
    fun `usable deck names are exactly the names the settings contract accepts`() {
        assertTrue(ProviderSnapshotValidation.isUsableDeckName(PERSIAN_DECK))
        assertTrue(ProviderSnapshotValidation.isUsableDeckName(EMOJI_NAME))
        for (name in REFUSED_NAMES + listOf(" Mining", "Café", "")) {
            assertFalse(name, ProviderSnapshotValidation.isUsableDeckName(name))
            assertThrows(name, InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(AppSettings(deckName = name))
            }
        }
    }

    @Test
    fun `run config snapshots carry joiner names and refuse other format characters`() {
        val settings =
            mapOf(
                "anki_deck_name" to BridgeJsonValue.Text(PERSIAN_DECK),
                "anki_note_type" to BridgeJsonValue.Text(EMOJI_NAME),
                "excluded_decks" to BridgeJsonValue.ArrayValue(listOf(BridgeJsonValue.Text(EMOJI_NAME))),
            )
        val request = videoRequest(settings)

        assertEquals(BridgeMessage.VideoRun(request), BridgeJsonCodec.decode(BridgeJsonCodec.encodeVideoRun(request)))
        for (name in REFUSED_NAMES) {
            // The encoder self-checks through the decoder, so it refuses to write the snapshot.
            assertThrows(name, BridgeProtocolException::class.java) {
                BridgeJsonCodec.encodeVideoRun(videoRequest(mapOf("anki_deck_name" to BridgeJsonValue.Text(name))))
            }
        }
    }

    private fun videoRequest(settings: Map<String, BridgeJsonValue>) =
        VideoMiningWireRequest(
            videoPath = "/proc/self/fd/8",
            subtitlePath = "/cache/subtitle.SRT",
            episodeName = "Episode 1",
            seriesName = "Series",
            sourceLabel = null,
            audioTrackOverride = null,
            audioOnly = false,
            cacheDir = "/cache",
            nativeLibraryDir = "/native",
            configSnapshot = MiningConfigSnapshot(settings, androidTtsEnabled = false),
        )

    private companion object {
        /** واژه‌ها, "words", with U+200C ZWNJ. */
        const val PERSIAN_DECK = "واژه‌ها"

        /** 🧑‍🎓 Vocab: a U+200D ZWJ emoji sequence. */
        const val EMOJI_NAME = "🧑‍🎓 Vocab"

        val REFUSED_NAMES =
            listOf(
                "Mining‮", // RIGHT-TO-LEFT OVERRIDE
                "‏Mining", // RIGHT-TO-LEFT MARK
                "﻿Mining", // BOM
                "Mi​ning", // ZERO WIDTH SPACE
                "Wort­teil", // SOFT HYPHEN
                "Mining⁦", // LEFT-TO-RIGHT ISOLATE
                "Mining\u0007", // control
                "Mining", // private use
            )
    }
}
