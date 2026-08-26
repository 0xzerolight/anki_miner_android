package com.ankiminer.android.reading

import com.ankiminer.android.AnkiMinerApplication

internal object ReadingRepositoryFactory {
    fun create(application: AnkiMinerApplication): ReadingMiningRepository =
        application.readingRepository
}
