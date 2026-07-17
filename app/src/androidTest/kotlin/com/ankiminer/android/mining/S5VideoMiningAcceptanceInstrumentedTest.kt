package com.ankiminer.android.mining

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.pm.PackageInfoCompat
import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.PythonInstrumentationRuntime
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.data.resources.ResourceBridgeCodec
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.debug.S3TestDocumentsProvider
import com.ankiminer.android.service.MiningForegroundService
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_S5_ARGUMENT = "ankiMinerRunS5"
private const val EVIDENCE_TAG = "AnkiMinerS5"

/**
 * Destructive, opt-in needle-thread against a pinned disposable AnkiDroid collection.
 *
 * Setup creates resources and the production model, but the accepted notes and media travel only through
 * the process-owned BridgeMiningRepository and its production durable provider callbacks.
 */
@RunWith(AndroidJUnit4::class)
class S5VideoMiningAcceptanceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context.applicationContext as AnkiMinerApplication
    private val api = AddContentApi(context)

    @Test
    fun production_repository_mines_real_media_and_cancels_an_active_ffmpeg_child() {
        assumeTrue(
            "S5 runs only through its pinned disposable-AnkiDroid runner",
            InstrumentationRegistry.getArguments().getString(RUN_S5_ARGUMENT) == "true",
        )
        check(BuildConfig.S1A_PUBLICATION_VERIFIED) {
            "S5 requires the selected S1a tokenizer publication"
        }
        val python = PythonInstrumentationRuntime.awaitReady()
        assertPinnedAnkiDroid()
        prepareProductionResources(python)
        createTarget()
        runBlocking {
            application.settingsRepository.update(
                AppSettings(
                    deckName = DECK_NAME,
                    tags = PROBE_TAG,
                    audioPaddingSeconds = 8.0,
                    screenshotOffsetSeconds = 0.2,
                    useKnownWordsDatabase = false,
                    useIPlusOneFilter = false,
                    useSentenceLengthFilter = false,
                    maxParallelWorkers = 1,
                    jishoEnabled = false,
                ),
            )
        }
        createSafFixtures(python)
        assertTrue(activeMediaChildren().isEmpty())
        assertForegroundServiceStopped()

        val success = mineOneRealNote()
        val verified = verifyCreatedNote(success)
        awaitForegroundServiceStopped()
        assertTrue(activeMediaChildren().isEmpty())

        val cancellation = cancelDuringRealMediaExtraction()
        awaitForegroundServiceStopped()
        assertTrue("media child survived terminal cancellation", activeMediaChildren().isEmpty())

        val evidence =
            JSONObject()
                .put("ankiDroidVersion", PINNED_ANKIDROID_VERSION)
                .put("apiSpec", 2)
                .put("noteId", verified.noteId)
                .put("cardId", verified.cardId)
                .put("picture", verified.picture)
                .put("audio", verified.audio)
                .put("curatedCancellationCandidates", cancellation.candidateCount)
                .put("observedFfmpegPid", cancellation.observedPid)
                .put("cancelToTerminalMillis", cancellation.terminalMillis)
        val rendered = "ANKI_MINER_S5_PROBE=$evidence"
        Log.i(EVIDENCE_TAG, rendered)
        println(rendered)
    }

    private fun prepareProductionResources(python: com.chaquo.python.Python) {
        PythonInstrumentationRuntime.publishExternalUniDicForAcceptance(
            BuiltInInstalledTokenizerResourceProvider.TREE_SHA_256,
        )
        val dictionary = writeDictionaryFixture()
        val request =
            ResourceBridgeCodec.encodeDictionaryImportRequest(
                operation = "s5_dictionary_import",
                sourcePath = dictionary.canonicalPath,
                selectedSlotId = DICTIONARY_SLOT,
                overwrite = true,
                catalogResourceId = null,
            )
        val raw =
            python.getModule("android_bridge.boundary")
                .callAttr("dispatch", request)
                .toJava(String::class.java)
        val imported = ResourceBridgeCodec.decodeImportedDictionary(raw)
        assertEquals(DICTIONARY_SLOT, imported.slotId)
        assertEquals(TERMS.size.toLong(), imported.entryCount)

        runBlocking { application.resourceManager.recoverAndRefresh() }
        assertEquals(listOf(DICTIONARY_SLOT), application.resourceManager.installedDictionaryIds())
        assertEquals(
            BuiltInInstalledTokenizerResourceProvider.TREE_SHA_256,
            application.resourceManager.state.value.installedUniDic?.treeSha256,
        )
    }

    private fun createTarget() {
        assertFalse(api.deckList.orEmpty().containsValue(DECK_NAME))
        assertFalse(api.modelList.orEmpty().containsValue(AnkiMinerNoteModel.MODEL_NAME))
        val deckId = requireNotNull(api.addNewDeck(DECK_NAME))
        application.ankiSetupManager.refresh()
        runBlocking {
            withTimeout(STATE_TIMEOUT_MS) {
                application.ankiSetupManager.state.first { it.operation == null }
            }
        }
        application.ankiSetupManager.provisionModel()
        val modelId =
            runBlocking {
                withTimeout(STATE_TIMEOUT_MS) {
                    val state =
                        application.ankiSetupManager.state.first {
                            it.operation == null && it.model is AnkiMinerModelProvisioningResult.Ready
                        }
                    (state.model as AnkiMinerModelProvisioningResult.Ready).modelId
                }
            }
        assertEquals(DECK_NAME, api.deckList.orEmpty()[deckId])
        assertEquals(AnkiMinerNoteModel.MODEL_NAME, api.modelList.orEmpty()[modelId])
        assertEquals(
            AnkiMinerNoteModel.FIELD_NAMES,
            requireNotNull(api.getFieldList(modelId)).toList(),
        )
    }

    private fun createSafFixtures(python: com.chaquo.python.Python) {
        val video = File(context.cacheDir, S3TestDocumentsProvider.S5_VIDEO_FIXTURE_NAME)
        video.delete()
        python.getModule("s5_video_fixture")
            .callAttr(
                "create_video",
                nativeTool("libffmpeg.so").canonicalPath,
                video.canonicalPath,
                VIDEO_DURATION_SECONDS,
            )
        assertTrue(video.isFile && video.length() > 0)

        val subtitle = File(context.cacheDir, S3TestDocumentsProvider.S5_SUBTITLE_FIXTURE_NAME)
        subtitle.writeText(
            TERMS.mapIndexed { index, term ->
                val start = index * 2 + 1
                val end = start + 1
                "${index + 1}\n${srtTime(start)},000 --> ${srtTime(end)},500\n$term。\n"
            }.joinToString("\n"),
            Charsets.UTF_8,
        )
        assertTrue(subtitle.isFile && subtitle.length() > 0)
    }

    private fun mineOneRealNote(): MiningRunState.Success {
        runBlocking { application.miningRepository.startVideo(INPUT) }
        val curating = awaitState { it is MiningRunState.Curating } as MiningRunState.Curating
        val cat = curating.request.candidates.single { it.minedForm == SUCCESS_TERM }
        runBlocking {
            application.miningRepository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                listOf(CurationSelection(cat.candidateId, cat.defaultSentenceId)),
            )
        }
        val terminal = awaitState(timeoutMillis = SUCCESS_TIMEOUT_MS, MiningRunState::isTerminal)
        check(terminal is MiningRunState.Success) { "S5 success run ended as $terminal" }
        assertEquals(1L, terminal.result.cardsCreated)
        assertEquals(listOf(SUCCESS_TERM), terminal.result.minedForms)
        assertEquals(1, terminal.result.cardIds.size)
        return terminal
    }

    private fun verifyCreatedNote(success: MiningRunState.Success): VerifiedNote {
        val noteId = success.result.cardIds.single()
        val note = requireNotNull(api.getNote(noteId))
        val fields = note.getFields()
        assertEquals(AnkiMinerNoteModel.FIELD_NAMES.size, fields.size)
        fun field(name: String) = fields[AnkiMinerNoteModel.FIELD_NAMES.indexOf(name)]
        assertEquals(SUCCESS_TERM, field("Expression"))
        assertTrue(field("Sentence").contains(SUCCESS_TERM))
        assertTrue(field("MainDefinition").contains(SUCCESS_DEFINITION))
        assertTrue(note.getTags().contains(PROBE_TAG))
        val picture = requireNotNull(PICTURE_MARKUP.matchEntire(field("Picture"))).groupValues[1]
        val audio = requireNotNull(AUDIO_MARKUP.matchEntire(field("SentenceAudio"))).groupValues[1]
        assertAnkiMediaNonEmpty(picture)
        assertAnkiMediaNonEmpty(audio)

        val cardsUri =
            Uri.withAppendedPath(
                Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString()),
                "cards",
            )
        val deckId = api.deckList.orEmpty().entries.single { it.value == DECK_NAME }.key
        var cardId = 0L
        context.contentResolver.query(cardsUri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            cardId = cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Card._ID))
            assertEquals(noteId, cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.NOTE_ID)))
            assertEquals(deckId, cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.DECK_ID)))
            val answer = cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.ANSWER))
            assertTrue(answer.contains(picture))
            assertTrue(answer.contains(audio))
            assertFalse(cursor.moveToNext())
        }
        return VerifiedNote(noteId, cardId, picture, audio)
    }

    private fun cancelDuringRealMediaExtraction(): CancellationEvidence {
        runBlocking { application.miningRepository.reset() }
        runBlocking { application.miningRepository.startVideo(INPUT) }
        val curating = awaitState { it is MiningRunState.Curating } as MiningRunState.Curating
        assertTrue(
            "fixture did not leave enough distinct candidates for a durable cancellation window",
            curating.request.candidates.size >= MIN_CANCELLATION_CANDIDATES,
        )
        val selections =
            curating.request.candidates.map { candidate ->
                CurationSelection(candidate.candidateId, candidate.defaultSentenceId)
            }
        runBlocking {
            application.miningRepository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                selections,
            )
        }
        awaitState {
            it is MiningRunState.Running &&
                it.progress.description.contains("Extracting media", ignoreCase = true)
        }
        val observedPid = awaitActiveMediaChild()
        val terminalMillis =
            measureTimeMillis {
                runBlocking { application.miningRepository.cancel(curating.request.runId) }
                val terminal =
                    awaitState(
                        timeoutMillis = CANCELLATION_TERMINAL_TIMEOUT_MS,
                        predicate = MiningRunState::isTerminal,
                    )
                check(terminal is MiningRunState.Cancelled) {
                    "S5 cancellation ended as $terminal"
                }
            }
        assertTrue(
            "cancellation exceeded $CANCELLATION_TERMINAL_TIMEOUT_MS ms",
            terminalMillis < CANCELLATION_TERMINAL_TIMEOUT_MS,
        )
        awaitNoMediaChildren()
        return CancellationEvidence(
            candidateCount = selections.size,
            observedPid = observedPid,
            terminalMillis = terminalMillis,
        )
    }

    private fun awaitState(
        timeoutMillis: Long = STATE_TIMEOUT_MS,
        predicate: (MiningRunState) -> Boolean,
    ): MiningRunState =
        runBlocking {
            withTimeout(timeoutMillis) {
                application.miningRepository.state.first(predicate)
            }
        }

    private fun awaitActiveMediaChild(): Int {
        val deadline = System.nanoTime() + ACTIVE_CHILD_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            activeMediaChildren().firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("No real ffmpeg/ffprobe child was observable during the cancellation run")
    }

    private fun awaitNoMediaChildren() {
        val deadline = System.nanoTime() + CHILD_EXIT_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (activeMediaChildren().isEmpty()) return
            Thread.sleep(20)
        }
        error("A media subprocess survived cancellation: ${activeMediaChildren()}")
    }

    private fun activeMediaChildren(): List<Int> {
        val selfCmdline = File("/proc/${Process.myPid()}/cmdline").readBytes()
        check(selfCmdline.isNotEmpty()) { "The acceptance lane cannot observe its own /proc entry" }
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == Process.myPid()) return@mapNotNull null
            val cmdline = runCatching { File(entry, "cmdline").readBytes() }.getOrNull()
                ?: return@mapNotNull null
            val rendered = cmdline.toString(Charsets.ISO_8859_1)
            pid.takeIf {
                rendered.contains("/libffmpeg.so") || rendered.contains("/libffprobe.so")
            }
        }
    }

    private fun assertAnkiMediaNonEmpty(filename: String) {
        check(filename.none { it == '/' || it == '\\' || it.isISOControl() })
        val path = "$ANKIDROID_MEDIA_ROOT/$filename"
        val output = shell("stat -c %s -- ${shellQuote(path)}")
        val bytes = output.trim().toLongOrNull()
        assertTrue("Anki media is missing or empty: $filename ($output)", bytes != null && bytes > 0)
    }

    private fun shell(command: String): String {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command)
        return AutoCloseInputStream(descriptor).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun assertPinnedAnkiDroid() {
        val info = context.packageManager.getPackageInfo("com.ichi2.anki", 0)
        assertEquals(PINNED_ANKIDROID_VERSION, info.versionName)
        assertEquals(PINNED_ANKIDROID_VERSION_CODE, PackageInfoCompat.getLongVersionCode(info))
    }

    private fun awaitForegroundServiceStopped() {
        val deadline = System.nanoTime() + SERVICE_STOP_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!foregroundServiceRunning()) return
            Thread.sleep(20)
        }
        assertForegroundServiceStopped()
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceRunning(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val expected = MiningForegroundService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == expected }
    }

    private fun assertForegroundServiceStopped() {
        assertFalse("MiningForegroundService is still running", foregroundServiceRunning())
    }

    private fun nativeTool(name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name).also { tool ->
            check(tool.isFile && tool.canExecute()) { "Native S5 tool is unavailable: $tool" }
        }

    private fun writeDictionaryFixture(): File {
        val destination = File(context.cacheDir, "s5-yomitan-dictionary.zip")
        ZipOutputStream(FileOutputStream(destination, false)).use { archive ->
            val index =
                JSONObject()
                    .put("title", "Anki Miner S5 Dictionary")
                    .put("revision", "1")
                    .put("format", 3)
                    .put("author", "Anki Miner acceptance fixture")
            archive.putNextEntry(ZipEntry("index.json"))
            archive.write(index.toString().toByteArray(Charsets.UTF_8))
            archive.closeEntry()

            val rows =
                JSONArray().apply {
                    TERMS.forEach { term ->
                        put(
                            JSONArray()
                                .put(term)
                                .put("")
                                .put("n")
                                .put("")
                                .put(0)
                                .put(JSONArray().put(definitionFor(term)))
                                .put(1)
                                .put(""),
                        )
                    }
                }
            archive.putNextEntry(ZipEntry("term_bank_1.json"))
            archive.write(rows.toString().toByteArray(Charsets.UTF_8))
            archive.closeEntry()
        }
        return destination.also { check(it.isFile && it.length() > 0) }
    }

    private fun definitionFor(term: String): String =
        if (term == SUCCESS_TERM) SUCCESS_DEFINITION else "S5 definition for $term"

    private fun srtTime(seconds: Int): String =
        "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    private data class VerifiedNote(
        val noteId: Long,
        val cardId: Long,
        val picture: String,
        val audio: String,
    )

    private data class CancellationEvidence(
        val candidateCount: Int,
        val observedPid: Int,
        val terminalMillis: Long,
    )

    companion object {
        private const val PINNED_ANKIDROID_VERSION = "2.24.0"
        private const val PINNED_ANKIDROID_VERSION_CODE = 422400300L
        private const val DECK_NAME = "Anki Miner S5 Probe"
        private const val PROBE_TAG = "anki_miner_s5_probe"
        private const val DICTIONARY_SLOT = "s5-fixture"
        private const val SUCCESS_TERM = "猫"
        private const val SUCCESS_DEFINITION = "cat acceptance definition"
        private const val VIDEO_DURATION_SECONDS = 90
        private const val MIN_CANCELLATION_CANDIDATES = 12
        private const val STATE_TIMEOUT_MS = 60_000L
        private const val SUCCESS_TIMEOUT_MS = 180_000L
        private const val ACTIVE_CHILD_TIMEOUT_MS = 20_000L
        private const val CANCELLATION_TERMINAL_TIMEOUT_MS = 10_000L
        private const val CHILD_EXIT_TIMEOUT_MS = 5_000L
        private const val SERVICE_STOP_TIMEOUT_MS = 5_000L
        private const val ANKIDROID_MEDIA_ROOT = "/storage/emulated/0/AnkiDroid/collection.media"

        private val PICTURE_MARKUP = Regex("^<img src=\"([^/\\\\\"<>]+)\">$")
        private val AUDIO_MARKUP = Regex("^\\[sound:([^/\\\\\\[\\]]+)]$")
        private val TERMS =
            listOf(
                "猫", "犬", "山", "川", "海", "空", "雨", "雪", "花", "鳥",
                "魚", "車", "駅", "学校", "先生", "学生", "本", "水", "火", "木",
                "金", "土", "月", "時間", "世界", "友達", "家族", "料理", "音楽", "映画",
                "電話", "写真", "部屋", "仕事", "言葉", "名前", "朝", "夜", "春", "夏",
                "秋", "冬",
            )
        private val INPUT =
            VideoMiningInput(
                video =
                    MiningSource(
                        "content://${BuildConfig.APPLICATION_ID}.s3.provider/s5/video",
                        "s5_episode.mkv",
                    ),
                subtitle =
                    MiningSource(
                        "content://${BuildConfig.APPLICATION_ID}.s3.provider/s5/subtitle",
                        "s5_episode.srt",
                    ),
            )
    }
}
