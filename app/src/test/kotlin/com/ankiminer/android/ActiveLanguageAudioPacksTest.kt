package com.ankiminer.android

import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Video and Audio tabs warn from the installed audio packs ("word audio is not mapped", "a pack
 * is unusable"); only the mining language's packs can feed its runs, so only they may warn.
 */
class ActiveLanguageAudioPacksTest {
    private val japanesePack = InstalledAudioPack("jpod", "JapanesePod", "ajt", 10, contentAvailable = true)
    private val hebrewPack =
        InstalledAudioPack("forvo-he", "Forvo", "ajt", 10, contentAvailable = true, language = "he")

    @Test
    fun `a switch from japanese to hebrew hands the tabs only hebrew packs`() =
        runTest {
            val settings = MutableStateFlow(AppSettings())
            val resources = MutableStateFlow(ResourceManagerState(audioPacks = listOf(japanesePack, hebrewPack)))
            val packs = activeLanguageAudioPacks(settings, resources)

            assertEquals(listOf(japanesePack), packs.first())

            settings.value = AppSettings(language = "he")

            assertEquals(listOf(hebrewPack), packs.first())
        }

    @Test
    fun `hebrew with only japanese packs installed sees none`() =
        runTest {
            val packs =
                activeLanguageAudioPacks(
                    MutableStateFlow(AppSettings(language = "he")),
                    MutableStateFlow(ResourceManagerState(audioPacks = listOf(japanesePack))),
                )

            assertEquals(emptyList<InstalledAudioPack>(), packs.first())
        }
}
