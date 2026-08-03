package com.ankiminer.android.ui.video

object VideoMiningTestTags {
    const val SCREEN = "video_mining_screen"
    const val CONTENT = "video_mining_content"
    const val PICK_VIDEO = "pick_video"
    const val PICK_SUBTITLE = "pick_subtitle"
    const val CLEAR_VIDEO = "clear_video"
    const val CLEAR_SUBTITLE = "clear_subtitle"
    const val START = "start_mining"
    const val PROGRESS = "mining_progress"
    const val SELECT_ALL = "select_all_candidates"
    const val DEFINITION = "curation_definition"
    const val CONFIRM_CURATION = "confirm_curation"
    const val CANCEL = "cancel_mining"
    const val RETRY = "retry_mining"
    const val RESET = "reset_mining"
    const val RESULT = "mining_result"

    fun candidate(candidateId: String): String = "candidate:$candidateId"

    fun candidateToggle(candidateId: String): String = "candidate_toggle:$candidateId"

    fun sentence(
        candidateId: String,
        sentenceId: String,
    ): String = "sentence:$candidateId:$sentenceId"
}
