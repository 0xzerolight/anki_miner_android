package com.ankiminer.android.ui.mining

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ankiminer.android.R

@Immutable
data class MediaMiningLabels(
    @param:StringRes val setupTitle: Int,
    @param:StringRes val fileLabel: Int,
    @param:StringRes val fileError: Int,
    @param:StringRes val transcriptLabel: Int,
    @param:StringRes val subtitleOffsetLabel: Int,
    /** Shown while nothing is chosen yet: what the two pickers below want. */
    @param:StringRes val setupHint: Int,
    /** Video files carry several audio streams; an audio file is the one stream. */
    val showsAudioTracks: Boolean,
) {
    companion object {
        val VIDEO =
            MediaMiningLabels(
                R.string.video_phase_setup_title,
                R.string.video_file_label,
                R.string.video_file_error,
                R.string.subtitle_file_label,
                R.string.video_subtitle_offset_label,
                R.string.video_setup_hint,
                showsAudioTracks = true,
            )
        val AUDIO =
            MediaMiningLabels(
                R.string.audio_phase_setup_title,
                R.string.audio_file_label,
                R.string.audio_file_error,
                R.string.subtitle_file_label,
                R.string.video_subtitle_offset_label,
                R.string.audio_setup_hint,
                showsAudioTracks = false,
            )
    }
}
