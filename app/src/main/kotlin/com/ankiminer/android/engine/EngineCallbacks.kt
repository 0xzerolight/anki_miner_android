package com.ankiminer.android.engine

/** Public Java-reflected surface called synchronously by the Python bridge. */
interface EngineCallbacks {
    /** Local cancellation admission observed by Python while its curation gate is parked. */
    fun cancellationRequested(): Boolean = false

    fun registerJob(message: String): String

    fun onStart(message: String)

    fun onProgress(message: String)

    /**
     * The engine entered a numbered pipeline stage.
     *
     * Called on every run since the engine stopped blending its stages into one
     * percentage: the stage pair is now the only whole-run position it reports.
     */
    fun onStage(message: String)

    fun onComplete(message: String)

    fun onError(message: String)

    fun onPresenterEvent(message: String)

    fun onCurationNeeded(message: String)

    /** Reading-only callback. Video runs and test fakes fail closed if it is ever invoked. */
    fun synthesizeSentenceAudio(message: String): String =
        throw UnsupportedOperationException("Sentence audio is unavailable for this run")

    fun ankiVerifyTarget(message: String): String

    fun ankiScanFirstFields(message: String): String

    fun ankiStoreMedia(message: String): String

    fun ankiCreateNotes(message: String): String

    fun ankiReleaseRunState(message: String): String
}
