package com.ankiminer.android.mining

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object MiningRepositoryFactory {
    fun create(application: AnkiMinerApplication): MiningRepository =
        application.miningRepository
}
