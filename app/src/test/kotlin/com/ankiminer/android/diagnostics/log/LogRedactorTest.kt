package com.ankiminer.android.diagnostics.log

import com.ankiminer.android.data.settings.AppSettings
import java.io.File
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogRedactorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val filesDir by lazy { temporaryFolder.newFolder("files") }
    private val cacheDir by lazy { temporaryFolder.newFolder("cache") }
    private val noBackupDir by lazy { temporaryFolder.newFolder("no_backup") }
    private val nativeLibDir by lazy { temporaryFolder.newFolder("lib") }
    private val externalDir by lazy { temporaryFolder.newFolder("ext") }

    // Rule 1

    @Test
    fun `app directory roots become readable symbolic tokens`() {
        val redacted =
            redactor().redact(
                "files=$filesDir cache=$cacheDir nobackup=$noBackupDir " +
                    "nativelib=$nativeLibDir ext=$externalDir",
            )

        assertEquals(
            "files=<files> cache=<cache> nobackup=<nobackup> " +
                "nativelib=<nativelib> ext=<extfiles-0>",
            redacted,
        )
    }

    @Test
    fun `a root is recognised through the symlink its canonical path resolves to`() {
        // The shape this exists for: on API 26 /data/user/0 is a symlink to /data/data, and the
        // bridge request builders log canonicalPath while everything else logs absolutePath.
        val real = temporaryFolder.newFolder("real")
        val link = File(temporaryFolder.root, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val viaLink = File(link, "files").also { it.mkdirs() }
        val viaReal = File(real, "files")

        val redacted =
            redactor(roots = mapOf("files" to viaLink)).redact("a=$viaLink b=$viaReal")

        assertEquals("a=<files> b=<files>", redacted)
    }

    // Rule 2

    @Test
    fun `an absolute path outside the app directories becomes one path token`() {
        val redacted = redactor().redact("source=/storage/emulated/0/Download/movie.mkv")

        assertTrue(redacted, redacted.matches(Regex("source=<path-[0-9a-f]{6}>")))
    }

    @Test
    fun `a path segment inside a longer path does not start a second match`() {
        // "media/data/x" must not be split at /data: absolute paths start at a boundary.
        val redacted = redactor().redact("$filesDir/media/data/clip.mkv")

        assertFalse(redacted, redacted.contains("<path-"))
        assertTrue(redacted, redacted.startsWith("<files>/media/data/<file-"))
    }

    @Test
    fun `a path behind a file scheme still matches`() {
        // A slash is deliberately absent from the boundary lookbehind for exactly this shape.
        val redacted = redactor().redact("uri=file:///storage/emulated/0/Movies/ep.mkv")

        assertTrue(redacted, redacted.matches(Regex("uri=file://<path-[0-9a-f]{6}>")))
    }

    // Rule 3

    @Test
    fun `a leaf filename under an app root is replaced but keeps its extension`() {
        val redacted = redactor().redact("staged=$cacheDir/saf-input/job-7/episode-01.mkv")

        assertTrue(
            redacted,
            redacted.matches(Regex("staged=<cache>/saf-input/job-7/<file-[0-9a-f]{6}>\\.mkv")),
        )
    }

    @Test
    fun `an extension that is not plain alphanumeric is dropped rather than copied through`() {
        // The extension is copied verbatim, so a Japanese one would leak half of the leaf.
        val redacted = redactor().redact("$filesDir/殺す.動画")

        assertFalse(redacted, redacted.contains("動画"))
        assertTrue(redacted, redacted.matches(Regex("<files>/<file-[0-9a-f]{6}>")))
    }

    // Rule 4

    @Test
    fun `a content uri keeps its authority and loses its document id`() {
        val redacted =
            redactor().redact(
                "uri=content://com.android.externalstorage.documents/document/primary%3AMovies",
            )

        assertTrue(
            redacted,
            redacted.matches(
                Regex("uri=content://com\\.android\\.externalstorage\\.documents/<doc-[0-9a-f]{6}>"),
            ),
        )
    }

    // Rule 5

    @Test
    fun `a saf display name is redacted where it appears outside any path`() {
        val redacted =
            redactor(safDisplayNames = listOf("Kimi no Na wa.mkv"))
                .redact("op=saf.picked name=\"Kimi no Na wa.mkv\"")

        assertTrue(redacted, redacted.matches(Regex("op=saf\\.picked name=\"<saf-[0-9a-f]{6}>\\.mkv\"")))
    }

    @Test
    fun `a japanese saf display name is claimed by rule 5 before rule 7 sees it`() {
        val redacted =
            redactor(safDisplayNames = listOf("殺す動画.mkv"))
                .redact("name=殺す動画.mkv")

        assertTrue(redacted, redacted.startsWith("name=<saf-"))
        assertFalse(redacted, redacted.contains("<jp-"))
    }

    // Rule 6

    @Test
    fun `anki target text from settings is redacted`() {
        val settings =
            AppSettings(
                deckName = "Immersion::Anime",
                excludedDecks = listOf("Retired Cards"),
                noteType = "JP Mining Note",
                fieldMap = mapOf("expression" to "WordReading"),
                cardTypeMarkerField = "IsWordAndSentenceCard",
                tags = "mined-from-anime",
            )
        val line =
            "deck=Immersion::Anime excluded=\"Retired Cards\" note=\"JP Mining Note\" " +
                "field=WordReading marker=IsWordAndSentenceCard tags=mined-from-anime"

        val redacted = redactor(settings = settings).redact(line)

        listOf(
            "Immersion::Anime",
            "Retired Cards",
            "JP Mining Note",
            "WordReading",
            "IsWordAndSentenceCard",
            "mined-from-anime",
        ).forEach { secret -> assertFalse(redacted, redacted.contains(secret)) }
        assertTrue(redacted, redacted.contains("deck=<deck-"))
        assertTrue(redacted, redacted.contains("note=\"<notetype-"))
        assertTrue(redacted, redacted.contains("field=<field-"))
        assertTrue(redacted, redacted.contains("tags=<tag-"))
    }

    @Test
    fun `a literal shorter than three characters is skipped rather than shredding the file`() {
        val redacted =
            redactor(settings = AppSettings(deckName = "JP"))
                .redact("op=anki.batch deck=JP note=JPMN status=JPEG")

        assertEquals("op=anki.batch deck=JP note=JPMN status=JPEG", redacted)
    }

    // Rule 7

    @Test
    fun `a run of japanese characters is replaced and its length preserved`() {
        val redacted = redactor().redact("expression=食べる sentence=カタカナ")

        assertTrue(
            redacted,
            redacted.matches(Regex("expression=<jp-[0-9a-f]{6}:3> sentence=<jp-[0-9a-f]{6}:4>")),
        )
    }

    @Test
    fun `a single japanese character is below the threshold and survives`() {
        val redacted = redactor().redact("reading=猫 kana=あ")

        assertEquals("reading=猫 kana=あ", redacted)
    }

    // Rule 8

    @Test
    fun `a percent encoded jisho url is redacted even though rule 7 cannot see it`() {
        val redacted =
            redactor().redact(
                "GET https://jisho.org/api/v1/search/words?keyword=%E6%AE%BA%E3%81%99 200",
            )

        assertFalse(redacted, redacted.contains("%E6"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "GET https://jisho\\.org/api/v1/search/words\\?keyword=<jp-enc-[0-9a-f]{6}> 200",
                ),
            ),
        )
    }

    @Test
    fun `a percent sequence that decodes to ascii is left alone`() {
        val redacted = redactor().redact("query=%41%42%43%2D%31 done")

        assertEquals("query=%41%42%43%2D%31 done", redacted)
    }

    // Rule 9

    @Test
    fun `the build user is redacted`() {
        val redacted = redactor(buildUser = "lazarev").redact("build=lazarev fingerprint=x")

        assertTrue(redacted, redacted.matches(Regex("build=<user-[0-9a-f]{6}> fingerprint=x")))
    }

    @Test
    fun `rule 2 already hides the android user id before rule 9 can label it`() {
        // Documents a real overlap rather than asserting a token that never appears: rule 2 matches
        // /storage/... and seals the whole path, so <user-10> is unreachable for an absolute path.
        val redacted = redactor().redact("root=/storage/emulated/10/Movies")

        assertFalse(redacted, redacted.contains("emulated"))
        assertFalse(redacted, redacted.contains("<user-"))
        assertTrue(redacted, redacted.matches(Regex("root=<path-[0-9a-f]{6}>")))
    }

    // Token stability

    @Test
    fun `the same text yields the same token on two different lines`() {
        val redactor = redactor()

        val first = redactor.redact("a=/storage/emulated/0/Movies/ep.mkv")
        val second = redactor.redact("b=/storage/emulated/0/Movies/ep.mkv other=1")

        val token = Regex("<path-[0-9a-f]{6}>").find(first)!!.value
        assertTrue(second, second.contains(token))
    }

    @Test
    fun `a different salt yields a different token for the same text`() {
        val line = "a=/storage/emulated/0/Movies/ep.mkv"

        val first = redactor(salt = ByteArray(16) { 1 }).redact(line)
        val second = redactor(salt = ByteArray(16) { 2 }).redact(line)

        assertNotEquals(first, second)
    }

    @Test
    fun `colliding fingerprints get a counter so two texts never share one token`() {
        // 24 bits of fingerprint over this many distinct strings collides many times over; the
        // counter is what keeps the mapping injective. Fixed salt, so this is deterministic.
        val mint = TokenMint(ByteArray(16) { 9 })

        val tokens = (0 until 20_000).map { index -> mint.token("file", "sample-$index.mkv") }

        assertEquals(tokens.size, tokens.toSet().size)
        assertTrue(tokens.any { it.contains('#') })
    }

    @Test
    fun `token counts report per kind without exposing the mapping`() {
        val redactor =
            redactor(
                settings = AppSettings(deckName = "Immersion", noteType = "JP Mining Note"),
                safDisplayNames = listOf("episode.mkv"),
            )

        redactor.redact("a=/storage/emulated/0/one.mkv b=/storage/emulated/0/two.mkv 猫猫")
        redactor.redact("c=/storage/emulated/0/one.mkv")

        val counts = redactor.tokenCounts()
        assertEquals(2, counts["path"])
        assertEquals(1, counts["jp"])
        assertEquals(1, counts["deck"])
        assertEquals(1, counts["notetype"])
        assertEquals(1, counts["saf"])
    }

    // Record format

    @Test
    fun `a record with a continuation line and quoted specials keeps its line count`() {
        val record =
            "2026-07-30T12:00:00.000Z E run=abc c=media op=media.extract " +
                "path=\"$filesDir/殺す.mkv\" note=\"he said \\\"go\\\" then \\n stopped\"" +
                "\n\tjava.io.IOException: $cacheDir/job/clip.mkv (カタカナ)" +
                "\n\t    at com.ankiminer.android.media.Extractor.run(Extractor.kt:41)"

        val redacted = record.lines().joinToString("\n") { redactor().redact(it) }

        assertEquals(record.lines().size, redacted.lines().size)
        assertEquals(3, redacted.lines().size)
        redacted.lines().drop(1).forEach { line -> assertTrue(line, line.startsWith("\t")) }
        assertTrue(redacted, redacted.lines()[0].contains("note=\"he said \\\"go\\\" then \\n stopped\""))
        assertFalse(redacted, redacted.contains("殺す"))
        assertFalse(redacted, redacted.contains("カタカナ"))
        assertTrue(redacted, redacted.contains("at com.ankiminer.android.media.Extractor.run"))
    }

    // Streaming

    @Test
    fun `a four megabyte file streams through with bounded reads and writes`() {
        val source = temporaryFolder.newFile("big.log")
        var lines = 0
        source.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            var written = 0L
            var index = 0
            while (written < FOUR_MEBIBYTES) {
                val line =
                    "2026-07-30T12:00:00.000Z I run=abc c=mining op=mining.word " +
                        "path=\"$filesDir/media/clip-$index.mkv\" " +
                        "expression=食べる uri=content://media/document/$index\n"
                writer.write(line)
                written += line.length
                index++
                lines++
            }
        }

        val reader = CountingReader(source.reader(StandardCharsets.UTF_8))
        val writer = CountingWriter()
        val startedAt = System.nanoTime()
        reader.use { LogRedactor(rules()).redactLines(it, writer) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(lines, writer.lines)
        // Bounded, not a fixed number: both stay at a buffer's worth however large the file gets.
        assertTrue("largest read ${reader.largestRead}", reader.largestRead <= BOUNDED_CHUNK)
        assertTrue("largest write ${writer.largestWrite}", writer.largestWrite <= BOUNDED_CHUNK)
        assertTrue("elapsed ${elapsedMillis}ms", elapsedMillis < STREAM_BUDGET_MILLIS)
    }

    @Test
    fun `redactInto writes the redacted copy without touching the source`() =
        kotlinx.coroutines.test.runTest {
            val source = temporaryFolder.newFile("app.log")
            val original = "a=/storage/emulated/0/Movies/ep.mkv\nb=食べる\n"
            source.writeText(original)
            val destination = File(temporaryFolder.root, "redacted.log")

            LogRedactor(rules()).redactInto(source, destination)

            assertEquals(original, source.readText())
            val redacted = destination.readLines()
            assertEquals(2, redacted.size)
            assertTrue(redacted[0], redacted[0].matches(Regex("a=<path-[0-9a-f]{6}>")))
            assertTrue(redacted[1], redacted[1].matches(Regex("b=<jp-[0-9a-f]{6}:3>")))
        }

    private fun redactor(
        settings: AppSettings = AppSettings(),
        safDisplayNames: List<String> = emptyList(),
        buildUser: String? = null,
        salt: ByteArray = FIXED_SALT,
        roots: Map<String, File> = defaultRoots(),
    ) = LogRedactor(rules(settings, safDisplayNames, buildUser, salt, roots))

    private fun rules(
        settings: AppSettings = AppSettings(),
        safDisplayNames: List<String> = emptyList(),
        buildUser: String? = null,
        salt: ByteArray = FIXED_SALT,
        roots: Map<String, File> = defaultRoots(),
    ) = RedactionRulesFactory.forExport(roots, settings, safDisplayNames, buildUser, salt)

    private fun defaultRoots(): Map<String, File> =
        linkedMapOf(
            "files" to filesDir,
            "cache" to cacheDir,
            "nobackup" to noBackupDir,
            "nativelib" to nativeLibDir,
            "extfiles-0" to externalDir,
        )

    private class CountingReader(private val delegate: Reader) : Reader() {
        var largestRead = 0
            private set

        override fun read(
            buffer: CharArray,
            offset: Int,
            length: Int,
        ): Int {
            largestRead = maxOf(largestRead, length)
            return delegate.read(buffer, offset, length)
        }

        override fun close() = delegate.close()
    }

    private class CountingWriter : Writer() {
        var largestWrite = 0
            private set

        var lines = 0
            private set

        override fun write(
            buffer: CharArray,
            offset: Int,
            length: Int,
        ) {
            largestWrite = maxOf(largestWrite, length)
            for (index in offset until offset + length) if (buffer[index] == '\n') lines++
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private companion object {
        val FIXED_SALT = ByteArray(16) { index -> index.toByte() }
        const val FOUR_MEBIBYTES = 4L * 1024 * 1024
        const val BOUNDED_CHUNK = 64 * 1024
        const val STREAM_BUDGET_MILLIS = 60_000L
    }
}
