package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiDroidConnectTest {
    @Test
    fun `a missing AnkiDroid is installed and only an old one is updated`() {
        assertEquals(R.string.install_ankidroid, ankiDroidInstallLabel(AnkiProviderReadiness.NotInstalled))
        assertEquals(R.string.update_ankidroid, ankiDroidInstallLabel(AnkiProviderReadiness.Incompatible(1)))
    }

    @Test
    fun `each connection step has its own sentence and a connected AnkiDroid has none`() {
        assertEquals(R.string.ankidroid_install_prompt, ankiDroidActionPrompt(SetupUiState(anki = AnkiProviderReadiness.NotInstalled)))
        assertEquals(R.string.ankidroid_update_prompt, ankiDroidActionPrompt(SetupUiState(anki = AnkiProviderReadiness.Incompatible(1))))
        assertEquals(R.string.ankidroid_open_prompt, ankiDroidActionPrompt(SetupUiState(anki = AnkiProviderReadiness.Uninitialized)))
        assertEquals(R.string.ankidroid_permission_prompt, ankiDroidActionPrompt(SetupUiState(anki = AnkiProviderReadiness.PermissionDenied)))
        assertNull(ankiDroidActionPrompt(SetupUiState(anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L))))
    }
}
