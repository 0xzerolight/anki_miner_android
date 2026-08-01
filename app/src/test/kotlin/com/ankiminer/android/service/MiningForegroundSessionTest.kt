package com.ankiminer.android.service

import com.ankiminer.android.R
import com.ankiminer.android.localization.byteProgressResource
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningForegroundSessionTest {
    @Test
    fun `foreground progress carries counts only`() {
        // Static fields are compiler-generated (Compose adds `$stable`); only instance state matters.
        val fieldTypes =
            MiningForegroundProgress::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .associate { it.name to it.type }

        assertEquals(
            "MiningForegroundProgress must not gain a text channel; engine descriptions name " +
                "mined terms and notifications can appear on a locked device",
            mapOf(
                "completed" to Integer::class.java,
                "total" to Integer::class.java,
                "unit" to MiningForegroundProgressUnit::class.java,
            ),
            fieldTypes,
        )
    }

    @Test
    fun `byte counts render through the byte string and item counts through the item string`() {
        val items =
            requireNotNull(
                miningNotificationProgressText(
                    MiningForegroundProgress(2, 3, MiningForegroundProgressUnit.ITEMS),
                ),
            )
        assertEquals(R.string.mining_notification_count, items.resourceId)
        assertEquals(listOf<Any>(2, 3), items.formatArguments)

        val bytes =
            requireNotNull(
                miningNotificationProgressText(
                    MiningForegroundProgress(
                        1024 * 1024,
                        4 * 1024 * 1024,
                        MiningForegroundProgressUnit.BYTES,
                    ),
                ),
            )
        // "1,048,576 of 4,194,304 items" was the defect; the byte scale is the shared rendering.
        assertEquals(R.string.progress_mebibytes, bytes.resourceId)
        assertEquals(listOf<Any>(1f, 4f), bytes.formatArguments)

        assertNull(miningNotificationProgressText(MiningForegroundProgress()))
    }

    @Test
    fun `a sub-megabyte copy does not render as zero megabytes`() {
        // Subtitle tracks, plain text and many EPUBs stage under a megabyte, so a fixed mebibyte
        // scale showed "0.0 of 0.0 MiB" for the ordinary reading source.
        val small =
            requireNotNull(
                miningNotificationProgressText(
                    MiningForegroundProgress(3, 5, MiningForegroundProgressUnit.BYTES),
                ),
            )
        assertEquals(R.string.progress_bytes, small.resourceId)
        assertEquals(listOf<Any>(3L, 5L), small.formatArguments)

        val kibibytes =
            requireNotNull(
                miningNotificationProgressText(
                    MiningForegroundProgress(512, 2048, MiningForegroundProgressUnit.BYTES),
                ),
            )
        assertEquals(R.string.progress_kibibytes, kibibytes.resourceId)
        assertEquals(listOf<Any>(0.5f, 2f), kibibytes.formatArguments)
    }

    @Test
    fun `the byte scale follows the total so a unit cannot change mid-copy`() {
        // Scale is chosen from the total alone; a copy that starts at zero bytes must not begin in
        // one unit and end in another.
        val units =
            listOf(0L, 1L, 512L, 1024L, 700_000L, 1024L * 1024).map { completed ->
                byteProgressResource(completed, 1024L * 1024).resourceId
            }

        assertEquals(List(units.size) { R.string.progress_mebibytes }, units)
        assertEquals(R.string.progress_kibibytes, byteProgressResource(0L, 1024L).resourceId)
        assertEquals(R.string.progress_bytes, byteProgressResource(0L, 1023L).resourceId)
    }

    @Test
    fun `determinate progress requires a complete valid pair`() {
        assertThrows(IllegalArgumentException::class.java) {
            MiningForegroundProgress(completed = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MiningForegroundProgress(completed = 2, total = 1)
        }

        assertEquals(MiningForegroundProgress(1, 2), MiningForegroundProgress(1, 2))
    }

    @Test
    fun `a buffer-per-event copy redraws the notification far less than once per buffer`() {
        val totalBytes = 512 * 1024 * 1024
        val bufferBytes = 256 * 1024
        val buffers = totalBytes / bufferBytes
        var drawn = MiningForegroundProgress(0, totalBytes)
        val redraws = mutableListOf<MiningForegroundProgress>()

        repeat(buffers) { index ->
            val next = MiningForegroundProgress((index + 1) * bufferBytes, totalBytes)
            if (miningNotificationRedrawRequired(drawn, next)) {
                drawn = next
                redraws += next
            }
        }

        assertEquals(2048, buffers)
        // One redraw per rendered percentage point, and the last of them carries the total.
        assertEquals(100, redraws.size)
        assertEquals(MiningForegroundProgress(totalBytes, totalBytes), redraws.last())
    }

    @Test
    fun `redraw guard keeps completion and determinacy changes but drops repeats`() {
        val indeterminate = MiningForegroundProgress()

        assertFalse(miningNotificationRedrawRequired(indeterminate, indeterminate))
        assertTrue(miningNotificationRedrawRequired(indeterminate, MiningForegroundProgress(0, 400)))
        assertTrue(miningNotificationRedrawRequired(MiningForegroundProgress(0, 400), indeterminate))
        // A new total is a new bar even when the percentage is unchanged.
        assertTrue(
            miningNotificationRedrawRequired(
                MiningForegroundProgress(1, 400),
                MiningForegroundProgress(1, 401),
            ),
        )
        assertFalse(
            miningNotificationRedrawRequired(
                MiningForegroundProgress(1, 400),
                MiningForegroundProgress(2, 400),
            ),
        )
        // Completion is never coalesced away, however small the last step was.
        assertTrue(
            miningNotificationRedrawRequired(
                MiningForegroundProgress(399, 400),
                MiningForegroundProgress(400, 400),
            ),
        )
        // Small totals move a percentage point per item, so item counts still redraw per item.
        assertTrue(
            miningNotificationRedrawRequired(
                MiningForegroundProgress(1, 30),
                MiningForegroundProgress(2, 30),
            ),
        )
    }

    @Test
    fun `cpu wake state only moves the lease when the wanted state is not the held one`() {
        // The service applies this on every command rather than on its own intent action, so the
        // two already-there cases have to be no-ops or a resume would re-acquire on each redraw.
        assertFalse(cpuWakeStateChangeRequired(parked = true, owned = false))
        assertFalse(cpuWakeStateChangeRequired(parked = false, owned = true))
        assertTrue(cpuWakeStateChangeRequired(parked = true, owned = true))
        assertTrue(cpuWakeStateChangeRequired(parked = false, owned = false))
    }

    @Test
    fun `cold stale command stops only an ownerless service`() {
        val current =
            MiningForegroundSessionIdentity(
                runId = "run-current",
                generation = 2,
                leaseId = "00000000-0000-4000-8000-000000000002",
            )
        val stale =
            MiningForegroundSessionIdentity(
                runId = "run-stale",
                generation = 1,
                leaseId = "00000000-0000-4000-8000-000000000001",
            )

        assertEquals(
            ForegroundCommandDisposition.STOP_COLD_SERVICE,
            foregroundCommandDisposition(activeIdentity = null, commandIdentity = stale),
        )
        assertEquals(
            ForegroundCommandDisposition.STOP_COLD_SERVICE,
            foregroundCommandDisposition(activeIdentity = null, commandIdentity = null),
        )
        assertEquals(
            ForegroundCommandDisposition.IGNORE_STALE_COMMAND,
            foregroundCommandDisposition(activeIdentity = current, commandIdentity = stale),
        )
        assertEquals(
            ForegroundCommandDisposition.HANDLE_ACTIVE_COMMAND,
            foregroundCommandDisposition(activeIdentity = current, commandIdentity = current),
        )
        assertTrue(current != stale)
    }
}
