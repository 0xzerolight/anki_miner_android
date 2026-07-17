package com.ankiminer.android.reading

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object ReadingRepositoryFactory {
    fun create(application: AnkiMinerApplication): ReadingMiningRepository =
        if (BuildConfig.S1A_PUBLICATION_VERIFIED) {
            application.readingRepository
        } else {
            FakeReadingMiningRepository()
        }
}
