package com.ankiminer.android.mining

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object MiningRepositoryFactory {
    fun create(application: AnkiMinerApplication): MiningRepository =
        if (BuildConfig.S1A_PUBLICATION_VERIFIED) {
            application.miningRepository
        } else {
            FakeMiningRepository()
        }

    fun createAudio(application: AnkiMinerApplication): MiningRepository =
        if (BuildConfig.S1A_PUBLICATION_VERIFIED) {
            application.audioRepository
        } else {
            FakeMiningRepository()
        }
}
