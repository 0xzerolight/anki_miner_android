package com.ankiminer.android.vm

import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftStoreTest {
    @Test
    fun numericValidationIsKeyedToTheEditedField() {
        val draft =
            SettingsDraft
                .from(AppSettings(), resources())
                .copy(
                    audioPadding = ".",
                    subtitleOffset = "-",
                    workers = "21",
                )

        assertEquals(
            R.string.b3_validation_numeric_incomplete,
            draft.validation[SettingsFieldKey.AUDIO_PADDING]?.resourceId,
        )
        assertEquals(
            R.string.b3_validation_numeric_incomplete,
            draft.validation[SettingsFieldKey.SUBTITLE_OFFSET]?.resourceId,
        )
        assertEquals(
            R.string.b3_validation_parallel_workers,
            draft.validation[SettingsFieldKey.WORKERS]?.resourceId,
        )
        assertFalse(draft.numericValuesValid)
    }

    @Test
    fun validNumericFieldsHaveNoFieldValidationResult() {
        val draft =
            SettingsDraft
                .from(AppSettings(), resources())
                .copy(
                    audioPadding = "0.3",
                    subtitleOffset = "-0.25",
                    workers = "20",
                )

        assertTrue(draft.validation.isEmpty())
        assertTrue(draft.numericValuesValid)
    }

    @Test
    fun animatedTuningStopsBlockingTheSaveOnceTheFeatureIsSwitchedOff() {
        val edited =
            SettingsDraft
                .from(AppSettings(), resources())
                .copy(animatedScreenshots = true, animatedScreenshotDuration = "12")

        assertEquals(
            R.string.b3_validation_animated_clip_duration,
            edited.validation[SettingsFieldKey.ANIMATED_SCREENSHOT_DURATION]?.resourceId,
        )

        // Both tuning fields are disabled while the toggle is off, so a leftover value must not
        // hold every other setting hostage behind a control the user cannot reach.
        val switchedOff = edited.copy(animatedScreenshots = false)

        assertTrue(switchedOff.validation.isEmpty())
        assertTrue(switchedOff.numericValuesValid)
    }

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
        // and the user's reordering untouched (the persisted "External" is ignored). This also covers
        // bug 2 (priority list stays reactive without a save/restart).
        assertTrue(store.state.value.dirty)
        assertEquals("Unsaved", store.state.value.draft.deckName)
        assertEquals(
            listOf("second", "first", "third"),
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId),
        )

        val saved = store.state.value.draft.toSettings(AppSettings())
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

    @Test
    fun dirtyDraftSurfacesTheFirstInstalledDictionaryFromEmpty() {
        // Exact user repro: no dictionaries yet, an unrelated edit dirties the draft, then a
        // dictionary is installed. The priority list must populate without an app restart.
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), resources()))
        assertTrue(store.state.value.draft.dictionarySources.isEmpty())

        store.update(store.state.value.draft.copy(deckName = "Unsaved"))
        store.reconcile(AppSettings(), resources("jmdict"))

        assertTrue(store.state.value.dirty)
        assertEquals(
            listOf("jmdict"),
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId),
        )
        assertTrue(store.state.value.draft.dictionarySources.single().enabled)
    }

    @Test
    fun dirtyDraftPreservesADisabledEntryWhenANewDictionaryArrives() {
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), resources("first")))
        store.update(
            store.state.value.draft.copy(
                deckName = "Unsaved",
                dictionarySources = listOf(ResourceChainSelection("first", enabled = false)),
            ),
        )

        store.reconcile(AppSettings(), resources("first", "second"))

        val chain = store.state.value.draft.dictionarySources
        assertEquals(listOf("first", "second"), chain.map(ResourceChainSelection::resourceId))
        assertFalse(chain.first().enabled)
        assertTrue(chain.last().enabled)
    }

    @Test
    fun reconcilingADirtyDraftTwiceWithTheSameInventoryIsIdempotent() {
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), resources("first")))
        store.update(store.state.value.draft.copy(deckName = "Unsaved"))

        val inventory = resources("first", "second")
        store.reconcile(AppSettings(), inventory)
        val afterFirst =
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId)
        store.reconcile(AppSettings(), inventory)
        val afterSecond =
            store.state.value.draft.dictionarySources.map(ResourceChainSelection::resourceId)

        assertEquals(listOf("first", "second"), afterFirst)
        assertEquals(afterFirst, afterSecond)
    }

    @Test
    fun dirtyDraftSurfacesNewlyInstalledFrequencyAndAudioResources() {
        // The reconcile guard is shared: the frequency and audio chains behave identically.
        val initial =
            ResourceManagerState(
                frequencySources = listOf(frequencySource("freq-a")),
                audioPacks = listOf(audioPack("pack-a")),
            )
        val store = SettingsDraftStore(SettingsDraft.from(AppSettings(), initial))
        store.update(store.state.value.draft.copy(deckName = "Unsaved"))

        val expanded =
            ResourceManagerState(
                frequencySources = listOf(frequencySource("freq-a"), frequencySource("freq-b")),
                audioPacks = listOf(audioPack("pack-a"), audioPack("pack-b")),
            )
        store.reconcile(AppSettings(), expanded)

        assertTrue(store.state.value.dirty)
        assertEquals(
            listOf("freq-a", "freq-b"),
            store.state.value.draft.frequencySources.map(ResourceChainSelection::resourceId),
        )
        assertEquals(
            listOf("pack-a", "pack-b"),
            store.state.value.draft.audioPacks.map(ResourceChainSelection::resourceId),
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

    private fun frequencySource(id: String): InstalledFrequencySource =
        InstalledFrequencySource(
            sourceId = id,
            sourceName = id,
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 1,
            isCategorical = false,
            rebuildSourcePath = null,
        )

    private fun audioPack(id: String): InstalledAudioPack =
        InstalledAudioPack(
            packId = id,
            sourceName = id,
            format = "pack",
            entryCount = 1,
            contentAvailable = true,
        )
}
