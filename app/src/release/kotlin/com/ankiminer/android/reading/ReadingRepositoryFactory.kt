package com.ankiminer.android.reading

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object ReadingRepositoryFactory {
    fun create(application: AnkiMinerApplication): ReadingMiningRepository =
        application.readingRepository
}
