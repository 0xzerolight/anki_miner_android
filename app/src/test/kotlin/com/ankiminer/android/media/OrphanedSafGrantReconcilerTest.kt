package com.ankiminer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanedSafGrantReconcilerTest {
    @Test
    fun `startup removes every surviving read grant exactly once`() {
        val access = FakeAccess(listOf("content://provider/video", "content://provider/subtitle"))
        val reconciler = OrphanedSafGrantReconciler(access)

        reconciler.reconcile()
        reconciler.reconcile()

        assertTrue(reconciler.isReconciled())
        assertEquals(
            listOf("content://provider/video", "content://provider/subtitle"),
            access.released,
        )
        assertEquals(1, access.readCount)
    }

    @Test
    fun `failed inventory read stays retryable`() {
        val access = FakeAccess(listOf("content://provider/video"), failFirstRead = true)
        val reconciler = OrphanedSafGrantReconciler(access)

        assertTrue(runCatching(reconciler::reconcile).isFailure)
        assertFalse(reconciler.isReconciled())
        reconciler.reconcile()

        assertTrue(reconciler.isReconciled())
        assertEquals(listOf("content://provider/video"), access.released)
        assertEquals(2, access.readCount)
    }

    private class FakeAccess(
        private val grants: List<String>,
        private val failFirstRead: Boolean = false,
    ) : PersistedSafGrantAccess {
        var readCount = 0
        val released = mutableListOf<String>()

        override fun readGrantUris(): List<String> {
            readCount += 1
            if (failFirstRead && readCount == 1) error("injected")
            return grants
        }

        override fun releaseReadGrant(uri: String) {
            released += uri
        }
    }
}
