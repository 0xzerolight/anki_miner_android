package com.ankiminer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafGrantLedgerTest {
    @Test
    fun sameUriReleasesPlatformGrantOnlyAfterFinalSelectionOwner() {
        val ledger = SafGrantLedger()

        ledger.retain("content://test/shared")
        ledger.retain("content://test/shared")

        assertEquals(2, ledger.referenceCount("content://test/shared"))
        assertFalse(ledger.release("content://test/shared"))
        assertEquals(1, ledger.referenceCount("content://test/shared"))
        assertTrue(ledger.release("content://test/shared"))
        assertEquals(0, ledger.referenceCount("content://test/shared"))
    }

    @Test
    fun dictionaryImportOwnerCannotRevokeSameUriVideoOwner() {
        val ledger = SafGrantLedger()
        val shared = "content://test/video-and-dictionary"
        ledger.retain(shared) // video picker
        ledger.retain(shared) // temporary resource importer

        assertFalse(ledger.release(shared))
        assertEquals(1, ledger.referenceCount(shared))
        assertTrue(ledger.release(shared))
    }

    @Test
    fun staleOrRepeatedReleaseCannotConsumeAnotherOwner() {
        val ledger = SafGrantLedger()

        assertFalse(ledger.release("content://test/missing"))
        ledger.retain("content://test/current")
        assertFalse(ledger.release("content://test/missing"))
        assertEquals(1, ledger.referenceCount("content://test/current"))
    }
}
