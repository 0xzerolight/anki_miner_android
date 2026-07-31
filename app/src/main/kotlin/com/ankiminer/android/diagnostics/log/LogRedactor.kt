package com.ankiminer.android.diagnostics.log

import android.content.Context
import android.os.Build
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionSlot
import java.io.File
import java.io.Reader
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rewrites one already-rendered record line so a bundle can be pasted into a public issue tracker.
 *
 * The on-device log deliberately holds full detail — exception messages, Python tracebacks,
 * filesystem paths, mined vocabulary. This class is the control that makes that safe: it runs at
 * export time only, and the file on disk is never rewritten.
 *
 * Redaction is per line and never introduces or removes a line break, so the record format survives
 * it: a line starting with a digit still begins a record, and a TAB-prefixed continuation line stays
 * one.
 *
 * State (the token memo and its counters) lives in [RedactionRules], so two redactors sharing one
 * rules instance — the app log and logcat inside the same bundle — agree on every token.
 */
internal class LogRedactor(private val rules: RedactionRules) {
    fun redact(line: String): String {
        if (line.isEmpty()) return line
        val segments = Segments(line)
        // Both Kotlin and Python records start with this timestamp. Seal it independently from
        // grammar selection so user literals cannot break line sorting, while Python's raw message
        // still takes the conservative grammar below.
        val timestampPrefix = TIMESTAMP_PREFIX.find(line)
        val kotlinRecordPrefix = KOTLIN_RECORD_PREFIX.find(line)
        val sealedPrefix = kotlinRecordPrefix ?: timestampPrefix
        sealedPrefix?.let { prefix -> segments.sealPrefix(prefix.value.length) }
        // Rule 1. Rewritten in place rather than sealed, because rule 3 has to see the root token it
        // produces in order to know a leaf filename is under an app directory.
        rules.literalAlternation?.let { roots ->
            segments.rewriteOpen { text ->
                roots.replace(text) { match -> rules.literalReplacements[match.value] ?: match.value }
            }
        }
        // Only LogRecord's complete structured prefix proves its quoted-field grammar applies.
        // Python, logcat and third-party lines may contain raw quotes in paths, so every other shape
        // takes the conservative grammar.
        val patterns =
            when {
                kotlinRecordPrefix != null -> rules.patterns
                line.startsWith('\t') -> rules.continuationPatterns
                else -> rules.unstructuredPatterns
            }
        for ((regex, replace) in patterns) segments.seal(regex, replace)
        return segments.render()
    }

    /**
     * Streams [source] into [destination] a line at a time.
     *
     * A log file is capped at 4 MiB and logcat is unbounded; reading either into one `String` would
     * be a multi-megabyte allocation on a phone for no benefit.
     */
    suspend fun redactInto(
        source: File,
        destination: File,
    ): File =
        withContext(Dispatchers.IO) {
            source.reader(StandardCharsets.UTF_8).use { reader ->
                destination.writer(StandardCharsets.UTF_8).use { writer ->
                    redactLines(reader, writer)
                }
            }
            destination
        }

    /** The streaming core, separated so a test can measure what it asks of the reader and writer. */
    fun redactLines(
        reader: Reader,
        writer: Writer,
    ) {
        // Buffered here rather than by the caller so both bounds are this class's to keep: what it
        // reads at a time, and what it holds before writing.
        val buffered = writer.buffered()
        reader.buffered().lineSequence().forEach { line ->
            buffered.write(redact(line))
            buffered.write("\n")
        }
        buffered.flush()
    }

    /**
     * Per-kind token counts for the bundle manifest. The mapping itself is never exposed — emitting
     * it would undo the whole exercise.
     *
     * Pattern kinds (`path`, `file`, `doc`, `text`, `jp`, `jp-enc`, `user`) count distinct values
     * actually found in the text redacted so far. Literal kinds (`saf`, `deck`, `notetype`, `field`,
     * `tag`) count the literals registered for redaction, whether or not they occurred: their tokens
     * are minted once when the rules are built.
     *
     * `path`, `file` and `doc` under-report where a match crossed a space: text absorbed past the
     * path is hashed as one `text` token, so a second path or a Japanese run that followed a bare
     * path on the same line is counted there instead of under its own kind.
     *
     * The `:n` suffix on a `jp` token is a code point count. See where it is minted for why.
     */
    fun tokenCounts(): Map<String, Int> = rules.tokens.counts()
}

