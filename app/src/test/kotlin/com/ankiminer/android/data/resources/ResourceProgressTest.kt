package com.ankiminer.android.data.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceProgressTest {
    @Test
    fun enteringANewPhaseWithoutCountsGoesIndeterminate() {
        val downloaded =
            ResourceOperationProgress(
                operationId = OPERATION,
                label = LABEL,
                phase = ResourceOperationPhase.DOWNLOADING,
                completed = ARCHIVE_BYTES,
                total = ARCHIVE_BYTES,
            )

        val importing =
            downloaded.advancedTo(OPERATION, LABEL, ResourceOperationPhase.IMPORTING)

        // The whole point: a finished download must not present as a finished import.
        assertEquals(0L, importing.completed)
        assertEquals(0L, importing.total)
        assertNull(importing.fraction)
    }

    @Test
    fun countsSurviveOnlyWithinTheSamePhase() {
        val partial =
            ResourceOperationProgress(
                operationId = OPERATION,
                label = LABEL,
                phase = ResourceOperationPhase.DOWNLOADING,
                completed = 4L,
                total = 10L,
            )

        val sustained = partial.advancedTo(OPERATION, LABEL, ResourceOperationPhase.DOWNLOADING)
        assertEquals(4L, sustained.completed)
        assertEquals(10L, sustained.total)

        // A different operation reusing the same phase must not inherit the old numbers.
        val other = partial.advancedTo("other", LABEL, ResourceOperationPhase.DOWNLOADING)
        assertEquals(0L, other.completed)
        assertEquals(0L, other.total)
    }

    @Test
    fun finalizingIsIndeterminateSoTheLastCountedStepIsNotTreatedAsDone() {
        val lastBank =
            ResourceOperationProgress(
                operationId = OPERATION,
                label = LABEL,
                phase = ResourceOperationPhase.IMPORTING,
                completed = 12L,
                total = 12L,
                unit = ResourceProgressUnit.ITEMS,
            )

        val finalizing =
            lastBank.advancedTo(OPERATION, LABEL, ResourceOperationPhase.FINALIZING)

        assertNull(finalizing.fraction)
    }

    @Test
    fun negativeCountsAreClamped() {
        val progress =
            ResourceOperationProgress(OPERATION, LABEL, ResourceOperationPhase.PREPARING)
                .advancedTo(
                    OPERATION,
                    LABEL,
                    ResourceOperationPhase.PREPARING,
                    completed = -5L,
                    total = -1L,
                )

        assertEquals(0L, progress.completed)
        assertEquals(0L, progress.total)
    }

    private companion object {
        const val OPERATION = "operation-1"
        const val LABEL = "Import catalog dictionary"
        const val ARCHIVE_BYTES = 15_493_641L
    }
}
