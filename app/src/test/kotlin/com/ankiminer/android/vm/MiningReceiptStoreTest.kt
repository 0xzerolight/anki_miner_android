package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.ui.mining.MiningReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiningReceiptStoreTest {
    private val receipt =
        MiningReceipt(
            "run_1",
            notesAdded = 2,
            deckName = "Anki Miner",
            noteIds = listOf(10, 11),
            minedForms = listOf("猫", "犬"),
            minedFormsLanguage = "de",
        )

    @Test
    fun aReceiptRoundTripsThroughSavedState() {
        val handle = SavedStateHandle()
        MiningReceiptStore(handle, "videoMining.receipt").save(receipt)

        assertEquals(receipt, MiningReceiptStore(handle, "videoMining.receipt").restore())
    }

    @Test
    fun aReceiptSavedBeforeItsLanguageWasKeptRestoresABlankLanguage() {
        val handle = SavedStateHandle()
        MiningReceiptStore(handle, "videoMining.receipt").save(receipt)
        handle.remove<String>("videoMining.receipt.minedFormsLanguage")

        assertEquals(
            receipt.copy(minedFormsLanguage = ""),
            MiningReceiptStore(handle, "videoMining.receipt").restore(),
        )
    }

    @Test
    fun anOversizedRunIsNotSavedAndClearsAnOlderReceipt() {
        val handle = SavedStateHandle()
        val store = MiningReceiptStore(handle, "videoMining.receipt")
        store.save(receipt)
        store.save(receipt.copy(noteIds = (1L..(MAX_SAVED_RECEIPT_NOTES + 1L)).toList()))

        assertNull(store.restore())
    }

    @Test
    fun clearForgetsTheReceipt() {
        val store = MiningReceiptStore(SavedStateHandle(), "videoMining.receipt")
        store.save(receipt)
        store.clear()

        assertNull(store.restore())
    }
}
