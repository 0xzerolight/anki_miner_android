package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import com.ankiminer.android.vm.DeckChoiceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiSectionsTest {
    @Test
    fun savedUnavailableDeckExplainsThatItWillBeCreatedOrUsed() {
        assertEquals(
            R.string.anki_deck_saved_unavailable_explanation,
            deckExplanationResource(DeckChoiceKind.SAVED_UNAVAILABLE),
        )
    }
}