/**
 * @property literalAlternation one pre-built alternation over the app directory roots (rule 1),
 *   longest-first so a root never loses to a prefix of itself.
 * @property literalReplacements lookup shared by both literal passes: roots map to readable symbolic
 *   tokens, user-supplied names to hashed ones.
 * @property patterns the remaining rules for a record line, in the order they must run.
 * @property continuationPatterns the conservative rules for TAB-prefixed continuations. These
 *   lines have no trusted quoting grammar: a raw `"` can be part of a file name rather than the end
 *   of a value.
 * @property unstructuredPatterns rules for logcat, raw Python and platform text. Field-shaped
 *   segments have no structural meaning there, so path absorption crosses them.
 * @property salt 16 bytes minted per bundle. Being a [ByteArray] makes the generated [equals] and
 *   [hashCode] identity-based for this whole class; nothing compares these, and a value comparison
 *   would be meaningless anyway because [tokens] is mutable.
 * @property tokens the memo behind every hashed token; shared by everything built from this instance.
 */
internal data class RedactionRules(
    val literalAlternation: Regex?,
    val literalReplacements: Map<String, String>,
    val patterns: List<Pair<Regex, (MatchResult) -> String>>,
    val salt: ByteArray,
    val tokens: TokenMint = TokenMint(salt),
    val continuationPatterns: List<Pair<Regex, (MatchResult) -> String>> = patterns,
    val unstructuredPatterns: List<Pair<Regex, (MatchResult) -> String>> = continuationPatterns,
) {
    /**
     * Shape only, and this override is load-bearing.
     *
     * The generated `toString()` renders [literalReplacements] — every plaintext-to-token pair, the
     * complete de-anonymization table — and [patterns], whose `Regex.toString()` hands back
     * `\Qsomeuser\E` for the `Build.USER` rule. A single `AppLog.d(..., "rules" to rules)` in the
     * export would write the key straight into the file being exported, and it would look like
     * ordinary debug logging in review.
     */
    override fun toString(): String {
        val roots = literalReplacements.values.count(SYMBOLIC_TOKEN::matches)
        return "RedactionRules(roots=$roots, " +
            "literals=${literalReplacements.size - roots}, patterns=${patterns.size})"
    }

    private companion object {
        /** `<files>`, `<extfiles-0>` — a rule 1 replacement, as opposed to a hashed token. */
        val SYMBOLIC_TOKEN = Regex("<[a-z]+(?:-\\d+)?>")
    }
}

/**
 * Mints stable, non-reversible tokens for one bundle.
 *
 * Stability is a security property, not a convenience. The salt makes the same text produce the same
 * token *within* one export, so a maintainer can follow one path across a hundred lines; it makes the
 * token differ across exports and across users, so a dictionary of likely file names cannot be
 * hashed offline and matched against a published bundle.
 *
 * Six hex digits, not four: four is a 65 536 space, which reaches a ~10% birthday collision at 120
 * distinct strings — an ordinary log. Six is 16.7M.
 */
internal class TokenMint(private val salt: ByteArray) {
    private val assigned = HashMap<String, String>()
    private val occupants = HashMap<String, Int>()
    private val counts = HashMap<String, Int>()

    /**
     * [detail] is appended after the fingerprint (`<jp-3f2a1c:12>`) and is not part of the memo key,
     * because it is derived from [text] and so cannot vary for the same text.
     */
    @Synchronized
    fun token(
        kind: String,
        text: String,
        detail: String? = null,
    ): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val key = "$kind\u0000$normalized"
        assigned[key]?.let { return it }
        val fingerprint = fingerprint(normalized)
        val bucket = "$kind-$fingerprint"
        // A genuine collision between two different texts would otherwise merge them into one token
        // and quietly mislead whoever reads the bundle.
        val ordinal = (occupants[bucket] ?: 0) + 1
        occupants[bucket] = ordinal
        val token =
            buildString {
                append('<').append(kind).append('-').append(fingerprint)
                if (ordinal > 1) append('#').append(ordinal)
                if (detail != null) append(':').append(detail)
                append('>')
            }
        assigned[key] = token
        counts[kind] = (counts[kind] ?: 0) + 1
        return token
    }

    @Synchronized
    fun counts(): Map<String, Int> = counts.toSortedMap()

    private fun fingerprint(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(normalized.toByteArray(StandardCharsets.UTF_8))
        val bytes = digest.digest()
        return buildString(FINGERPRINT_HEX) {
            for (index in 0 until FINGERPRINT_HEX / 2) {
                val byte = bytes[index].toInt() and 0xFF
                append(HEX[byte ushr 4]).append(HEX[byte and 0xF])
            }
        }
    }

    private companion object {
        const val FINGERPRINT_HEX = 6
        val HEX = "0123456789abcdef".toCharArray()
    }
}

