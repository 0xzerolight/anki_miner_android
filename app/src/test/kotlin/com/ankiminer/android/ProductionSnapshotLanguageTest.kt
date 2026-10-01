package com.ankiminer.android

import com.ankiminer.android.data.resources.LanguageInventoryFixtures
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.switchLanguage
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot
import com.ankiminer.android.vm.SessionResourceManager
import com.ankiminer.android.vm.SessionSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A slot imported for one mining language never reaches another language's run, as desktop's
 * `usable_enabled` skips a slot stamped for another language.
 */
class ProductionSnapshotLanguageTest {
    private val japaneseUser =
        AppSettings(
            dictionarySources =
                listOf(
                    ResourceChainSelection("jmdict", enabled = false),
                    ResourceChainSelection("jitendex", enabled = true),
                ),
            frequencySources = listOf(ResourceChainSelection("jpdb", enabled = true)),
            pitchSources = listOf(ResourceChainSelection("kanjium", enabled = true)),
            audioPacks = listOf(ResourceChainSelection("jpod", enabled = true)),
        )

    @Test
    fun `a Japanese snapshot is what it was before another language's slots were installed`() =
        runTest {
            val withHebrewInstalled = snapshot(japaneseUser, LanguageInventoryFixtures.mixed)

            assertEquals(snapshot(japaneseUser, LanguageInventoryFixtures.japaneseOnly), withHebrewInstalled)
            assertEquals(LanguageInventoryFixtures.japaneseIds, chainIds(withHebrewInstalled))
        }

    @Test
    fun `a Hebrew snapshot lists only Hebrew slots`() =
        runTest {
            val hebrew = japaneseUser.switchLanguage(LanguageProfileFixtures.hebrew)

            val sent = snapshot(hebrew, LanguageInventoryFixtures.mixed)

            assertEquals(LanguageInventoryFixtures.hebrewIds, chainIds(sent))
        }

    @Test
    fun `a Hebrew chain naming a Japanese slot never sends it`() =
        runTest {
            val hebrew =
                japaneseUser.switchLanguage(LanguageProfileFixtures.hebrew).copy(
                    dictionarySources =
                        listOf(
                            ResourceChainSelection("jitendex", enabled = true),
                            ResourceChainSelection("wty-he-en", enabled = true),
                        ),
                )

            val sent = snapshot(hebrew, LanguageInventoryFixtures.mixed)

            assertEquals(listOf("wty-he-en"), ids(sent, "dictionary_chain", "dict_id"))
        }

    private suspend fun snapshot(
        settings: AppSettings,
        inventory: ResourceManagerState,
    ): MiningConfigSnapshot =
        SessionResourceManager(inventory)
            .snapshotProductionSettings(SessionSettingsRepository(settings)) { false }

    private fun chainIds(snapshot: MiningConfigSnapshot): Set<String> =
        (
            ids(snapshot, "dictionary_chain", "dict_id") +
                ids(snapshot, "frequency_chain", "source_id") +
                ids(snapshot, "pitch_chain", "source_id") +
                ids(snapshot, "expression_audio_chain", "pack_id")
        ).toSet()

    private fun ids(
        snapshot: MiningConfigSnapshot,
        chain: String,
        idKey: String,
    ): List<String> =
        (snapshot.settings.getValue(chain) as BridgeJsonValue.ArrayValue).values.mapNotNull { entry ->
            ((entry as BridgeJsonValue.ObjectValue).values[idKey] as? BridgeJsonValue.Text)?.value
        }
}
