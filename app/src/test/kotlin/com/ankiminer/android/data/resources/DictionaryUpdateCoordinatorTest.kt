package com.ankiminer.android.data.resources

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.update.DictionaryUpdateStamp
import com.ankiminer.android.vm.SessionResourceManager
import com.ankiminer.android.vm.SessionSettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryUpdateCoordinatorTest {
    @Test
    fun `the automatic run asks for the active chains in order and installs what it finds`() =
        runTest {
            val fixture = Fixture(this)
            fixture.resources.checkAnswer = { found(JITENDEX) }

            fixture.coordinator.runAutomaticIfDue()

            val request = fixture.resources.checks.single()
            assertEquals("ja", request.language)
            // Chain order, the disabled entry included; an uninstalled chain entry is not asked.
            assertEquals(listOf("jmdict", "jitendex"), request.dictionaryIds)
            assertEquals(listOf("jpdb"), request.frequencyIds)
            assertEquals(emptyList<String>(), request.pitchIds)
            assertEquals(listOf(JITENDEX to "ja"), fixture.resources.installs)
            assertEquals(NOW, fixture.stamp.checkedAt)
            assertEquals(
                DictionaryUpdateResult.Finished(updated = listOf(JITENDEX.title), failed = emptyList()),
                fixture.coordinator.state.value.result,
            )
            // Updates install in place: the chain order and on/off state are never rewritten.
            assertEquals(0, fixture.settings.writeAttempts)
        }

    @Test
    fun `the automatic run waits a week, and a stamp in the future counts as due`() =
        runTest {
            listOf(
                NOW - TimeUnit.DAYS.toMillis(6) to 0,
                NOW - TimeUnit.DAYS.toMillis(7) to 1,
                NOW + TimeUnit.HOURS.toMillis(1) to 1,
                null to 1,
            ).forEach { (checkedAt, expectedChecks) ->
                val fixture = Fixture(this, stampedAt = checkedAt)

                fixture.coordinator.runAutomaticIfDue()

                assertEquals("stamp $checkedAt", expectedChecks, fixture.resources.checks.size)
            }
        }

    @Test
    fun `the automatic run needs the switch on, startup ready and an idle runtime`() =
        runTest {
            val off = Fixture(this, settings = CHAINS.copy(autoUpdateDictionaries = false))
            off.coordinator.runAutomaticIfDue()
            assertEquals(0, off.resources.checks.size)

            val recovering = Fixture(this, readiness = ResourceStartupReadiness.RECOVERING)
            recovering.coordinator.runAutomaticIfDue()
            assertEquals(0, recovering.resources.checks.size)
            // Nothing started, so the process latch is still open for a later trigger.
            recovering.resources.mutableState.value =
                recovering.resources.mutableState.value.copy(startupReadiness = ResourceStartupReadiness.READY)
            recovering.coordinator.runAutomaticIfDue()
            assertEquals(1, recovering.resources.checks.size)

            val mining = Fixture(this, runtimeKind = RuntimeWorkCoordinator.Kind.MINING)
            mining.coordinator.runAutomaticIfDue()
            assertEquals(0, mining.resources.checks.size)
        }

    @Test
    fun `a check that reached a publisher is stamped, an unreachable one is not`() =
        runTest {
            listOf(
                ResourceUpdateCheck(checked = 2, reached = true, failedCount = 1, updates = emptyList()) to NOW,
                ResourceUpdateCheck(checked = 0, reached = true, failedCount = 0, updates = emptyList()) to NOW,
                ResourceUpdateCheck(checked = 2, reached = false, failedCount = 2, updates = emptyList()) to null,
            ).forEach { (answer, expectedStamp) ->
                val fixture = Fixture(this)
                fixture.resources.checkAnswer = { answer }

                fixture.coordinator.runAutomaticIfDue()

                assertEquals(answer.toString(), expectedStamp, fixture.stamp.checkedAt)
                // Nobody asked, so nothing but installed names is ever reported.
                assertNull(fixture.coordinator.state.value.result)
            }
        }

    @Test
    fun `Update Now reports every check outcome`() =
        runTest {
            listOf(
                ResourceUpdateCheck(0, true, 0, emptyList()) to DictionaryUpdateResult.NothingPublishes,
                ResourceUpdateCheck(3, true, 0, emptyList()) to DictionaryUpdateResult.UpToDate(uncheckedCount = 0),
                ResourceUpdateCheck(3, true, 2, emptyList()) to DictionaryUpdateResult.UpToDate(uncheckedCount = 2),
                ResourceUpdateCheck(3, false, 3, emptyList()) to DictionaryUpdateResult.CouldNotCheck,
            ).forEach { (answer, expected) ->
                val fixture = Fixture(this)
                fixture.resources.checkAnswer = { answer }

                fixture.coordinator.runNow()

                assertEquals(answer.toString(), expected, fixture.coordinator.state.value.result)
            }
        }

    @Test
    fun `a check the bridge refuses is reported, and a busy runtime says busy`() =
        runTest {
            val failed = Fixture(this)
            failed.resources.checkAnswer = { throw ResourceBridgeException("unsupported_language", "no") }
            failed.coordinator.runNow()
            assertEquals(DictionaryUpdateResult.CouldNotCheck, failed.coordinator.state.value.result)
            assertNull(failed.stamp.checkedAt)

            val busy = Fixture(this, runtimeKind = RuntimeWorkCoordinator.Kind.MINING)
            busy.coordinator.runNow()
            assertEquals(DictionaryUpdateResult.Busy, busy.coordinator.state.value.result)
            assertEquals(0, busy.resources.checks.size)
        }

    @Test
    fun `automatic installs wait for an unmetered network and two resumes check once`() =
        runTest {
            val fixture = Fixture(this, unmetered = false)
            fixture.resources.checkAnswer = { found(JITENDEX) }

            fixture.coordinator.onUiVisible()
            runCurrent()
            fixture.coordinator.onUiVisible()
            runCurrent()

            assertEquals(1, fixture.resources.checks.size)
            assertEquals(emptyList<Pair<ResourceUpdate, String>>(), fixture.resources.installs)
            // Deferred, so the week is not over: a later due run installs it.
            assertNull(fixture.stamp.checkedAt)
            assertNull(fixture.coordinator.state.value.result)
        }

    @Test
    fun `Update Now after the latch runs again and ignores the unmetered rule`() =
        runTest {
            val fixture = Fixture(this, unmetered = false)
            fixture.resources.checkAnswer = { found(JITENDEX) }
            fixture.coordinator.runAutomaticIfDue()
            fixture.coordinator.runAutomaticIfDue()

            fixture.coordinator.runNow()

            assertEquals(2, fixture.resources.checks.size)
            assertEquals(listOf(JITENDEX to "ja"), fixture.resources.installs)
            assertEquals(NOW, fixture.stamp.checkedAt)
        }

    @Test
    fun `a busy or refused install defers the run without a stamp`() =
        runTest {
            listOf("resource_busy", "resource_foreground_refused", "resource_not_ready").forEach { code ->
                val automatic = Fixture(this)
                automatic.resources.checkAnswer = { found(JITENDEX, JMDICT) }
                automatic.resources.installAnswer = { code }
                automatic.coordinator.runAutomaticIfDue()
                assertNull(code, automatic.stamp.checkedAt)
                // The first deferral ends the run: the rest would meet the same refusal.
                assertEquals(code, 1, automatic.resources.installs.size)
                assertNull(code, automatic.coordinator.state.value.result)

                val manual = Fixture(this)
                manual.resources.checkAnswer = { found(JITENDEX) }
                manual.resources.installAnswer = { code }
                manual.coordinator.runNow()
                assertEquals(code, DictionaryUpdateResult.Busy, manual.coordinator.state.value.result)
                assertNull(code, manual.stamp.checkedAt)
            }
        }

    @Test
    fun `an install that fails on its own still completes the run`() =
        runTest {
            val manual = Fixture(this)
            manual.resources.checkAnswer = { found(JITENDEX, JMDICT) }
            manual.resources.installAnswer = { update -> if (update == JITENDEX) "dictionary_import_failed" else null }

            manual.coordinator.runNow()

            assertEquals(listOf(JITENDEX to "ja", JMDICT to "ja"), manual.resources.installs)
            assertEquals(NOW, manual.stamp.checkedAt)
            assertEquals(
                DictionaryUpdateResult.Finished(updated = listOf(JMDICT.title), failed = listOf(JITENDEX.title)),
                manual.coordinator.state.value.result,
            )

            val automatic = Fixture(this)
            automatic.resources.checkAnswer = { found(JITENDEX) }
            automatic.resources.installAnswer = { "download_http_rejected" }
            automatic.coordinator.runAutomaticIfDue()
            // Logged only: an automatic failure leaves the result line alone.
            assertNull(automatic.coordinator.state.value.result)
            assertEquals(NOW, automatic.stamp.checkedAt)
        }

    @Test
    fun `a cancelled install ends the run without a stamp`() =
        runTest {
            val fixture = Fixture(this)
            fixture.resources.checkAnswer = { found(JITENDEX, JMDICT) }
            fixture.resources.installAnswer = { "resource_operation_cancelled" }

            fixture.coordinator.runNow()

            assertEquals(1, fixture.resources.installs.size)
            assertNull(fixture.stamp.checkedAt)
            assertNull(fixture.coordinator.state.value.result)
        }

    @Test
    fun `updates are revalidated against the live chain and language before installing`() =
        runTest {
            val removed = Fixture(this)
            removed.resources.checkAnswer = {
                // Removed from the chain while the publishers answered.
                removed.resources.drop("jitendex")
                found(JITENDEX, JMDICT)
            }
            removed.coordinator.runNow()
            assertEquals(listOf(JMDICT to "ja"), removed.resources.installs)

            val switched = Fixture(this)
            switched.resources.checkAnswer = {
                switched.settings.update { it.copy(language = "de") }
                found(JITENDEX)
            }
            switched.coordinator.runNow()
            assertEquals(emptyList<Pair<ResourceUpdate, String>>(), switched.resources.installs)
            assertEquals(DictionaryUpdateResult.ChangedDuringCheck, switched.coordinator.state.value.result)
            assertNull(switched.stamp.checkedAt)

            val stale = Fixture(this)
            stale.resources.checkAnswer = { found(JITENDEX) }
            stale.resources.installAnswer = { "resource_update_stale" }
            stale.coordinator.runNow()
            assertEquals(DictionaryUpdateResult.ChangedDuringCheck, stale.coordinator.state.value.result)
            assertNull(stale.stamp.checkedAt)
        }

    @Test
    fun `Update Now joins a running automatic check and reports it`() =
        runTest {
            val fixture = Fixture(this)
            val answer = CompletableDeferred<ResourceUpdateCheck>()
            fixture.resources.checkAnswer = { answer.await() }
            val automatic = launch { fixture.coordinator.runAutomaticIfDue() }
            runCurrent()
            assertNull(fixture.coordinator.state.value.result)

            val manual = launch { fixture.coordinator.runNow() }
            runCurrent()
            assertEquals(DictionaryUpdateResult.Checking, fixture.coordinator.state.value.result)
            assertTrue(fixture.coordinator.state.value.running)
            answer.complete(ResourceUpdateCheck(1, true, 0, emptyList()))
            automatic.join()
            manual.join()

            assertEquals(1, fixture.resources.checks.size)
            assertEquals(DictionaryUpdateResult.UpToDate(uncheckedCount = 0), fixture.coordinator.state.value.result)
        }

    @Test
    fun `a check that never ran or was cancelled reopens the latch for the next visible idle edge`() =
        runTest {
            val fixture = Fixture(this)
            var calls = 0
            fixture.resources.checkAnswer = {
                calls += 1
                if (calls == 1) throw ResourceBridgeException("resource_operation_cancelled", "cancelled")
                ResourceUpdateCheck(1, true, 0, emptyList())
            }

            fixture.coordinator.runAutomaticIfDue()
            assertNull(fixture.stamp.checkedAt)
            fixture.coordinator.runAutomaticIfDue()

            assertEquals(2, fixture.resources.checks.size)
            assertEquals(NOW, fixture.stamp.checkedAt)
        }

    @Test
    fun `the visible trigger runs on each edge to ready and idle`() =
        runTest {
            val fixture = Fixture(this, readiness = ResourceStartupReadiness.RECOVERING)
            val visible = launch { fixture.coordinator.whileVisible() }
            runCurrent()
            assertEquals(0, fixture.resources.checks.size)

            fixture.resources.mutableState.value =
                fixture.resources.mutableState.value.copy(startupReadiness = ResourceStartupReadiness.READY)
            runCurrent()

            assertEquals(1, fixture.resources.checks.size)
            visible.cancel()
        }

    private class Fixture(
        scope: TestScope,
        settings: AppSettings = CHAINS,
        stampedAt: Long? = null,
        readiness: ResourceStartupReadiness = ResourceStartupReadiness.READY,
        runtimeKind: RuntimeWorkCoordinator.Kind? = null,
        unmetered: Boolean = true,
    ) {
        val settings = SessionSettingsRepository(settings)
        val stamp = FakeStamp(stampedAt)
        val resources = FakeUpdateResources(INVENTORY.copy(startupReadiness = readiness))
        private var operations = 0
        val coordinator =
            DictionaryUpdateCoordinator(
                resources = resources,
                settings = this.settings,
                stamp = stamp,
                runtimeWork = MutableStateFlow(runtimeKind),
                unmeteredNetwork = { unmetered },
                now = { NOW },
                dispatcher = StandardTestDispatcher(scope.testScheduler),
                newOperationId = { "resource_update${++operations}" },
            )
    }

    private class FakeStamp(var checkedAt: Long?) : DictionaryUpdateStamp {
        override suspend fun dictionaryUpdatesCheckedAt(): Long? = checkedAt

        override suspend fun recordDictionaryUpdatesChecked(atMillis: Long) {
            checkedAt = atMillis
        }
    }

    private class FakeUpdateResources(
        initial: ResourceManagerState,
    ) : ResourceManager by SessionResourceManager(initial) {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<ResourceManagerState> = mutableState
        val checks = mutableListOf<ResourceUpdateCheckRequest>()
        val installs = mutableListOf<Pair<ResourceUpdate, String>>()
        var checkAnswer: suspend (ResourceUpdateCheckRequest) -> ResourceUpdateCheck = {
            ResourceUpdateCheck(checked = 0, reached = true, failedCount = 0, updates = emptyList())
        }
        var installAnswer: suspend (ResourceUpdate) -> String? = { null }

        fun drop(slotId: String) {
            mutableState.value =
                mutableState.value.copy(dictionaries = mutableState.value.dictionaries.filter { it.slotId != slotId })
        }

        override fun installedDictionaryIds(language: String) = state.value.usableDictionaryIds(language)

        override fun installedFrequencyIds(language: String) = state.value.usableFrequencyIds(language)

        override fun installedPitchIds(language: String) = state.value.usablePitchIds(language)

        override suspend fun checkResourceUpdates(request: ResourceUpdateCheckRequest): ResourceUpdateCheck {
            checks += request
            return checkAnswer(request)
        }

        override suspend fun installResourceUpdate(update: ResourceUpdate, language: String): String? {
            installs += update to language
            return installAnswer(update)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        val JITENDEX =
            ResourceUpdate(
                ResourceUpdateKind.DICTIONARY,
                "jitendex",
                "2026.07.09.0",
                "2026.10.03.0",
                "Jitendex.org [2026-10-03]",
                "https://example.org/jitendex.zip",
                1_073_741_824,
            )
        val JMDICT =
            ResourceUpdate(
                ResourceUpdateKind.DICTIONARY,
                "jmdict",
                "JMdict.2026-07-17",
                "JMdict.2026-10-07",
                "JMdict [2026-10-07]",
                "https://example.org/jmdict.zip",
                1_073_741_824,
            )

        val CHAINS =
            AppSettings(
                dictionarySources =
                    listOf(
                        ResourceChainSelection("jmdict", enabled = false),
                        ResourceChainSelection("jitendex"),
                        ResourceChainSelection("gone"),
                    ),
                frequencySources = listOf(ResourceChainSelection("jpdb")),
            )

        val INVENTORY =
            ResourceManagerState(
                dictionaries = listOf(dictionary("jitendex"), dictionary("jmdict")),
                frequencySources =
                    listOf(
                        InstalledFrequencySource(
                            sourceId = "jpdb",
                            sourceName = "JPDB",
                            format = "yomitan",
                            entryCount = 10,
                            schemaOk = true,
                            schemaVersion = 1,
                            isCategorical = false,
                            rebuildSourcePath = null,
                        ),
                    ),
            )

        fun dictionary(slotId: String) =
            InstalledDictionary(
                slotId = slotId,
                occupied = true,
                valid = true,
                sourceName = slotId,
                sourceRevision = "1",
                format = "yomitan",
                entryCount = 10,
                schemaOk = true,
                embeddedAttribution = emptyMap(),
                catalogResourceId = null,
                attribution = emptyList(),
                rebuildSourcePath = null,
            )

        fun found(vararg updates: ResourceUpdate) =
            ResourceUpdateCheck(checked = updates.size, reached = true, failedCount = 0, updates = updates.toList())
    }
}
