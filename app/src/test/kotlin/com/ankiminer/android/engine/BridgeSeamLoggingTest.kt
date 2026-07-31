package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The records the Python bridge seam emits, and the two helpers that build their fields. */
class BridgeSeamLoggingTest {
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        // Two installs: the first discards whatever a previous test class left in the pre-install
        // buffer, so the second starts from a known-empty recorder.
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun detachRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
    }

    @Test
    fun `a rejection raised inside the envelope reader is recorded once with its category`() {
        val raw = """{"schemaVersion":2,"type":"job.cancel","payload":{"runId":"run_${"0".repeat(32)}"}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }

        assertEquals(
            listOf(
                "W run=- c=bridge op=codec.decode category=UNSUPPORTED_SCHEMA_VERSION " +
                    "bytes=${raw.toByteArray(StandardCharsets.UTF_8).size} outcome=fail",
            ),
            decodeRecords(),
        )
    }

    @Test
    fun `dispatch entry carries an ok outcome`() {
        val raw = BridgeJsonCodec.encodeJobCancel("run_${"0".repeat(32)}")
        AppLog.setMinLevel(LogLevel.DEBUG)

        emitDispatchEntry(bridgeEnvelopeType(raw), raw)

        assertEquals(
            "D run=- c=bridge op=dispatch at=enter type=job.cancel " +
                "bytes=${raw.toByteArray(StandardCharsets.UTF_8).size} outcome=ok",
            recorded.records.single().substringBefore('\n').substring(TIMESTAMP_PREFIX),
        )
    }

    @Test
    fun `a duplicate key is recorded as its own category rather than as malformed json`() {
        val raw = """{"schemaVersion":1,"schemaVersion":1,"type":"job.cancel","payload":{}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }

        assertEquals(
            listOf(
                "W run=- c=bridge op=codec.decode category=DUPLICATE_JSON_KEY " +
                    "bytes=${raw.toByteArray(StandardCharsets.UTF_8).size} outcome=fail",
            ),
            decodeRecords(),
        )
    }

    @Test
    fun `no part of the rejected envelope reaches the record`() {
        val raw = """{"schemaVersion":1,"type":"curation.request","payload":{"runId":"猫を賭ける"}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }

        val records = recorded.records.single()
        assertTrue(records, records.contains("op=codec.decode"))
        assertTrue(records, !records.contains("猫"))
        assertTrue(records, !records.contains("賭"))
    }

    @Test
    fun `the request type is read from the envelope prefix`() {
        val envelope = BridgeJsonCodec.encodeJobCancel("run_${"0".repeat(32)}")

        assertEquals("job.cancel", bridgeEnvelopeType(envelope))
    }

    @Test
    fun `a type beyond the scanned prefix is not read at all`() {
        val buried = " ".repeat(200) + """"type":"mining.video.run""""

        assertEquals("?", bridgeEnvelopeType(buried))
    }

    @Test
    fun `an unterminated or absent type yields the unknown marker`() {
        assertEquals("?", bridgeEnvelopeType(""""type":"job.canc"""))
        assertEquals("?", bridgeEnvelopeType("""{"schemaVersion":1}"""))
        assertEquals("?", bridgeEnvelopeType(""))
    }

    @Test
    fun `the byte count matches what the encoder would produce`() {
        // One string per UTF-8 width: 1, 2, 3 and 4 bytes. Without the two-byte case a widened
        // two-byte range is invisible, which is how this list was first written.
        listOf("", "job.cancel", "café ü", "猫を賭ける", "🍣 sushi", "a b")
            .forEach { text ->
                assertEquals(text, text.toByteArray(StandardCharsets.UTF_8).size, utf8Length(text))
            }
    }

    @Test
    fun `an unpaired surrogate is counted rather than throwing or truncating the scan`() {
        // 3, the width of the U+FFFD a lenient encoder substitutes. String.toByteArray substitutes a
        // single '?' instead, so the two deliberately disagree for this input.
        assertEquals(3, utf8Length("\uD83C"))
        assertEquals(4, utf8Length("a\uD83C"))
    }

    /** Records without their throwable continuation lines, which carry Jackson's own line numbers. */
    private fun decodeRecords(): List<String> =
        recorded.records.map { record -> record.substringBefore('\n').substring(TIMESTAMP_PREFIX) }

    private companion object {
        /** `yyyy-MM-ddTHH:mm:ss.SSSZ` plus the space before the level character. */
        const val TIMESTAMP_PREFIX = 25
    }
}
