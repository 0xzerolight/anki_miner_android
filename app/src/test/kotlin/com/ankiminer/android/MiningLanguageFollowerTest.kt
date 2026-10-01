package com.ankiminer.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
