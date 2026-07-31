package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BundleEntryResult(
    val name: String,
    val includedBytes: Long,
    val omittedBytes: Long,
    val omittedLines: Long,
    val totalBytes: Long,
    val capBytes: Long,
    val writtenBytes: Long,
    val truncated: Boolean,
    val dropped: Boolean,
    val missing: Boolean,
)

internal class DiagnosticsBundleWriter(
    private val capturedAt: Instant,
    private val compressionLevel: Int,
    private val totalBudgetBytes: Long,
) {
    init {
        require(compressionLevel in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION)
        require(totalBudgetBytes >= 0)
    }

    fun write(
        destination: OutputStream,
        sources: List<BundleSource>,
        redactor: LineRedactor,
        manifest: (List<BundleEntryResult>) -> Map<String, String>,
        readme: (List<BundleEntryResult>, Map<String, String>) -> String,
    ): List<BundleEntryResult> {
        require(sources.map { it.name }.distinct().size == sources.size) {
            "bundle entry names must be unique"
        }
        sources.forEach(::validate)

        val material = sources.map { source -> read(source, redactor) }
        val caps = sources.associate { it.name to it.capBytes }.toMutableMap()
        val dropped = mutableSetOf<String>()
        lateinit var prepared: FinalArchive
        while (true) {
            val candidate = prepare(material, caps, dropped)
            val manifestValues = manifest(candidate.results)
            val manifestBytes =
                DiagnosticsManifest.render(manifestValues).toByteArray(StandardCharsets.UTF_8)
            val readmeBytes =
                readme(candidate.results, manifestValues).toByteArray(StandardCharsets.UTF_8)
            val total =
                candidate.entries.sumOf { it.bytes.size.toLong() } +
                    manifestBytes.size +
                    readmeBytes.size
            if (total <= totalBudgetBytes || !shed(material, caps, dropped, total - totalBudgetBytes)) {
                prepared = FinalArchive(candidate, manifestBytes, readmeBytes)
                break
            }
        }

        ZipOutputStream(destination).use { zip ->
            zip.setLevel(compressionLevel)
            prepared.candidate.entries.forEach { entry -> writeEntry(zip, entry.name, entry.bytes) }
            writeEntry(zip, README_NAME, prepared.readme)
            writeEntry(zip, MANIFEST_NAME, prepared.manifest)
        }
        return prepared.candidate.results
    }

    private fun read(
        source: BundleSource,
        redactor: LineRedactor,
    ): SourceMaterial {
        val opened =
            try {
                source.open()
            } catch (_: IOException) {
                null
            }
        val tail =
            if (opened == null) {
                LogTailResult("", 0, 0, 0)
            } else {
                try {
                    opened.use { LogTail.of(it, source.capBytes) }
                } catch (_: IOException) {
                    LogTailResult("", 0, 0, 0)
                }
            }
        val lines = splitLines(tail.text)
        val rendered =
            if (source.redacted) {
                lines.map { line -> line.copy(text = redactor.redact(line.text)) }
            } else {
                lines
            }
        return SourceMaterial(
            source = source,
            initialTail = tail,
            initialLines = lines,
            renderedLines = rendered,
            missing = opened == null,
        )
    }

    private fun prepare(
        material: List<SourceMaterial>,
        caps: Map<String, Long>,
        dropped: Set<String>,
    ): Prepared {
        val results = ArrayList<BundleEntryResult>(material.size)
        val entries = ArrayList<PreparedEntry>(material.size)
        material.forEach { source ->
            val cap = caps.getValue(source.source.name)
            if (source.source.name in dropped) {
                results += source.droppedResult(cap)
                return@forEach
            }
            val tail = source.tailAt(cap)
            val body = source.renderedTail(tail)
            val framed = frame(tail, body, cap)
            val result =
                BundleEntryResult(
                    name = source.source.name,
                    includedBytes = body.size.toLong(),
                    omittedBytes = tail.omittedBytes,
                    omittedLines = tail.omittedLines,
                    totalBytes = tail.totalBytes,
                    capBytes = cap,
                    writtenBytes = framed.size.toLong(),
                    truncated = tail.omittedBytes > 0,
                    dropped = false,
                    missing = source.missing,
                )
            results += result
            entries += PreparedEntry(source.source.name, framed)
        }
        return Prepared(results, entries)
    }

    private fun shed(
        material: List<SourceMaterial>,
        caps: MutableMap<String, Long>,
        dropped: MutableSet<String>,
        excessBytes: Long,
    ): Boolean {
        val candidates =
            material.asSequence()
                .filterNot { it.source.required }
                .mapNotNull { source ->
                    val rank =
                        when (val policy = source.source.shedding) {
                            BundleShedding.None -> null
                            is BundleShedding.Drop -> policy.rank
                            is BundleShedding.Shrink -> policy.rank
                        }
                    rank?.let { it to source }
                }.sortedBy { it.first }
                .map { it.second }
                .toList()
        for (candidate in candidates) {
            val name = candidate.source.name
            when (val policy = candidate.source.shedding) {
                BundleShedding.None -> Unit
                is BundleShedding.Drop -> {
                    if (name !in dropped) {
                        dropped += name
                        return true
                    }
                }
                is BundleShedding.Shrink -> {
                    val current = caps.getValue(name)
                    if (current > policy.floorBytes) {
                        caps[name] =
                            maxOf(
                                policy.floorBytes,
                                current - maxOf(excessBytes, 1L),
                            )
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun frame(
        tail: LogTailResult,
        body: ByteArray,
        capBytes: Long,
    ): ByteArray =
        ByteArrayOutputStream().apply {
            if (tail.omittedBytes > 0) {
                writeUtf8("### anki-miner-bundle: TRUNCATED HEAD ###\n")
                writeUtf8(
                    "### ${tail.omittedBytes} bytes / ${tail.omittedLines} lines omitted before " +
                        "this point; entry cap is $capBytes bytes ###\n",
                )
                writeUtf8(
                    "### The log continues below and is COMPLETE TO THE END OF THE FILE. ###\n",
                )
            }
            write(body)
            if (body.isNotEmpty() && body.last() != NEWLINE) write(NEWLINE.toInt())
            writeUtf8(
                "### anki-miner-bundle: END OF ENTRY " +
                    "(captured $capturedAt, ${body.size} bytes) ###\n",
            )
        }.toByteArray()

    private fun SourceMaterial.tailAt(capBytes: Long): LogTailResult {
        if (capBytes == source.capBytes) return initialTail
        val adjusted =
            LogTail.of(
                ByteArrayInputStream(initialTail.text.toByteArray(StandardCharsets.UTF_8)),
                capBytes,
            )
        return adjusted.copy(
            omittedBytes = initialTail.omittedBytes + adjusted.omittedBytes,
            omittedLines = initialTail.omittedLines + adjusted.omittedLines,
            totalBytes = initialTail.totalBytes,
        )
    }

    private fun SourceMaterial.renderedTail(tail: LogTailResult): ByteArray {
        if (tail.text.isEmpty()) return ByteArray(0)
        val initialStart = initialTail.text.length - tail.text.length
        val selected =
            renderedLines.filterIndexed { index, _ ->
                initialLines[index].start >= initialStart
            }
        return joinLines(selected).toByteArray(StandardCharsets.UTF_8)
    }

    private fun SourceMaterial.droppedResult(capBytes: Long): BundleEntryResult {
        val retainedLines = initialTail.text.count { it == '\n' }.toLong()
        return BundleEntryResult(
            name = source.name,
            includedBytes = 0,
            omittedBytes = initialTail.totalBytes,
            omittedLines = initialTail.omittedLines + retainedLines,
            totalBytes = initialTail.totalBytes,
            capBytes = capBytes,
            writtenBytes = 0,
            truncated = initialTail.totalBytes > 0,
            dropped = true,
            missing = missing,
        )
    }

    private fun splitLines(text: String): List<LinePart> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<LinePart>()
        var start = 0
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                lines += LinePart(text.substring(start, index), hasNewline = true, start = start)
                start = index + 1
            }
        }
        if (start < text.length) {
            lines += LinePart(text.substring(start), hasNewline = false, start = start)
        }
        return lines
    }

    private fun joinLines(lines: List<LinePart>): String =
        buildString {
            lines.forEach { line ->
                append(line.text)
                if (line.hasNewline) append('\n')
            }
        }

    private fun validate(source: BundleSource) {
        require(source.name.isNotEmpty())
        require(!source.name.startsWith('/'))
        require('\\' !in source.name)
        require(source.name.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require(source.capBytes in 0 until Int.MAX_VALUE.toLong())
        when (val policy = source.shedding) {
            BundleShedding.None -> Unit
            is BundleShedding.Drop -> require(policy.rank > 0)
            is BundleShedding.Shrink -> {
                require(policy.rank > 0)
                require(policy.floorBytes in 0..source.capBytes)
            }
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(name)
        entry.time = capturedAt.toEpochMilli()
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun ByteArrayOutputStream.writeUtf8(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    private data class LinePart(
        val text: String,
        val hasNewline: Boolean,
        val start: Int,
    )

    private data class SourceMaterial(
        val source: BundleSource,
        val initialTail: LogTailResult,
        val initialLines: List<LinePart>,
        val renderedLines: List<LinePart>,
        val missing: Boolean,
    )

    private data class PreparedEntry(
        val name: String,
        val bytes: ByteArray,
    )

    private data class Prepared(
        val results: List<BundleEntryResult>,
        val entries: List<PreparedEntry>,
    )

    private data class FinalArchive(
        val candidate: Prepared,
        val manifest: ByteArray,
        val readme: ByteArray,
    )

    private companion object {
        const val README_NAME = "README.txt"
        const val MANIFEST_NAME = "manifest.txt"
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
