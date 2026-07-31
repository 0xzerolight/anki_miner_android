package com.ankiminer.android.diagnostics

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleJanitorTest {
    private val now = Instant.parse("2026-07-31T12:00:00Z")

    @Test
    fun `age cleanup touches only owned bundles older than 24 hours`() {
        val root = Files.createTempDirectory("diagnostics-janitor").toFile()
        try {
            val expired = file(root, owned("expired"), now.minus(Duration.ofHours(25)))
            val fresh = file(root, owned("fresh"), now.minus(Duration.ofHours(23)))
            val foreign = file(root, "notes.zip", now.minus(Duration.ofDays(10)))

            val removed = DiagnosticsBundleJanitor(root, now = { now }).clean()

            assertEquals(listOf(expired), removed)
            assertFalse(expired.exists())
            assertTrue(fresh.exists())
            assertTrue(foreign.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `count cleanup retains the eight newest owned bundles`() {
        val root = Files.createTempDirectory("diagnostics-count").toFile()
        try {
            val owned =
                (0 until 10).map { index ->
                    file(root, owned(index.toString()), now.minusSeconds(index.toLong()))
                }

            val removed = DiagnosticsBundleJanitor(root, now = { now }).clean()

            assertEquals(owned.drop(8).toSet(), removed.toSet())
            assertEquals(owned.take(8).toSet(), root.listFiles()!!.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `absent bundle directory is a no-op`() {
        val parent = Files.createTempDirectory("diagnostics-absent").toFile()
        try {
            val root = parent.resolve("absent")

            assertEquals(emptyList<java.io.File>(), DiagnosticsBundleJanitor(root, now = { now }).clean())
            assertFalse(root.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `stale owned temporary artifacts are removed without touching fresh or similar names`() {
        val root = Files.createTempDirectory("diagnostics-temporary").toFile()
        try {
            val oldWorkspace =
                directory(root, ".capture-1753959600000", now.minus(Duration.ofHours(25)))
            val oldBuilding = file(root, ".building-123456789.zip", now.minus(Duration.ofHours(25)))
            val freshWorkspace =
                directory(root, ".capture-1754042400000", now.minus(Duration.ofHours(23)))
            val freshBuilding = file(root, ".building-987654321.zip", now.minus(Duration.ofHours(23)))
            val foreign =
                listOf(
                    directory(root, ".capture-live", now.minus(Duration.ofDays(3))),
                    file(root, ".building-live.zip", now.minus(Duration.ofDays(3))),
                    file(root, ".building-123456789.zip.part", now.minus(Duration.ofDays(3))),
                )

            val removed = DiagnosticsBundleJanitor(root, now = { now }).clean()

            assertEquals(setOf(oldWorkspace, oldBuilding), removed.toSet())
            assertFalse(oldWorkspace.exists())
            assertFalse(oldBuilding.exists())
            assertTrue(freshWorkspace.exists())
            assertTrue(freshBuilding.exists())
            foreign.forEach { artifact -> assertTrue(artifact.exists()) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun owned(id: String) =
        "anki-miner-diagnostics-20260730T143012Z-0.1.8-${id.padEnd(7, 'a')}.zip"

    private fun file(
        root: java.io.File,
        name: String,
        modifiedAt: Instant,
    ) = root.resolve(name).apply {
        writeText(name)
        assertTrue(setLastModified(modifiedAt.toEpochMilli()))
    }

    private fun directory(
        root: java.io.File,
        name: String,
        modifiedAt: Instant,
    ) = root.resolve(name).apply {
        mkdirs()
        resolve("partial").writeText(name)
        assertTrue(setLastModified(modifiedAt.toEpochMilli()))
    }
}
