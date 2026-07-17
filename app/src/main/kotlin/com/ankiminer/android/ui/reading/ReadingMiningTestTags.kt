package com.ankiminer.android.ui.reading

object ReadingMiningTestTags {
    const val SCREEN = "reading_mining_screen"
    const val CONTENT = "reading_mining_content"
    const val PICK_SOURCE = "reading_pick_source"
    const val PICK_ARCHIVE = "reading_pick_archive"
    const val CLEAR_SOURCE = "reading_clear_source"
    const val CLEAR_ARCHIVE = "reading_clear_archive"
    const val SERIES_NAME = "reading_series_name"
    const val START = "reading_start_mining"
    const val PROGRESS = "reading_mining_progress"
    const val SELECT_ALL = "reading_select_all_candidates"
    const val CONFIRM_CURATION = "reading_confirm_curation"
    const val CANCEL = "reading_cancel_mining"
    const val RETRY = "reading_retry_mining"
    const val RESET = "reading_reset_mining"
    const val RESULT = "reading_mining_result"

    fun candidate(candidateId: String): String = "reading_candidate:$candidateId"

    fun candidateToggle(candidateId: String): String =
        "reading_candidate_toggle:$candidateId"

    fun sentence(
        candidateId: String,
        sentenceId: String,
    ): String = "reading_sentence:$candidateId:$sentenceId"
}
