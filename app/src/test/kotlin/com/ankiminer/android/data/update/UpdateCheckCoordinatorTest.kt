package com.ankiminer.android.data.update

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
                        UpdateCheckResult.Available(UPDATE)
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
                        UpdateCheckResult.Available(UPDATE)
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
                        UpdateCheckResult.UpToDate
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
                        UpdateCheckResult.Available(UPDATE)
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
                        UpdateCheckResult.UpToDate
                    },
                    NOW,
                )
            var disabledCalls = 0
            val disabled =
                coordinator(
                    FakeUpdateCheckRepository(UpdateCheckPreferences(enabled = false)),
                    UpdateCheckClient {
                        disabledCalls += 1
                        UpdateCheckResult.UpToDate
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
            val coordinator =
                coordinator(repository, UpdateCheckClient { UpdateCheckResult.UpToDate }, NOW)

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
            val coordinator =
                coordinator(repository, UpdateCheckClient { UpdateCheckResult.UpToDate }, NOW)

            assertNull(coordinator.uiState.value.available)
        }

    @Test
    fun `a failed observation preserves the cached update`() =
        runTest {
            val previousCheck = NOW - TimeUnit.HOURS.toMillis(1)
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(
                        lastCheckedAtMillis = previousCheck,
                        availableVersion = UPDATE.version,
                        availableUrl = UPDATE.releasePageUrl,
                    ),
                )
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient { UpdateCheckResult.Failure },
                    NOW,
                )

            coordinator.checkNow()

            assertTrue(coordinator.uiState.value.lastCheckFailed)
            assertEquals(UPDATE, coordinator.uiState.value.available)
            assertEquals(previousCheck, repository.value.lastCheckedAtMillis)
            assertEquals(0, repository.recordCalls)
        }

    @Test
    fun `a failed automatic check consumes the automatic window across process starts`() =
        runTest {
            val previousCheck = NOW - TimeUnit.HOURS.toMillis(25)
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(
                        lastCheckedAtMillis = previousCheck,
                        availableVersion = UPDATE.version,
                        availableUrl = UPDATE.releasePageUrl,
                    ),
                )
            val first =
                coordinator(
                    repository,
                    UpdateCheckClient { throw IOException("offline") },
                    NOW,
                )

            first.checkIfDue()

            assertTrue(first.uiState.value.lastCheckFailed)
            assertEquals(UPDATE, first.uiState.value.available)
            assertEquals(previousCheck, repository.value.lastCheckedAtMillis)
            assertEquals(NOW, repository.value.lastAutomaticAttemptAtMillis)
            assertEquals(1, repository.recordAttemptCalls)
            assertEquals(0, repository.recordCalls)

            var secondProcessCalls = 0
            val second =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        secondProcessCalls += 1
                        UpdateCheckResult.UpToDate
                    },
                    NOW + TimeUnit.HOURS.toMillis(1),
                )

            second.checkIfDue()

            assertEquals(0, secondProcessCalls)
            assertEquals(1, repository.recordAttemptCalls)
        }

    @Test
    fun `an overlapping manual check joins the automatic check`() =
        runTest {
            val repository =
                FakeUpdateCheckRepository(
                    UpdateCheckPreferences(lastCheckedAtMillis = NOW - CHECK_INTERVAL_MILLIS),
                )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val clientCalls = AtomicInteger()
            val coordinator =
                coordinator(
                    repository,
                    UpdateCheckClient {
                        clientCalls.incrementAndGet()
                        entered.countDown()
                        check(release.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        UpdateCheckResult.Available(UPDATE)
                    },
                    NOW,
                    dispatcher = Dispatchers.Default,
                )
            val automatic =
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.checkIfDue() }

            var manual: Deferred<Unit>? = null
            try {
                assertTrue(entered.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(coordinator.uiState.value.checking)
                manual = async(start = CoroutineStart.UNDISPATCHED) { coordinator.checkNow() }
                assertTrue(coordinator.uiState.value.checking)
            } finally {
                release.countDown()
            }

            automatic.await()
            requireNotNull(manual).await()

            assertEquals(1, clientCalls.get())
            assertEquals(1, repository.recordCalls)
            assertFalse(coordinator.uiState.value.checking)
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
                        UpdateCheckResult.UpToDate
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
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) =
        UpdateCheckCoordinator(
            repository = repository,
            client = client,
            currentVersion = "0.4.1",
            now = { now },
            dispatcher = dispatcher,
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
        var recordAttemptCalls = 0
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

        override suspend fun recordAutomaticAttempt(atMillis: Long) {
            recordAttemptCalls += 1
            mutableState.value = mutableState.value.copy(lastAutomaticAttemptAtMillis = atMillis)
        }

        override suspend fun skip(version: String) {
            mutableState.value = mutableState.value.copy(skippedVersion = version)
        }
    }

    private companion object {
        /**
         * Budget for the two latch handshakes in the overlapping-check test.
         *
         * They coordinate the test thread with a real [kotlinx.coroutines.Dispatchers.Default]
         * worker; nothing here is slow. A tight budget turns a scheduling delay on a loaded
         * machine into a failure, which is what made this test flake in the full suite while
         * passing in isolation. Sized to trip only on a genuine deadlock.
         */
        const val HANDSHAKE_TIMEOUT_SECONDS = 30L

        const val NOW = 1_800_000_000_000L
        val UPDATE =
            AvailableUpdate(
                version = "0.5.0",
                releasePageUrl =
                    "https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.5.0",
            )
    }
}