internal object RedactionRulesFactory {
    /**
     * Takes the inventory rather than a list of names, because the inventory holds two kinds of user
     * text and a caller passing a list would predictably forget the second: `selection(slot)`
     * carries the picked file's display name, and `text(slot)` carries the reading subtitle series
     * name, which the user types by hand. Collecting over [SafSelectionSlot.entries] also means a
     * slot added later is covered without anyone remembering to come back here.
     */
    fun forExport(
        context: Context,
        settings: AppSettings,
        inventory: SafSelectionInventory,
    ): RedactionRules {
        val application = context.applicationContext
        val roots = LinkedHashMap<String, File>()
        fun root(
            token: String,
            directory: File?,
        ) {
            if (directory != null) roots[token] = directory
        }
        root("files", application.filesDir)
        root("cache", application.cacheDir)
        root("nobackup", application.noBackupFilesDir)
        // Not a Context accessor: the native library directory is only reachable through
        // ApplicationInfo, and it is the directory ffmpeg and ffprobe are executed from.
        application.applicationInfo?.nativeLibraryDir?.let { root("nativelib", File(it)) }
        // Entries are null for a volume that is not currently mounted.
        application.getExternalFilesDirs(null)?.forEachIndexed { index, directory ->
            root("extfiles-$index", directory)
        }
        val safUserText =
            SafSelectionSlot.entries.flatMap { slot ->
                listOfNotNull(inventory.selection(slot)?.displayName, inventory.text(slot))
            }
        return forExport(roots, settings, safUserText, Build.USER, newSalt())
    }

