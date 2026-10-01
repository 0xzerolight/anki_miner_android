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
 * Engine parity: `audio_track_detector.JAPANESE_LANGUAGE_CODES`, the ja profile's
 * `audio_track_codes`. Every other language passes its own profile's codes.
 */
val JAPANESE_AUDIO_TRACK_CODES: List<String> = listOf("jpn", "ja", "japanese", "jp")

/**
 * Normalized, because media3's [Format] constructor runs its language through
 * [Util.normalizeLanguageCode] — a container tagged `jpn` reaches us as `ja`, so comparing the raw
 * container codes would silently never match.
 */
@OptIn(UnstableApi::class)
private fun normalizedLanguageCodes(codes: Collection<String>): Set<String> =
    codes.mapNotNull(Util::normalizeLanguageCode).toSet()

/**
 * The audio track the preview should play, mirroring the engine's rule: the first audio stream
 * tagged in the mining language (`find_japanese_audio_stream`, or the profile's
 * `audio_track_codes` for another language), else the first audio stream (its `-map 0:a:0`
 * fallback). Returns null when the media has no audio.
 *
 * Progressive media groups carry numeric IDs in extractor/source order, but [Tracks.groups] is
 * rebuilt in renderer order. Sort those IDs before applying the engine rule; retain list order for
 * any non-progressive source whose group IDs have another shape.
 *
 * [audioTrackOverride] is the engine's `audio_index` for this run: a source-order ordinal that
 * outranks every other rule, `0` included — it is a real ordinal, not a falsy absence, hence the
 * explicit `null` check rather than an "if present" one. An invalid or out-of-range value (no
 * override, or an index the source doesn't have) falls back to the same rule
 * `_resolve_audio_track_global_index` uses: first Japanese-tagged, else first audio. Deliberately
 * no language veto on the overridden track — a mislabeled file, where the desired track isn't
 * tagged Japanese at all, is the reason the override exists.
 *
 * Language only, with no renderer-support filter. If the selected track is a codec this device
 * cannot decode, selecting it anyway surfaces [PreviewFailure.AudioTrackUnsupported], which is the
 * honest answer; quietly dropping to another track is the behaviour this replaces.
 */
@OptIn(UnstableApi::class)
fun preferredAudioGroup(
    tracks: Tracks,
    audioTrackOverride: Long? = null,
    languageCodes: Collection<String> = JAPANESE_AUDIO_TRACK_CODES,
): Tracks.Group? {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val sourceIndexes = audioGroups.map { it.mediaTrackGroup.id.toIntOrNull() }
    val sourceOrderedGroups =
        if (sourceIndexes.all { it != null }) {
            audioGroups.zip(sourceIndexes).sortedBy { (_, index) -> index }.map { it.first }
        } else {
            audioGroups
        }
    if (audioTrackOverride != null && audioTrackOverride in 0 until sourceOrderedGroups.size.toLong()) {
        return sourceOrderedGroups[audioTrackOverride.toInt()]
    }
    val preferred = normalizedLanguageCodes(languageCodes)
    return sourceOrderedGroups.firstOrNull { group ->
        preferred.contains(group.getTrackFormat(0).language ?: "")
    } ?: sourceOrderedGroups.firstOrNull()
}
