package com.ankiminer.android.vm

import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ResourceChainSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftStoreTest {
    @Test
    fun editsAreIgnoredUntilTheFirstPersistedSettingsValueLoads() {
        val resources = resources("first")
        val initial = SettingsDraft.from(AppSettings(), resources)
        val store = SettingsDraftStore(initial, initiallyLoaded = false)

        store.update(initial.copy(deckName = "Too early"))

        assertFalse(store.state.value.loaded)
        assertFalse(store.state.value.dirty)
        store.reconcile(AppSettings(deckName = "Persisted"), resources)
        assertTrue(store.state.value.loaded)
        assertEquals("Persisted", store.state.value.draft.deckName)
    }

    @Test
    fun cleanDraftReconcilesPersistedSettingsAndInventory() {
        val initialResources = resources("first", "second")
        val store =
            SettingsDraftStore(
                SettingsDraft.from(AppSettings(deckName = "Initial"), initialResources),
            )

        store.reconcile(AppSettings(deckName = "Persisted"), resources("first", "second", "third"))

        assertFalse(store.state.value.dirty)
        assertEquals("Persisted", store.state.value.draft.deckName)
        assertEquals(
            listOf("first", "second", "third"),
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId),
        )
    }

    @Test
    fun dirtyDraftSurvivesInventoryAndPersistenceEmissionsUntilSaved() {
        val initialResources = resources("first", "second")
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), initialResources))
        val reordered =
            store.state.value.draft.copy(
                deckName = "Unsaved",
                dictionarySources =
                    listOf(
                        ResourceChainSelection("second"),
                        ResourceChainSelection("first"),
                    ),
            )
        store.update(reordered)

        val expandedResources = resources("first", "second", "third")
        store.reconcile(AppSettings(deckName = "External"), expandedResources)

        assertTrue(store.state.value.dirty)
        assertEquals("Unsaved", store.state.value.draft.deckName)
        assertEquals(
            listOf("second", "first"),
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId),
        )

        val saved = reordered.toSettings(AppSettings())
        store.markClean(saved, expandedResources)
        assertFalse(store.state.value.dirty)
        assertEquals(
            listOf("second", "first", "third"),
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId),
        )
    }

    private fun resources(vararg ids: String): ResourceManagerState =
        ResourceManagerState(dictionaries = ids.map(::dictionary))

    private fun dictionary(id: String): InstalledDictionary =
        InstalledDictionary(
            slotId = id,
            occupied = true,
            valid = true,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
        )
}