    /**
     * The seam the tests drive. Everything the platform owns — the directories, the build user, the
     * randomness — is a parameter, because the unit test build has neither Robolectric nor
     * `returnDefaultValues`, so touching [Build] or a real [Context] there throws.
     */
    fun forExport(
        roots: Map<String, File>,
        settings: AppSettings,
        safUserText: List<String>,
        buildUser: String?,
        salt: ByteArray,
    ): RedactionRules {
        val tokens = TokenMint(salt)
        val replacements = LinkedHashMap<String, String>()

        // Rule 1. Both spellings of every root: paths reach the log through canonicalPath in the
        // bridge request builders and through absolutePath elsewhere, and on API 26 /data/user/0 is
        // a symlink to /data/data, so the two differ on the device this app is tested on.
        for ((token, directory) in roots) {
            for (path in setOf(directory.absolutePath, canonicalOrNull(directory))) {
                if (path == null || path.length < 2) continue
                replacements.putIfAbsent(path.trimEnd('/'), "<$token>")
            }
        }
        val rootAlternation = alternation(replacements.keys)

        // Rules 5 and 6. Both are literal user-supplied text, so they share one alternation, and it
        // runs at rule 5's position rather than with the roots: folding them forward would let a
        // deck named "data" consume the head of an absolute path before rule 2 could match it.
        val literals = LinkedHashMap<String, String>()
        fun literal(
            kind: String,
            value: String?,
            keepExtension: Boolean = false,
        ) {
            val text = value ?: return
            // A one- or two-character literal replaced everywhere shreds the file: it would hit
            // every English word that happens to contain those characters.
            if (text.length < MIN_LITERAL_LENGTH) return
            // Every spelling the file can hold, not just the one the user typed. The renderer
            // escapes a value before writing it, so a deck named `My "Best" Deck` is only ever on
            // disk as `My \"Best\" Deck` and an alternation built from the raw setting could never
            // match it. escapeForValue/escapeForText are the renderer's own functions, called rather
            // than reimplemented so the two cannot drift.
            val spellings =
                linkedSetOf(text, escapeForValue(text), escapeForText(text))
                    .filter { spelling ->
                        spelling.length >= MIN_LITERAL_LENGTH &&
                            spelling !in replacements &&
                            spelling !in literals
                    }
            if (spellings.isEmpty()) return
            // One token for all of them: they are the same secret written three ways, and a
            // maintainer correlating across lines must not see two.
            val replacement =
                tokens.token(kind, text) + if (keepExtension) extensionOf(text) else ""
            spellings.forEach { spelling -> literals[spelling] = replacement }
        }
        safUserText.forEach { literal("saf", it, keepExtension = true) }
        literal("deck", settings.deckName)
        settings.excludedDecks.forEach { literal("deck", it) }
        literal("notetype", settings.noteType)
        // AppSettings.cardType is a CardType enum whose wire values are code constants, not user
        // text; cardTypeMarkerField is the note-type field name the user actually chose.
        literal("field", settings.cardTypeMarkerField)
        settings.fieldMap.values.forEach { literal("field", it) }
        literal("tag", settings.tags)
        replacements.putAll(literals)
        val literalAlternation = alternation(literals.keys)

        // Whatever an absorbed match dragged in past the path, hidden under its own token so the
        // path's token stays the same across every carrier. Blank remainders are pure spacing and
        // are handed back untouched.
        val prose = { rest: String ->
            val lead = rest.takeWhile { it == ' ' }
            if (rest.length == lead.length) rest else lead + tokens.token("text", rest.substring(lead.length))
        }

        // Each of rules 2, 3 and 4 exists once per line grammar. The quoted branches are only ever
        // reachable on a record line, so they are absent from the continuation list.
        val path = { match: MatchResult ->
            val (core, rest) = splitAbsorbed(match.value)
            // Trailing sentence punctuation is trimmed back out of the token. An exception message
            // ends `…/ep.mkv: open failed`, and without this the colon would land inside the hashed
            // text — so the same file would carry a different token here than on the line that
            // opened it.
            val trimmed = core.trimEnd(*TRAILING_PUNCTUATION)
            if (trimmed.length < 2) {
                match.value
            } else {
                tokens.token("path", trimmed) + core.substring(trimmed.length) + prose(rest)
            }
        }
        val leafOf = { match: MatchResult ->
            // The tail is group 1, so whatever precedes it is the root token rule 1 left.
            val captured = match.groupValues[1]
            val root = match.value.dropLast(captured.length)
            val (core, rest) = splitAbsorbed(captured)
            val tail = core.trimEnd(*TRAILING_PUNCTUATION)
            val leaf = tail.substringAfterLast('/')
            if (leaf.isEmpty()) {
                match.value
            } else {
                // Directories under an app root are the app's own and stay readable. The extension
                // survives too: .mkv against .ass is exactly the signal a media-extraction bug
                // needs, and it identifies nobody.
                root + tail.dropLast(leaf.length) +
                    tokens.token("file", leaf) +
                    extensionOf(leaf) +
                    core.substring(tail.length) +
                    prose(rest)
            }
        }
        val documentId = { match: MatchResult ->
            val captured = match.groupValues[2]
            val (core, rest) = splitAbsorbed(captured)
            val remainder = core.trimEnd(*TRAILING_PUNCTUATION)
            // The authority distinguishes a FUSE provider from a cloud one and is worth keeping;
            // the document id is the user's file.
            if (remainder.length < 2) {
                match.value
            } else {
                "content://${match.groupValues[1]}/" +
                    tokens.token("doc", remainder) +
                    core.substring(remainder.length) +
                    prose(rest)
            }
        }

        // Rules 5 to 9 read the same under every line grammar, so they are built once and appended
        // to each list rather than duplicated.
        val shared =
            buildList<Pair<Regex, (MatchResult) -> String>> {
                literalAlternation?.let { regex ->
                    add(regex to { match -> literals[match.value] ?: match.value })
                }
                add(
                    JAPANESE_RUN to { match ->
                        // Normalized here rather than left to the mint, so the length is measured on
                        // the same string the memo is keyed by. Measuring the raw match instead let
                        // NFD か+U+3099 and NFC が share a token whose :n was whichever arrived
                        // first — the token was right and the length was a coin toss.
                        val normalized = Normalizer.normalize(match.value, Normalizer.Form.NFC)
                        // The length rides along so a truncation or off-by-one in the engine is
                        // still visible in a redacted bundle. It is a count of CODE POINTS, not of
                        // UTF-16 units: the engine that produces these strings is Python, whose
                        // len() counts code points, so an astral kanji reads as 1 here and as 1
                        // there. A maintainer comparing <jp-…:12> against an engine length is
                        // comparing like with like.
                        val length = normalized.codePointCount(0, normalized.length)
                        tokens.token("jp", normalized, detail = length.toString())
                    },
                )
                add(
                    PERCENT_RUN to { match ->
                        val decoded = decodePercentRun(match.value)
                        // Rule 7 is blind to percent-encoding, and urllib3 logs Jisho request URLs
                        // as ?keyword=%E6%AE%BA%E3%81%99. Task 2 pins those loggers, but a bundle
                        // also carries logcat, which third-party code writes to freely.
                        if (decoded == null || !containsJapanese(decoded)) {
                            match.value
                        } else {
                            tokens.token("jp-enc", decoded)
                        }
                    },
                )
                if (buildUser != null && buildUser.length >= MIN_LITERAL_LENGTH) {
                    add(Regex(Regex.escape(buildUser)) to { match -> tokens.token("user", match.value) })
                }
                add(EMULATED_USER to { match -> "<user-${match.groupValues[1]}>" })
            }

        val recordPatterns =
            listOf(
                QUOTED_ABSOLUTE_PATH to path,
                RECORD_ABSOLUTE_PATH to path,
                QUOTED_APP_ROOT_LEAF to leafOf,
                RECORD_APP_ROOT_LEAF to leafOf,
                QUOTED_CONTENT_URI to documentId,
                RECORD_CONTENT_URI to documentId,
            ) + shared
        val continuationPatterns =
            listOf(
                CONTINUATION_ABSOLUTE_PATH to path,
                CONTINUATION_APP_ROOT_LEAF to leafOf,
                CONTINUATION_CONTENT_URI to documentId,
            ) + shared
        val unstructuredPatterns =
            listOf(
                UNSTRUCTURED_ABSOLUTE_PATH to path,
                UNSTRUCTURED_APP_ROOT_LEAF to leafOf,
                UNSTRUCTURED_CONTENT_URI to documentId,
            ) + shared

        return RedactionRules(
            rootAlternation,
            replacements,
            recordPatterns,
            salt,
            tokens,
            continuationPatterns,
            unstructuredPatterns,
        )
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)

