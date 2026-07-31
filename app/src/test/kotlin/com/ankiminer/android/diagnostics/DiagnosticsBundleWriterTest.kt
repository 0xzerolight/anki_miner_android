package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleWriterTest {
    private val capturedAt = Instant.parse("2026-07-30T14:30:12Z")

    @Test
    fun `archive has the canonical entry set and order`() {
        val sources =
            DiagnosticsBundleSpec.entries.map { entry ->
                source(
                    name = entry.name,
                    text = "${entry.name}\n",
                    capBytes = entry.capBytes,
                    redacted = entry.redacted,
                    required = entry.required,
                    shedding = entry.shedding,
                )
            }
        val archive = ByteArrayOutputStream()

        writer().write(
            destination = archive,
            sources = sources,
            redactor = LineRedactor { it },
            manifest = { linkedMapOf("schema" to "1") },
            readme = { _, _ -> "read me\n" },
        )

        assertEquals(
            DiagnosticsBundleSpec.entries.map { it.name } + listOf("README.txt", "manifest.txt"),
            unzip(archive.toByteArray()).map { it.first },
        )
        assertEquals(
            "anki-miner-diagnostics-20260730T143012Z-0.1.8-a1b2c3d.zip",
            DiagnosticsBundleSpec.fileName(capturedAt, "0.1.8", "a1b2c3dffeedd"),
        )
        assertEquals(
            "anki-miner-diagnostics-20260730T143012Z-0.1_8_rc-abc_def.zip",
            DiagnosticsBundleSpec.fileName(capturedAt, "0.1/8 rc", "abc/defghi"),
        )
    }

    @Test
    fun `missing sources remain explicit empty entries`() {
        val archive = ByteArrayOutputStream()

        val results =
            writer().write(
                destination = archive,
                sources =
                    listOf(
                        BundleSource(
                            name = "logs/missing.log",
                            capBytes = 1024,
                            redacted = true,
                            open = { null },
                        ),
                    ),
                redactor = LineRedactor { it },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            )

        val result = results.single { it.name == "logs/missing.log" }
        assertTrue(result.missing)
        assertEquals(0, result.includedBytes)
        assertEquals(0, result.totalBytes)
        val entry = unzip(archive.toByteArray()).associate { it.first to it.second }["logs/missing.log"]
        assertNotNull(entry)
        assertTrue(String(entry!!, StandardCharsets.UTF_8).contains("END OF ENTRY"))
    }

    @Test
    fun `truncated entries carry head and end markers with exact counts`() {
        val archive = ByteArrayOutputStream()

        val results =
            writer().write(
                destination = archive,
                sources =
                    listOf(
                        source(
                            name = "logs/anki_miner.log",
                            text = "first\nsecond long\nthird\n",
                            capBytes = 16,
                            redacted = true,
                        ),
                    ),
                redactor = LineRedactor { it },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            )

        val result = results.single { it.name == "logs/anki_miner.log" }
        assertEquals(18, result.omittedBytes)
        assertEquals(2, result.omittedLines)
        assertEquals(6, result.includedBytes)
        val text =
            String(
                unzip(archive.toByteArray()).associate { it.first to it.second }.getValue(
                    "logs/anki_miner.log",
                ),
                StandardCharsets.UTF_8,
            )
        assertTrue(text, text.startsWith("### anki-miner-bundle: TRUNCATED HEAD ###\n"))
        assertTrue(text, text.contains("### 18 bytes / 2 lines omitted before this point; entry cap is 16 bytes ###\n"))
        assertTrue(text, text.contains("### The log continues below and is COMPLETE TO THE END OF THE FILE. ###\n"))
        assertTrue(text, text.contains("\nthird\n"))
        assertTrue(
            text,
            text.endsWith(
                "### anki-miner-bundle: END OF ENTRY " +
                    "(captured 2026-07-30T14:30:12Z, 6 bytes) ###\n",
            ),
        )
    }

    @Test
    fun `budget shedding follows the documented rank order`() {
        val full = ByteArrayOutputStream()
        val sources = sheddingSources()
        writer(totalBudgetBytes = Long.MAX_VALUE).write(
            full,
            sources,
            LineRedactor { it },
            manifest = { emptyMap() },
            readme = { _, _ -> "" },
        )
        val fullBytes = unzip(full.toByteArray()).sumOf { it.second.size.toLong() }

        val first =
            writeWithBudget(sources, fullBytes - 512)
        assertTrue(first.getValue("logcat/logcat.txt").capBytes < 4096)
        assertFalse(first.getValue("logs/anki_miner.log.1").dropped)

        val second =
            writeWithBudget(sources, fullBytes - 3_000)
        assertEquals(2048, second.getValue("logcat/logcat.txt").capBytes)
        assertTrue(second.getValue("logs/anki_miner.log.1").dropped)
        assertFalse(second.getValue("logs/app.log.1").dropped)

        val third =
            writeWithBudget(sources, fullBytes - 12_000)
        assertTrue(third.getValue("logs/anki_miner.log.1").dropped)
        assertTrue(third.getValue("logs/app.log.1").dropped)
        assertTrue(third.getValue("logs/anki_miner.log").capBytes < 4096)
    }

    @Test
    fun `redactor is called exactly once for every source line`() {
        val seen = mutableListOf<String>()
        val archive = ByteArrayOutputStream()

        writer().write(
            destination = archive,
            sources =
                listOf(
                    source(
                        name = "logs/app.log",
                        text = "one\ntwo\nthree",
                        capBytes = 1024,
                        redacted = true,
                    ),
                    source(
                        name = "diagnostics.txt",
                        text = "not redacted\n",
                        capBytes = 1024,
                        redacted = false,
                    ),
                ),
            redactor =
                LineRedactor { line ->
                    seen += line
                    line.uppercase()
                },
            manifest = { emptyMap() },
            readme = { _, _ -> "" },
        )

        assertEquals(listOf("one", "two", "three"), seen)
        val log =
            unzip(archive.toByteArray()).associate { it.first to it.second }.getValue("logs/app.log")
        assertTrue(String(log, StandardCharsets.UTF_8).contains("ONE\nTWO\nTHREE"))
    }

    @Test
    fun `manifest observes byte accurate entry results`() {
        val archive = ByteArrayOutputStream()

        val results =
            writer().write(
                destination = archive,
                sources =
                    listOf(
                        source(
                            name = "diagnostics.txt",
                            text = "coded diagnostics\n",
                            capBytes = 4096,
                            redacted = false,
                            required = true,
                        ),
                    ),
                redactor = LineRedactor { it },
                manifest = { entries ->
                    val entry = entries.single { it.name == "diagnostics.txt" }
                    linkedMapOf(
                        "entry.diagnostics.bytes" to entry.includedBytes.toString(),
                        "entry.diagnostics.omitted_bytes" to entry.omittedBytes.toString(),
                        "entry.diagnostics.omitted_lines" to entry.omittedLines.toString(),
                    )
                },
                readme = { _, _ -> "" },
            )

        val files = unzip(archive.toByteArray()).associate { it.first to it.second }
        val result = results.single { it.name == "diagnostics.txt" }
        assertEquals("coded diagnostics\n".toByteArray().size.toLong(), result.includedBytes)
        assertEquals(files.getValue("diagnostics.txt").size.toLong(), result.writtenBytes)
        assertEquals(
            "entry.diagnostics.bytes=18\n" +
                "entry.diagnostics.omitted_bytes=0\n" +
                "entry.diagnostics.omitted_lines=0\n",
            String(files.getValue("manifest.txt"), StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `same captured instant and inputs produce byte identical archives`() {
        fun archive(): ByteArray =
            ByteArrayOutputStream().also { destination ->
                writer().write(
                    destination = destination,
                    sources =
                        listOf(
                            source(
                                name = "logs/app.log",
                                text = "stable\ninput\n",
                                capBytes = 1024,
                                redacted = true,
                            ),
                        ),
                    redactor = LineRedactor { "<$it>" },
                    manifest = { linkedMapOf("stable" to "yes") },
                    readme = { _, manifest -> "stable=${manifest.getValue("stable")}\n" },
                )
            }.toByteArray()

        assertArrayEquals(archive(), archive())
    }

    private fun writer(totalBudgetBytes: Long = 6L * 1024 * 1024) =
        DiagnosticsBundleWriter(
            capturedAt = capturedAt,
            compressionLevel = java.util.zip.Deflater.BEST_COMPRESSION,
            totalBudgetBytes = totalBudgetBytes,
        )

    private fun source(
        name: String,
        text: String,
        capBytes: Long,
        redacted: Boolean,
        required: Boolean = false,
        shedding: BundleShedding = BundleShedding.None,
    ) = BundleSource(
        name = name,
        capBytes = capBytes,
        redacted = redacted,
        required = required,
        shedding = shedding,
        open = { ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)) },
    )

    private fun sheddingSources(): List<BundleSource> =
        listOf(
            source(
                "logcat/logcat.txt",
                lines("logcat"),
                4096,
                true,
                shedding = BundleShedding.Shrink(rank = 1, floorBytes = 2048),
            ),
            source(
                "logs/anki_miner.log.1",
                lines("python-backup"),
                4096,
                true,
                shedding = BundleShedding.Drop(rank = 2),
            ),
            source(
                "logs/app.log.1",
                lines("app-backup"),
                4096,
                true,
                shedding = BundleShedding.Drop(rank = 3),
            ),
            source(
                "logs/anki_miner.log",
                lines("python-current"),
                4096,
                true,
                shedding = BundleShedding.Shrink(rank = 4, floorBytes = 1024),
            ),
            source(
                "diagnostics.txt",
                "required\n",
                4096,
                false,
                required = true,
            ),
        )

    private fun lines(label: String): String =
        buildString {
            var index = 0
            while (length < 5000) {
                append(label).append('-').append(index++).append('\n')
            }
        }

    private fun writeWithBudget(
        sources: List<BundleSource>,
        budget: Long,
    ): Map<String, BundleEntryResult> {
        val archive = ByteArrayOutputStream()
        return writer(budget)
            .write(
                archive,
                sources,
                LineRedactor { it },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            ).associateBy { it.name }
    }

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes()
            }
        }
        return entries
    }
}
