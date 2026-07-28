package com.ankiminer.android.data.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSizeBudgetTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun budgetHalvesFreeSpaceAfterTheReserve() {
        val free = ARCHIVE_BUDGET_RESERVE_BYTES + 8L * 1024 * 1024 * 1024
        assertEquals(4L * 1024 * 1024 * 1024, audioArchiveBudget(free))
    }

    @Test
    fun budgetClearsTwoGigabytesOnAnOrdinaryPhone() {
        // The bug this replaced: a fixed 2 GiB cap rejected every large local
        // audio pack regardless of how much room the device had.
        val free = 24L * 1024 * 1024 * 1024
        assertTrue(audioArchiveBudget(free) > 2L * 1024 * 1024 * 1024)
    }

    @Test
    fun budgetStopsAtTheAbsoluteCeiling() {
        assertEquals(AUDIO_ARCHIVE_CEILING_BYTES, audioArchiveBudget(Long.MAX_VALUE))
    }

    @Test
    fun budgetStaysPositiveWhenStorageIsExhausted() {
        assertEquals(1L, audioArchiveBudget(0))
        assertEquals(1L, audioArchiveBudget(ARCHIVE_BUDGET_RESERVE_BYTES))
        assertEquals(1L, audioArchiveBudget(-1))
    }

    @Test
    fun stagingSpaceFallsBackToTheNearestExistingAncestor() {
        val existing = temporary.newFolder("root")
        val missing = File(existing, "staging/not/created/yet")

        assertEquals(0L, missing.usableSpace)
        assertEquals(existing.usableSpace, usableSpaceForStaging(missing))
    }

    @Test
    fun sizesReadInTheUnitsPeopleUse() {
        assertEquals("512 B", formatArchiveBytes(512))
        assertEquals("1.0 KB", formatArchiveBytes(1024))
        assertEquals("1.5 MB", formatArchiveBytes(1536L * 1024))
        assertEquals("3.0 GB", formatArchiveBytes(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun rejectionCarriesTheLabelAndBothSizes() {
        val failure = archiveTooLarge("audio-pack ZIP", 3L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024)

        assertEquals("resource_archive_too_large", failure.stableCode)
        assertEquals(listOf("audio-pack ZIP", "3.0 GB", "2.0 GB"), failure.formatArguments)
    }
}
