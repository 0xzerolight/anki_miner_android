package com.ankiminer.android.mining

import com.ankiminer.android.AnkiMinerApplication

internal object MiningRepositoryFactory {
    fun create(application: AnkiMinerApplication): MiningRepository = application.miningRepository
}
