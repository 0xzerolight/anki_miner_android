package com.ankiminer.android.data.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceOperationJournalTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun resourceImportOwnershipRoundTripsWithTheInterruptedOperation() {
        val root = temporary.newFolder("journal")
        val journal = ResourceOperationJournal(root, syncDirectory = {})
        val operation =
            PersistedResourceOperation(
                origin = ResourceFailureOrigin.FREQUENCY,
                retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                resourceImportUri = "content://provider/frequency.zip",
                resourceImportOwnership = ResourceImportOwnershipPhase.INVENTORY_RETAINED,
            )

        journal.write(operation)

        assertEquals(operation, journal.read())
    }

    @Test
    fun legacyRecordWithoutSafOwnershipRemainsRecoverable() {
        val root = temporary.newFolder("legacy")
        File(root, "resource-operation-v1.pending").writeText(
            "resource-operation-v1\nPITCH\nCHOOSE_ANOTHER\n\nfalse\n\n",
            Charsets.UTF_8,
        )

        val operation = ResourceOperationJournal(root, syncDirectory = {}).read()

        assertEquals(ResourceFailureOrigin.PITCH, operation?.origin)
        assertNull(operation?.resourceImportUri)
        assertNull(operation?.resourceImportOwnership)
    }
}
