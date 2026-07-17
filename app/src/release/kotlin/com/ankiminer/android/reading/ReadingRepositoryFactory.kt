package com.ankiminer.android.reading

import com.ankiminer.android.AnkiMinerApplication
import com.ankiminer.android.BuildConfig

internal object ReadingRepositoryFactory {
    fun create(application: AnkiMinerApplication): ReadingMiningRepository {
        check(BuildConfig.S1A_PUBLICATION_VERIFIED) {
            "Release reading mining requires the exact verified S1a wheel publication"
        }
        check(BuildConfig.S1A_ARM64_ACCEPTED) {
            "Release reading mining requires a verified physical ARM64 acceptance receipt"
        }
        check(BuildConfig.S1A_PUBLICATION_BUILD_KEY.matches(Regex("[0-9a-f]{64}"))) {
            "Release reading mining lacks an immutable S1a publication identity"
        }
        return application.readingRepository
    }
}
