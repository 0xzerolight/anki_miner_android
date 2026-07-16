package com.ankiminer.android.anki.s2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorDetail
import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.AnkiOperation
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.CreateNotesResult
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.MediaKind
import com.ankiminer.android.anki.protocol.NotAttemptedMedia
import com.ankiminer.android.anki.protocol.NotAttemptedNote
import com.ankiminer.android.anki.protocol.ReleaseRunStateRequest
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.UncertainMedia
import com.ankiminer.android.anki.protocol.UncertainNote
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.anki.provider.ContentResolverAnkiGateway
import com.ankiminer.android.anki.provider.ProviderAccessStatus
import com.ankiminer.android.anki.provider.WorkerThreadGuard
import com.ankiminer.android.debug.s2.S2ProbeMediaProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_S2_ARGUMENT = "ankiMinerRunS2"
private const val EVIDENCE_TAG = "AnkiMinerS2"

@RunWith(AndroidJUnit4::class)
class AnkiDroidS2CapabilityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = AddContentApi(context)

    @Test
    fun current_stable_provider_and_android_adapter_complete_the_raw_round_trip() {
        assumeTrue(
            "S2 runs only through its pinned AnkiDroid capability-probe runner",
            InstrumentationRegistry.getArguments().getString(RUN_S2_ARGUMENT) == "true",
        )
        assertPinnedProviderIdentity()
        val fixture = writeFixtures()
        val deckId = createProbeDeck()
        val modelId = createProbeModel(deckId)

        val directAudio = addMedia(fixture.audio, "s2_direct_audio", "audio")
        val directImage = addMedia(fixture.image, "s2_direct_image", "image")
        val retryAudio = addMedia(fixture.audio, "s2_direct_audio", "audio")
        val directAudioName = rawAudioName(directAudio)
        val directImageName = rawImageName(directImage)
        val retryAudioName = rawAudioName(retryAudio)
        assertNotEquals(
            "AnkiDroid's random temporary filename makes a blind retry a second media insert",
            directAudioName,
            retryAudioName,
        )

        val directFields = arrayOf(DIRECT_EXPRESSION, directImage, directAudio)
        val directNoteId = requireNotNull(api.addNote(modelId, deckId, directFields, setOf(PROBE_TAG)))
        assertArrayEquals(directFields, requireNotNull(api.getNote(directNoteId)).getFields())
        assertCardRouted(directNoteId, deckId, directAudioName, directImageName)

        AnkiProviderRuntime(context, WorkerThreadGuard { }).use { runtime ->
            assertTrue(runtime.callbacks.registerRun(RUN_ID))
            val callbacks = ProbeMutationCallbacks(context, runtime, api, deckId, modelId)
            startPython()
            val summary =
                JSONObject(
                    Python.getInstance()
                        .getModule("s2_anki_adapter_probe")
                        .callAttr(
                            "run",
                            context.filesDir.absolutePath,
                            callbacks,
                            RUN_ID,
                            DECK_NAME,
                            MODEL_NAME,
                            fixture.audio.absolutePath,
                            fixture.image.absolutePath,
                        ).toString(),
                )

            assertEquals(1, summary.getInt("created"))
            val noteIds = summary.getJSONArray("noteIds")
            assertEquals(1, noteIds.length())
            val adapterNoteId = noteIds.getLong(0)
            val adapterAudio = summary.getString("audioFilename")
            val adapterImage = summary.getString("imageFilename")
            assertTrue(rawStrings(summary.getJSONArray("knownBefore")).contains(DIRECT_EXPRESSION))
            assertTrue(rawStrings(summary.getJSONArray("knownAfter")).containsAll(listOf(DIRECT_EXPRESSION, "猫")))
            assertEquals(setOf(adapterAudio, adapterImage), callbacks.storedMediaNames)
            assertArrayEquals(
                arrayOf("猫", "<img src=\"$adapterImage\">", "[sound:$adapterAudio]"),
                requireNotNull(api.getNote(adapterNoteId)).getFields(),
            )
            assertCardRouted(adapterNoteId, deckId, adapterAudio, adapterImage)

            val evidence =
                JSONObject()
                    .put("ankiDroidVersion", "2.24.0")
                    .put("apiSpec", 2)
                    .put("deckId", deckId)
                    .put("modelId", modelId)
                    .put("directNoteId", directNoteId)
                    .put("adapterNoteId", adapterNoteId)
                    .put("directAudio", directAudioName)
                    .put("directImage", directImageName)
                    .put("blindRetryAudio", retryAudioName)
                    .put("adapterAudio", adapterAudio)
                    .put("adapterImage", adapterImage)
                    .put("mediaQueryAvailable", false)
            val renderedEvidence = "ANKI_MINER_S2_PROBE=${evidence}"
            Log.i(EVIDENCE_TAG, renderedEvidence)
            println(renderedEvidence)
        }
    }

    private fun assertPinnedProviderIdentity() {
        val status =
            ContentResolverAnkiGateway(context, WorkerThreadGuard { }).accessStatus()
        assertTrue(status is ProviderAccessStatus.Available)
        status as ProviderAccessStatus.Available
        assertEquals("com.ichi2.anki", status.packageName)
        assertEquals(2, status.apiSpecVersion)
        assertEquals(422400300L, status.versionCode)
    }

    private fun createProbeDeck(): Long {
        assertFalse(api.deckList.orEmpty().containsValue(DECK_NAME))
        val id = requireNotNull(api.addNewDeck(DECK_NAME))
        assertEquals(DECK_NAME, api.deckList.orEmpty()[id])
        return id
    }

    private fun createProbeModel(deckId: Long): Long {
        assertFalse(api.modelList.orEmpty().containsValue(MODEL_NAME))
        val id =
            requireNotNull(
                api.addNewCustomModel(
                    MODEL_NAME,
                    MODEL_FIELDS,
                    arrayOf("Probe Card"),
                    arrayOf("{{Expression}}"),
                    arrayOf("{{FrontSide}}<hr id=answer>{{Picture}}{{SentenceAudio}}"),
                    ".card { font-family: sans-serif; }",
                    deckId,
                    0,
                ),
            )
        assertEquals(MODEL_NAME, api.modelList.orEmpty()[id])
        assertArrayEquals(MODEL_FIELDS, api.getFieldList(id))
        return id
    }

    private fun addMedia(source: File, preferredName: String, kind: String): String {
        val staged = stage(source, "${preferredName}_${System.nanoTime()}${source.extensionWithDot()}")
        val uri = Uri.parse("content://${S2ProbeMediaProvider.AUTHORITY}/${staged.name}")
        context.grantUriPermission("com.ichi2.anki", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            requireNotNull(api.addMediaFromUri(uri, preferredName, kind))
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun stage(source: File, requestedName: String): File {
        val safeName = requestedName.lowercase().replace(Regex("[^a-z0-9_.]"), "_")
        val root = File(context.cacheDir, S2ProbeMediaProvider.ROOT).apply { mkdirs() }
        return File(root, safeName).also { source.copyTo(it, overwrite = true) }
    }

    private fun assertCardRouted(noteId: Long, deckId: Long, audioName: String, imageName: String) {
        val uri =
            Uri.withAppendedPath(
                Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString()),
                "cards",
            )
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(noteId, cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.NOTE_ID)))
            assertEquals(deckId, cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.DECK_ID)))
            val answer = cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.ANSWER))
            assertTrue(answer.contains(audioName))
            assertTrue(answer.contains(imageName))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun startPython() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance()
            .getModule("android_bridge.bootstrap")
            .callAttr("initialize", context.filesDir.absolutePath)
    }

    private fun rawStrings(values: JSONArray): Set<String> =
        (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) }

    private fun rawAudioName(markup: String): String =
        requireNotNull(Regex("^\\[sound:([^/\\\\\\[\\]]+)]$").matchEntire(markup)).groupValues[1]

    private fun rawImageName(markup: String): String =
        requireNotNull(Regex("^<img src=\"([^/\\\\\"<>]+)\" />$").matchEntire(markup)).groupValues[1]

    private fun File.extensionWithDot(): String = ".${extension.lowercase()}"

    private fun writeFixtures(): Fixture {
        val root = File(context.cacheDir, "s2-input").apply {
            deleteRecursively()
            mkdirs()
        }
        val audio = File(root, "probe.mp3").apply { writeBytes(Base64.getDecoder().decode(MP3_BASE64)) }
        val image = File(root, "probe.webp").apply { writeBytes(Base64.getDecoder().decode(WEBP_BASE64)) }
        assertEquals(MP3_SHA256, sha256(audio))
        assertEquals(WEBP_SHA256, sha256(image))
        return Fixture(audio, image)
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private data class Fixture(val audio: File, val image: File)

    class ProbeMutationCallbacks internal constructor(
        private val context: Context,
        private val runtime: AnkiProviderRuntime,
        private val api: AddContentApi,
        private val deckId: Long,
        private val modelId: Long,
    ) {
        private val mediaByAsset = linkedMapOf<String, String>()
        val storedMediaNames: Set<String> get() = mediaByAsset.values.toSet()

        fun ankiVerifyTarget(raw: String): String = runtime.callbacks.ankiVerifyTarget(raw)

        fun ankiScanFirstFields(raw: String): String = runtime.callbacks.ankiScanFirstFields(raw)

        fun ankiReleaseRunState(raw: String): String = runtime.callbacks.ankiReleaseRunState(raw)

        fun ankiStoreMedia(raw: String): String {
            val request = AnkiJsonCodec.decodeRequest(raw, AnkiOperation.STORE_MEDIA) as StoreMediaRequest
            val rows = mutableListOf<com.ankiminer.android.anki.protocol.MediaStoreRow>()
            var error: AnkiErrorDetail? = null
            request.assets.forEach { asset ->
                if (error != null) {
                    rows += NotAttemptedMedia(asset.assetId)
                    return@forEach
                }
                val source = File(asset.sourcePath)
                require(source.isFile && source.length() == asset.expectedSizeBytes)
                require(sha256(source) == asset.expectedSha256)
                val extension = if (asset.mediaKind == MediaKind.AUDIO) ".mp3" else ".webp"
                val staged = stage(source, "${asset.assetId}$extension")
                val uri = Uri.parse("content://${S2ProbeMediaProvider.AUTHORITY}/${staged.name}")
                context.grantUriPermission("com.ichi2.anki", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val markup =
                    try {
                        api.addMediaFromUri(uri, asset.preferredName, asset.mediaKind.wireName)
                    } finally {
                        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                if (markup == null) {
                    rows += UncertainMedia(asset.assetId)
                    error =
                        AnkiErrorDetail(
                            AnkiErrorCode.POST_COMMIT_UNCERTAIN,
                            "AnkiDroid returned no media filename after provider entry",
                            false,
                        )
                } else {
                    val actual =
                        if (asset.mediaKind == MediaKind.AUDIO) rawAudioName(markup) else rawImageName(markup)
                    mediaByAsset[asset.assetId] = actual
                    rows += StoredMedia(asset.assetId, actual)
                }
            }
            return AnkiJsonCodec.encodeResponse(
                StoreMediaResult(request.runId, request.requestId, rows, error),
                request,
            )
        }

        fun ankiCreateNotes(raw: String): String {
            val request = AnkiJsonCodec.decodeRequest(raw, AnkiOperation.CREATE_NOTES) as CreateNotesRequest
            require(request.deckName == DECK_NAME && request.modelName == MODEL_NAME)
            require(request.firstFieldName == MODEL_FIELDS.first())
            val rows = mutableListOf<com.ankiminer.android.anki.protocol.CreateNoteRow>()
            var error: AnkiErrorDetail? = null
            request.notes.forEach { note ->
                if (error != null) {
                    rows += NotAttemptedNote(note.clientNoteId)
                    return@forEach
                }
                require(note.fields.keys == MODEL_FIELDS.toSet())
                note.mediaBindings.forEach { binding ->
                    require(mediaByAsset[binding.assetId] == binding.actualFilename)
                }
                val id =
                    api.addNote(
                        modelId,
                        deckId,
                        MODEL_FIELDS.map { note.fields.getValue(it) }.toTypedArray(),
                        note.tags.toSet(),
                    )
                if (id == null) {
                    rows += UncertainNote(note.clientNoteId)
                    error =
                        AnkiErrorDetail(
                            AnkiErrorCode.POST_COMMIT_UNCERTAIN,
                            "AnkiDroid returned no note ID after provider entry",
                            false,
                        )
                } else {
                    rows += CreatedNote(note.clientNoteId, id)
                }
            }
            return AnkiJsonCodec.encodeResponse(
                CreateNotesResult(request.runId, request.requestId, rows, error),
                request,
            )
        }

        private fun stage(source: File, name: String): File {
            val root = File(context.cacheDir, S2ProbeMediaProvider.ROOT).apply { mkdirs() }
            return File(root, name).also { source.copyTo(it, overwrite = true) }
        }

        private fun rawAudioName(markup: String): String =
            requireNotNull(Regex("^\\[sound:([^/\\\\\\[\\]]+)]$").matchEntire(markup)).groupValues[1]

        private fun rawImageName(markup: String): String =
            requireNotNull(Regex("^<img src=\"([^/\\\\\"<>]+)\" />$").matchEntire(markup)).groupValues[1]

        private fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RUN_ID = "run_00000000000000000000000000000052"
        private const val DECK_NAME = "Anki Miner S2 Probe"
        private const val MODEL_NAME = "Anki Miner S2 Probe Model"
        private const val DIRECT_EXPRESSION = "直接確認"
        private const val PROBE_TAG = "anki_miner_s2_probe"
        private val MODEL_FIELDS = arrayOf("Expression", "Picture", "SentenceAudio")
        private const val MP3_SHA256 = "5a61c64e1884e766f20f5e38b87c199a4a93a53f6ff834d933794656da199285"
        private const val WEBP_SHA256 = "7e5c125b10bb6938c9e1c5f20517459f4e6b7daa5d9646faeaf510b9c5eaecd1"
        private const val MP3_BASE64 =
            (
            "/+NIxAA7syZkCUnQARCsVhcEw2KxWK0ZIKBQKBQKBQgQIyMVisVisnRtoEAoFAoFCBiE1yMVisVo9+wQCgUIIkYrjEssTbtu/AQEJGoYHVpnPim1SmVCpr2sNSh34ft//508bjEYpMMMKlJSRuX09vPOnp6eWUlJYwwpKSkp6enp869PT09JSUljDCkpKSnp6e3nnXp6ekpKTDDDCksU9PT555517dJSWMMMMMMKe3nnnnT09PSUlJSUlJSUlJT09PT09PT09ukpKSkpKSkpLFPT5h4eHgAAAAAGHh4eHgAAAAAGHh4eHgAAAAAGHh4eHgAAAAAGHh4ePAABAJgSxmzRpWhgjePMsv//M8hNU9MOgNOo7Vx5//5s4xilZsGp/+NIxCRDazJACZqwAMCvW1lV//8DAmDgDCGEoDC2HYDBEFcDDQHcDEsMkxNTIyLxDfwMJYwgMg47gMTMDgP2KsgMzpDAMZozgMEwYQMNAYROAGBEA4GPzJAGZ07AGIoJYAQKkkwkAEAAJAGBXkAHiotgGKAHoDANsDC0GEDAaDADByDQDBqDgDAABsDBACEDA8CMAkBwGBUCQGBMCiKLJKo//wFgDgYCgFgYCAFg3SAwBgCAwBgCDAoNkg2DQ6IG4gtG////E2hcKGWRXg1cJSHSIKi4SHCtiDFEc3////yqcIsX0y6WEUiNLyQ5RMl0Y0miLf9PShCBP//VOqdt1SMCUCLrMB//9QRtFA0x0xS6q0khv//bdIRFRkhapSlC/+NIxClHK8JkEZjoAEgQv///6EsuWr8soYBAElwjcWyMAwMCwAmAgEmCQRt3BgIGIq4BK0DQKvfzzAsEzBUIzJEYzbY8TGEHwEEZgUAwCAQAgwYRhAYJgEYXjkY2h4YQhQDAJMBgD//////9PhB9Itp67F2Ooztr8XMBAJSqQDFwmAqlSFcJiSw3///////7OGcQI5blxd/Hcij/v/Pvq5LDX2cprURf12Yk/ztf////////7sORFH/f+Xv4/k5Db/z8YjEpjL+yqNQ1KYzDNaNRqrS////////////2KeX26SksU9PbpKSxT09ukpIzWppVVpZTWppVVpZTWppVVvUUAQVahqGq2ZiQ46orchyHK6dhOU0XJtQ1DVDMxHMa/+NIxB83EtYEDc94AU4sRzGkoozCW0nLMwmiXE6XFTEGHqUK6IMLcLkhR2g2QIJ5E9DUjhVMJXIc7J8DaBvFiXJoqwOICEcI+g1QuSFNpyk5Li5KU0TpZaMSHIdFYkOUTNd6rVbGgq1WxdQmJXVhPn0bcF693BevYvs+fas+fW9Xr3dYMWvtCfaxa1vWsXea1r8WtrFrW9YL3ea1r7WjahBXQVsFNhDIQ3oL0FdCOhDfBfBXZHZBXQVsFNhDIQV0FbBTYQyEFdBWwVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"
                + "VVVVVVVV"
            )
        private const val WEBP_BASE64 = "UklGRh4AAABXRUJQVlA4TBEAAAAvA8AAAAfQsso0s/+BiOh/AAA="
    }
}
