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

    fun ankiVerifyTarget(message: String): String

    fun ankiScanFirstFields(message: String): String

    fun ankiStoreMedia(message: String): String

    fun ankiCreateNotes(message: String): String

    fun ankiReleaseRunState(message: String): String
}
