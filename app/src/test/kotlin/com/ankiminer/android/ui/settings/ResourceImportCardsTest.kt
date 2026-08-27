package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.KnownWordsImportPreview
import com.ankiminer.android.data.resources.WordListKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceImportCardsTest {
    @Test
    fun requestingRemovalDoesNotDispatchUntilConfirmed() {
        val removed = mutableListOf<WordListKind>()

        val requested =
            WordListRemovalConfirmation()
                .request(WordListKind.BLACKLIST)

        assertEquals(WordListKind.BLACKLIST, requested.pending)
        assertEquals(emptyList<WordListKind>(), removed)

        val confirmed = requested.confirm(removed::add)

        assertEquals(listOf(WordListKind.BLACKLIST), removed)
        assertNull(confirmed.pending)
    }

    @Test
    fun cancellingRemovalLeavesTheInstalledListUntouched() {
        val removed = mutableListOf<WordListKind>()

        val cancelled =
            WordListRemovalConfirmation()
                .request(WordListKind.WHITELIST)
                .cancel()

        assertEquals(emptyList<WordListKind>(), removed)
        assertNull(cancelled.pending)
    }

    @Test
    fun aGenericWordListWarnsThatEveryEntryImports() {
        val preview =
            KnownWordsImportPreview(
                format = "generic",
                importedCount = 4200,
                totalEntries = 4200,
                isGeneric = true,
                sampleWords = listOf("食べる"),
            )

        assertTrue(knownWordsPreviewWarnsWholesale(preview))
    }

    @Test
    fun aStatusCarryingExportDoesNotWarn() {
        val preview =
            KnownWordsImportPreview(
                format = "jpdb",
                importedCount = 900,
                totalEntries = 4200,
                isGeneric = false,
                sampleWords = listOf("食べる"),
            )

        assertFalse(knownWordsPreviewWarnsWholesale(preview))
    }
}
