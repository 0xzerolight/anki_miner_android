package com.ankiminer.android.diagnostics

import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.LogRedactor
import com.ankiminer.android.diagnostics.log.RedactionRulesFactory
import com.ankiminer.android.diagnostics.log.renderLogRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bundle a tester shares, end to end, in every script the app mines.
 *
 * `logs/anki_miner.log` is real bridge output: `test_diagnostics_redaction_corpus.py` re-derives the
 * committed corpus from the live Python file-handler chain, so the sentinel spans here are the ones
 * the bridge writes. Kotlin records and third-party logcat text ride alongside, and everything goes
 * through [DiagnosticsBundleWriter] with the export redactor, the way [DiagnosticsBundleStager] wires
 * them. LogRedactorTest pins each rule alone; this pins the promise the Settings screen makes, that
 * no mined word survives a redacted entry.
 */
class DiagnosticsBundleRedactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `no mined word of any script survives a redacted bundle`() {
        val secrets =
            corpus("mined_words.txt").lines().filter(String::isNotBlank).map { it.substringAfter(' ') }
        val python = corpus("python_log.txt")
        val logcat = corpus("logcat.txt")
        val kotlin =
            renderLogRecord(
                Instant.parse("2026-10-09T12:00:09.250Z"),
                LogLevel.INFO,
                "run_" + "c".repeat(32),
                LogComponent.MINING,
                "curation.preview",
                arrayOf("outcome" to "ok", "word" to "αγάπη", "sentence" to "שלום עולם"),
                null,
            ) + "\n"
        secrets.forEach { secret ->
            assertTrue("the raw corpus must carry $secret", (python + kotlin + logcat).contains(secret))
        }

        val entries =
            writeBundle(
                mapOf(
                    "logs/anki_miner.log" to python,
                    "logs/app.log" to kotlin,
                    "logcat/logcat.txt" to logcat,
                ),
            )

        entries.forEach { (name, text) ->
            secrets.forEach { secret -> assertFalse("$secret survived in $name:\n$text", text.contains(secret)) }
            assertFalse(name, text.contains('⟦') || text.contains('⟧'))
        }
        // Not vacuous: the record skeleton and the engine's own wording survive around the tokens.
        val engine = entries.getValue("logs/anki_miner.log")
        assertTrue(engine, engine.contains("not attested; fronting the surface <arg-"))
        assertTrue(engine, engine.contains("purpose=card kind=audio"))
        assertTrue(engine, engine.contains("\tRuntimeError: Python log call omitted exception context"))
        val thirdParty = entries.getValue("logcat/logcat.txt")
        assertTrue(thirdParty, thirdParty.contains("I TextToSpeech: speak utterance <script-"))
        assertTrue(thirdParty, thirdParty.contains("q=<script-enc-"))
    }

    /** Every redacted entry of the archive, by name, as the writer produced it. */
    private fun writeBundle(texts: Map<String, String>): Map<String, String> {
        val roots =
            mapOf(
                "files" to temporaryFolder.newFolder("files"),
                "cache" to temporaryFolder.newFolder("cache"),
            )
        val rules =
            RedactionRulesFactory.forExport(
                roots,
                AppSettings(),
                safUserText = emptyList(),
                buildUser = null,
                salt = ByteArray(16) { index -> index.toByte() },
            )
        val redactor = LogRedactor(rules)
        val sources =
            DiagnosticsBundleSpec.entries.map { spec ->
                BundleSource(spec.name, spec.capBytes, spec.redacted, spec.required, spec.shedding) {
                    texts[spec.name]?.let { ByteArrayInputStream(it.toByteArray(StandardCharsets.UTF_8)) }
                }
            }
        val archive = ByteArrayOutputStream()
        DiagnosticsBundleWriter(
            capturedAt = Instant.parse("2026-10-09T12:00:10Z"),
            compressionLevel = Deflater.BEST_COMPRESSION,
            totalBudgetBytes = DiagnosticsBundleSpec.TOTAL_BUDGET_BYTES,
        ).write(
            destination = archive,
            sources = sources,
            redactor = LineRedactor(redactor::redact),
            manifest = { emptyMap() },
            readme = { _, _ -> "" },
        )
        val redactedNames = DiagnosticsBundleSpec.entries.filter { it.redacted }.map { it.name }.toSet()
        return unzip(archive.toByteArray()).filterKeys { it in redactedNames }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> =
        buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, String(zip.readBytes(), StandardCharsets.UTF_8))
                }
            }
        }

    private fun corpus(name: String): String {
        val resource = "contracts/diagnostics_redaction_v1/$name"
        val input =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                "missing redaction corpus: $resource"
            }
        return input.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }
}
