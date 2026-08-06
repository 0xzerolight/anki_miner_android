package com.ankiminer.android.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckCoordinatorTest {
    @Test
    fun `a check within the window does not reach the network`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(lastCheckedAtMillis = NOW - TimeUnit.HOURS.toMillis(23)),
                )
            var clientCalls = 0
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        clientCalls += 1
                        UPDATE
                    },
                    NOW,
                )

            coordinator.checkIfDue()

            assertEquals(0, clientCalls)
        }

    @Test
    fun `a check past the window reaches the network and records the time`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(lastCheckedAtMillis = NOW - TimeUnit.HOURS.toMillis(25)),
                )
            var clientCalls = 0
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        clientCalls += 1
                        UPDATE
                    },
                    NOW,
                )

            coordinator.checkIfDue()

            assertEquals(1, clientCalls)
            assertEquals(NOW, repository.value.lastCheckedAtMillis)
            assertEquals("0.5.0", coordinator.uiState.value.available?.version)
        }

    @Test
    fun `a clock that moved backwards still counts as due`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(lastCheckedAtMillis = NOW + TimeUnit.HOURS.toMillis(1)),
                )
            var clientCalls = 0
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        clientCalls += 1
                        null
                    },
                    NOW,
                )

            coordinator.checkIfDue()

            assertEquals(1, clientCalls)
        }

    @Test
    fun `a disabled check never reaches the network`() =
        runTest {
            val repository = FakeUpdateCheckRepository(UpdateCheckPreferences(enabled = false))
            var clientCalls = 0
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        clientCalls += 1
                        UPDATE
                    },
                    NOW,
                )

            coordinator.checkIfDue()

            assertEquals(0, clientCalls)
        }

    @Test
    fun `checkNow ignores the window but still respects the switch`() =
        runTest {
            var enabledCalls = 0
            val enabled =
                coordinator(
                    FakeUpdateCheckRepository(UpdateCheckPreferences(lastCheckedAtMillis = NOW)),
                    UpdateCheckClient {
                        enabledCalls += 1
                        null
                    },
                    NOW,
                )
            var disabledCalls = 0
            val disabled =
                coordinator(
                    FakeUpdateCheckRepository(UpdateCheckPreferences(enabled = false)),
                    UpdateCheckClient {
                        disabledCalls += 1
                        null
                    },
                    NOW,
                )

            enabled.checkNow()
            disabled.checkNow()

            assertEquals(1, enabledCalls)
            assertEquals(0, disabledCalls)
        }

    @Test
    fun `a skipped version is not offered again`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(
                        availableVersion = UPDATE.version,
                        availableUrl = UPDATE.releasePageUrl,
                        skippedVersion = UPDATE.version,
                    ),
                )
            val coordinator = coordinator(repository, UpdateCheckClient { null }, NOW)

            assertNull(coordinator.uiState.value.available)
        }

    @Test
    fun `a version older than the installed one is not offered`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(
                        availableVersion = "0.3.0",
                        availableUrl = "https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.3.0",
                    ),
                )
            val coordinator = coordinator(repository, UpdateCheckClient { null }, NOW)

            assertNull(coordinator.uiState.value.available)
        }

    @Test
    fun `a failed check is reported and does not consume the window`() =
        runTest {
            val previousCheck = NOW - TimeUnit.HOURS.toMillis(25)
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(lastCheckedAtMillis = previousCheck),
                )
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient { throw IOException("offline") },
                    NOW,
                )

            coordinator.checkIfDue()

            assertTrue(coordinator.uiState.value.lastCheckFailed)
            assertEquals(previousCheck, repository.value.lastCheckedAtMillis)
            assertEquals(0, repository.recordCalls)
        }

    @Test
    fun `a successful check clears an earlier failure`() =
        runTest {
            val repository = FakeUpdateCheckRepository()
            var fail = true
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        if (fail) throw IOException("offline")
                        null
                    },
                    NOW,
                )

            coordinator.checkNow()
            assertTrue(coordinator.uiState.value.lastCheckFailed)

            fail = false
            coordinator.checkNow()

            assertFalse(coordinator.uiState.value.lastCheckFailed)
            assertEquals(NOW, repository.value.lastCheckedAtMillis)
        }

    private fun TestScope.coordinator(
        repository: UpdateCheckRepository,
        client: UpdateCheckClient,
        now: Long,
    ) =
        UpdateCheckCoordinator(
            repository = repository,
            client = client,
            currentVersion = "0.4.1",
            now = { now },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private class FakeUpdateCheckRepository(
        initial: UpdateCheckPreferences = UpdateCheckPreferences(),
    ) : UpdateCheckRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: Flow<UpdateCheckPreferences> = mutableState
        val value: UpdateCheckPreferences
            get() = mutableState.value
        var recordCalls = 0
            private set

        override suspend fun setEnabled(enabled: Boolean) {
            mutableState.value = mutableState.value.copy(enabled = enabled)
        }

        override suspend fun recordCheck(
            atMillis: Long,
            found: AvailableUpdate?,
        ) {
            recordCalls += 1
            mutableState.value =
                mutableState.value.copy(
                    lastCheckedAtMillis = atMillis,
                    availableVersion = found?.version,
                    availableUrl = found?.releasePageUrl,
                )
        }

        override suspend fun skip(version: String) {
            mutableState.value = mutableState.value.copy(skippedVersion = version)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        val UPDATE =
            AvailableUpdate(
                version = "0.5.0",
                releasePageUrl =
                    "https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.5.0",
            )
    }
}
