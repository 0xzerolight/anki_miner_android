package com.ankiminer.android.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented rather than JVM: media3's [Format] and [PlaybackException] constructors touch
 * `TextUtils`/`SystemClock`, which the mockable android.jar throws on.
 */
@RunWith(AndroidJUnit4::class)
class PreviewFailureMappingTest {
    @Test
    fun supportedVideoAndAudioMapsToNoFailure() {
        val tracks =
            Tracks(
                listOf(
                    group(videoFormat(), C.FORMAT_HANDLED, selected = true),
                    group(audioFormat(), C.FORMAT_HANDLED, selected = true),
                ),
            )

        assertNull(previewFailureFor(tracks))
    }

    @Test
    fun emptyTracksMapsToNoFailure() {
        assertNull(previewFailureFor(Tracks.EMPTY))
    }

    @Test
    fun unsupportedVideoTrackMapsToVideoTrackUnsupportedWithCodecLabel() {
        val tracks =
            Tracks(
                listOf(
                    group(videoFormat(), C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
                    group(audioFormat(), C.FORMAT_HANDLED, selected = true),
                ),
            )

        assertEquals(
            PreviewFailure.VideoTrackUnsupported("av01.0.05M.08"),
            previewFailureFor(tracks),
        )
    }

    @Test
    fun supportedDubDoesNotMaskUnsupportedPreferredJapaneseTrack() {
        val supportedEnglish =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("eng")
                .build()
        val unsupportedJapanese =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_DTS)
                .setCodecs("dts")
                .setLanguage("jpn")
                .build()
        val tracks =
            Tracks(
                listOf(
                    group(supportedEnglish, C.FORMAT_HANDLED, selected = true),
                    group(unsupportedJapanese, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
                ),
            )

        assertEquals(
            PreviewFailure.AudioTrackUnsupported("dts"),
            previewFailureFor(tracks),
        )
    }

    @Test
    fun overriddenAudioTrackDecidesTheUnsupportedAudioFailure() {
        val supported =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("eng")
                .build()
        val unsupported =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_DTS)
                .setCodecs("dts")
                .setLanguage("eng")
                .build()
        val tracks =
            Tracks(
                listOf(
                    group(supported, C.FORMAT_HANDLED, selected = true),
                    group(unsupported, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
                ),
            )

        assertNull(previewFailureFor(tracks))
        assertEquals(
            PreviewFailure.AudioTrackUnsupported("dts"),
            previewFailureFor(tracks, 1L),
        )
    }

    @Test
    fun unsupportedVideoWinsOverUnsupportedAudio() {
        val tracks =
            Tracks(
                listOf(
                    group(videoFormat(), C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
                    group(audioFormat(), C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false),
                ),
            )

        assertEquals(
            PreviewFailure.VideoTrackUnsupported("av01.0.05M.08"),
            previewFailureFor(tracks),
        )
    }

    @Test
    fun codecLabelFallsBackToMimeTypeWhenCodecsMissing() {
        val format =
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .build()
        val tracks =
            Tracks(listOf(group(format, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)))

        assertEquals(
            PreviewFailure.VideoTrackUnsupported(MimeTypes.VIDEO_AV1),
            previewFailureFor(tracks),
        )
    }

    @Test
    fun playbackExceptionMapsToPlaybackFailedWithErrorCodeName() {
        val error =
            PlaybackException(
                "decoder init failed",
                null,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            )

        assertEquals(
            PreviewFailure.PlaybackFailed("ERROR_CODE_DECODER_INIT_FAILED"),
            previewFailureFor(error),
        )
    }

    private fun group(
        format: Format,
        support: Int,
        selected: Boolean,
    ): Tracks.Group =
        Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(support),
            booleanArrayOf(selected),
        )

    private fun videoFormat(): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.05M.08")
            .build()

    private fun audioFormat(): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setCodecs("dts")
            .build()
}
