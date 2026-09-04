package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.diagnostics.StagedBundle
import com.ankiminer.android.vm.DiagnosticsExportState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenModelTest {
    @Test
    fun categoryOrderMatchesTheApprovedSettingsInformationArchitecture() {
        assertEquals(
            listOf(
                SettingsCategory.ANKI,
                SettingsCategory.MEDIA,
                SettingsCategory.RESOURCES,
                SettingsCategory.FILTERING,
                SettingsCategory.UI,
                SettingsCategory.DIAGNOSTICS,
            ),
            SettingsCategory.entries,
        )
    }

    @Test
    fun stableFailureOriginsRouteToOneSettingsCategory() {
        assertEquals(SettingsCategory.DIAGNOSTICS, settingsCategoryFor(ResourceFailureOrigin.SETUP))
        assertEquals(SettingsCategory.DIAGNOSTICS, settingsCategoryFor(ResourceFailureOrigin.UNIDIC))
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.CATALOG_DICTIONARY),
        )
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.CUSTOM_DICTIONARY),
        )
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.RECOMMENDED_SET),
        )
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.PITCH),
        )
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.DICTIONARY_LOOKUP),
        )
        assertEquals(SettingsCategory.RESOURCES, settingsCategoryFor(ResourceFailureOrigin.AUDIO))
        assertEquals(
            SettingsCategory.RESOURCES,
            settingsCategoryFor(ResourceFailureOrigin.FREQUENCY),
        )
        assertEquals(
            SettingsCategory.FILTERING,
            settingsCategoryFor(ResourceFailureOrigin.KNOWN_WORDS),
        )
        assertEquals(
            SettingsCategory.FILTERING,
            settingsCategoryFor(ResourceFailureOrigin.WORD_LIST),
        )
        assertEquals(SettingsCategory.ANKI, settingsCategoryFor(AnkiSetupFailureOrigin.TARGET))
        // These are constants only because every conditional card is emitted after the last
        // deep-link target in its category. dictionary-lookup is the only conditional card in
        // Resources and it is emitted last, so nothing behind it can shift.
        // SETUP has no owning card; it renders in the shared header, which is lazy item 0.
        assertEquals(0, settingsCardIndexFor(ResourceFailureOrigin.SETUP))
        // Diagnostics: diagnostic-runtime(2), unidic(3).
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.UNIDIC))
        // Resources: dictionary-sources(2) owns all three dictionary origins, pitch-sources(3),
        // audio-sources(4), frequency-sources(5), dictionary-lookup(6).
        assertEquals(2, settingsCardIndexFor(ResourceFailureOrigin.CATALOG_DICTIONARY))
        assertEquals(2, settingsCardIndexFor(ResourceFailureOrigin.CUSTOM_DICTIONARY))
        assertEquals(2, settingsCardIndexFor(ResourceFailureOrigin.RECOMMENDED_SET))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.PITCH))
        assertEquals(4, settingsCardIndexFor(ResourceFailureOrigin.AUDIO))
        assertEquals(5, settingsCardIndexFor(ResourceFailureOrigin.FREQUENCY))
        assertEquals(6, settingsCardIndexFor(ResourceFailureOrigin.DICTIONARY_LOOKUP))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.KNOWN_WORDS))
        // Filtering: word-lists sits after known-words-import, ahead of the conditional
        // filtering-import-result card.
        assertEquals(4, settingsCardIndexFor(ResourceFailureOrigin.WORD_LIST))
        assertEquals(3, settingsCardIndexFor(AnkiSetupFailureOrigin.TARGET))
    }

    @Test
    fun savedExcludedDeckAbsentFromLiveDiscoveryRemainsCheckedAndVisible() {
        val choices =
            excludedDeckChoices(
                availableDecks = listOf("Live", "Shared"),
                excludedDecks = listOf("Saved::Gone", "Shared"),
            )

        assertEquals(listOf("Live", "Saved::Gone", "Shared"), choices.map { it.name })
        val saved = choices.single { it.name == "Saved::Gone" }
        assertTrue(saved.checked)
        assertFalse(saved.discovered)
    }

    @Test
    fun retainedReadyExportLaunchesOneShareSheetPerRequest() {
        val bundle = stagedBundle("diagnostics-1.zip")
        val ready = DiagnosticsExportState.Ready(bundle)

        val request = diagnosticsDeliveryToLaunch(ready, launchedRequest = null)
        assertNotNull(request)

        // Rotation: a fresh composition observes an equal retained Ready and must not relaunch.
        assertNull(
            diagnosticsDeliveryToLaunch(
                DiagnosticsExportState.Ready(stagedBundle("diagnostics-1.zip")),
                launchedRequest = request,
            ),
        )
        // A rebuilt bundle is a new request.
        assertNotNull(
            diagnosticsDeliveryToLaunch(
                DiagnosticsExportState.Ready(stagedBundle("diagnostics-2.zip")),
                launchedRequest = request,
            ),
        )
        // Every non-Ready state ends the request; the composition clears its key there.
        DiagnosticsExportState.Idle
            .let { assertNull(diagnosticsDeliveryToLaunch(it, launchedRequest = request)) }
        assertEquals(request, diagnosticsDeliveryToLaunch(ready, launchedRequest = null))
    }

    private fun stagedBundle(name: String): StagedBundle =
        StagedBundle(
            file = File("/data/user/0/com.ankiminer.android/cache/diagnostics/$name"),
            uri = "content://com.ankiminer.android.files/diagnostics/$name",
            sizeBytes = 1_024L,
            entries = emptyList(),
        )
}