    /** Longest-first so an alternative can never win by being a prefix of a longer one. */
    private fun alternation(literals: Collection<String>): Regex? {
        if (literals.isEmpty()) return null
        val ordered = literals.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        return Regex(ordered.joinToString("|") { Regex.escape(it) })
    }

    private fun canonicalOrNull(directory: File): String? =
        try {
            directory.canonicalPath
        } catch (_: java.io.IOException) {
            null
        }

    private const val MIN_LITERAL_LENGTH = 3
    private const val SALT_BYTES = 16
}

/**
 * The line under rewrite, as alternating open and sealed runs.
 *
 * A rule only ever looks at the open runs. Without that, a token minted by an earlier rule would be
 * fair game for a later one — a deck name of "cache" would rewrite the inside of `<cache>`, and the
 * `<path-…>` fingerprint's hex digits would be matched by anything scanning for hex.
 */
private class Segments(line: String) {
    private var runs = mutableListOf(Run(line, sealed = false))

    /** Seals the first [length] characters, so no rule can rewrite the fixed record header. */
    fun sealPrefix(length: Int) {
        val run = runs.single()
        if (length <= 0) return
        if (length >= run.text.length) {
            runs = mutableListOf(Run(run.text, sealed = true))
            return
        }
        runs =
            mutableListOf(
                Run(run.text.take(length), sealed = true),
                Run(run.text.substring(length), sealed = false),
            )
    }

    /** Rewrites open runs without sealing the result, for a rule whose output a later rule needs. */
    fun rewriteOpen(transform: (String) -> String) {
        for (index in runs.indices) {
            val run = runs[index]
            if (!run.sealed) runs[index] = Run(transform(run.text), sealed = false)
        }
    }

    fun seal(
        regex: Regex,
        replace: (MatchResult) -> String,
    ) {
        val next = ArrayList<Run>(runs.size)
        for (run in runs) {
            if (run.sealed) {
                next += run
                continue
            }
            var consumed = 0
            var match = regex.find(run.text)
            while (match != null) {
                val replacement = replace(match)
                // A rule that inspects a match and declines it (a percent run that decodes to
                // ASCII) leaves the text open for the rules that follow.
                if (replacement != match.value) {
                    if (match.range.first > consumed) {
                        next += Run(run.text.substring(consumed, match.range.first), sealed = false)
                    }
                    next += Run(replacement, sealed = true)
                    consumed = match.range.last + 1
                }
                match = match.next()
            }
            if (consumed < run.text.length) next += Run(run.text.substring(consumed), sealed = false)
        }
        runs = next
    }

    fun render(): String = runs.joinToString("") { it.text }

    private class Run(val text: String, val sealed: Boolean)
}

/**
 * The timestamp common to Kotlin and Python records, exactly [TIMESTAMP_LENGTH] characters.
 */
private const val TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"
private const val BARE_TOKEN_PATTERN = "[A-Za-z0-9._:/@+\\-]+"
private val TIMESTAMP_PREFIX = Regex("^$TIMESTAMP_PATTERN")

/** The complete fixed prefix emitted by [renderLogRecord], through the operation token. */
private val KOTLIN_RECORD_PREFIX =
    Regex(
        "^$TIMESTAMP_PATTERN [DIWE] run=$BARE_TOKEN_PATTERN " +
            "c=$BARE_TOKEN_PATTERN op=$BARE_TOKEN_PATTERN(?=\\s|$)",
    )

private const val PATH_ROOTS = "storage|sdcard|mnt|data|system"

private const val APP_ROOTS = "files|cache|nobackup|nativelib|extfiles-\\d+"

