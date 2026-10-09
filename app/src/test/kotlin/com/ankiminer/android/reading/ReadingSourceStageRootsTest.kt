package com.ankiminer.android.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingSourceStageRootsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `sweep roots list the no-backup root before the legacy cache root`() {
        val noBackupFilesDir = temporary.newFolder("no_backup")
        val cacheDir = temporary.newFolder("cache")

        assertEquals(
            listOf(
                File(noBackupFilesDir.canonicalFile, "reading-sources-v1"),
                File(cacheDir.canonicalFile, "reading-sources-v1"),
            ),
            readingSourceSweepRoots(noBackupFilesDir, cacheDir),
        )
    }

    @Test
    fun `janitor sweeps orphans from the live root and the legacy cache root`() {
        val roots = readingSourceSweepRoots(temporary.newFolder("no_backup"), temporary.newFolder("cache"))
        val liveOrphan = orphanStage(roots[0], 'a')
        val legacyOrphan = orphanStage(roots[1], 'b')
        val unrelated = File(roots[1], "future-resource.bin").apply { writeText("keep") }

        assertEquals(2, ReadingSourceStageJanitor(roots).removeOrphans())

        assertFalse(liveOrphan.exists())
        assertFalse(legacyOrphan.exists())
        assertTrue(unrelated.isFile)
        assertEquals(0, ReadingSourceStageJanitor(roots).removeOrphans())
    }

    private fun orphanStage(
        root: File,
        nonce: Char,
    ): File =
        File(root, "reading-job-v1-" + nonce.toString().repeat(32)).apply {
            mkdirs()
            File(this, "Novel.txt").writeText("novel")
        }
}
