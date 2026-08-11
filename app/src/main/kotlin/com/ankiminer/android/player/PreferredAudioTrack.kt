package com.ankiminer.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * Which audio track the curation preview plays.
 *
 * Its own file on purpose: [JAPANESE_LANGUAGE_CODES] is computed at class-init, and putting it
 * beside the cue helpers in `CurationPreviewPlayer.kt` made every JVM unit test that touches that
 * file load a facade whose initializer calls into `TextUtils` — which the mockable android.jar
 * throws on.
 */

/**
 * Engine parity: `audio_track_detector.JAPANESE_LANGUAGE_CODES`.
 *
 * Normalized, because media3's [Format] constructor runs its language through
 * [Util.normalizeLanguageCode] — a container tagged `jpn` reaches us as `ja`, so comparing the raw
 * container codes would silently never match.
 */
@OptIn(UnstableApi::class)
private val JAPANESE_LANGUAGE_CODES: Set<String> =
    setOf("jpn", "ja", "japanese", "jp").mapNotNull(Util::normalizeLanguageCode).toSet()

/**
 * The audio track the preview should play, mirroring the engine's rule: the first Japanese-tagged
 * audio stream (`find_japanese_audio_stream`), else the first audio stream (its `-map 0:a:0`
 * fallback). Returns null when the media has no audio.
 *
 * Progressive media groups carry numeric IDs in extractor/source order, but [Tracks.groups] is
 * rebuilt in renderer order. Sort those IDs before applying the engine rule; retain list order for
 * any non-progressive source whose group IDs have another shape.
 *
 * Language only, with no renderer-support filter. If the Japanese track is a codec this device
 * cannot decode, selecting it anyway surfaces [PreviewFailure.AudioTrackUnsupported], which is the
 * honest answer; quietly dropping to the English dub is the behaviour this replaces.
 */
@OptIn(UnstableApi::class)
fun preferredAudioGroup(tracks: Tracks): Tracks.Group? {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val sourceIndexes = audioGroups.map { it.mediaTrackGroup.id.toIntOrNull() }
    val sourceOrderedGroups =
        if (sourceIndexes.all { it != null }) {
            audioGroups.zip(sourceIndexes).sortedBy { (_, index) -> index }.map { it.first }
        } else {
            audioGroups
        }
    return sourceOrderedGroups.firstOrNull { group ->
        JAPANESE_LANGUAGE_CODES.contains(group.getTrackFormat(0).language ?: "")
    } ?: sourceOrderedGroups.firstOrNull()
}