/**
 * A run of value text, as `(?:<class>++|\\.)*+`.
 *
 * Both quantifiers are possessive on purpose, and it is the only shape of the three that survives a
 * long line. `(?:[^"\\]|\\.)*` looks equivalent but makes `java.util.regex` recurse once per
 * *character*, and it threw `StackOverflowError` at 2 000 characters — well inside reach, since
 * `PATH_MAX` is 4096 and a logcat line has no length limit at all. Hoisting the class into its own
 * `++` loop makes the common run iterative, and the outer `*+` keeps the group loop from recursing
 * per escape. An `Error` on `Dispatchers.IO` kills the export outright rather than degrading it.
 *
 * The escape alternative is what stops a path truncating on its own contents: `renderValue` writes a
 * `"` as `\"` and a `\` as `\\`, both legal in an ext4 file name.
 */
private fun tailOf(stopClass: String) = "(?:[^$stopClass]++|\\\\.)*+"

/** Inside quotes: only an unescaped quote closes the value. A space is ordinary. */
private val QUOTED_TAIL = tailOf("\"\\\\\\t\\r\\n")

/**
 * On a record line outside quotes: whitespace ends a bare value, and a raw quote is a value
 * delimiter, because [renderValue] quotes anything a space could hide in.
 */
private val RECORD_TAIL = tailOf("\\s\"\\\\")

/**
 * On a continuation line: whitespace only.
 *
 * A quote is ordinary here, and that distinction is load-bearing. The record renderer does **not**
 * escape `"` on a continuation line — there is no quoting grammar there to protect — so a file name
 * holding a quote reaches the line raw, and `isValidSafSelectionRecord` admits one: it rejects `/`,
 * `\` and control characters, but not quotes. Reusing the record-line class stopped the match dead
 * inside `Say "Hi"/ep.mkv` and published the rest.
 */
private val CONTINUATION_TAIL = tailOf("\\s\\\\")

/**
 * How an unquoted path crosses a space.
 *
 * [renderText] writes throwable messages with no quoting at all, so
 * `\tjava.io.FileNotFoundException: /storage/emulated/0/My Anime/ep 01.mkv: open failed` has no
 * right edge — and exception messages carrying paths are the most common content in this file. A
 * whitespace-terminated match published everything after `My`.
 *
 * A following token is absorbed unless it opens a new field. Requiring the token to *look* like a
 * path instead is not enough: `/storage/emulated/0/My Anime Shows` is a directory, its last segment
 * has neither a slash nor an extension, and staging and output directories are logged as
 * directories. So the test is inverted — absorb anything that is not `key=`.
 *
 * The `key=` guard is what keeps the record's field structure intact, which the format requires and
 * which absorbing blindly broke: `src=/a/x.mkv dst=/b/y.mkv n=1` swallowed `dst` whole, merged two
 * different paths into one token, and left `tokenCounts()` reporting one path where there were two.
 * A spaced value on a record line is always quoted, so nothing legitimate is lost to the guard.
 */
private fun absorbing(
    tail: String,
    stopAtField: Boolean = true,
): String {
    val fieldBoundary = if (stopAtField) "(?![A-Za-z0-9_.]++=)" else ""
    return "(?:$PATH_ALREADY_ENDED ++$fieldBoundary$tail)*+"
}

/**
 * The other end of absorption: stop once the path has plainly finished.
 *
 * Absorbing to end of line costs the failure reason on every exception, and `ENOENT` against
 * `EACCES` is often the whole answer to a bug report:
 *
 * ```
 * …/ep 01.mkv: open failed: ENOENT
 *              ^ everything from here was being hashed away
 * ```
 *
 * A colon-space is the separator between a Java or Python exception message and its detail, and it
 * is vanishingly rare inside a directory name — but *only* checking for colon-space would leak, and
 * on an ordinary input: `…/Movies/Star Wars: A New Hope.mkv` would stop at `Wars:` and publish the
 * rest of the title. Colon-space in a Western film title is not exotic.
 *
 * So the test is narrower. Absorption stops at a colon-space only when what precedes the colon is a
 * *file extension* — the path has reached a file name and cannot continue. `ep 01.mkv:` stops;
 * `Star Wars:` does not, because a path can still continue after it, and it runs on to consume the
 * whole title. A directory path has no extension anywhere, so it is unaffected and still absorbs to
 * end of line.
 *
 * Bounded so it is a legal lookbehind, and bounded by the same 1-8 alphanumerics [extensionOfToken]
 * accepts, so the two agree on what an extension is.
 */
private const val PATH_ALREADY_ENDED = "(?<!\\.[A-Za-z0-9]{1,8}:)"

/**
 * Rule 2, quoted branch: the closing quote is an exact right edge, so this one needs no absorption.
 */
