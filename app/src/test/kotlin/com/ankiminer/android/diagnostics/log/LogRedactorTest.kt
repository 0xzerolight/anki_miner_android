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
    fun `a quoted path with spaces becomes one token and leaves the field structure alone`() {
        val line =
            "2026-07-30T12:00:00.000Z I run=abc c=media op=media.extract " +
                "path=\"/storage/emulated/0/My Anime Shows/ep 01.mkv\" bytes=42"

        val redacted = redactor().redact(line)

        assertEquals(
            "2026-07-30T12:00:00.000Z I run=abc c=media op=media.extract " +
                "path=\"<path-${fingerprintIn(redacted)}>\" bytes=42",
            redacted,
        )
        assertEquals(fieldCount(line), fieldCount(redacted))
    }

    @Test
    fun `a space next to the closing quote stays outside the token`() {
        val redacted = redactor().redactRecord("path=\"/storage/emulated/0/My Videos/ep.mkv \" n=1")

        assertTrue(redacted, redacted.matches(Regex("path=\"<path-[0-9a-f]{6}> \" n=1")))
    }

    @Test
    fun `a quoted leaf under an app root survives spaces and keeps its extension`() {
        val redacted = redactor().redactRecord("path=\"$filesDir/media/My Video 01.mkv\" n=1")

        assertTrue(
            redacted,
            redacted.matches(Regex("path=\"<files>/media/<file-[0-9a-f]{6}>\\.mkv\" n=1")),
        )
    }

    @Test
    fun `an unquoted path with spaces on a continuation line is redacted whole`() {
        // renderText writes throwable messages with no quoting at all, so this line has no closing
        // quote to bound the match. Exception messages carrying paths are the most common content
        // in this file, and the quoted-branch fix did not reach them.
        val redacted =
            redactor().redact(
                "\tjava.io.FileNotFoundException: /storage/emulated/0/My Anime/ep 01.mkv: " +
                    "open failed: ENOENT",
            )

        assertFalse(redacted, redacted.contains("Anime"))
        assertFalse(redacted, redacted.contains("01.mkv"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "\tjava\\.io\\.FileNotFoundException: <path-[0-9a-f]{6}>: open failed: ENOENT",
                ),
            ),
        )
    }

    @Test
    fun `a path mid sentence inside a quoted value is redacted whole`() {
        // The quoted branch needs the path at offset 0 of the value, so this one falls through to
        // the unquoted branch. The trailing prose is absorbed and hashed under its own token: it
        // could just as easily be the rest of a directory name.
        val redacted =
            redactor().redactRecord(
                "msg=\"failed to open /storage/emulated/0/My Anime/ep.mkv for reading\"",
            )

        assertFalse(redacted, redacted.contains("Anime"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex("msg=\"failed to open <path-[0-9a-f]{6}> <text-[0-9a-f]{6}>\""),
            ),
        )
    }

    @Test
    fun `an unquoted app root leaf with spaces is redacted whole`() {
        val redacted = redactor().redact("\tjava.io.IOException: $filesDir/media/My Video 01.mkv (open)")

        assertFalse(redacted, redacted.contains("My Video"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "\tjava\\.io\\.IOException: <files>/media/<file-[0-9a-f]{6}>\\.mkv " +
                        "<text-[0-9a-f]{6}>",
                ),
            ),
        )
    }

    @Test
    fun `an exception message keeps its failure reason after the path`() {
        // ENOENT against EACCES is missing-file against permission-denied, and that is frequently
        // the whole answer to a bug report.
        val redacted =
            redactor().redact(
                "\tjava.io.FileNotFoundException: /storage/emulated/0/My Anime/ep 01.mkv: " +
                    "open failed: ENOENT (No such file or directory)",
            )

        assertFalse(redacted, redacted.contains("Anime"))
        assertFalse(redacted, redacted.contains("01.mkv"))
        assertFalse(redacted, redacted.contains("<text-"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "\tjava\\.io\\.FileNotFoundException: <path-[0-9a-f]{6}>: " +
                        "open failed: ENOENT \\(No such file or directory\\)",
                ),
            ),
        )
    }

    @Test
    fun `a colon inside a title does not end absorption early`() {
        // Stopping at any colon-space would publish the rest of the title here. Absorption only
        // stops once the colon follows a file extension, so a path can still continue past one.
        val redacted =
            redactor().redact(
                "\tIOException: /storage/emulated/0/Movies/Star Wars: A New Hope.mkv: open failed",
            )

        listOf("Star", "Wars", "A New Hope", "Hope.mkv").forEach { secret ->
            assertFalse(redacted, redacted.contains(secret))
        }
        assertTrue(
            redacted,
            redacted.matches(Regex("\tIOException: <path-[0-9a-f]{6}>: open failed")),
        )
    }

    @Test
    fun `a directory with no extension still absorbs to end of line`() {
        // No colon-space, and nothing that looks like a file name, so the stop never fires and the
        // folder stays protected.
        val redacted = redactor().redact("\tIOException: /storage/emulated/0/My Anime Shows (dir missing)")

        listOf("Anime", "Shows", "dir missing").forEach { secret ->
            assertFalse(redacted, redacted.contains(secret))
        }
        assertTrue(
            redacted,
            redacted.matches(Regex("\tIOException: <path-[0-9a-f]{6}> <text-[0-9a-f]{6}>")),
        )
    }

    @Test
    fun `one path carries one token across all five carriers`() {
        val redactor = redactor()
        val path = "/storage/emulated/0/My Anime/ep 01.mkv"

        val quoted = redactor.redactRecord("path=\"$path\"")
        val bare = redactor.redact("\tIOException: $path")
        val parenthesised = redactor.redact("\tIOException: opened ($path)")
        val repr = redactor.redact("\tPyException: FileNotFoundError: '$path'")
        val exception = redactor.redact("\tjava.io.FileNotFoundException: $path: open failed: ENOENT")

        val token = Regex("<path-[0-9a-f]{6}>").find(quoted)!!.value
        listOf(bare, parenthesised, repr, exception).forEach { carrier ->
            assertTrue("$carrier should carry $token", carrier.contains(token))
        }
    }

    @Test
    fun `a quote in a file name does not truncate a continuation line`() {
        // renderText does not escape ", so a display name holding one — which
        // isValidSafSelectionRecord explicitly admits — reaches the line raw.
        val path = redactor().redact("\tIOException: /storage/emulated/0/Say \"Hi\"/ep.mkv: nope")
        val leaf = redactor().redact("\tIOException: $filesDir/media/Say \"Hi\".mkv")

        assertFalse(path, path.contains("Hi"))
        assertFalse(path, path.contains("ep.mkv"))
        assertFalse(leaf, leaf.contains("Hi"))
        assertTrue(path, path.matches(Regex("\tIOException: <path-[0-9a-f]{6}>: nope")))
        assertTrue(leaf, leaf.matches(Regex("\tIOException: <files>/media/<file-[0-9a-f]{6}>\\.mkv")))
    }

    @Test
    fun `a quote in a logcat path uses the conservative line grammar`() {
        val redacted =
            redactor().redact(
                "07-30 14:30:12.345 1234 1234 E AnkiMiner: " +
                    "failed /storage/emulated/0/Say \"Hi\"/ep.mkv: nope",
            )

        assertFalse(redacted, redacted.contains("Hi"))
        assertFalse(redacted, redacted.contains("ep.mkv"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "07-30 14:30:12\\.345 1234 1234 E AnkiMiner: " +
                        "failed <path-[0-9a-f]{6}>: nope",
                ),
            ),
        )
    }

    @Test
    fun `a directory whose last segment is plain words is redacted whole`() {
        // A directory has neither a slash nor an extension in its final segment, and staging and
        // output directories are logged as directories. Requiring a path-shaped token to continue
        // the match left the folder name in the clear.
        val continuation = redactor().redact("\tIOException: /storage/emulated/0/My Anime Shows (dir missing)")
        val bare = redactor().redact("outDir=/storage/emulated/0/Yes! Precure! Movies")

        assertFalse(continuation, continuation.contains("Anime"))
        assertFalse(continuation, continuation.contains("Shows"))
        assertFalse(bare, bare.contains("Precure"))
        assertFalse(bare, bare.contains("Movies"))
    }

    @Test
    fun `absorption stops at the next field and never merges two paths`() {
        // Redaction must not change the field count, and two distinct paths merging into one token
        // would also make tokenCounts under-report.
        val redactor = redactor()
        val line =
            "2026-07-30T12:00:00.000Z I run=abc c=media op=media.extract " +
                "src=/storage/emulated/0/a.mkv dst=/data/user/0/b.mkv n=1"

        val redacted = redactor.redact(line)

        assertEquals(fieldCount(line), fieldCount(redacted))
        assertTrue(redacted, redacted.endsWith(" n=1"))
        assertEquals(2, redactor.tokenCounts()["path"])
    }

    @Test
    fun `a python repr path carries the same token as a quoted one`() {
        // repr() is how a path reaches a Chaquopy traceback, so the apostrophe has to come off
        // before hashing or the same file reads as two different files.
        val redactor = redactor()

        val quoted = redactor.redactRecord("path=\"/storage/emulated/0/Movies/ep.mkv\"")
        val repr = redactor.redact("\tPyException: FileNotFoundError: '/storage/emulated/0/Movies/ep.mkv'")

        val token = Regex("<path-[0-9a-f]{6}>").find(quoted)!!.value
        assertTrue(repr, repr.contains("$token'"))
    }

    @Test
    fun `the same path carries one token whether or not a sentence punctuates it`() {
        val redactor = redactor()

        val bare = redactor.redact("path=/storage/emulated/0/Movies/ep.mkv")
        val punctuated = redactor.redact("\tjava.io.IOException: /storage/emulated/0/Movies/ep.mkv: nope")

        val token = Regex("<path-[0-9a-f]{6}>").find(bare)!!.value
        assertTrue(punctuated, punctuated.contains("$token:"))
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

    @Test
    fun `a quoted path containing escaped quotes is redacted past the escape`() {
        // Both " and \ are legal in an ext4 file name, and the renderer writes them escaped. A
        // pattern that treated the backslash as a hard stop ended the match inside the value.
        val redacted =
            redactor().redactRecord(
                "path=\"${escapeForValue("/storage/emulated/0/Say \"Hi\"/ep.mkv")}\"",
            )

        assertFalse(redacted, redacted.contains("Hi"))
        assertTrue(redacted, redacted.matches(Regex("path=\"<path-[0-9a-f]{6}>\"")))
    }

    @Test
    fun `a double slash under an app root does not leave the leaf in the clear`() {
        val redacted = redactor().redact("$filesDir//media/secret.mkv")

        assertFalse(redacted, redacted.contains("secret"))
        assertTrue(redacted, redacted.matches(Regex("<files>//media/<file-[0-9a-f]{6}>\\.mkv")))
    }

    // Rule 4

    @Test
    fun `a document id containing spaces is redacted whole`() {
        // getDocumentId returns the decoded id and logcat prints decoded URIs, so the file name
        // arrives verbatim, spaces and all.
        val redacted =
            redactor().redact(
                "uri=content://com.android.externalstorage.documents/document/" +
                    "primary:Anime/Kimi no Na wa.mkv end",
            )

        assertFalse(redacted, redacted.contains("Kimi"))
        assertFalse(redacted, redacted.contains("Na wa"))
        assertTrue(
            redacted,
            redacted.matches(
                Regex(
                    "uri=content://com\\.android\\.externalstorage\\.documents/" +
                        "<doc-[0-9a-f]{6}> <text-[0-9a-f]{6}>",
                ),
            ),
        )
    }

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
            redactor(safUserText = listOf("Kimi no Na wa.mkv"))
                .redact("op=saf.picked name=\"Kimi no Na wa.mkv\"")

        assertTrue(redacted, redacted.matches(Regex("op=saf\\.picked name=\"<saf-[0-9a-f]{6}>\\.mkv\"")))
    }

    @Test
    fun `a japanese saf display name is claimed by rule 5 before rule 7 sees it`() {
        val redacted =
            redactor(safUserText = listOf("殺す動画.mkv"))
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
    fun `a literal is matched in the escaped spelling the renderer actually writes`() {
        // The log never holds the typed text: renderValue escapes it on the way in. An alternation
        // built only from the raw setting matched nothing and leaked the name in full.
        val settings = AppSettings(deckName = "My \"Best\" Deck", tags = "path\\to\\tag")
        val line = "deck=\"${escapeForValue("My \"Best\" Deck")}\" tags=\"${escapeForValue("path\\to\\tag")}\""

        val redacted = redactor(settings = settings).redact(line)

        assertFalse(redacted, redacted.contains("Best"))
        assertFalse(redacted, redacted.contains("to"))
        assertTrue(redacted, redacted.matches(Regex("deck=\"<deck-[0-9a-f]{6}>\" tags=\"<tag-[0-9a-f]{6}>\"")))
    }

    @Test
    fun `a saf display name holding a quote is matched in both spellings`() {
        // isValidSafSelectionRecord rejects only / \ and control characters, so a display name with
        // a quote in it is explicitly admitted, and rip names like this are ordinary.
        val name = "Kimi no Na wa \"Special\".mkv"
        val redactor = redactor(safUserText = listOf(name))

        val escaped = redactor.redact("name=\"${escapeForValue(name)}\"")
        val raw = redactor.redact("\tjava.io.IOException: $name missing")

        assertFalse(escaped, escaped.contains("Special"))
        assertFalse(raw, raw.contains("Special"))
        val token = Regex("<saf-[0-9a-f]{6}>").find(escaped)!!.value
        assertTrue(raw, raw.contains(token))
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
    fun `a single japanese character is redacted too`() {
        // Single-kanji vocabulary is exactly what this app mines, so there is no lower threshold.
        val redacted = redactor().redact("reading=猫 kana=あ")

        assertTrue(
            redacted,
            redacted.matches(Regex("reading=<jp-[0-9a-f]{6}:1> kana=<jp-[0-9a-f]{6}:1>")),
        )
    }

    @Test
    fun `an iteration mark joins the run it sits in rather than splitting it`() {
        val redacted = redactor().redact("a=時々 b=人々")

        assertTrue(redacted, redacted.matches(Regex("a=<jp-[0-9a-f]{6}:2> b=<jp-[0-9a-f]{6}:2>")))
    }

    @Test
    fun `an astral kanji is redacted and its length counted in code points`() {
        // U+20B9F 叱 (CJK Extension B) is a surrogate pair in UTF-16: two chars, one code point.
        // A BMP-shaped character class would have matched neither half and let it through.
        val redacted = redactor().redact("a=$ASTRAL_KANJI b=${ASTRAL_KANJI}る")

        assertFalse(redacted, redacted.contains(ASTRAL_KANJI))
        assertTrue(redacted, redacted.matches(Regex("a=<jp-[0-9a-f]{6}:1> b=<jp-[0-9a-f]{6}:2>")))
    }

    @Test
    fun `a percent encoded astral kanji is caught by rule 8 too`() {
        // The same sweep has to reach rule 8's decode check, not only the character class.
        val redacted = redactor().redact("q=%F0%A0%AE%9F end")

        assertTrue(redacted, redacted.matches(Regex("q=<jp-enc-[0-9a-f]{6}> end")))
    }

    @Test
    fun `extension a compatibility and halfwidth japanese are covered`() {
        val redacted = redactor().redact("a=㐂 b=豈 c=ｱﾆﾒ")

        assertTrue(
            redacted,
            redacted.matches(
                Regex("a=<jp-[0-9a-f]{6}:1> b=<jp-[0-9a-f]{6}:1> c=<jp-[0-9a-f]{6}:3>"),
            ),
        )
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

    // Ordering, the record header, and the rules object itself

    @Test
    fun `rule 1 claims an app root before rule 2 can hash the whole path`() {
        // The TemporaryFolder roots never live under /storage or /data, so nothing else in this
        // suite exercises the ordering that keeps an app root readable.
        val root = File("/storage/emulated/0/Android/data/com.ankiminer.android/files")

        val redacted =
            redactor(roots = mapOf("files" to root)).redact("$root/media/clip.mkv")

        assertFalse(redacted, redacted.contains("<path-"))
        assertTrue(redacted, redacted.matches(Regex("<files>/media/<file-[0-9a-f]{6}>\\.mkv")))
    }

    @Test
    fun `a deck named data does not pre-empt rule 2 on an absolute path`() {
        // The hazard the two-pass structure exists for: one alternation covering roots and user
        // literals together would rewrite /data/... into /<deck>/... and leak the rest of the path.
        val redacted =
            redactor(settings = AppSettings(deckName = "data"))
                .redact("deck=data path=/data/user/0/com.ankiminer.android/secret.mkv")

        assertFalse(redacted, redacted.contains("secret"))
        assertTrue(
            redacted,
            redacted.matches(Regex("deck=<deck-[0-9a-f]{6}> path=<path-[0-9a-f]{6}>")),
        )
    }

    @Test
    fun `the record header is never rewritten by a literal`() {
        // LogRecord's one parse rule is that a line starting with a digit begins a record. A deck
        // named 2026 would otherwise mangle every timestamp in that user's bundle.
        val header = "2026-07-30T12:00:00.000Z"
        assertEquals(TIMESTAMP_LENGTH, header.length)

        val redacted =
            redactor(settings = AppSettings(deckName = "2026"))
                .redact("$header I run=abc c=diag op=x deck=2026")

        assertTrue(redacted, redacted.startsWith("$header I run=abc c=diag op=x deck=<deck-"))
    }

    @Test
    fun `the rules object prints its shape and never its mapping`() {
        // One AppLog.d(..., "rules" to rules) must not write the de-anonymization table into the
        // file being exported.
        val rules =
            rules(
                settings = AppSettings(deckName = "Immersion", noteType = "JP Mining Note"),
                safUserText = listOf("episode.mkv"),
                buildUser = "somebody",
            )

        val printed = rules.toString()

        assertFalse(printed, printed.contains("Immersion"))
        assertFalse(printed, printed.contains("JP Mining Note"))
        assertFalse(printed, printed.contains("episode"))
        assertFalse(printed, printed.contains("somebody"))
        assertTrue(printed, printed.matches(Regex("RedactionRules\\(roots=\\d+, literals=\\d+, patterns=\\d+\\)")))
    }

    @Test
    fun `newSalt is sixteen random bytes`() {
        // The most load-bearing line in the file, and the whole suite would stay green if its body
        // were replaced by ByteArray(16) — all zeros, every token reproducible by anyone.
        val first = RedactionRulesFactory.newSalt()
        val second = RedactionRulesFactory.newSalt()

        assertEquals(16, first.size)
        assertEquals(16, second.size)
        assertFalse(first.all { it == 0.toByte() })
        assertFalse(first.contentEquals(second))
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
                safUserText = listOf("episode.mkv"),
            )

        // Written as real fields: a bare value trailing a path would be absorbed into the path's
        // match instead of reaching rule 7, which is the documented cost of crossing a space.
        redactor.redact("a=/storage/emulated/0/one.mkv b=/storage/emulated/0/two.mkv jp=猫猫")
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
                "\n\tjava.io.IOException: $filesDir/殺す.mkv (カタカナ)" +
                "\n\t    at com.ankiminer.android.media.Extractor.run(Extractor.kt:41)"

        // One redactor across every line. Building one per line inside the lambda gave each line its
        // own TokenMint, so the multi-line test quietly proved nothing about cross-line stability —
        // which is the only reason to write a multi-line test.
        val redactor = redactor()
        val redacted = record.lines().joinToString("\n") { redactor.redact(it) }

        assertEquals(record.lines().size, redacted.lines().size)
        assertEquals(3, redacted.lines().size)
        redacted.lines().drop(1).forEach { line -> assertTrue(line, line.startsWith("\t")) }
        assertTrue(redacted, redacted.lines()[0].contains("note=\"he said \\\"go\\\" then \\n stopped\""))
        assertFalse(redacted, redacted.contains("殺す"))
        assertFalse(redacted, redacted.contains("カタカナ"))
        assertTrue(redacted, redacted.contains("at com.ankiminer.android.media.Extractor.run"))
        // The same file on a quoted field and on a continuation line carries the same token.
        val token = Regex("<file-[0-9a-f]{6}>").find(redacted.lines()[0])!!.value
        assertTrue(redacted.lines()[1], redacted.lines()[1].contains(token))
    }

    @Test
    fun `a pathologically deep path does not blow the regex stack`() {
        // A nested quantifier over path segments recurses inside java.util.regex and throws
        // StackOverflowError. An Error on Dispatchers.IO kills the export outright rather than
        // degrading it, and logcat's content is written by code nobody here controls.
        // The first version of this test used bare forms only, which is why a rewrite that made the
        // quoted branches recurse per character got through. Every branch is covered now.
        val deep = (0 until 5000).joinToString("") { "/seg$it" }
        val redactor = redactor()

        val underRoot = redactor.redact("$filesDir$deep/leaf.mkv")
        val absolute = redactor.redact("path=/storage/emulated/0$deep/leaf.mkv")
        val quotedAbsolute = redactor.redactRecord("path=\"/storage/emulated/0$deep/leaf.mkv\"")
        val quotedRoot = redactor.redactRecord("path=\"$filesDir$deep/leaf.mkv\"")
        val quotedUri = redactor.redactRecord("uri=\"content://media$deep/leaf.mkv\"")
        val bareUri = redactor.redact("uri=content://media$deep/leaf.mkv")
        val continuation = redactor.redact("\tIOException: /storage/emulated/0$deep/leaf.mkv")

        assertTrue(underRoot, underRoot.startsWith("<files>/seg0/"))
        assertFalse(underRoot, underRoot.contains("leaf.mkv"))
        assertTrue(absolute, absolute.matches(Regex("path=<path-[0-9a-f]{6}>")))
        assertTrue(quotedAbsolute, quotedAbsolute.matches(Regex("path=\"<path-[0-9a-f]{6}>\"")))
        assertTrue(quotedRoot, quotedRoot.startsWith("path=\"<files>/seg0/"))
        assertFalse(quotedRoot, quotedRoot.contains("leaf.mkv"))
        assertTrue(quotedUri, quotedUri.matches(Regex("uri=\"content://media/<doc-[0-9a-f]{6}>\"")))
        assertTrue(bareUri, bareUri.matches(Regex("uri=content://media/<doc-[0-9a-f]{6}>")))
        assertTrue(continuation, continuation.matches(Regex("\tIOException: <path-[0-9a-f]{6}>")))
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

    /** Top-level `key=` fields, which redaction must neither add to nor remove from a record. */
    private fun fieldCount(line: String): Int =
        Regex("(?:^|\\s)[A-Za-z0-9_.]+=").findAll(line).count()

    private fun fingerprintIn(line: String): String =
        Regex("<path-([0-9a-f]{6})>").find(line)!!.groupValues[1]

    private fun LogRedactor.redactRecord(body: String): String =
        redact(RECORD_PREFIX + body).removePrefix(RECORD_PREFIX)

    private fun redactor(
        settings: AppSettings = AppSettings(),
        safUserText: List<String> = emptyList(),
        buildUser: String? = null,
        salt: ByteArray = FIXED_SALT,
        roots: Map<String, File> = defaultRoots(),
    ) = LogRedactor(rules(settings, safUserText, buildUser, salt, roots))

    private fun rules(
        settings: AppSettings = AppSettings(),
        safUserText: List<String> = emptyList(),
        buildUser: String? = null,
        salt: ByteArray = FIXED_SALT,
        roots: Map<String, File> = defaultRoots(),
    ) = RedactionRulesFactory.forExport(roots, settings, safUserText, buildUser, salt)

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
        /** U+20B9F, written as its surrogate pair so the encoding under test is explicit. */
        const val ASTRAL_KANJI = "\uD842\uDF9F"
        const val RECORD_PREFIX = "2026-07-30T12:00:00.000Z I run=abc c=diag op=test "
        val FIXED_SALT = ByteArray(16) { index -> index.toByte() }
        const val FOUR_MEBIBYTES = 4L * 1024 * 1024
        const val BOUNDED_CHUNK = 64 * 1024
        const val STREAM_BUDGET_MILLIS = 60_000L
    }
}
