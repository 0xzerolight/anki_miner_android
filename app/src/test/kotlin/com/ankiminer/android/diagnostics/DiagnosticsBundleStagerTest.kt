package com.ankiminer.android.diagnostics

import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleStagerTest {
    @Test
    fun `two captures inside one second receive ordered owned names`() {
        val root = Files.createTempDirectory("diagnostics-stager").toFile()
        try {
            val allocator = DiagnosticsBundleNameAllocator(root)
            val first =
                allocator.allocate(
                    capturedAt = Instant.parse("2026-07-30T14:30:12.100Z"),
                    versionName = "0.1.8",
                    sourceCommit = "a1b2c3dffeedd",
                )
            first.writeText("first bundle")

            val second =
                allocator.allocate(
                    capturedAt = Instant.parse("2026-07-30T14:30:12.900Z"),
                    versionName = "0.1.8",
                    sourceCommit = "a1b2c3dffeedd",
                )

            assertEquals(
                "anki-miner-diagnostics-20260730T143012Z-0.1.8-a1b2c3d.zip",
                first.name,
            )
            assertEquals(
                "anki-miner-diagnostics-20260730T143013Z-0.1.8-a1b2c3d.zip",
                second.name,
            )
            assertTrue(first.name < second.name)
            assertTrue(DiagnosticsBundleJanitor.OWNED_NAME.matches(first.name))
            assertTrue(DiagnosticsBundleJanitor.OWNED_NAME.matches(second.name))
            assertEquals("first bundle", first.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
