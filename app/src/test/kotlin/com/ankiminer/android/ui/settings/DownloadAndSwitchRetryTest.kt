package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadAndSwitchRetryTest {
    // Fixture profiles: ja and he are available, ar needs its data.
    private val language = LanguageSettingsState(activeCode = "ja", profiles = LanguageProfileFixtures.all)

    private fun failure(
        targetId: String?,
        origin: ResourceFailureOrigin = ResourceFailureOrigin.RECOMMENDED_SET,
    ) = ResourceFailure(
        code = "recommended_set_incomplete",
        message = "The language data did not download.",
        retryable = true,
        origin = origin,
        retry = ResourceFailureRetry(ResourceFailureAction.RETRY, targetId = targetId),
    )

    @Test
    fun `a failed download and switch retries the switch while the language still needs its data`() {
        assertEquals("ar", language.downloadAndSwitchRetry(failure("ar")))
    }

    @Test
    fun `the active language's set, an untargeted set and other origins keep the generic retry`() {
        assertNull(language.downloadAndSwitchRetry(failure("ja")))
        assertNull(language.downloadAndSwitchRetry(failure(null)))
        assertNull(language.downloadAndSwitchRetry(failure("ar", ResourceFailureOrigin.CATALOG_DICTIONARY)))
    }

    @Test
    fun `a language whose data is in, or that is not listed, keeps the generic retry`() {
        // The user switched back after a partial set: Retry finishes its install, never re-switches.
        assertNull(language.downloadAndSwitchRetry(failure("he")))
        assertNull(language.downloadAndSwitchRetry(failure("xx")))
        // Before the profiles load nothing is known to need data.
        assertNull(LanguageSettingsState(activeCode = "ja").downloadAndSwitchRetry(failure("ar")))
    }
}
