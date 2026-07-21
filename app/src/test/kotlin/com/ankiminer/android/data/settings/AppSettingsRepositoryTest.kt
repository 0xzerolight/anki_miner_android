package com.ankiminer.android.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiProviderReadService
import com.ankiminer.android.anki.provider.AnkiRunStateRegistry
import com.ankiminer.android.anki.provider.FakeAnkiProviderGateway
import com.ankiminer.android.anki.provider.FakeProviderCursor
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.anki.provider.ProviderEndpoint
import com.ankiminer.android.anki.provider.modelRow
import com.ankiminer.android.anki.provider.templateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsRepositoryTest {
    @Test
    fun `legacy duplicate raw field map is quarantined without erasing unrelated settings`() {
        val preferences =
            preferencesOf(
                booleanPreferencesKey("setup_wizard_seen") to true,
                stringPreferencesKey("theme_mode") to "light",
                stringPreferencesKey("deck_name") to "Japanese",
                stringPreferencesKey("note_type") to "Lapis",
                stringPreferencesKey("field_map_v1") to
                    "field-map-v1\nword=Expression\nsentence=Expression\n",
                stringPreferencesKey("tags") to "mined japanese",
                stringPreferencesKey("dictionary_sources_v1") to
                    "resource-selection-v1\n+jitendex\n-custom-dictionary\n",
                stringPreferencesKey("frequency_sources_v1") to
                    "resource-selection-v1\n+bccwj\n",
                stringPreferencesKey("audio_packs_v1") to
                    "resource-selection-v1\n+local-audio\n",
                stringPreferencesKey("excluded_wordsets_v1") to
                    "resource-selection-v1\n+place-names\n",
                booleanPreferencesKey("reading_tts_enabled") to true,
                booleanPreferencesKey("jisho_enabled") to true,
            )

        val settings = DataStoreAppSettingsRepository.decodePreferences(preferences)

        assertTrue(settings.setupWizardSeen)
        assertEquals(ThemeMode.LIGHT, settings.theme)
        assertEquals("Japanese", settings.deckName)
        assertEquals("Lapis", settings.noteType)
        assertTrue(settings.fieldMap.isEmpty())
        assertEquals("mined japanese", settings.tags)
        assertEquals(
            listOf(
                ResourceChainSelection("jitendex"),
                ResourceChainSelection("custom-dictionary", enabled = false),
            ),
            settings.dictionarySources,
        )
        assertEquals(listOf(ResourceChainSelection("bccwj")), settings.frequencySources)
        assertEquals(listOf(ResourceChainSelection("local-audio")), settings.audioPacks)
        assertEquals(listOf("place-names"), settings.excludedWordsets)
        assertTrue(settings.readingTtsEnabled)
        assertTrue(settings.jishoEnabled)

        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(modelRow(name = "Lapis", fields = "Expression\u001fMeaning")),
                    )
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                else -> error("unexpected query $query")
            }
        }
        val status =
            AnkiProviderReadService(gateway, AnkiRunStateRegistry())
                .verifyUserNoteType(
                    requireNotNull(settings.noteType),
                    settings.fieldMap,
                    AnkiCancellation.NONE,
                )

        assertEquals(
            NoteTypeSetupStatus.FieldMapInvalid(
                destination = "Expression",
                logicalKeys = listOf(AnkiFieldKeys.WORD),
            ),
            status,
        )
        assertTrue(gateway.noteCommands.isEmpty())
    }
}
