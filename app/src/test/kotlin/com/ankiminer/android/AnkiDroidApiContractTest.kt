package com.ankiminer.android

import com.ichi2.anki.FlashCardsContract
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiDroidApiContractTest {
    @Test
    fun vendoredContractUsesReleaseAnkiDroidEndpoint() {
        assertEquals("com.ichi2.anki.flashcards", FlashCardsContract.AUTHORITY)
        assertEquals(
            "com.ichi2.anki.permission.READ_WRITE_DATABASE",
            FlashCardsContract.READ_WRITE_PERMISSION,
        )
    }
}
