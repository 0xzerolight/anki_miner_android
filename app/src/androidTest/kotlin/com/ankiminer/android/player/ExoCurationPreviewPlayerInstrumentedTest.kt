package com.ankiminer.android.player

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.RendererCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.PythonInstrumentationRuntime
import com.ankiminer.android.debug.S3TestDocumentsProvider
import com.ankiminer.android.media.SafJobFileOwner
import com.ankiminer.android.ui.video.isSeekableVideoSource
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoCurationPreviewPlayerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun stagedMediaCopyBindsSeeksAndPlays() {
        createFixture()

        SafJobFileOwner(context).use { owner ->
            val staged = owner.openVideo(SEEKABLE_URI)
            assertTrue(staged.path.endsWith(".media"))
            // The preview plays this path for the whole curation session: it must live under
            // noBackupFilesDir, where the OS cannot evict it mid-run the way it can cacheDir.
            assertEquals(
                File(context.noBackupFilesDir, "saf-inputs"),
                File(staged.path).parentFile,
            )

            assertPlayable(Uri.fromFile(File(staged.path)))
        }
    }

    @Test
    fun seekableContentUriBindsSeeksAndPlays() {
        createFixture()

        assertPlayable(SEEKABLE_URI)
    }

    @Test
    fun pipeContentUriIsRejectedBySeekabilityProbe() {
        createFixture()

        assertFalse(runBlocking { isSeekableVideoSource(context, PIPE_URI) })
    }

    @Test
    fun av1StagedCopySurfacesVideoTrackUnsupportedAndRetryKeepsItVisible() {
        createAv1Fixture()

        SafJobFileOwner(context).use { owner ->
            val staged = owner.openVideo(AV1_URI)

            assertAv1ModeTwoSignature(Uri.fromFile(File(staged.path)))
        }
    }

    @Test
    fun av1ContentUriSurfacesVideoTrackUnsupportedAndRetryKeepsItVisible() {
        createAv1Fixture()

        assertAv1ModeTwoSignature(AV1_URI)
    }

    @Test
    fun dualAudioPreviewSelectsTheJapaneseTrackNotTheDeviceLocale() {
        val fixture = createDualAudioFixture()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            }
        lateinit var player: ExoCurationPreviewPlayer

        instrumentation.runOnMainSync { player = ExoCurationPreviewPlayer(context) }
        try {
            instrumentation.runOnMainSync {
                player.media3Player.addListener(listener)
                player.bind(Uri.fromFile(fixture))
            }

            assertTrue(
                "Player did not reach STATE_READY for the dual-audio fixture",
                ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertNull("Dual-audio preview raised an error: ${playbackError.get()}", playbackError.get())

            instrumentation.runOnMainSync {
                // The override is applied from onTracksChanged, which lands before STATE_READY.
                val selected =
                    player.media3Player.currentTracks.groups
                        .filter { it.type == C.TRACK_TYPE_AUDIO }
                        .also { assertEquals("fixture should carry two audio tracks", 2, it.size) }
                        .single { it.isTrackSelected(0) }
                // Without the override the DefaultTrackSelector breaks the tie on the device
                // locale, and the CI emulator is en_US, so this reads "en".
                assertEquals("ja", selected.getTrackFormat(0).language)
            }
        } finally {
            instrumentation.runOnMainSync {
                player.media3Player.removeListener(listener)
                player.release()
            }
        }
    }

    @Test
    fun dualAudioOverrideSelectsTheEnglishOrdinalOverJapaneseAuto() {
        val fixture = createDualAudioFixture()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            }
        lateinit var player: ExoCurationPreviewPlayer

        instrumentation.runOnMainSync { player = ExoCurationPreviewPlayer(context) }
        try {
            instrumentation.runOnMainSync {
                player.media3Player.addListener(listener)
                player.bind(Uri.fromFile(fixture), audioTrackOverride = 1L)
            }

            assertTrue(
                "Player did not reach STATE_READY for the dual-audio override fixture",
                ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertNull(
                "Dual-audio override preview raised an error: ${playbackError.get()}",
                playbackError.get(),
            )

            instrumentation.runOnMainSync {
                // audio_index 1 is the English track (ffprobe ordinal). The JP-auto rule can
                // never pick "en" on this fixture, so a pass proves the Media3-group ↔ ffprobe
                // audio_index mapping holds on a real container.
                val selected =
                    player.media3Player.currentTracks.groups
                        .filter { it.type == C.TRACK_TYPE_AUDIO }
                        .also { assertEquals("fixture should carry two audio tracks", 2, it.size) }
                        .single { it.isTrackSelected(0) }
                assertEquals("en", selected.getTrackFormat(0).language)
            }
        } finally {
            instrumentation.runOnMainSync {
                player.media3Player.removeListener(listener)
                player.release()
            }
        }
    }

    @Test
    fun ffmpegRenderersAreLoadedAndHandleTenBitH264() {
        assertTrue("nextlib native library failed to load", FfmpegLibrary.isAvailable())

        // The shipped ffmpeg cannot encode Hi10P, so capability is asserted at the renderer
        // seam: the software renderer must handle a 10-bit H.264 format that MediaCodec-only
        // devices reject.
        val hi10p =
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs("avc1.6E0033")
                .setWidth(1920)
                .setHeight(1080)
                .build()
        val renderer = FfmpegVideoRenderer(ALLOWED_JOINING_TIME_MS, null, null, MAX_DROPPED_FRAMES)
        assertEquals(
            C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(hi10p)),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoCurationPreviewPlayer
        instrumentation.runOnMainSync { player = ExoCurationPreviewPlayer(context) }
        try {
            instrumentation.runOnMainSync {
                val exo = player.media3Player as androidx.media3.exoplayer.ExoPlayer
                val rendererNames = (0 until exo.rendererCount).map { exo.getRenderer(it).name }
                assertTrue(
                    "FfmpegVideoRenderer is not wired into the preview player: $rendererNames",
                    rendererNames.contains("FfmpegVideoRenderer"),
                )
                assertTrue(
                    "FfmpegAudioRenderer is not wired into the preview player: $rendererNames",
                    rendererNames.contains("FfmpegAudioRenderer"),
                )
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test
    fun playRangeStopsAtTheOutPointAndASeekCancelsAPendingStop() {
        val fixture = createFixture()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            }
        lateinit var player: ExoCurationPreviewPlayer

        instrumentation.runOnMainSync { player = ExoCurationPreviewPlayer(context) }
        try {
            instrumentation.runOnMainSync {
                player.media3Player.addListener(listener)
                player.bind(Uri.fromFile(fixture))
            }
            assertTrue(
                "Player did not reach STATE_READY for the range-playback fixture",
                ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            // Play a short window in the middle of the 1.5s fixture and let it stop itself.
            instrumentation.runOnMainSync { player.playRange(RANGE_START_SECONDS, RANGE_END_SECONDS) }
            assertTrue(
                "Ranged playback never started",
                awaitIsPlaying(player, expected = true, timeoutSeconds = PLAYING_TIMEOUT_SECONDS),
            )
            assertTrue(
                "Ranged playback did not stop itself at the out point",
                awaitIsPlaying(player, expected = false, timeoutSeconds = PLAYING_TIMEOUT_SECONDS),
            )
            assertNull(
                "Ranged playback raised an error: ${playbackError.get()}",
                playbackError.get(),
            )

            val outPointMillis = (RANGE_END_SECONDS * MILLIS_PER_SECOND).toLong()
            val stoppedAtMillis = mainThreadPositionMillis(instrumentation, player)
            assertTrue(
                "Playback stopped short of its out point: ${stoppedAtMillis}ms < ${outPointMillis}ms",
                stoppedAtMillis >= outPointMillis - RANGE_POSITION_TOLERANCE_MILLIS,
            )
            assertTrue(
                "Playback ran past its out point instead of stopping: ${stoppedAtMillis}ms",
                stoppedAtMillis < FIXTURE_DURATION_MILLIS,
            )

            // A second range re-arms a stop at the same out point. Seeking elsewhere while that
            // range is still mid-flight must cancel it, so it never fires once playback resumes
            // and later crosses the same absolute position again.
            instrumentation.runOnMainSync { player.playRange(RANGE_START_SECONDS, RANGE_END_SECONDS) }
            assertTrue(
                "Second ranged playback never started",
                awaitIsPlaying(player, expected = true, timeoutSeconds = PLAYING_TIMEOUT_SECONDS),
            )
            instrumentation.runOnMainSync { player.seekTo(SEEK_ELSEWHERE_SECONDS) }
            // Resume through the raw media3 Player, not the wrapper: every wrapper method
            // that can resume playback (seekAndPlay, togglePlayPause, ...) cancels a pending
            // range stop itself, which would mask a seekTo that failed to cancel one. Going
            // around the wrapper isolates seekTo's own cancellation as the only thing tested.
            instrumentation.runOnMainSync { player.media3Player.play() }

            assertTrue(
                "Playback did not resume after the cancelling seek",
                awaitIsPlaying(player, expected = true, timeoutSeconds = PLAYING_TIMEOUT_SECONDS),
            )
            assertTrue(
                "Playback never stopped after resuming past the stale out point",
                awaitIsPlaying(player, expected = false, timeoutSeconds = FULL_PLAYBACK_TIMEOUT_SECONDS),
            )
            assertNull(
                "Playback failed after the cancelling seek: ${playbackError.get()}",
                playbackError.get(),
            )

            val finalPositionMillis = mainThreadPositionMillis(instrumentation, player)
            assertTrue(
                "Stale range-stop message fired at the old out point after a cancelling seek: " +
                    "${finalPositionMillis}ms",
                finalPositionMillis >= outPointMillis + RANGE_POSITION_TOLERANCE_MILLIS,
            )
        } finally {
            instrumentation.runOnMainSync {
                player.media3Player.removeListener(listener)
                player.release()
            }
        }
    }

    private fun awaitIsPlaying(
        player: ExoCurationPreviewPlayer,
        expected: Boolean,
        timeoutSeconds: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (player.isPlaying.value == expected) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return player.isPlaying.value == expected
    }

    private fun mainThreadPositionMillis(
        instrumentation: Instrumentation,
        player: ExoCurationPreviewPlayer,
    ): Long {
        val position = AtomicLong()
        instrumentation.runOnMainSync { position.set(player.media3Player.currentPosition) }
        return position.get()
    }

    private fun assertAv1ModeTwoSignature(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val retriedReady = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (ready.count > 0) {
                            ready.countDown()
                        } else {
                            retriedReady.countDown()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            }
        lateinit var player: ExoCurationPreviewPlayer

        instrumentation.runOnMainSync { player = ExoCurationPreviewPlayer(context) }
        try {
            instrumentation.runOnMainSync {
                player.media3Player.addListener(listener)
                player.bind(uri)
            }

            val becameReady = ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull("AV1 preview raised an error: ${playbackError.get()}", playbackError.get())
            assertTrue("Player did not reach STATE_READY for $uri", becameReady)

            instrumentation.runOnMainSync {
                val tracks = player.media3Player.currentTracks
                assertFalse(
                    "AV1 video track is unexpectedly supported on this image",
                    tracks.isTypeSupportedOrEmpty(C.TRACK_TYPE_VIDEO),
                )
                assertTrue(
                    "Audio track was not selected beside the unsupported video",
                    tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
                )
            }
            val failure = player.failure.value
            assertTrue(
                "Expected VideoTrackUnsupported, got $failure",
                failure is PreviewFailure.VideoTrackUnsupported,
            )

            instrumentation.runOnMainSync {
                player.retry()
                assertEquals(failure, player.failure.value)
            }
            assertTrue(
                "Retry did not re-prepare unsupported-track playback for $uri",
                retriedReady.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertEquals(failure, player.failure.value)

            instrumentation.runOnMainSync { player.seekAndPlay(0.0) }
            assertTrue(
                "Audio-only playback did not start for $uri",
                playing.await(PLAYING_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertNull("Playback failed after start: ${playbackError.get()}", playbackError.get())
        } finally {
            instrumentation.runOnMainSync {
                player.media3Player.removeListener(listener)
                player.release()
            }
        }
    }

    private fun assertPlayable(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            }
        lateinit var player: ExoCurationPreviewPlayer

        instrumentation.runOnMainSync {
            player = ExoCurationPreviewPlayer(context)
        }
        try {
            instrumentation.runOnMainSync {
                player.media3Player.addListener(listener)
                player.bind(uri)
            }

            val becameReady = ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull("Playback failed before ready: ${playbackError.get()}", playbackError.get())
            assertTrue("Player did not reach STATE_READY for $uri", becameReady)

            instrumentation.runOnMainSync {
                player.seekTo(SEEK_SECONDS)
                player.tick()
            }
            assertEquals(
                SEEK_SECONDS,
                player.positionSeconds.value,
                POSITION_TOLERANCE_SECONDS,
            )

            instrumentation.runOnMainSync { player.seekAndPlay(SEEK_SECONDS) }
            val startedPlaying = playing.await(PLAYING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull("Playback failed after seek: ${playbackError.get()}", playbackError.get())
            assertTrue("Player did not start playback for $uri", startedPlaying)
            instrumentation.runOnMainSync { assertTrue(player.isPlaying.value) }
        } finally {
            instrumentation.runOnMainSync {
                player.media3Player.removeListener(listener)
                player.release()
            }
        }
    }

    private fun createFixture(): File {
        val fixture = File(context.cacheDir, S3TestDocumentsProvider.FIXTURE_NAME)
        fixture.delete()
        pythonModule().callAttr("create_fixture", nativeTool("libffmpeg.so").path, fixture.path)
        assertTrue(fixture.isFile && fixture.length() > 0L)
        return fixture
    }

    private fun createDualAudioFixture(): File {
        val fixture = File(context.cacheDir, DUAL_AUDIO_FIXTURE_NAME)
        fixture.delete()
        pythonModule().callAttr(
            "create_dual_audio_fixture",
            nativeTool("libffmpeg.so").path,
            fixture.path,
        )
        assertTrue(fixture.isFile && fixture.length() > 0L)
        return fixture
    }

    private fun createAv1Fixture(): File {
        val fixture = File(context.cacheDir, S3TestDocumentsProvider.AV1_FIXTURE_NAME)
        fixture.delete()
        pythonModule().callAttr("create_av1_fixture", nativeTool("libffmpeg.so").path, fixture.path)
        assertTrue(fixture.isFile && fixture.length() > 0L)
        return fixture
    }

    private fun pythonModule() =
        synchronized(PythonInstrumentationRuntime::class.java) {
            PythonInstrumentationRuntime.awaitReady().getModule("s3_media_probe")
        }

    private fun nativeTool(name: String): File {
        val tool = File(context.applicationInfo.nativeLibraryDir, name)
        assertTrue("Native player test tool is missing: $tool", tool.isFile)
        assertTrue("Native player test tool is not executable: $tool", tool.canExecute())
        return tool
    }

    private companion object {
        val SEEKABLE_URI: Uri =
            Uri.parse("content://${BuildConfig.APPLICATION_ID}.s3.provider/seekable")
        val PIPE_URI: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.s3.provider/pipe")
        val AV1_URI: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.s3.provider/av1")
        const val DUAL_AUDIO_FIXTURE_NAME = "preview-dual-audio-fixture.mkv"
        const val SEEK_SECONDS = 1.0
        const val POSITION_TOLERANCE_SECONDS = 0.15
        const val READY_TIMEOUT_SECONDS = 20L
        const val PLAYING_TIMEOUT_SECONDS = 10L
        const val ALLOWED_JOINING_TIME_MS = 0L
        const val MAX_DROPPED_FRAMES = 50

        // create_fixture() renders a 1.5s clip; the range window sits in the middle so there is
        // slack both before its start and after its out point to the fixture's actual end.
        const val FIXTURE_DURATION_MILLIS = 1500L
        const val RANGE_START_SECONDS = 0.1
        const val RANGE_END_SECONDS = 0.6
        const val SEEK_ELSEWHERE_SECONDS = 0.05
        const val RANGE_POSITION_TOLERANCE_MILLIS = 200L
        const val MILLIS_PER_SECOND = 1_000.0
        const val POLL_INTERVAL_MILLIS = 20L
        const val FULL_PLAYBACK_TIMEOUT_SECONDS = 15L
    }
}
