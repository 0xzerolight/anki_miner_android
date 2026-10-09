package com.ankiminer.android

import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.LanguageScope
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiningLanguageFollowerTest {
    @Test
    fun `a switch made while the runtime is idle refreshes for the new language`() =
        runTest {
            val language = MutableStateFlow("ja")
            val idle = MutableStateFlow(true)
            val refreshedFor = mutableListOf<String>()
            val follower =
                launch { followMiningLanguage(language, idle) { refreshedFor += language.value } }
            runCurrent()

            language.value = "he"
            runCurrent()

            assertEquals(listOf("ja", "he"), refreshedFor)
            follower.cancel()
        }

    @Test
    fun `a switch made during a run or before startup recovery is caught up once idle`() =
        runTest {
            val language = MutableStateFlow("ja")
            val idle = MutableStateFlow(false)
            val refreshedFor = mutableListOf<String>()
            val follower =
                launch { followMiningLanguage(language, idle) { refreshedFor += language.value } }
            runCurrent()

            language.value = "he"
            runCurrent()
            assertEquals(emptyList<String>(), refreshedFor)

            idle.value = true
            runCurrent()

            assertEquals(listOf("he"), refreshedFor)
            follower.cancel()
        }

    @Test
    fun `only a change of language re-verifies the Anki target, after its resources`() =
        runTest {
            val language = MutableStateFlow("ja")
            val idle = MutableStateFlow(true)
            val events = mutableListOf<String>()
            val follower =
                launch {
                    followMiningLanguage(
                        language,
                        idle,
                        refresh = { events += "refresh:${language.value}" },
                        reverify = { events += "reverify:${language.value}" },
                    )
                }
            runCurrent()
            idle.value = false
            runCurrent()
            idle.value = true
            runCurrent()
            assertEquals(listOf("refresh:ja", "refresh:ja"), events)
            events.clear()

            idle.value = false
            language.value = "he"
            runCurrent()
            idle.value = true
            runCurrent()

            assertEquals(listOf("refresh:he", "reverify:he"), events)
            follower.cancel()
        }

    @Test
    fun `a failed first settings read is retried instead of freezing the language at the seed`() =
        runTest {
            val repository = FlakySettingsRepository(failedReads = 1, stored = AppSettings(language = "de"))
            val language =
                repository.miningLanguageUpdates()
                    .stateIn(backgroundScope, SharingStarted.Eagerly, LanguageScope.JAPANESE)
            runCurrent()
            assertEquals(LanguageScope.JAPANESE, language.value)

            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals("de", language.value)
            assertEquals(2, repository.reads)
        }

    @Test
    fun `an unreadable store is re-read with backoff, never in a tight loop`() =
        runTest {
            val repository = FlakySettingsRepository(failedReads = Int.MAX_VALUE, stored = AppSettings())
            backgroundScope.launch { repository.miningLanguageUpdates().collect {} }

            advanceTimeBy(10.minutes)

            // 1 s doubling to a one-minute cap: 15 reads in ten minutes, not thousands.
            assertTrue("reads=${repository.reads}", repository.reads in 2..20)
        }

    private class FlakySettingsRepository(
        private val failedReads: Int,
        private val stored: AppSettings,
    ) : AppSettingsRepository {
        var reads = 0
            private set

        override val settings: Flow<AppSettings> =
            flow {
                reads++
                if (reads <= failedReads) throw IOException("transient read failure")
                emit(stored)
            }

        override suspend fun update(settings: AppSettings) = error("write not expected")

        override suspend fun update(transform: (AppSettings) -> AppSettings) =
            error("write not expected")
    }
}
