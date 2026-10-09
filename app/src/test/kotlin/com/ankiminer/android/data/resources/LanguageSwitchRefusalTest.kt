package com.ankiminer.android.data.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageSwitchRefusalTest {
    @Test
    fun `nothing pending allows a switch`() {
        assertNull(ResourceManagerState().languageSwitchRefusal())
    }

    @Test
    fun `an open preview refuses a switch`() {
        val preview = KnownWordsImportPreview("plain", 1, 1, isGeneric = true, sampleWords = listOf("猫"))

        assertEquals(
            LanguageSwitchRefusal.KNOWN_WORDS_IMPORT_PENDING,
            ResourceManagerState(knownWordsImportPreview = preview).languageSwitchRefusal(),
        )
    }

    @Test
    fun `an import awaiting Retry refuses a switch, as one restored after process death does`() {
        assertEquals(
            LanguageSwitchRefusal.KNOWN_WORDS_IMPORT_PENDING,
            ResourceManagerState(
                failure = knownWordsFailure(KnownWordsFailureOperation.IMPORT, ResourceFailureAction.RETRY),
            ).languageSwitchRefusal(),
        )
    }

    @Test
    fun `other known-words failures allow a switch`() {
        // A removal or a reset retries into its own language, whichever is active.
        assertNull(
            ResourceManagerState(failure = knownWordsFailure(null, ResourceFailureAction.RETRY)).languageSwitchRefusal(),
        )
        assertNull(
            ResourceManagerState(
                failure = knownWordsFailure(KnownWordsFailureOperation.PREVIEW, ResourceFailureAction.CHOOSE_ANOTHER),
            ).languageSwitchRefusal(),
        )
        // An import whose file must be chosen again, or whose store needs repair, has nothing staged.
        assertNull(
            ResourceManagerState(
                failure = knownWordsFailure(KnownWordsFailureOperation.IMPORT, ResourceFailureAction.CHOOSE_ANOTHER),
            ).languageSwitchRefusal(),
        )
        assertNull(
            ResourceManagerState(
                failure = knownWordsFailure(KnownWordsFailureOperation.IMPORT, ResourceFailureAction.RESOLVE),
            ).languageSwitchRefusal(),
        )
    }

    private fun knownWordsFailure(
        operation: KnownWordsFailureOperation?,
        action: ResourceFailureAction,
    ) = ResourceFailure(
        code = "resource_operation_failed",
        message = "failed",
        retryable = true,
        origin = ResourceFailureOrigin.KNOWN_WORDS,
        retry = ResourceFailureRetry(action),
        knownWordsOperation = operation,
    )
}
