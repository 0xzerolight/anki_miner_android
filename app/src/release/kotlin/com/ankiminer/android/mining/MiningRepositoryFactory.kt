package com.ankiminer.android.mining

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object MiningRepositoryFactory {
    fun create(application: AnkiMinerApplication): MiningRepository {
        check(BuildConfig.S1A_PUBLICATION_VERIFIED) {
            "Release mining requires the exact verified S1a wheel publication"
        }
        check(BuildConfig.S1A_ARM64_ACCEPTED) {
            "Release mining requires a verified physical ARM64 acceptance receipt"
        }
        check(BuildConfig.S1A_PUBLICATION_BUILD_KEY.matches(Regex("[0-9a-f]{64}"))) {
            "Release mining lacks an immutable S1a publication identity"
        }
        return application.miningRepository
    }
}
