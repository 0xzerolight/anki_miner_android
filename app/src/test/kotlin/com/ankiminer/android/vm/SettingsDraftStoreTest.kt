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
    fun dirtyDraftPreservesScalarEditsButMergesNewInventory() {
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

        // Auto-save keeps the draft dirty for the whole session, so a later inventory emission must
        // merge the newly installed "third" into the pending edit while leaving the scalar deck edit
        // and the user's reordering untouched (the persisted "External" is ignored).
        assertTrue(store.state.value.dirty)
        assertEquals("Unsaved", store.state.value.draft.deckName)
        assertEquals(
            listOf("second", "first", "third"),
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

    @Test
    fun dirtyReconcileKeepsRawNumericTextWhileMergingInventory() {
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), resources("first")))
        store.update(store.state.value.draft.copy(audioPadding = "1.50"))

        store.reconcile(AppSettings(audioPaddingSeconds = 1.5), resources("first", "second"))

        // Merge-while-dirty must never canonicalize in-progress text: "1.50" stays verbatim even
        // though the persisted value round-trips to 1.5, while the new dictionary still merges in.
        assertTrue(store.state.value.dirty)
        assertEquals("1.50", store.state.value.draft.audioPadding)
        assertEquals(
            listOf("first", "second"),
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
