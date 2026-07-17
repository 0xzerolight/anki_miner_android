package com.ankiminer.android.engine

/** Public Java-reflected surface called synchronously by the Python bridge. */
interface EngineCallbacks {
    fun registerJob(message: String): String

    fun onStart(message: String)

    fun onProgress(message: String)

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
