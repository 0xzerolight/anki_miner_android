package com.ankiminer.android.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadCpuSnapshotTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun theBusiestThreadIsReportedFirstWithItsTimeInMilliseconds() {
        val root = temporary.newFolder("task")
        writeThread(root, tid = 100, name = "main", utimeTicks = 10, stimeTicks = 5)
        writeThread(root, tid = 101, name = "engine-python", utimeTicks = 2_800, stimeTicks = 200)
        writeThread(root, tid = 102, name = "resource-import", utimeTicks = 300, stimeTicks = 0)

        val snapshot = ThreadCpuSnapshot.read(root, clockTicksPerSecond = 100)

        assertTrue(snapshot, snapshot.contains("threads.total=3"))
        assertTrue(snapshot, snapshot.contains("threads.clock_ticks_per_second=100"))
        // 3000 ticks at 100 Hz is 30 s of CPU: the shape that gets a process killed while cached.
        assertTrue(snapshot, snapshot.contains("thread[0].name=engine-python"))
        assertTrue(snapshot, snapshot.contains("thread[0].cpu_ms=30000"))
        assertTrue(snapshot, snapshot.contains("thread[0].utime_ms=28000"))
        assertTrue(snapshot, snapshot.contains("thread[0].stime_ms=2000"))
        assertTrue(snapshot, snapshot.contains("thread[1].name=resource-import"))
        assertTrue(snapshot, snapshot.contains("thread[2].name=main"))
    }

    @Test
    fun aThreadNameHoldingSpacesAndParenthesesStillParses() {
        // `comm` is free-form and the stat line is split on the LAST ')', so a name like
        // "Chaquopy (bg)" must not shift every field that follows it.
        val parsed =
            ThreadCpuSnapshot.parseStat(
                tid = 7,
                stat = statLine(tid = 7, name = "Chaquopy (bg) 1", utimeTicks = 400, stimeTicks = 100),
                clockTicksPerSecond = 100,
            )

        assertEquals("Chaquopy (bg) 1", parsed?.name)
        assertEquals(5_000L, parsed?.cpuMillis)
    }

    @Test
    fun anUnreadableOrTruncatedStatLineIsSkippedRatherThanGuessed() {
        assertNull(ThreadCpuSnapshot.parseStat(1, "no parentheses here", 100))
        assertNull(ThreadCpuSnapshot.parseStat(1, "1 (main) S 0 0 0", 100))
    }

    @Test
    fun aMissingTaskDirectoryReportsWhyRatherThanAnEmptyEntry() {
        val absent = File(temporary.root, "not-there")

        val snapshot = ThreadCpuSnapshot.read(absent, clockTicksPerSecond = 100)

        assertTrue(snapshot, snapshot.startsWith("Thread CPU times are unavailable:"))
        assertTrue(snapshot, snapshot.endsWith("\n"))
    }

    @Test
    fun anUnusableClockRateNeverDividesByZero() {
        val root = temporary.newFolder("zero-hz")
        writeThread(root, tid = 1, name = "main", utimeTicks = 1, stimeTicks = 1)

        val snapshot = ThreadCpuSnapshot.read(root, clockTicksPerSecond = 0)

        assertTrue(snapshot, snapshot.startsWith("Thread CPU times are unavailable:"))
    }

    private fun writeThread(
        root: File,
        tid: Long,
        name: String,
        utimeTicks: Long,
        stimeTicks: Long,
    ) {
        val directory = File(root, tid.toString()).apply { mkdirs() }
        File(directory, "stat").writeText(statLine(tid, name, utimeTicks, stimeTicks))
    }

    /** `/proc/<tid>/stat` up to and including `stime`, which is field 15. */
    private fun statLine(
        tid: Long,
        name: String,
        utimeTicks: Long,
        stimeTicks: Long,
    ): String {
        val beforeUtime = listOf("S", "0", "0", "0", "0", "-1", "0", "0", "0", "0", "0")
        return "$tid ($name) ${beforeUtime.joinToString(" ")} $utimeTicks $stimeTicks 0 0 20 0 1 0 0\n"
    }
}
