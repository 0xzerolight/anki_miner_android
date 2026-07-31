package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticsBundleWriterTest {
    private val capturedAt = Instant.parse("2026-07-30T14:30:12Z")

    @Test
    fun `archive has the canonical entry set and order`() {
        val expectedEntries = canonicalEntries()
        assertEquals(expectedEntries, DiagnosticsBundleSpec.entries)
        val sources =
            expectedEntries.map { entry ->
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
            expectedEntries.map { it.name } + listOf("README.txt", "manifest.txt"),
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
        assertFalse(result.unavailable)
        assertEquals(0, result.includedBytes)
        assertEquals(0, result.totalBytes)
        val entry = unzip(archive.toByteArray()).associate { it.first to it.second }["logs/missing.log"]
        assertNotNull(entry)
        assertTrue(String(entry!!, StandardCharsets.UTF_8).contains("END OF ENTRY"))
    }

    @Test
    fun `source read failures are unavailable rather than missing and remain explicit`() {
        val archive = ByteArrayOutputStream()

        val results =
            writer().write(
                destination = archive,
                sources =
                    listOf(
                        BundleSource(
                            name = "logs/unavailable.log",
                            capBytes = 1024,
                            redacted = true,
                            open =
                                {
                                    object : InputStream() {
                                        override fun read(): Int = throw IOException("read failed")
                                    }
                                },
                        ),
                        BundleSource(
                            name = "logs/forbidden.log",
                            capBytes = 1024,
                            redacted = true,
                            open = { throw SecurityException("access denied") },
                        ),
                    ),
                redactor = LineRedactor { it },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            )

        val result = results.single { it.name == "logs/unavailable.log" }
        assertTrue(result.unavailable)
        assertFalse(result.missing)
        assertEquals(0, result.includedBytes)
        assertEquals(0, result.totalBytes)
        val entry =
            unzip(archive.toByteArray()).associate { it.first to it.second }["logs/unavailable.log"]
        assertNotNull(entry)
        assertTrue(String(entry!!, StandardCharsets.UTF_8).contains("END OF ENTRY"))
        val forbidden = results.single { it.name == "logs/forbidden.log" }
        assertTrue(forbidden.unavailable)
        assertFalse(forbidden.missing)
        val forbiddenEntry =
            unzip(archive.toByteArray()).associate { it.first to it.second }["logs/forbidden.log"]
        assertNotNull(forbiddenEntry)
        assertTrue(String(forbiddenEntry!!, StandardCharsets.UTF_8).contains("END OF ENTRY"))
    }

    @Test
    fun `preclassified unavailable logcat remains explicit and is not missing`() {
        val logcatSpec = DiagnosticsBundleSpec.entries.single { it.name == "logcat/logcat.txt" }
        val source =
            logcatSpec.logcatSource(
                LogcatCaptureResult(
                    text = "",
                    status = LogcatCaptureStatus.UNAVAILABLE,
                    exitCode = null,
                    omittedBytes = 0,
                    omittedLines = 0,
                ),
            )
        val archive = ByteArrayOutputStream()

        val result =
            writer().write(
                destination = archive,
                sources = listOf(source),
                redactor = LineRedactor { it },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            ).single()

        assertTrue(source.unavailable)
        assertTrue(result.unavailable)
        assertFalse(result.missing)
        val entry = unzip(archive.toByteArray()).associate { it.first to it.second }[source.name]
        assertNotNull(entry)
        assertTrue(String(entry!!, StandardCharsets.UTF_8).contains("END OF ENTRY"))
    }

    @Test
    fun `unsatisfiable uncompressed budget fails before writing a zip`() {
        val archive = ByteArrayOutputStream()
        val source = source("diagnostics.txt", "expand\n", 1024, redacted = true, required = true)

        try {
            writer(totalBudgetBytes = 128).write(
                destination = archive,
                sources = listOf(source),
                redactor = LineRedactor { it.repeat(128) },
                manifest = { emptyMap() },
                readme = { _, _ -> "" },
            )
            fail("expected an unsatisfiable budget failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("uncompressed budget"))
        }

        assertEquals(0, archive.size())
    }

    @Test
    fun `satisfiable archive entry sizes stay within the uncompressed budget`() {
        val budget = 512L
        val archive = ByteArrayOutputStream()

        writer(totalBudgetBytes = budget).write(
            destination = archive,
            sources = listOf(source("diagnostics.txt", "small\n", 1024, redacted = false)),
            redactor = LineRedactor { it },
            manifest = { emptyMap() },
            readme = { _, _ -> "" },
        )

        val total = unzip(archive.toByteArray()).sumOf { it.second.size.toLong() }
        assertTrue("uncompressed entry sum $total exceeds $budget", total <= budget)
    }

    @Test
    fun `manifest and readme count toward the hard budget`() {
        val sources = listOf(source("diagnostics.txt", "required\n", 1024, false, required = true))
        val manifest = { _: List<BundleEntryResult> -> linkedMapOf("detail" to "m".repeat(128)) }
        val readme = { _: List<BundleEntryResult>, _: Map<String, String> -> "r".repeat(128) }
        val unconstrained = ByteArrayOutputStream()
        writer(totalBudgetBytes = Long.MAX_VALUE).write(
            unconstrained,
            sources,
            LineRedactor { it },
            manifest,
            readme,
        )
        val totalBytes = unzip(unconstrained.toByteArray()).sumOf { it.second.size.toLong() }
        val constrained = ByteArrayOutputStream()

        try {
            writer(totalBudgetBytes = totalBytes - 1).write(
                constrained,
                sources,
                LineRedactor { it },
                manifest,
                readme,
            )
            fail("expected synthesized entries to exceed the budget")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("uncompressed budget"))
        }

        assertEquals(0, constrained.size())
    }

    @Test
    fun `unused cap slack is skipped before effective shedding`() {
        val sources =
            listOf(
                source(
                    name = "logcat/logcat.txt",
                    text = "slack\n".repeat(100),
                    capBytes = 4096,
                    redacted = true,
                    shedding = BundleShedding.Shrink(rank = 1, floorBytes = 128),
                ),
            )
        val unconstrained = ByteArrayOutputStream()
        writer(totalBudgetBytes = Long.MAX_VALUE).write(
            unconstrained,
            sources,
            LineRedactor { it },
            manifest = { emptyMap() },
            readme = { _, _ -> "" },
        )
        val fullBytes = unzip(unconstrained.toByteArray()).sumOf { it.second.size.toLong() }
        var preparations = 0

        val results =
            writer(totalBudgetBytes = fullBytes - 1).write(
                ByteArrayOutputStream(),
                sources,
                LineRedactor { it },
                manifest = {
                    preparations++
                    check(preparations <= 10) { "shedding made no effective progress" }
                    emptyMap()
                },
                readme = { _, _ -> "" },
            )

        assertTrue("preparations=$preparations", preparations <= 10)
        assertTrue(results.single().capBytes < 600)
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
        val oldCapturedAt = Instant.parse("2000-01-02T03:04:06Z")
        fun archive(): ByteArray =
            ByteArrayOutputStream().also { destination ->
                writer(capturedAt = oldCapturedAt).write(
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

        val first = archive()
        assertArrayEquals(first, archive())
        assertEquals(List(3) { oldCapturedAt.toEpochMilli() }, zipEntryTimes(first))
    }

    private fun writer(
        totalBudgetBytes: Long = 6L * 1024 * 1024,
        capturedAt: Instant = this.capturedAt,
    ) =
        DiagnosticsBundleWriter(
            capturedAt = capturedAt,
            compressionLevel = java.util.zip.Deflater.BEST_COMPRESSION,
            totalBudgetBytes = totalBudgetBytes,
        )

    private fun canonicalEntries(): List<BundleEntrySpec> =
        listOf(
            BundleEntrySpec(
                name = "logs/anki_miner.log",
                capBytes = 1024L * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.Shrink(rank = 4, floorBytes = 256L * 1024),
            ),
            BundleEntrySpec(
                name = "logs/anki_miner.log.1",
                capBytes = 1024L * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.Drop(rank = 2),
            ),
            BundleEntrySpec(
                name = "logs/app.log",
                capBytes = 512L * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.None,
            ),
            BundleEntrySpec(
                name = "logs/app.log.1",
                capBytes = 512L * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.Drop(rank = 3),
            ),
            BundleEntrySpec(
                name = "logcat/logcat.txt",
                capBytes = 2L * 1024 * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.Shrink(rank = 1, floorBytes = 512L * 1024),
            ),
            BundleEntrySpec(
                name = "diagnostics.txt",
                capBytes = 4L * 1024,
                redacted = false,
                required = true,
                shedding = BundleShedding.None,
            ),
            BundleEntrySpec(
                name = "system/exit-reasons.txt",
                capBytes = 64L * 1024,
                redacted = true,
                required = false,
                shedding = BundleShedding.None,
            ),
            BundleEntrySpec(
                name = "redaction.txt",
                capBytes = 64L * 1024,
                redacted = false,
                required = false,
                shedding = BundleShedding.None,
            ),
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

    private fun zipEntryTimes(bytes: ByteArray): List<Long> {
        val times = mutableListOf<Long>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                times += entry.time
            }
        }
        return times
    }
}
