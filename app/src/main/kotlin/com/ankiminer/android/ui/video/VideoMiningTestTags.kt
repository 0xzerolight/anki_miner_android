package com.ankiminer.android.ui.video

object VideoMiningTestTags {
    const val SCREEN = "video_mining_screen"
    const val CONTENT = "video_mining_content"
    const val PICK_VIDEO = "pick_video"
    const val PICK_SUBTITLE = "pick_subtitle"
    const val CLEAR_VIDEO = "clear_video"
    const val CLEAR_SUBTITLE = "clear_subtitle"
    const val SUBTITLE_OFFSET_FIELD = "subtitle_offset_field"
    const val TEST_TIMING = "test_timing"
    const val START = "start_mining"
    const val PROGRESS = "mining_progress"
    const val SELECT_ALL = "select_all_candidates"
    const val DEFINITION = "curation_definition"
    const val CUES_UNAVAILABLE = "curation_cues_unavailable"
    const val CONFIRM_CURATION = "confirm_curation"
    const val CANCEL = "cancel_mining"
    const val RETRY = "retry_mining"
    const val RESET = "reset_mining"
    const val RESULT = "mining_result"
    const val UNDO = "undo_mining_run"
    const val UNDO_CONFIRM = "undo_mining_run_confirm"
    const val TIMING_PREVIEW = "timing_preview"
    const val TIMING_PREVIEW_CONTENT = "timing_preview_content"
    const val TIMING_PREVIEW_TITLE = "timing_preview_title"
    const val TIMING_PREVIEW_UNAVAILABLE = "timing_preview_unavailable"
    const val TIMING_PREVIEW_READOUT = "timing_preview_readout"
    const val TIMING_PREVIEW_NUDGE_EARLIER = "timing_preview_nudge_earlier"
    const val TIMING_PREVIEW_NUDGE_LATER = "timing_preview_nudge_later"
    const val TIMING_PREVIEW_TOGGLE = "timing_preview_toggle"
    const val TIMING_PREVIEW_OFFSET_FIELD = "timing_preview_offset_field"
    const val TIMING_PREVIEW_APPLY = "timing_preview_apply"
    const val TIMING_PREVIEW_CANCEL = "timing_preview_cancel"
    const val AUDIO_TRACKS = "audio_tracks"
    const val AUDIO_TRACK_PICKER = "audio_track_picker"
    const val AUDIO_TRACK_PICKER_APPLY = "audio_track_picker_apply"
    const val AUDIO_TRACK_PICKER_CANCEL = "audio_track_picker_cancel"
    const val AUDIO_TRACK_PICKER_CLOSE = "audio_track_picker_close"

    fun candidate(candidateId: String): String = "candidate:$candidateId"

    fun timingPreviewCue(index: Int): String = "timing_preview_cue:$index"

    fun candidateToggle(candidateId: String): String = "candidate_toggle:$candidateId"

    fun candidateKnown(candidateId: String): String = "candidate_known:$candidateId"

    fun candidateCopyWord(candidateId: String): String = "candidate_copy_word:$candidateId"

    fun candidateCopySentence(candidateId: String): String =
        "candidate_copy_sentence:$candidateId"

    fun candidateExpandPrev(candidateId: String): String = "candidate_expand_prev:$candidateId"

    fun candidateExpandNext(candidateId: String): String = "candidate_expand_next:$candidateId"

    fun candidateExpandReset(candidateId: String): String = "candidate_expand_reset:$candidateId"

    fun expansionPreview(candidateId: String): String = "expansion_preview:$candidateId"

    fun sentence(
        candidateId: String,
        sentenceId: String,
    ): String = "sentence:$candidateId:$sentenceId"

    fun audioTrackRow(audioIndex: Long?): String = "audio_track_row:${audioIndex ?: "auto"}"

    fun chosenSentence(candidateId: String): String = "chosen_sentence:$candidateId"

    fun alternativesToggle(candidateId: String): String =
        "sentence_alternatives_toggle:$candidateId"
}
