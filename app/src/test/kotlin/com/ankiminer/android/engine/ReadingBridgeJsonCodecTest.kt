package com.ankiminer.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingBridgeJsonCodecTest {
    @Test
    fun `reading request round trips with an exact nullable archive field`() {
        val request =
            ReadingMiningWireRequest(
                sourceKind = ReadingMiningSourceKind.MOKURO,
                sourcePath = "/data/user/0/app/cache/reading/job/Volume.mokuro",
                imageArchivePath = "/data/user/0/app/cache/reading/job/Volume.cbz",
                seriesName = null,
                cacheDir = "/data/user/0/app/cache",
                nativeLibraryDir = "/data/app/app/lib/arm64",
                configSnapshot =
                    MiningConfigSnapshot(
                        settings =
                            mapOf(
                                "anki_deck_name" to BridgeJsonValue.Text("Mining"),
                                "reading_min_occurrence" to BridgeJsonValue.Integer(2),
                            ),
                        androidTtsEnabled = true,
                    ),
            )

        val encoded = BridgeJsonCodec.encodeReadingRun(request)

        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"mining.reading.run\",\"payload\":{" +
                "\"sourceKind\":\"mokuro\",\"sourcePath\":\"/data/user/0/app/cache/reading/job/Volume.mokuro\"," +
                "\"imageArchivePath\":\"/data/user/0/app/cache/reading/job/Volume.cbz\"," +
                "\"seriesName\":null," +
                "\"cacheDir\":\"/data/user/0/app/cache\",\"nativeLibraryDir\":\"/data/app/app/lib/arm64\"," +
                "\"configSnapshot\":{\"settings\":{\"anki_deck_name\":\"Mining\",\"reading_min_occurrence\":2}," +
                "\"androidTtsEnabled\":true}}}",
            encoded,
        )
        assertEquals(request, (BridgeJsonCodec.decode(encoded) as BridgeMessage.ReadingRun).request)

        val text =
            request.copy(
                sourceKind = ReadingMiningSourceKind.TXT,
                sourcePath = "/data/user/0/app/cache/reading/job/Novel.txt",
                imageArchivePath = null,
                seriesName = null,
            )
        val textEncoded = BridgeJsonCodec.encodeReadingRun(text)
        assertEquals(text, (BridgeJsonCodec.decode(textEncoded) as BridgeMessage.ReadingRun).request)
        assertEquals(true, textEncoded.contains("\"imageArchivePath\":null"))
    }

    @Test
    fun `reading wire rejects mismatched kinds archives and paths outside cache`() {
        val validPayload =
            "\"sourceKind\":\"mokuro\",\"sourcePath\":\"/cache/job/Volume.mokuro\"," +
            "\"imageArchivePath\":\"/cache/job/Volume.cbz\",\"seriesName\":null,\"cacheDir\":\"/cache\"," +
                "\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{}}"

        val cases =
            listOf(
                validPayload.replace("\"mokuro\"", "\"pdf\""),
                validPayload.replace("Volume.mokuro", "Volume.txt"),
                validPayload.replace("Volume.cbz", "Other.cbz"),
                validPayload.replace("/cache/job/Volume.mokuro", "/outside/Volume.mokuro"),
                validPayload.replace("\"mokuro\"", "\"txt\""),
            )

        cases.forEach { payload ->
            val failure =
                assertThrows(BridgeProtocolException::class.java) {
                    BridgeJsonCodec.decode(envelope(payload))
                }
            assertEquals(BridgeProtocolCategory.INVALID_VALUE, failure.category)
        }
    }

    @Test
    fun `subtitle requires a canonical explicit series and other kinds prohibit one`() {
        val subtitle =
            ReadingMiningWireRequest(
                sourceKind = ReadingMiningSourceKind.SUBTITLE,
                sourcePath = "/cache/job/Episode.srt",
                imageArchivePath = null,
                seriesName = "My Series",
                cacheDir = "/cache",
                nativeLibraryDir = "/native",
                configSnapshot = MiningConfigSnapshot(emptyMap(), false),
            )
        assertEquals(
            subtitle,
            (BridgeJsonCodec.decode(BridgeJsonCodec.encodeReadingRun(subtitle)) as BridgeMessage.ReadingRun)
                .request,
        )

        val invalid =
            listOf(
                subtitle.copy(seriesName = null),
                subtitle.copy(seriesName = " My Series "),
                subtitle.copy(seriesName = "x".repeat(BridgeJsonCodec.MAX_READING_SERIES_NAME_UTF8_BYTES + 1)),
                subtitle.copy(
                    sourceKind = ReadingMiningSourceKind.TXT,
                    sourcePath = "/cache/job/Novel.txt",
                ),
            )
        invalid.forEach { request ->
            val failure =
                assertThrows(BridgeProtocolException::class.java) {
                    BridgeJsonCodec.encodeReadingRun(request)
                }
            assertEquals(BridgeProtocolCategory.INVALID_VALUE, failure.category)
        }
    }

    @Test
    fun `reading wire rejects unknown and missing fields`() {
        val valid =
            "\"sourceKind\":\"txt\",\"sourcePath\":\"/cache/job/Novel.txt\"," +
            "\"imageArchivePath\":null,\"seriesName\":null,\"cacheDir\":\"/cache\"," +
                "\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{}}"
        val unknown = "$valid,\"sourceLabel\":\"Novel\""
        val missing = valid.replace(",\"imageArchivePath\":null", "")

        listOf(unknown, missing).forEach { payload ->
            val failure =
                assertThrows(BridgeProtocolException::class.java) {
                    BridgeJsonCodec.decode(envelope(payload))
                }
            assertEquals(BridgeProtocolCategory.INVALID_PAYLOAD, failure.category)
        }
    }

    @Test
    fun `reading request has the same one mebibyte bound as Python`() {
        val oversized = "x".repeat(BridgeJsonCodec.MAX_READING_RUN_UTF8_BYTES)
        val raw =
            envelope(
                "\"sourceKind\":\"txt\",\"sourcePath\":\"/cache/job/Novel.txt\"," +
                "\"imageArchivePath\":null,\"seriesName\":null,\"cacheDir\":\"/cache\"," +
                    "\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{" +
                    "\"anki_tags\":\"$oversized\"}}",
            )

        val failure =
            assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }

        assertEquals(BridgeProtocolCategory.INPUT_TOO_LARGE, failure.category)
    }

    private fun envelope(payload: String): String =
        "{\"schemaVersion\":1,\"type\":\"mining.reading.run\",\"payload\":{$payload}}"
}