private val QUOTED_ABSOLUTE_PATH = Regex("(?<=\")/(?:$PATH_ROOTS)/$QUOTED_TAIL")

/**
 * Rule 2, unquoted branch. The lookbehind keeps the match at a path boundary, so a directory named
 * `data` inside a longer path cannot start a second, bogus match. `/` is deliberately absent from
 * it: `file:///data` must still match.
 */
private fun absolutePath(
    tail: String,
    stopAtField: Boolean = true,
) = Regex("(?<![A-Za-z0-9._\\-])/(?:$PATH_ROOTS)/$tail${absorbing(tail, stopAtField)}")

private val RECORD_ABSOLUTE_PATH = absolutePath(RECORD_TAIL)
private val CONTINUATION_ABSOLUTE_PATH = absolutePath(CONTINUATION_TAIL)
private val UNSTRUCTURED_ABSOLUTE_PATH = absolutePath(CONTINUATION_TAIL, stopAtField = false)

/**
 * Rule 3. Captures the whole tail; the leaf is split off in code.
 *
 * Splitting in code rather than in the pattern is why `<files>//media/secret.mkv` no longer defeats
 * the rule — a segment-by-segment group could not cross the empty segment and left the leaf in the
 * clear — and it is what lets the tail be one flat possessive run.
 */
private val QUOTED_APP_ROOT_LEAF = Regex("(?<=\")<(?:$APP_ROOTS)>(/$QUOTED_TAIL)")

private fun appRootLeaf(
    tail: String,
    stopAtField: Boolean = true,
) = Regex("<(?:$APP_ROOTS)>(/$tail${absorbing(tail, stopAtField)})")

private val RECORD_APP_ROOT_LEAF = appRootLeaf(RECORD_TAIL)
private val CONTINUATION_APP_ROOT_LEAF = appRootLeaf(CONTINUATION_TAIL)
private val UNSTRUCTURED_APP_ROOT_LEAF = appRootLeaf(CONTINUATION_TAIL, stopAtField = false)

/**
 * Rule 4. `DocumentsContract.getDocumentId` returns the *decoded* id and logcat prints decoded
 * URIs, so a document id holds the file name verbatim, spaces and all.
 */
private val QUOTED_CONTENT_URI =
    Regex("(?<=\")content://([A-Za-z0-9._\\-]+)(/$QUOTED_TAIL)?")

private fun contentUri(
    tail: String,
    stopAtField: Boolean = true,
) = Regex("content://([A-Za-z0-9._\\-]+)(/$tail${absorbing(tail, stopAtField)})?")

private val RECORD_CONTENT_URI = contentUri(RECORD_TAIL)
private val CONTINUATION_CONTENT_URI = contentUri(CONTINUATION_TAIL)
private val UNSTRUCTURED_CONTENT_URI = contentUri(CONTINUATION_TAIL, stopAtField = false)

/**
 * Rule 7. Hiragana, katakana including the halfwidth forms, CJK Unified Ideographs with Extension A
 * and Extension B, both compatibility ideograph blocks, and the iteration marks.
 *
 * The iteration marks are not decoration: 々 sits outside every CJK block, so without it 時々, 人々
 * and 色々 split into two runs of one character each.
 *
 * Extension B and the supplementary compatibility ideographs are written as `\x{…}` rather than as
 * `\uXXXX` ranges because they are above the BMP. Kotlin regex runs over UTF-16, and a BMP-shaped
 * class silently matches neither half of a surrogate pair — the rare given names and rare vocabulary
 * that live up there would have passed straight through. This repo's tokenizer corpus already
 * carries astral characters as a seeded adversarial case, so they reach this app in practice.
 *
 * One character is enough. Single-kanji vocabulary — 犬, 猫, 本, 殺 — is exactly what this app mines,
 * so a two-character floor would leak the most sensitive class of content in the file. Engine status
 * strings are English, so a lone CJK character in one is rare and costs a maintainer nothing.
 */
private val JAPANESE_RUN =
    Regex(
        "[\\u3005\\u3006\\u303B\\u3040-\\u309F\\u30A0-\\u30FF" +
            "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF66-\\uFF9F" +
            "\\x{20000}-\\x{2A6DF}\\x{2F800}-\\x{2FA1F}]+",
    )

/** Rule 8. Three triplets minimum: one CJK character is three bytes in UTF-8. */
private val PERCENT_RUN = Regex("(?:%[0-9A-Fa-f]{2}){3,}")

/** Rule 9. */
private val EMULATED_USER = Regex("/storage/emulated/(\\d+)")

private const val MAX_EXTENSION = 8

