package com.ankiminer.android.mining

internal object MiningRepositoryFactory {
    fun create(): MiningRepository = FakeMiningRepository()
}
