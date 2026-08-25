package com.ankiminer.android.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented for the same reason as [PreviewFailureMappingTest]: media3's [Format] constructor
 * touches `TextUtils`, which the mockable android.jar throws on. That constructor is also what
 * normalizes the language tag, so these cases only mean anything on a real runtime.
 *
 * Assertions use identity, not equality: [Tracks.Group] compares by value, so two same-language
 * groups would be interchangeable and the ordering cases would prove nothing.
 */
@RunWith(AndroidJUnit4::class)
class PreferredAudioGroupTest {
    @Test
    fun japaneseTrackWinsOverAnEarlierEnglishTrack() {
        val japanese = audioGroup("a", "jpn")
        val tracks = Tracks(listOf(videoGroup(), audioGroup("b", "eng"), japanese))

        assertSame(japanese, preferredAudioGroup(tracks))
    }

    @Test
    fun twoLetterJapaneseTagMatchesTheThreeLetterOne() {
        val japanese = audioGroup("a", "ja")
        val tracks = Tracks(listOf(audioGroup("b", "eng"), japanese))

        assertSame(japanese, preferredAudioGroup(tracks))
    }

    @Test
    fun containerTagIsNormalizedBeforeMatching() {
        // The trap this guards: media3 rewrites `jpn` to `ja` inside Format, so a selector
        // comparing raw container codes matches nothing.
        assertEquals("ja", audioGroup("a", "jpn").getTrackFormat(0).language)
    }

    @Test
    fun undeterminedTracksFallBackToTheFirstAudioTrack() {
        val first = audioGroup("a", "und")
        val tracks = Tracks(listOf(videoGroup(), first, audioGroup("b", "und")))

        assertSame(first, preferredAudioGroup(tracks))
    }

    @Test
    fun untaggedTracksFallBackToTheFirstAudioTrack() {
        val first = audioGroup("a", null)
        val tracks = Tracks(listOf(first, audioGroup("b", null)))

        assertSame(first, preferredAudioGroup(tracks))
    }

    @Test
    fun englishOnlyMediaStillGetsItsOnlyAudioTrack() {
        val only = audioGroup("a", "eng")

        assertSame(only, preferredAudioGroup(Tracks(listOf(videoGroup(), only))))
    }

    @Test
    fun mediaWithoutAudioReturnsNull() {
        assertNull(preferredAudioGroup(Tracks(listOf(videoGroup()))))
        assertNull(preferredAudioGroup(Tracks.EMPTY))
    }

    @Test
    fun sourceOrderWinsWhenJapaneseTracksMapToDifferentRenderers() {
        val sourceFirstDts = audioGroup("1", "jpn", sampleMimeType = MimeTypes.AUDIO_DTS)
        val sourceSecondAac = audioGroup("2", "jpn", sampleMimeType = MimeTypes.AUDIO_AAC)
        val rendererOrderedTracks = Tracks(listOf(sourceSecondAac, sourceFirstDts))

        assertSame(sourceFirstDts, preferredAudioGroup(rendererOrderedTracks))
    }

    @Test
    fun overrideSelectsTheAudioOrdinalInSourceOrderNotRendererOrder() {
        val sourceFirst = audioGroup("1", "eng")
        val sourceSecond = audioGroup("2", "eng")
        val rendererOrderedTracks = Tracks(listOf(sourceSecond, sourceFirst))

        assertSame(sourceSecond, preferredAudioGroup(rendererOrderedTracks, 1L))
    }

    @Test
    fun overrideZeroSelectsTheFirstSourceAudioTrack() {
        val english = audioGroup("1", "eng")
        val japanese = audioGroup("2", "jpn")
        val tracks = Tracks(listOf(english, japanese))

        assertSame(english, preferredAudioGroup(tracks, 0L))
    }

    @Test
    fun overrideSelectsAMislabeledTrackWithoutLanguageVeto() {
        val japanese = audioGroup("1", "jpn")
        val mislabeled = audioGroup("2", "und")
        val tracks = Tracks(listOf(japanese, mislabeled))

        assertSame(mislabeled, preferredAudioGroup(tracks, 1L))
    }

    @Test
    fun outOfRangeOverrideFallsBackToJapaneseAutoDetect() {
        val english = audioGroup("1", "eng")
        val japanese = audioGroup("2", "jpn")
        val tracks = Tracks(listOf(english, japanese))

        assertSame(japanese, preferredAudioGroup(tracks, 5L))
    }

    @Test
    fun outOfRangeOverrideWithoutJapaneseFallsBackToFirstAudio() {
        val first = audioGroup("1", "und")
        val second = audioGroup("2", "eng")
        val tracks = Tracks(listOf(first, second))

        assertSame(first, preferredAudioGroup(tracks, 5L))
    }

    @Test
    fun overrideIndexesListOrderWhenGroupIdsAreNotNumeric() {
        val first = audioGroup("a", "eng")
        val second = audioGroup("b", "eng")
        val tracks = Tracks(listOf(first, second))

        assertSame(second, preferredAudioGroup(tracks, 1L))
    }

    private fun audioGroup(
        id: String,
        language: String?,
        support: Int = C.FORMAT_HANDLED,
        sampleMimeType: String = MimeTypes.AUDIO_AAC,
    ): Tracks.Group =
        Tracks.Group(
            TrackGroup(
                id,
                Format.Builder()
                    .setSampleMimeType(sampleMimeType)
                    .setLanguage(language)
                    .build(),
            ),
            false,
            intArrayOf(support),
            booleanArrayOf(false),
        )

    private fun videoGroup(): Tracks.Group =
        Tracks.Group(
            TrackGroup("v", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
}