/**
 * Trimmed off the end of a match before it is hashed, then written back after the token. Absorbing a
 * space means a match routinely ends in the punctuation of the message around it, and hashing that
 * would give the same file a different token on every line.
 *
 * The apostrophe earns its place: `repr()` is exactly how a path reaches a Chaquopy traceback, and
 * without it `'…/ep.mkv'` hashed differently from the same file in a quoted field. The space is here
 * because the quoted branches no longer exclude a trailing one in the pattern — a possessive run
 * cannot backtrack off it, so it comes off here instead.
 */
private val TRAILING_PUNCTUATION =
    charArrayOf(':', ',', ';', '.', '!', '?', ')', ']', '}', '\'', ' ')

/**
 * Splits an absorbed match into the part that still looks like a path and the message text dragged
 * in with it.
 *
 * Absorbing everything up to a field key is what closes the directory leak, but hashing the absorbed
 * prose along with the path would undo the property the tokens exist for: `/data/x.mkv` inside
 * `open failed: ENOENT` would carry a different token from the same file in `path="/data/x.mkv"`,
 * and correlating one file across a bundle is the entire point of a stable token. So the match is
 * cut after the last token that still looks like a path, and the two halves are hashed separately.
 *
 * Both halves are hashed — only the spacing between them is written back verbatim — so a directory
 * name that lands in the prose half is as hidden as it would be without the split.
 */
private fun splitAbsorbed(value: String): Pair<String, String> {
    if (' ' !in value) return value to ""
    var index = 0
    var coreEnd = 0
    while (index < value.length) {
        while (index < value.length && value[index] == ' ') index++
        val start = index
        while (index < value.length && value[index] != ' ') index++
        if (start < index && continuesAPath(value.substring(start, index))) coreEnd = index
    }
    if (coreEnd == 0) return value to ""
    return value.substring(0, coreEnd) to value.substring(coreEnd)
}

/**
 * A token still reads as part of a path when it holds a separator or ends in a file extension.
 *
 * Punctuation comes off first, or the last segment of `…/ep 01.mkv: open failed` fails the
 * extension gate on its own trailing colon, the cut lands before the file name, and the path token
 * stops matching the one the same file gets from a quoted field.
 */
private fun continuesAPath(token: String): Boolean =
    token.contains('/') || extensionOfToken(token.trimEnd(*TRAILING_PUNCTUATION)).isNotEmpty()

/**
 * The extension of a leaf, which may now hold spaces.
 *
 * The last token that has one wins, so `My Video 01.mkv` still reports `.mkv`. Nothing is trusted
 * from the text itself — every candidate goes through [extensionOfToken]'s alphanumeric gate — so
 * scanning more of the leaf cannot widen what is published.
 */
private fun extensionOf(name: String): String {
    if (' ' !in name) return extensionOfToken(name)
    return name.split(' ').asReversed().firstNotNullOfOrNull { token ->
        extensionOfToken(token).ifEmpty { null }
    } ?: ""
}

/**
 * The extension only survives when it is short and alphanumeric. An extension is copied through
 * verbatim, so `殺す.動画` would otherwise leak half of what rule 3 was asked to hide.
 */
private fun extensionOfToken(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    val extension = name.substring(dot + 1)
    if (extension.length > MAX_EXTENSION) return ""
    if (!extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return ""
    return ".$extension"
}

/**
 * The same set [JAPANESE_RUN] matches, applied to already-decoded text.
 *
 * Walks code points rather than chars. A `Char` loop would test each half of a surrogate pair on its
 * own, and neither half falls in any of these ranges, so every astral kanji would report false \u2014
 * which is exactly how a percent-encoded one would have slipped through rule 8.
 */
private fun containsJapanese(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (isJapanese(codePoint)) return true
        index += Character.charCount(codePoint)
    }
    return false
}

private fun isJapanese(codePoint: Int): Boolean =
    codePoint == 0x3005 ||
        codePoint == 0x3006 ||
        codePoint == 0x303B ||
        codePoint in 0x3040..0x309F ||
        codePoint in 0x30A0..0x30FF ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0xFF66..0xFF9F ||
        codePoint in 0x20000..0x2A6DF ||
        codePoint in 0x2F800..0x2FA1F

/**
 * Decodes a run of percent triplets. Malformed bytes become U+FFFD rather than failing the whole
 * run: a truncated sequence still carries the characters that did decode, and those are the ones
 * worth hiding.
 */
private fun decodePercentRun(run: String): String? {
    if (run.length < 3) return null
    val bytes = ByteArray(run.length / 3)
    for (index in bytes.indices) {
        val offset = index * 3
        val high = Character.digit(run[offset + 1], 16)
        val low = Character.digit(run[offset + 2], 16)
        if (high < 0 || low < 0) return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)).toString()
}
