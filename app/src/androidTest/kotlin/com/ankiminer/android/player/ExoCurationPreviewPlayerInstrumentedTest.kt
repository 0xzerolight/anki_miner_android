package com.ankiminer.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.PythonInstrumentationRuntime
import com.ankiminer.android.debug.S3TestDocumentsProvider
import com.ankiminer.android.media.SafJobFileOwner
import com.ankiminer.android.ui.video.isSeekableVideoSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExoCurationPreviewPlayerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun stagedMediaCopyBindsSeeksAndPlays() {
        createFixture()

        SafJobFileOwner(context).use { owner ->
            val staged = owner.openVideo(SEEKABLE_URI)
            assertTrue(staged.path.endsWith(".media"))

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
        const val SEEK_SECONDS = 1.0
        const val POSITION_TOLERANCE_SECONDS = 0.15
        const val READY_TIMEOUT_SECONDS = 20L
        const val PLAYING_TIMEOUT_SECONDS = 10L
    }
}
