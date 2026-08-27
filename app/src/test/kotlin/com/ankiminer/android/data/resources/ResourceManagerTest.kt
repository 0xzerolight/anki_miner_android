package com.ankiminer.android.data.resources

import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.media.SafAccessException
import com.ankiminer.android.media.SafAccessFailureKind
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionRecord
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.TransientSafSelectionInventory
import com.ankiminer.android.snapshotProductionSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun cancellationCodesEmitOnlyDebugSkipRecords() =
        runTest {
            val recorded = RecordingLogSink()
            AppLog.setMinLevel(LogLevel.DEBUG)
            AppLog.install(NoOpSink)
            AppLog.install(recorded)
            try {
                Harness(rootName = "manager-download", stagingFailureCode = "resource_operation_cancelled")
                    .manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
                Harness(
                    rootName = "manager-bridge",
                    bridgeFailureCode = "resource_operation_cancelled",
                    autoRecover = false,
                )
                    .manager.recoverAndRefresh()

                val cancellations = recorded.records.filter { it.contains("op=operation.run") }
                assertEquals(2, cancellations.size)
                assertTrue(cancellations.all { it.contains(" D run=- c=resources") })
                assertTrue(cancellations.all { it.contains("code=resource_operation_cancelled") })
                assertTrue(cancellations.all { it.contains("outcome=skip") })
                assertFalse(cancellations.any { it.contains(" E run=") || it.contains("outcome=fail") })
            } finally {
                AppLog.setMinLevel(LogLevel.INFO)
                AppLog.install(NoOpSink)
            }
        }

    @Test
    fun busyFailuresKeepStableOriginAndRetryMetadataUntilDismissed() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val harness = Harness(runtimeWorkCoordinator = coordinator)
            val lease =
                requireNotNull(
                    coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING),
                )

            harness.manager.installUniDic()
            assertEquals(ResourceFailureOrigin.UNIDIC, harness.manager.state.value.failure?.origin)
            assertEquals(
                ResourceFailureAction.RETRY,
                harness.manager.state.value.failure?.retry?.action,
            )

            harness.manager.dismissFailure()
            assertNull(harness.manager.state.value.failure)
            harness.manager.searchKnownWords("")
            assertEquals(
                ResourceFailureOrigin.KNOWN_WORDS,
                harness.manager.state.value.failure?.origin,
            )

            lease.close()
        }

    @Test
    fun knownWordsPickerFailuresPreserveOperationIdentity() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val harness = Harness(runtimeWorkCoordinator = coordinator)
            val lease =
                requireNotNull(
                    coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING),
                )

            harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
            assertEquals(
                KnownWordsFailureOperation.IMPORT,
                harness.manager.state.value.failure?.knownWordsOperation,
            )

            harness.manager.dismissFailure()
            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)
            assertEquals(
                KnownWordsFailureOperation.PREVIEW,
                harness.manager.state.value.failure?.knownWordsOperation,
            )

            harness.manager.dismissFailure()
            harness.manager.exportKnownWords(EXPORT_URI)
            assertEquals(
                KnownWordsFailureOperation.EXPORT,
                harness.manager.state.value.failure?.knownWordsOperation,
            )

            lease.close()
        }

    @Test
    fun productionSnapshotCarriesUsableInstalledPitchSourcesToBridgeConfig() =
        runTest {
            val harness = Harness(installedPitchSourceId = "kanjium")
            harness.manager.recoverAndRefresh()
            val repository =
                object : AppSettingsRepository {
                    override val settings: Flow<AppSettings> =
                        flowOf(
                            AppSettings(
                                pitchSources =
                                    listOf(ResourceChainSelection("kanjium", enabled = true)),
                            ),
                        )

                    override suspend fun update(settings: AppSettings) = Unit

                    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
                }

            // MimeTypeMap is not mocked under the JVM android.jar stub; this test is about the
            // resource chains, not the device's AVIF answer.
            val snapshot = harness.manager.snapshotProductionSettings(repository) { false }

            val pitchChain =
                snapshot.settings.getValue("pitch_chain") as BridgeJsonValue.ArrayValue
            assertEquals(1, pitchChain.values.size)
            val source = pitchChain.values.single() as BridgeJsonValue.ObjectValue
            assertEquals(
                BridgeJsonValue.Text("kanjium"),
                source.values["source_id"],
            )
            assertEquals(BridgeJsonValue.Bool(true), source.values["enabled"])
        }

    @Test
    fun productionSnapshotAsksTheDeviceWhichAnimatedScreenshotFormatItCanStore() =
        runTest {
            // Regression guard for the whole point of the parameter: the mapper was tested directly
            // with avifNameable = true while every production path took the default, so the shipped
            // app sent "webp" on every device. Measured on a 720p anime clip, that costs several
            // hundred KB per card for a visibly worse image than AVIF at the same SSIM.
            val harness = Harness()
            harness.manager.recoverAndRefresh()
            val repository =
                object : AppSettingsRepository {
                    override val settings: Flow<AppSettings> =
                        flowOf(AppSettings(animatedScreenshotsEnabled = true))

                    override suspend fun update(settings: AppSettings) = Unit

                    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
                }

            suspend fun formatFor(avifNameable: Boolean) =
                harness.manager
                    .snapshotProductionSettings(repository) { avifNameable }
                    .settings["screenshot_animated_format"]

            assertEquals(BridgeJsonValue.Text("avif"), formatFor(avifNameable = true))
            assertEquals(BridgeJsonValue.Text("webp"), formatFor(avifNameable = false))
        }

    @Test
    fun startupRecoveryBlocksMutationsUntilItsQueuedCleanupPublishesReady() =
        runTest {
            val executor = QueuedExecutor()
            val harness =
                Harness(
                    autoRecover = false,
                    resourceExecutor = executor,
                )

            val recovery = launch { harness.manager.recoverAndRefresh() }
            runCurrent()
            assertEquals(ResourceStartupReadiness.RECOVERING, harness.manager.state.value.startupReadiness)
            assertEquals(1, executor.queued.size)

            harness.manager.installUniDic()
            assertEquals(1, executor.queued.size)

            while (!recovery.isCompleted) {
                assertTrue(executor.queued.isNotEmpty())
                executor.runNext()
                runCurrent()
            }
            recovery.join()

            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            assertTrue(harness.bridge.requestsOfType("resource.unidic.install").isEmpty())
        }

    @Test
    fun interruptedCatalogInstallSurvivesRestartAsExplicitRetryWithPartialBytes() =
        runTest {
            val harness = Harness(autoRecover = false)
            val resource = FrozenResourceCatalog.value.dictionaries.first()
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.CATALOG_DICTIONARY,
                    retry =
                        ResourceFailureRetry(
                            action = ResourceFailureAction.RETRY,
                            targetId = resource.resourceId,
                            replace = false,
                        ),
                ),
            )
            val partial =
                File(harness.downloadRoot, "${resource.archive.sha256}.part").apply {
                    parentFile.mkdirs()
                    writeBytes(byteArrayOf(1))
                }

            harness.manager.recoverAndRefresh()

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_operation_interrupted", failure.code)
            assertEquals(ResourceFailureOrigin.CATALOG_DICTIONARY, failure.origin)
            assertEquals(resource.resourceId, failure.retry.targetId)
            assertEquals(ResourceFailureAction.RETRY, failure.retry.action)
            assertTrue(partial.isFile)
            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            assertFalse(ResourceOperationJournal(harness.root).exists())
        }

    @Test
    fun interruptedAudioPreflightDiscardsPartialStateAndOffersAnotherArchive() =
        runTest {
            val harness = Harness(autoRecover = false)
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.AUDIO,
                    retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                ),
            )
            val staged = File(harness.stagingRoot, "interrupted-audio.bin").apply { writeText("partial") }
            val pendingArchive =
                File(harness.audioPendingRoot, "pending-audio-archive").apply {
                    parentFile.mkdirs()
                    writeText("partial")
                }
            val pendingIndex =
                File(harness.audioPendingRoot, "pending-audio-packs.tsv").apply {
                    writeText("jpod\tjpod_files\tajt\n")
                }

            harness.manager.recoverAndRefresh()

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_operation_interrupted", failure.code)
            assertEquals(ResourceFailureOrigin.AUDIO, failure.origin)
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
            assertFalse(staged.exists())
            assertFalse(pendingArchive.exists())
            assertFalse(pendingIndex.exists())
            assertFalse(harness.audioPendingRoot.exists())
            assertFalse(ResourceOperationJournal(harness.root).exists())
        }

    @Test
    fun interruptedResourceImportClearsDurableOwnerBeforeReleasingGrant() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingResourceImportInventory(events)
            inventory.putSelection(
                SafSelectionSlot.RESOURCE_IMPORT,
                SafSelectionRecord(INPUT_URI, "Pending resource import"),
            )
            events.clear()
            val harness =
                Harness(
                    autoRecover = false,
                    safSelectionInventory = inventory,
                    onReleaseReadAccess = { events += "release:$it" },
                )
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.FREQUENCY,
                    retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                    resourceImportUri = INPUT_URI,
                    resourceImportOwnership = ResourceImportOwnershipPhase.INVENTORY_RETAINED,
                ),
            )

            harness.manager.recoverAndRefresh()

            assertEquals(listOf("clear:RESOURCE_IMPORT", "release:$INPUT_URI"), events)
            assertNull(inventory.selection(SafSelectionSlot.RESOURCE_IMPORT))
            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
        }

    @Test
    fun failedInterruptedResourceImportClearRetainsJournalAndGrantCleanupIntent() =
        runTest {
            val inventory = RecordingResourceImportInventory().also { it.failResourceImportClear = true }
            inventory.putSelection(
                SafSelectionSlot.RESOURCE_IMPORT,
                SafSelectionRecord(INPUT_URI, "Pending resource import"),
            )
            val harness =
                Harness(
                    autoRecover = false,
                    safSelectionInventory = inventory,
                )
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.PITCH,
                    retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                    resourceImportUri = INPUT_URI,
                    resourceImportOwnership = ResourceImportOwnershipPhase.INVENTORY_RETAINED,
                ),
            )

            harness.manager.recoverAndRefresh()

            assertEquals(ResourceStartupReadiness.FAILED, harness.manager.state.value.startupReadiness)
            assertTrue(ResourceOperationJournal(harness.root, {}).exists())
            assertEquals(INPUT_URI, inventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri)
            assertTrue(harness.broker.released.isEmpty())

            inventory.failResourceImportClear = false
            harness.manager.recoverAndRefresh()

            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
            assertNull(inventory.selection(SafSelectionSlot.RESOURCE_IMPORT))
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun importCleanupFailureRetainsJournalForStartupGrantRecovery() =
        runTest {
            val inventory = RecordingResourceImportInventory()
            inventory.putSelection(
                SafSelectionSlot.RESOURCE_IMPORT,
                SafSelectionRecord(INPUT_URI, "Pending resource import"),
            )
            inventory.failResourceImportClear = true
            val harness =
                Harness(
                    sourceLabel = "frequency source",
                    safSelectionInventory = inventory,
                )

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.CSV,
                replace = false,
            )

            assertEquals("resource_operation_failed", harness.manager.state.value.failure?.code)
            assertTrue(ResourceOperationJournal(harness.root, {}).exists())
            assertEquals(INPUT_URI, inventory.selection(SafSelectionSlot.RESOURCE_IMPORT)?.uri)
            assertTrue(harness.broker.released.isEmpty())

            inventory.failResourceImportClear = false
            harness.manager.recoverAndRefresh()

            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
            assertNull(inventory.selection(SafSelectionSlot.RESOURCE_IMPORT))
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun catalogCommitRefreshFailureRetriesReconciliationWithoutReplayingImport() =
        runTest {
            val harness =
                Harness(
                    fakePinnedDownloads = true,
                    failRefreshAfterDictionaryImport = true,
                )
            val resource = FrozenResourceCatalog.value.dictionaries.first()

            harness.manager.installCatalogDictionary(resource.resourceId, replace = false)

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_inventory_failed", failure.code)
            assertEquals(ResourceFailureOrigin.SETUP, failure.origin)
            assertEquals(ResourceFailureAction.RETRY, failure.retry.action)
            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)

            harness.manager.recoverAndRefresh()

            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertTrue(resource.slotId in harness.manager.installedDictionaryIds())
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun committedLocalMutationsRetryReconciliationWithoutReplayingMutation() =
        runTest {
            val scenarios: List<Triple<String, String, suspend (Harness) -> Unit>> =
                listOf(
                    Triple("resource.dictionary.import", "dictionary archive") { harness ->
                        harness.manager.importCustomDictionary(
                            INPUT_URI,
                            slotId = "fixture-dictionary",
                            replace = false,
                        )
                    },
                    Triple("resource.frequency.import", "frequency source") { harness ->
                        harness.manager.importFrequencySource(
                            INPUT_URI,
                            sourceId = "fixture-frequency",
                            sourceName = "Fixture Frequency",
                            format = FrequencySourceFormat.CSV,
                            replace = false,
                        )
                    },
                    Triple("resource.pitch.import", "pitch-accent source") { harness ->
                        harness.manager.importPitchAccent(
                            INPUT_URI,
                            sourceId = "fixture-pitch",
                            sourceName = "Fixture Pitch",
                            format = PitchAccentSourceFormat.YOMITAN_ZIP,
                            replace = false,
                        )
                    },
                    Triple("resource.audiopack.import", "audio-pack archive") { harness ->
                        harness.manager.importAudioPack(
                            INPUT_URI,
                            AudioPackCandidate("jpod", "jpod_files", "ajt"),
                            replace = false,
                        )
                    },
                    Triple("resource.knownwords.import", "known-word file") { harness ->
                        harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
                    },
                    Triple("resource.knownwords.import", "known-word file") { harness ->
                        harness.manager.previewKnownWords(
                            INPUT_URI,
                            ResourceImportFileKind.JSON,
                        )
                        harness.manager.confirmKnownWordsImport()
                        assertFalse(harness.pendingRoot.exists())
                        assertNull(harness.manager.state.value.knownWordsImportPreview)
                    },
                    Triple("resource.knownwords.remove", "known-word file") { harness ->
                        harness.manager.removeKnownWords(listOf("mutable0"))
                    },
                    Triple("resource.knownwords.reset", "known-word file") { harness ->
                        harness.manager.resetKnownWords(KnownWordsResetScope.CACHE)
                    },
                )

            scenarios.forEachIndexed { index, (requestType, sourceLabel, mutate) ->
                val harness =
                    Harness(
                        rootName = "manager-committed-local-$index",
                        initialUserCount = 2,
                        sourceLabel = sourceLabel,
                        failRefreshAfterMutation = requestType,
                    )

                mutate(harness)

                val failure = requireNotNull(harness.manager.state.value.failure)
                assertEquals(requestType, "resource_inventory_failed", failure.code)
                assertEquals(requestType, ResourceFailureOrigin.SETUP, failure.origin)
                assertEquals(requestType, ResourceFailureAction.RETRY, failure.retry.action)
                assertEquals(requestType, 1, harness.bridge.requestsOfType(requestType).size)

                harness.manager.recoverAndRefresh()

                assertEquals(requestType, 1, harness.bridge.requestsOfType(requestType).size)
                assertNull(requestType, harness.manager.state.value.failure)
            }
        }

    @Test
    fun unidicInstallDispatchesWithProgressCallbacks() =
        runTest {
            val harness = Harness(fakePinnedDownloads = true)

            harness.manager.installUniDic()

            // installedUniDic in state is re-derived by refreshFromPython from the on-device
            // tokenizer provider (fixed to null in this harness), not from this response, so
            // success here is judged by the dispatch itself, not by state.installedUniDic.
            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.unidic.install").size)
            assertEquals(
                listOf(true),
                harness.bridge.callbackPresenceForType("resource.unidic.install"),
            )
        }

    @Test
    fun progressCallbacksReachEachOfTheSevenImportAndInstallDispatchSites() =
        runTest {
            val resource = FrozenResourceCatalog.value.dictionaries.first()
            val scenarios: List<Triple<String, Harness, suspend (Harness) -> Unit>> =
                listOf(
                    Triple(
                        "resource.unidic.install",
                        Harness(rootName = "progress-unidic", fakePinnedDownloads = true),
                    ) { harness -> harness.manager.installUniDic() },
                    Triple(
                        "resource.dictionary.import",
                        Harness(rootName = "progress-catalog-dictionary", fakePinnedDownloads = true),
                    ) { harness ->
                        harness.manager.installCatalogDictionary(resource.resourceId, replace = false)
                    },
                    Triple(
                        "resource.dictionary.import",
                        Harness(rootName = "progress-custom-dictionary", sourceLabel = "dictionary archive"),
                    ) { harness ->
                        harness.manager.importCustomDictionary(
                            INPUT_URI,
                            slotId = "fixture-dictionary",
                            replace = false,
                        )
                    },
                    Triple(
                        "resource.frequency.import",
                        Harness(rootName = "progress-frequency", sourceLabel = "frequency source"),
                    ) { harness ->
                        harness.manager.importFrequencySource(
                            INPUT_URI,
                            sourceId = "fixture-frequency",
                            sourceName = "Fixture Frequency",
                            format = FrequencySourceFormat.CSV,
                            replace = false,
                        )
                    },
                    Triple(
                        "resource.pitch.import",
                        Harness(rootName = "progress-pitch", sourceLabel = "pitch-accent source"),
                    ) { harness ->
                        harness.manager.importPitchAccent(
                            INPUT_URI,
                            sourceId = "fixture-pitch",
                            sourceName = "Fixture Pitch",
                            format = PitchAccentSourceFormat.YOMITAN_ZIP,
                            replace = false,
                        )
                    },
                    Triple(
                        "resource.audiopack.import",
                        Harness(rootName = "progress-audiopack", sourceLabel = "audio-pack archive"),
                    ) { harness ->
                        harness.manager.importAudioPack(
                            INPUT_URI,
                            AudioPackCandidate("jpod", "jpod_files", "ajt"),
                            replace = false,
                        )
                    },
                    Triple(
                        "resource.knownwords.import",
                        Harness(rootName = "progress-known-words"),
                    ) { harness -> harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON) },
                )

            scenarios.forEach { (requestType, harness, operate) ->
                operate(harness)

                assertNull(requestType, harness.manager.state.value.failure)
                assertEquals(
                    requestType,
                    listOf(true),
                    harness.bridge.callbackPresenceForType(requestType),
                )
            }
        }

    @Test
    fun nonProgressDispatchesReceiveNoCallbacks() =
        runTest {
            val harness = Harness(autoRecover = false, sourceLabel = "dictionary archive")

            harness.manager.recoverAndRefresh()

            // Preflight/list dispatches never carry a sink; recoverAndRefresh alone fans out to
            // several of them (startup rebuild scan plus the post-rebuild inventory refresh).
            for (type in listOf("resource.catalog.get", "resource.dictionary.list", "resource.local.list")) {
                val presence = harness.bridge.callbackPresenceForType(type)
                assertTrue(type, presence.isNotEmpty())
                assertTrue(type, presence.none { it })
            }

            harness.manager.preflightCustomDictionary(INPUT_URI)

            assertEquals(
                listOf(false),
                harness.bridge.callbackPresenceForType("resource.dictionary.preflight"),
            )

            lateinit var cancelHarness: Harness
            cancelHarness =
                Harness(
                    rootName = "progress-cancel",
                    initialUserCount = 2,
                    onKnownWordsRemoveDispatch = { cancelHarness.manager.cancelActive() },
                )
            cancelHarness.manager.removeKnownWords(listOf("mutable0"))

            assertEquals(
                listOf(false),
                cancelHarness.bridge.callbackPresenceForType("resource.operation.cancel"),
            )
        }

    @Test
    fun resourceProgressEnvelopeDuringDispatchAdvancesActiveOperationState() =
        runTest {
            val harness = Harness(sourceLabel = "frequency source")
            var observed: ResourceOperationProgress? = null
            harness.bridge.progressInjector = { rawRequest, callbacks ->
                val operationId = stringField(rawRequest, "operationId")
                callbacks.onProgress(
                    envelope(
                        "resource.progress",
                        """{"operationId":"$operationId","phase":"importing","kind":"items","current":7,"total":20}""",
                    ),
                )
                observed = harness.manager.state.value.activeOperation
            }

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.CSV,
                replace = false,
            )

            val progress = requireNotNull(observed)
            assertEquals(ResourceOperationPhase.IMPORTING, progress.phase)
            assertEquals(7L, progress.completed)
            assertEquals(20L, progress.total)
            assertEquals(ResourceProgressUnit.ITEMS, progress.unit)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun resourceProgressEnvelopeForAnotherOperationIsDropped() =
        runTest {
            val harness = Harness(sourceLabel = "frequency source")
            var observed: ResourceOperationProgress? = null
            harness.bridge.progressInjector = { _, callbacks ->
                callbacks.onProgress(
                    envelope(
                        "resource.progress",
                        """{"operationId":"resource_00000000000000000000000000000000","phase":"finalizing","kind":"items","current":7,"total":20}""",
                    ),
                )
                observed = harness.manager.state.value.activeOperation
            }

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.CSV,
                replace = false,
            )

            val progress = requireNotNull(observed)
            // The mismatched operationId must not move the phase to FINALIZING or adopt its counts;
            // the state stays at whatever the real operation last published for itself.
            assertEquals(ResourceOperationPhase.IMPORTING, progress.phase)
            assertEquals(0L, progress.completed)
            assertEquals(0L, progress.total)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun malformedResourceProgressEnvelopeDoesNotFailTheOperation() =
        runTest {
            val harness = Harness(sourceLabel = "frequency source")
            harness.bridge.progressInjector = { _, callbacks ->
                callbacks.onProgress("not a resource-progress envelope")
            }

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.CSV,
                replace = false,
            )

            assertNull(harness.manager.state.value.failure)
            assertTrue(
                harness.manager.state.value.frequencySources.any { it.sourceId == "fixture-frequency" },
            )
        }

    @Test
    fun audioPackImportProgressEnvelopeReachesTheForegroundLease() =
        runTest {
            val harness = Harness(sourceLabel = "audio-pack archive")
            harness.bridge.progressInjector = { rawRequest, callbacks ->
                val operationId = stringField(rawRequest, "operationId")
                callbacks.onProgress(
                    envelope(
                        "resource.progress",
                        """{"operationId":"$operationId","phase":"importing","kind":"bytes","current":1234,"total":98765}""",
                    ),
                )
            }

            harness.manager.importAudioPack(
                INPUT_URI,
                AudioPackCandidate("jpod", "jpod_files", "ajt"),
                replace = false,
            )

            assertNull(harness.manager.state.value.failure)
            assertTrue(
                harness.foregroundLease.updates.any {
                    it.phase == ResourceOperationPhase.IMPORTING &&
                        it.completed == 1234L &&
                        it.total == 98765L &&
                        it.unit == ResourceProgressUnit.BYTES
                },
            )
        }

    @Test
    fun wordListReplacementFailurePreservesThePreviouslyPublishedFile() =
        runTest {
            var failReplacementPublish = false
            val harness =
                Harness(
                    sourceLabel = "word-list file",
                    wordListMover = { source, target ->
                        if (
                            failReplacementPublish &&
                                source.name.endsWith(".candidate") &&
                                target.name == WordListKind.BLACKLIST.fileName
                        ) {
                            false
                        } else {
                            source.renameTo(target)
                        }
                    },
                )
            harness.stager.sourceText = "old\n"
            harness.manager.importWordList(INPUT_URI, WordListKind.BLACKLIST)
            val path = requireNotNull(harness.manager.wordListPath(WordListKind.BLACKLIST))
            assertEquals("old\n", File(path).readText())

            harness.stager.sourceText = "new\n"
            failReplacementPublish = true
            harness.manager.importWordList(INPUT_URI, WordListKind.BLACKLIST)

            assertEquals("old\n", File(path).readText())
            assertEquals(ResourceFailureOrigin.WORD_LIST, harness.manager.state.value.failure?.origin)
        }

    @Test
    fun startupPublishesDurableWordListCandidateAndRemovesCrashBackup() =
        runTest {
            val harness = Harness(autoRecover = false)
            val wordListRoot = File(harness.root, "resource-word-lists").apply { mkdirs() }
            File(wordListRoot, "blacklist.txt.backup").writeText("old\n")
            File(wordListRoot, "blacklist.txt.candidate").writeText("new\n")

            harness.manager.recoverAndRefresh()

            assertEquals(
                "new\n",
                File(requireNotNull(harness.manager.wordListPath(WordListKind.BLACKLIST))).readText(),
            )
            assertFalse(File(wordListRoot, "blacklist.txt.backup").exists())
            assertFalse(File(wordListRoot, "blacklist.txt.candidate").exists())
            assertEquals(1, harness.manager.state.value.wordList(WordListKind.BLACKLIST)?.entryCount)
        }

    @Test
    fun previewLifecycleRetainsOneCopyAndConfirmRefreshesInventory() =
        runTest {
            val harness = Harness()

            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)

            assertEquals(listOf(INPUT_URI), harness.broker.retained)
            assertEquals(listOf(INPUT_URI), harness.broker.released)
            assertEquals(
                KnownWordsImportPreview(
                    format = "migaku_json",
                    importedCount = 2,
                    totalEntries = 3,
                    isGeneric = false,
                    sampleWords = listOf("犬", "猫"),
                ),
                harness.manager.state.value.knownWordsImportPreview,
            )
            assertEquals(1, harness.pendingRoot.listFiles().orEmpty().size)
            assertTrue(harness.pendingRoot.listFiles().single().name.endsWith(".json"))
            assertFalse(harness.stager.stagedFiles.single().exists())

            harness.manager.dismissKnownWordsImportPreview()

            assertNull(harness.manager.state.value.knownWordsImportPreview)
            assertFalse(harness.pendingRoot.exists())

            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)
            harness.manager.confirmKnownWordsImport()

            assertNull(harness.manager.state.value.knownWordsImportPreview)
            assertFalse(harness.pendingRoot.exists())
            assertEquals(2L, harness.manager.state.value.knownWords.userCount)
            assertEquals(2L, harness.manager.state.value.knownWords.totalCount)
            assertEquals(
                ImportedKnownWords("migaku_json", 2, 2, 3, isGeneric = false),
                harness.manager.state.value.lastLocalImport,
            )
            assertEquals(
                listOf(
                    "resource.knownwords.preview",
                    "resource.knownwords.preview",
                    "resource.knownwords.import",
                    // No resource.catalog.get: the startup recovery barrier already cached the
                    // pinned catalog, so a committed mutation only re-reads the inventories.
                    "resource.dictionary.list",
                    "resource.local.list",
                ),
                harness.bridge.requestTypes,
            )
            assertEquals(listOf(INPUT_URI, INPUT_URI), harness.broker.retained)
            assertEquals(listOf(INPUT_URI, INPUT_URI), harness.broker.released)
        }

    @Test
    fun failedConfirmedImportRetainsStagedInputAndRetryRepeatsImport() =
        runTest {
            val harness = Harness(failKnownWordsImportOnce = true)
            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)

            harness.manager.confirmKnownWordsImport()

            assertEquals(
                KnownWordsFailureOperation.IMPORT,
                harness.manager.state.value.failure?.knownWordsOperation,
            )
            assertEquals(ResourceFailureAction.RETRY, harness.manager.state.value.failure?.retry?.action)
            assertTrue(harness.pendingRoot.isDirectory)
            assertTrue(harness.manager.state.value.knownWordsImportPreview != null)

            harness.manager.retryKnownWordsFailure()

            assertEquals(2, harness.bridge.requestsOfType("resource.knownwords.import").size)
            assertFalse(harness.pendingRoot.exists())
            assertNull(harness.manager.state.value.knownWordsImportPreview)
            assertEquals(2L, harness.manager.state.value.knownWords.userCount)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun dismissWhileConfirmOwnsKnownWordsPreviewPreservesRetryInput() =
        runTest {
            val executor = PausableExecutor()
            val harness =
                Harness(
                    resourceExecutor = executor,
                    failKnownWordsImportOnce = true,
                )
            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)
            val retained = harness.pendingRoot.listFiles().single()
            executor.paused = true

            val confirmation = launch { harness.manager.confirmKnownWordsImport() }
            runCurrent()
            assertEquals(1, executor.queued.size)

            harness.manager.dismissKnownWordsImportPreview()

            assertTrue(retained.isFile)
            assertTrue(harness.manager.state.value.knownWordsImportPreview != null)

            executor.runNext()
            runCurrent()
            while (executor.queued.isNotEmpty()) {
                executor.runNext()
                runCurrent()
            }
            confirmation.join()

            assertTrue(retained.isFile)
            assertEquals(
                KnownWordsFailureOperation.IMPORT,
                harness.manager.state.value.failure?.knownWordsOperation,
            )

            executor.paused = false
            harness.manager.retryKnownWordsFailure()

            assertEquals(2, harness.bridge.requestsOfType("resource.knownwords.import").size)
            assertFalse(harness.pendingRoot.exists())
            assertNull(harness.manager.state.value.knownWordsImportPreview)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun failedKnownWordRemoveRetryKeepsPayloadAndSearchCannotClearFailure() =
        runTest {
            val harness = Harness(initialUserCount = 2, failKnownWordsRemoveOnce = true)

            harness.manager.removeKnownWords(listOf("mutable0"))

            assertNull(harness.manager.state.value.failure?.knownWordsOperation)
            assertEquals(ResourceFailureAction.RETRY, harness.manager.state.value.failure?.retry?.action)

            harness.manager.searchKnownWords("mutable")
            assertNull(harness.manager.state.value.failure?.knownWordsOperation)

            harness.manager.retryKnownWordsFailure()

            assertEquals(2, harness.bridge.requestsOfType("resource.knownwords.remove").size)
            assertEquals(1L, harness.manager.state.value.knownWords.userCount)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun failedKnownWordResetRetryKeepsExactScope() =
        runTest {
            val harness = Harness(initialUserCount = 2, failKnownWordsResetOnce = true)

            harness.manager.resetKnownWords(KnownWordsResetScope.CACHE)
            harness.manager.retryKnownWordsFailure()

            assertEquals(2, harness.bridge.requestsOfType("resource.knownwords.reset").size)
            assertEquals(
                listOf("cache", "cache"),
                harness.bridge.requestsOfType("resource.knownwords.reset").map {
                    stringField(it, "scope")
                },
            )
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun removeMinedWordsDedupesChunksAndDispatchesInsideOneOperation() =
        runTest {
            val harness = Harness(rootName = "manager-mined-chunk", initialUserCount = 2)
            val words = (0 until 256).map { "w$it" } + listOf("w0", "w255", "extra")

            val result = harness.manager.removeMinedWords(words)

            assertTrue(result)
            val requests = harness.bridge.requestsOfType("resource.minedwords.remove")
            assertEquals(2, requests.size)
            assertEquals(
                1,
                requests.map { stringField(it, "operationId") }.distinct().size,
            )
            val dispatchedChunks = requests.map { wordsField(it) }
            assertEquals(listOf(256, 1), dispatchedChunks.map { it.size })
            val dispatchedWords = dispatchedChunks.flatten()
            assertEquals(257, dispatchedWords.size)
            assertEquals(257, dispatchedWords.distinct().size)
        }

    @Test
    fun removeMinedWordsWhileTheRuntimeLeaseIsHeldReturnsFalseWithoutDispatch() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val harness = Harness(rootName = "manager-mined-busy", runtimeWorkCoordinator = coordinator)
            val lease = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))

            val result = harness.manager.removeMinedWords(listOf("word"))

            assertFalse(result)
            assertTrue(harness.bridge.requestsOfType("resource.minedwords.remove").isEmpty())
            assertEquals("resource_busy", harness.manager.state.value.failure?.code)
            lease.close()
        }

    @Test
    fun removeMinedWordsBeforeStartupIsReadyReturnsFalseWithoutDispatch() =
        runTest {
            val harness = Harness(rootName = "manager-mined-not-ready", autoRecover = false)

            val result = harness.manager.removeMinedWords(listOf("word"))

            assertFalse(result)
            assertTrue(harness.bridge.requestsOfType("resource.minedwords.remove").isEmpty())
        }

    @Test
    fun failedMinedWordsRemoveRetryReplaysRemoveMined() =
        runTest {
            val harness =
                Harness(
                    rootName = "manager-mined-retry",
                    initialUserCount = 2,
                    failMinedWordsRemoveOnce = true,
                )

            val result = harness.manager.removeMinedWords(listOf("w0", "w1"))

            assertFalse(result)
            assertNull(harness.manager.state.value.failure?.knownWordsOperation)
            assertEquals(ResourceFailureAction.RETRY, harness.manager.state.value.failure?.retry?.action)

            harness.manager.retryKnownWordsFailure()

            assertEquals(2, harness.bridge.requestsOfType("resource.minedwords.remove").size)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun rejectedKnownWordMutationCannotReplaceAdmittedOperationsRetryPayload() =
        runTest {
            val executor = PausableExecutor()
            val harness =
                Harness(
                    initialUserCount = 2,
                    resourceExecutor = executor,
                    failKnownWordsRemoveOnce = true,
                )
            executor.paused = true

            val admitted = launch { harness.manager.removeKnownWords(listOf("mutable0")) }
            runCurrent()
            assertEquals(1, executor.queued.size)

            val rejected = launch { harness.manager.removeKnownWords(listOf("mutable1")) }
            runCurrent()
            rejected.join()
            assertEquals(1, executor.queued.size)

            executor.runNext()
            runCurrent()
            while (executor.queued.isNotEmpty()) {
                executor.runNext()
                runCurrent()
            }
            admitted.join()

            executor.paused = false
            harness.manager.retryKnownWordsFailure()

            val requests = harness.bridge.requestsOfType("resource.knownwords.remove")
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.contains("\"words\":[\"mutable0\"]") })
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun paginationAppendsOnlyMatchingContinuationAndNewQueryReplacesPage() =
        runTest {
            val harness = Harness(initialUserCount = 101)

            harness.manager.searchKnownWords(query = "", loadMore = false)
            harness.manager.searchKnownWords(query = "", loadMore = true)
            harness.manager.searchKnownWords(query = "", loadMore = true)

            val complete = harness.manager.state.value.knownWordsPage!!
            assertEquals(101, complete.words.size)
            assertEquals("word0", complete.words.first())
            assertEquals("word100", complete.words.last())
            assertEquals(101L, complete.totalCount)
            assertFalse(complete.hasMore)

            harness.manager.searchKnownWords(query = "食", loadMore = false)

            assertEquals(
                KnownWordsPage("食", 0, 1, listOf("食べる"), hasMore = false),
                harness.manager.state.value.knownWordsPage,
            )
            assertEquals(
                listOf(0, 100, 0),
                harness.bridge.requestsOfType("resource.knownwords.list").map {
                    intField(it, "offset")
                },
            )
        }

    @Test
    fun removeAndResetRefreshCountsAndInvalidateLoadedPage() =
        runTest {
            val harness = Harness(initialUserCount = 2)
            harness.manager.searchKnownWords(query = "mutable", loadMore = false)
            assertEquals(2, harness.manager.state.value.knownWordsPage!!.words.size)

            harness.manager.removeKnownWords(listOf("mutable0"))

            assertNull(harness.manager.state.value.knownWordsPage)
            assertEquals(1L, harness.manager.state.value.knownWords.userCount)
            assertEquals(1L, harness.manager.state.value.knownWords.totalCount)

            harness.manager.searchKnownWords(query = "mutable", loadMore = false)
            assertEquals(listOf("mutable0"), harness.manager.state.value.knownWordsPage!!.words)

            harness.manager.resetKnownWords(KnownWordsResetScope.USER)

            assertNull(harness.manager.state.value.knownWordsPage)
            assertEquals(0L, harness.manager.state.value.knownWords.userCount)
            assertEquals(0L, harness.manager.state.value.knownWords.totalCount)
            assertEquals(2, harness.bridge.requestsOfType("resource.local.list").size)
        }

    @Test
    fun exportCopiesValidatedOperationFileToSafAndRemovesPrivateCopy() =
        runTest {
            val harness = Harness(initialUserCount = 2)

            harness.manager.exportKnownWords(EXPORT_URI)

            assertEquals(listOf(EXPORT_URI), harness.writer.openedUris)
            assertEquals("犬\n猫\n", harness.writer.output.toString(Charsets.UTF_8.name()))
            assertEquals(
                listOf("resource.knownwords.export"),
                harness.bridge.requestTypes,
            )
            assertFalse(harness.bridge.lastExportFile!!.exists())
            assertFalse(harness.bridge.lastExportFile!!.parentFile.exists())
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun exportCancellationClosesSafOutputAndDeletesPartialDestination() =
        runTest {
            lateinit var harness: Harness
            harness =
                Harness(
                    onFirstExportWrite = { harness.manager.cancelActive() },
                )

            harness.manager.exportKnownWords(EXPORT_URI)

            assertTrue(harness.writer.output.size() > 0)
            assertEquals(listOf(EXPORT_URI), harness.writer.deletedUris)
            assertTrue(harness.writer.closeCount > 0)
            assertNull(harness.manager.state.value.failure)
            assertNull(harness.manager.state.value.activeOperation)
        }

    @Test
    fun failedPythonCancelDeliveryCannotTurnCommittedMutationIntoSuccess() =
        runTest {
            lateinit var harness: Harness
            harness =
                Harness(
                    initialUserCount = 2,
                    failCancelDelivery = true,
                    onKnownWordsRemoveDispatch = { harness.manager.cancelActive() },
                )

            harness.manager.removeKnownWords(listOf("mutable0"))

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_cancel_delivery_failed", failure.code)
            assertEquals(ResourceFailureOrigin.SETUP, failure.origin)
            assertEquals(ResourceFailureAction.RETRY, failure.retry.action)
            assertEquals(1, harness.bridge.requestsOfType("resource.knownwords.remove").size)
            assertEquals(1, harness.bridge.userCount)
        }

    @Test
    fun cancelStillQueuedForPythonLeavesTheCommittedMutationAlone() =
        runTest {
            // Production runs the cancel dispatch on its own single-thread executor, so a cancel
            // raised as the worker commits is still undelivered when the worker returns.
            val control = QueuedExecutor()
            lateinit var harness: Harness
            harness =
                Harness(
                    initialUserCount = 2,
                    controlExecutor = control,
                    onKnownWordsRemoveDispatch = { harness.manager.cancelActive() },
                )

            harness.manager.removeKnownWords(listOf("mutable0"))

            assertEquals(1, control.queued.size)
            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.knownwords.remove").size)
            assertEquals(1, harness.bridge.userCount)
        }

    @Test
    fun cancelDeliveryFailingAfterTheOperationEndedPublishesNothing() =
        runTest {
            // The disclosed cost of treating REQUESTED as non-terminal, pinned as intended: once
            // the worker has returned there is nothing left to cancel, so a delivery failure that
            // lands afterwards must not raise Retry against work that committed.
            val control = QueuedExecutor()
            lateinit var harness: Harness
            harness =
                Harness(
                    initialUserCount = 2,
                    controlExecutor = control,
                    failCancelDelivery = true,
                    onKnownWordsRemoveDispatch = { harness.manager.cancelActive() },
                )

            harness.manager.removeKnownWords(listOf("mutable0"))

            assertNull(harness.manager.state.value.activeOperation)

            control.runNext()

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.userCount)
        }

    @Test
    fun audioPackImportOutOfSpaceReportsStorageNotADegenerateSizeLimit() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    reportedSourceSizeBytes = 64L * 1024 * 1024,
                    stagingAvailableBytes = ARCHIVE_BUDGET_RESERVE_BYTES / 2,
                )

            harness.manager.preflightAudioPack(INPUT_URI)

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("insufficient_storage", failure.code)
            assertEquals(ResourceFailureOrigin.AUDIO, failure.origin)
            assertTrue(harness.stager.stagedFiles.isEmpty())
            assertNull(harness.stager.lastMaximumBytes)
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun audioPackBudgetTracksFreeSpaceInsteadOfAFixedTwoGigabyteCap() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    reportedSourceSizeBytes = 3L * 1024 * 1024 * 1024,
                    stagingAvailableBytes = 64L * 1024 * 1024 * 1024,
                )

            val packs = requireNotNull(harness.manager.preflightAudioPack(INPUT_URI))
            harness.manager.importAudioPack(INPUT_URI, packs.single(), replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.preflight").size)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.import").size)
            assertEquals(1, harness.stager.stagedFiles.size)
            assertEquals(1, harness.stager.audioStageCalls)
            assertEquals(0, harness.stager.genericStageCalls)
            assertTrue(harness.stager.lastMaximumBytes!! > 3L * 1024 * 1024 * 1024)
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun audioPackImportReusesPreflightWhenPickerDropsDetectedFormat() =
        runTest {
            val harness = Harness(sourceLabel = "audio-pack archive")
            val detected = requireNotNull(harness.manager.preflightAudioPack(INPUT_URI)).single()

            harness.manager.importAudioPack(
                INPUT_URI,
                detected.copy(format = ""),
                replace = false,
            )

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.stager.audioStageCalls)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.preflight").size)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.import").size)
            assertEquals(listOf(INPUT_URI), harness.broker.retained)
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun directAudioPackRestagingUsesVerifiedAudioStaging() =
        runTest {
            val harness = Harness(sourceLabel = "audio-pack archive")

            harness.manager.importAudioPack(
                INPUT_URI,
                AudioPackCandidate("jpod", "jpod_files", "ajt"),
                replace = false,
            )

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.stager.audioStageCalls)
            assertEquals(0, harness.stager.genericStageCalls)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.import").size)
        }

    @Test
    fun audioArchiveStagingLogsOnlyBoundedRepresentationMetadata() =
        runTest {
            val recorded = RecordingLogSink()
            AppLog.install(NoOpSink)
            AppLog.install(recorded)
            try {
                val harness =
                    Harness(
                        sourceLabel = "audio-pack archive",
                        reportedSourceSizeBytes = 7,
                        sourceDisplayName = "private collection.tar.xz",
                        sourceMimeType = "application/x-xz; private=value",
                        audioReadMode = AudioArchiveReadMode.RAW,
                        audioContainer = AudioArchiveContainer.XZ,
                    )

                assertTrue(harness.manager.preflightAudioPack(INPUT_URI)!!.isNotEmpty())

                val record = recorded.records.single { it.contains("op=audio.archive.stage") }
                assertTrue(record.contains("authority=fixtures"))
                assertTrue(record.contains("mime=application/x-xz"))
                assertTrue(record.contains("filename_type=tar.xz"))
                assertTrue(record.contains("reported_bytes=7"))
                assertTrue(record.contains("staged_bytes=7"))
                assertTrue(record.contains("size_agreement=match"))
                assertTrue(record.contains("read_mode=raw"))
                assertTrue(record.contains("container=xz"))
                assertFalse(record.contains(INPUT_URI))
                assertFalse(record.contains("private collection"))
                assertFalse(record.contains("0".repeat(64)))
            } finally {
                AppLog.install(NoOpSink)
            }
        }

    @Test
    fun rejectedAudioArchiveLogsBoundedMetadataWithoutProviderDetails() =
        runTest {
            val recorded = RecordingLogSink()
            val privateHash = "a".repeat(64)
            val providerDetail = "$INPUT_URI private archive.torrent $privateHash"
            AppLog.install(NoOpSink)
            AppLog.install(recorded)
            try {
                val expectedModes =
                    mapOf(
                        "resource_archive_unrecognized" to "raw",
                        "resource_archive_provider_representation" to "asset_fallback",
                    )
                expectedModes.forEach { (code, readMode) ->
                    val harness =
                        Harness(
                            rootName = "manager-log-$code",
                            sourceLabel = "audio-pack archive",
                            reportedSourceSizeBytes = 91,
                            sourceDisplayName = "private archive.torrent",
                            sourceMimeType = "text/html; private=value",
                            stagingFailureCode = code,
                            stagingFailureDetail = providerDetail,
                            autoRecover = false,
                        )

                    assertNull(harness.manager.preflightAudioPack(INPUT_URI))

                    val record =
                        recorded.records.last { it.contains("op=audio.archive.stage") }
                    assertTrue(record.contains("outcome=fail"))
                    assertTrue(record.contains("authority=fixtures"))
                    assertTrue(record.contains("mime=text/html"))
                    assertTrue(record.contains("filename_type=torrent"))
                    assertTrue(record.contains("reported_bytes=91"))
                    assertTrue(record.contains("staged_bytes=unknown"))
                    assertTrue(record.contains("size_agreement=unknown"))
                    assertTrue(record.contains("read_mode=$readMode"))
                    assertTrue(record.contains("container=unknown"))
                }

                recorded.records.forEach { record ->
                    assertFalse(record.contains(INPUT_URI))
                    assertFalse(record.contains("private archive"))
                    assertFalse(record.contains(privateHash))
                }
            } finally {
                AppLog.install(NoOpSink)
            }
        }

    @Test
    fun audioArchiveRepresentationFailuresUseActionableMessages() =
        runTest {
            val expectations =
                mapOf(
                    "resource_archive_unrecognized" to
                        R.string.resource_failure_archive_unrecognized,
                    "resource_archive_provider_representation" to
                        R.string.resource_failure_archive_provider_representation,
                )
            for ((code, messageId) in expectations) {
                val harness =
                    Harness(
                        rootName = "manager-$code",
                        sourceLabel = "audio-pack archive",
                        stagingFailureCode = code,
                        autoRecover = false,
                    )

                assertNull(harness.manager.preflightAudioPack(INPUT_URI))

                val failure = requireNotNull(harness.manager.state.value.failure)
                assertEquals(code, failure.code)
                assertEquals("resource:$messageId", failure.message)
                assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
            }
        }

    @Test
    fun preflightReportsNoneDetectedWhenBridgeReturnsEmptyPackList() =
        runTest {
            val harness = Harness(sourceLabel = "audio-pack archive", autoRecover = false)
            harness.bridge.emptyAudioPackPreflight = true

            assertNull(harness.manager.preflightAudioPack(INPUT_URI))

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("audio_pack_none_detected", failure.code)
            assertEquals(
                "resource:${R.string.resource_failure_audio_pack_none_detected}",
                failure.message,
            )
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
            assertFalse(harness.audioPendingRoot.exists())
        }

    @Test
    fun safGrantRefusalSurfacesRepickGuidanceInsteadOfGenericFailure() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    autoRecover = false,
                    onRetainReadAccess = {
                        throw SafAccessException(
                            SafAccessFailureKind.PERMISSION_REVOKED,
                            "provider refused a persistable grant",
                        )
                    },
                )

            assertNull(harness.manager.preflightAudioPack(INPUT_URI))

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("saf_permission_not_granted", failure.code)
            assertEquals("resource:${R.string.resource_failure_saf_permission}", failure.message)
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
        }

    @Test
    fun providerTimeoutSurfacesProviderUnavailableGuidance() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    autoRecover = false,
                    onRetainReadAccess = {
                        throw SafAccessException(
                            SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                            "provider did not respond",
                        )
                    },
                )

            assertNull(harness.manager.preflightAudioPack(INPUT_URI))

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("saf_provider_unavailable", failure.code)
            assertEquals("resource:${R.string.resource_failure_saf_provider}", failure.message)
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
        }

    @Test
    fun audioPackTooBigForTheDeviceIsRejectedBeforeAnythingIsCopied() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    reportedSourceSizeBytes = 8L * 1024 * 1024 * 1024,
                    stagingAvailableBytes = 4L * 1024 * 1024 * 1024,
                )

            harness.manager.preflightAudioPack(INPUT_URI)

            val failure = harness.manager.state.value.failure
            assertEquals(ResourceFailureOrigin.AUDIO, failure?.origin)
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure?.retry?.action)
            // The staged copy never starts, and the message carries both sizes.
            assertTrue(harness.stager.stagedFiles.isEmpty())
            assertNull(harness.stager.lastMaximumBytes)
            assertTrue(harness.bridge.requestTypes.none { it == "resource.audiopack.preflight" })
            assertTrue(failure!!.message.contains("Audio,8.0 GB,2.0 GB"))
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun audioPackWithNoReportedSizeStillReachesTheStreamingLimit() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    reportedSourceSizeBytes = null,
                    stagingAvailableBytes = 4L * 1024 * 1024 * 1024,
                )

            val packs = requireNotNull(harness.manager.preflightAudioPack(INPUT_URI))
            harness.manager.importAudioPack(INPUT_URI, packs.single(), replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.stager.stagedFiles.size)
            assertEquals(
                audioArchiveBudget(4L * 1024 * 1024 * 1024),
                harness.stager.lastMaximumBytes,
            )
        }

    @Test
    fun committedDictionaryInventoryRefreshesWhenResponseDecodeFails() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "dictionary archive",
                    committedDictionaryDecodeFailure = true,
                )

            harness.manager.importCustomDictionary(INPUT_URI, "revisionless", replace = false)

            // The install is committed in Python, so inventory must still show it even though
            // Kotlin refused the response.
            assertTrue(harness.manager.state.value.dictionaries.isNotEmpty())
            assertEquals("invalid_resource_response", harness.manager.state.value.failure?.code)
        }

    @Test
    fun overLimitFrequencyRevisionIsRejectedBeforePythonPublication() =
        runTest {
            val harness = Harness(sourceLabel = "frequency source")
            harness.stager.sourceBytes = frequencyArchiveBytes("a".repeat(4097))

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.YOMITAN_ZIP,
                replace = false,
            )

            assertEquals("frequency_import_failed", harness.manager.state.value.failure?.code)
            assertTrue(harness.bridge.requestsOfType("resource.frequency.import").isEmpty())
            assertTrue(harness.manager.state.value.frequencySources.isEmpty())
        }

    @Test
    fun committedFrequencyInventoryRefreshesWhenResponseDecodeFails() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "frequency source",
                    committedFrequencyDecodeFailure = true,
                )

            harness.manager.importFrequencySource(
                INPUT_URI,
                sourceId = "fixture-frequency",
                sourceName = "Fixture Frequency",
                format = FrequencySourceFormat.CSV,
                replace = false,
            )

            assertEquals(
                listOf("fixture-frequency"),
                harness.manager.state.value.frequencySources.map { it.sourceId },
            )
            assertEquals("invalid_resource_response", harness.manager.state.value.failure?.code)
        }

    @Test
    fun customDictionaryPreflightReturnsTheArchiveDerivedSlotBeforeImport() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "dictionary archive",
                    sourceDisplayName = "fixture.zip",
                    sourceMimeType = "application/zip",
                )
            val retained = harness.manager.retainResourceImport(INPUT_URI)

            val slotId = harness.manager.preflightCustomDictionary(retained.uri)

            assertEquals("fixture-dictionary-2026-08", slotId)
            val request = harness.bridge.requestsOfType("resource.dictionary.preflight").single()
            assertFalse(request.contains("content://"))
            assertFalse(request.contains("fixture-dictionary-2026-08"))
        }

    @Test
    fun committedCustomDictionaryStageKeepsDictionarySpecificSourceLabel() =
        runTest {
            val harness = Harness(sourceLabel = "dictionary archive")

            harness.manager.importCustomDictionary(
                INPUT_URI,
                slotId = "fixture-dictionary",
                replace = false,
            )

            assertEquals("dictionary archive", harness.stager.lastSourceLabel)
        }

    @Test
    fun committedPitchInventoryRefreshesWhenResponseDecodeFails() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "pitch-accent source",
                    installedPitchSourceId = "fixture-pitch",
                    committedPitchDecodeFailure = true,
                )

            harness.manager.importPitchAccent(
                INPUT_URI,
                sourceId = "fixture-pitch",
                sourceName = "Fixture Pitch",
                format = PitchAccentSourceFormat.YOMITAN_ZIP,
                replace = false,
            )

            assertEquals(listOf("fixture-pitch"), harness.manager.state.value.pitchSources.map { it.sourceId })
            assertEquals("invalid_resource_response", harness.manager.state.value.failure?.code)
            assertTrue(harness.manager.state.value.pitchSources.isNotEmpty())
        }

    @Test
    fun wordListImportPublishesBomFreeFirstWordForBothKinds() =
        runTest {
            val harness = Harness(sourceLabel = "word-list file")
            harness.stager.sourceText = "\uFEFF\u732b\n"

            harness.manager.importWordList(INPUT_URI, WordListKind.BLACKLIST)
            harness.manager.importWordList(INPUT_URI, WordListKind.WHITELIST)

            WordListKind.entries.forEach { kind ->
                val installed = harness.manager.state.value.wordLists.single { it.kind == kind }
                assertEquals(1, installed.entryCount)
                val path = requireNotNull(harness.manager.wordListPath(kind))
                assertEquals("\u732b\n", File(path).readText(Charsets.UTF_8))
            }
        }

    @Test
    fun mutationJournalAndStagingCleanupRunOnResourceExecutor() =
        runTest {
            val executor = TrackingExecutor()
            val syncContexts = mutableListOf<Boolean>()
            val harness =
                Harness(
                    resourceExecutor = executor,
                    resourceDirectorySync = { syncContexts += executor.executing },
                )
            syncContexts.clear()
            val leftover = File(harness.stagingRoot, "leftover").apply { writeText("stale") }
            var cleanupObservedOnExecutor = false
            executor.afterTask = {
                if (!leftover.exists()) cleanupObservedOnExecutor = true
            }

            harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)

            assertEquals(listOf(true, true), syncContexts)
            assertTrue(cleanupObservedOnExecutor)
            assertFalse(leftover.exists())
        }

    @Test
    fun recoveryJournalReadAndClearRunOnResourceExecutor() =
        runTest {
            val executor = TrackingExecutor()
            val syncContexts = mutableListOf<Boolean>()
            val harness =
                Harness(
                    autoRecover = false,
                    resourceExecutor = executor,
                    resourceDirectorySync = { syncContexts += executor.executing },
                )
            File(harness.root, "resource-operation-v1.pending").writeText("malformed")

            harness.manager.recoverAndRefresh()

            assertTrue(syncContexts.isNotEmpty())
            assertTrue(syncContexts.all { it })
            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
        }

    /** Records the foreground-service lifecycle a long import is supposed to drive. */
    private class RecordingForegroundLease : ResourceForegroundLease {
        val events = mutableListOf<String>()
        val updates = mutableListOf<ResourceOperationProgress>()
        var failStart = false
        var startedWhileRunning = false
            private set
        private var running = false

        override fun start(progress: ResourceOperationProgress) {
            events += "start:${progress.phase}"
            if (failStart) throw IllegalStateException("foreground admission denied")
            running = true
            startedWhileRunning = true
        }

        override fun update(progress: ResourceOperationProgress) {
            events += "update:${progress.phase}"
            updates += progress
        }

        override fun stop() {
            running = false
            events += "stop"
        }

        fun isRunning(): Boolean = running
    }

    @Test
    fun audioPreflightIsProtectedFromBeforeSafAccessThroughPendingPublication() =
        runTest {
            lateinit var harness: Harness
            var safAccessProtected = false
            var publicationProtected = false
            val inspectingExecutor =
                Executor { command ->
                    command.run()
                    publicationProtected =
                        publicationProtected ||
                            (
                                harness.foregroundLease.isRunning() &&
                                    ResourceOperationJournal(harness.root).exists() &&
                                    harness.audioPendingRoot.listFiles().orEmpty().size == 2
                            )
                }
            harness =
                Harness(
                    autoRecover = false,
                    sourceLabel = "audio-pack archive",
                    resourceExecutor = inspectingExecutor,
                    onRetainReadAccess = {
                        safAccessProtected =
                            harness.foregroundLease.isRunning() &&
                            ResourceOperationJournal(harness.root).exists()
                    },
                )

            assertTrue(harness.manager.preflightAudioPack(INPUT_URI)!!.isNotEmpty())

            assertTrue(safAccessProtected)
            assertTrue(publicationProtected)
            assertFalse(harness.foregroundLease.isRunning())
            assertEquals("start:PREPARING", harness.foregroundLease.events.first())
            assertEquals("stop", harness.foregroundLease.events.last())
            assertFalse(ResourceOperationJournal(harness.root).exists())
        }

    @Test
    fun failedForegroundAdmissionPreventsImportAndJournalAdmission() =
        runTest {
            val harness =
                Harness(
                    autoRecover = false,
                    sourceLabel = "audio-pack archive",
                    foregroundStartFailure = true,
                )

            assertNull(harness.manager.preflightAudioPack(INPUT_URI))

            assertEquals("resource_operation_failed", harness.manager.state.value.failure?.code)
            assertEquals(listOf("start:PREPARING"), harness.foregroundLease.events)
            assertTrue(harness.broker.retained.isEmpty())
            assertTrue(harness.stager.stagedFiles.isEmpty())
            assertTrue(harness.bridge.requestsOfType("resource.audiopack.preflight").isEmpty())
            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
            assertNull(harness.manager.state.value.activeOperation)
        }

    @Test
    fun failedAudioPreflightClearsForegroundLeaseJournalAndPartialState() =
        runTest {
            val harness =
                Harness(
                    autoRecover = false,
                    sourceLabel = "audio-pack archive",
                    stagingFailureCode = "import_staging_failed",
                )

            assertNull(harness.manager.preflightAudioPack(INPUT_URI))

            assertEquals("import_staging_failed", harness.manager.state.value.failure?.code)
            assertFalse(harness.foregroundLease.isRunning())
            assertEquals("start:PREPARING", harness.foregroundLease.events.first())
            assertEquals("stop", harness.foregroundLease.events.last())
            assertFalse(ResourceOperationJournal(harness.root).exists())
            assertTrue(harness.stagingRoot.listFiles().orEmpty().isEmpty())
            assertFalse(harness.audioPendingRoot.exists())
        }

    @Test
    fun aLongAudioPackImportHoldsTheForegroundServiceForItsWholeRun() =
        runTest {
            val harness = Harness(sourceLabel = "audio-pack archive")

            val packs = requireNotNull(harness.manager.preflightAudioPack(INPUT_URI))
            assertEquals("start:PREPARING", harness.foregroundLease.events.first())
            assertEquals("stop", harness.foregroundLease.events.last())
            harness.foregroundLease.events.clear()

            harness.manager.importAudioPack(INPUT_URI, packs.single(), replace = false)

            assertNull(harness.manager.state.value.failure)
            assertTrue(harness.foregroundLease.startedWhileRunning)
            assertEquals("stop", harness.foregroundLease.events.last())
            // Released once the operation ends, so no notification outlives the work.
            assertTrue(!harness.foregroundLease.isRunning())
        }

    @Test
    fun aFailedAudioPackImportStillReleasesTheForegroundService() =
        runTest {
            // Fails during staging, which is after the lease is taken: the point is
            // that an import which dies mid-flight still puts the service down.
            val harness =
                Harness(
                    sourceLabel = "audio-pack archive",
                    stagingFailureCode = "import_staging_failed",
                )

            harness.manager.importAudioPack(
                INPUT_URI,
                AudioPackCandidate("jpod", "jpod_files", "ajt"),
                replace = false,
            )

            assertEquals("import_staging_failed", harness.manager.state.value.failure?.code)
            assertTrue(harness.foregroundLease.startedWhileRunning)
            assertTrue(!harness.foregroundLease.isRunning())
            assertEquals("stop", harness.foregroundLease.events.last())
        }

    @Test
    fun aCatalogDictionaryInstallHoldsTheForegroundServiceForItsWholeRun() =
        runTest {
            // A jitendex import is 15+ minutes of pinned CPU on an entry-level
            // phone; without foreground importance the cached process is killed
            // at the background CPU quota and the import restarts from zero.
            val harness = Harness(fakePinnedDownloads = true)
            val resource = FrozenResourceCatalog.value.dictionaries.first()

            harness.manager.installCatalogDictionary(resource.resourceId, replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertTrue(harness.foregroundLease.startedWhileRunning)
            assertEquals("start:PREPARING", harness.foregroundLease.events.first())
            assertEquals("stop", harness.foregroundLease.events.last())
            assertFalse(harness.foregroundLease.isRunning())
        }

    @Test
    fun aCustomDictionaryImportHoldsTheForegroundServiceForItsWholeRun() =
        runTest {
            val harness = Harness(sourceLabel = "dictionary archive")

            harness.manager.importCustomDictionary(INPUT_URI, "fixture-dictionary", replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertTrue(harness.foregroundLease.startedWhileRunning)
            assertEquals("stop", harness.foregroundLease.events.last())
            assertFalse(harness.foregroundLease.isRunning())
        }

    @Test
    fun failedForegroundAdmissionPreventsDictionaryImportAndJournalAdmission() =
        runTest {
            val harness =
                Harness(
                    fakePinnedDownloads = true,
                    foregroundStartFailure = true,
                )
            val resource = FrozenResourceCatalog.value.dictionaries.first()

            harness.manager.installCatalogDictionary(resource.resourceId, replace = false)

            assertEquals("resource_operation_failed", harness.manager.state.value.failure?.code)
            assertEquals(listOf("start:PREPARING"), harness.foregroundLease.events)
            assertTrue(harness.bridge.requestsOfType("resource.dictionary.import").isEmpty())
            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
            assertNull(harness.manager.state.value.activeOperation)
        }

    @Test
    fun aStaleDictionaryRebuildHoldsTheForegroundServiceWhileItRuns() =
        runTest {
            // The startup schema rebuild re-runs a full dictionary import; a
            // 0.7.x upgrader with a big dictionary burns the same 15 minutes
            // here as an interactive install does.
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-dictionary-lease",
                    installedCustomDictionaryValid = false,
                    installedCustomDictionaryRebuildPath =
                        "/data/user/0/files/dicts/fixture-dictionary/source.zip",
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(harness.foregroundLease.startedWhileRunning)
            assertEquals("stop", harness.foregroundLease.events.last())
            assertFalse(harness.foregroundLease.isRunning())
        }

    @Test
    fun aDeniedForegroundStartDoesNotBlockTheStaleDictionaryRebuild() =
        runTest {
            // Unlike an interactive import, the rebuild lease is best-effort:
            // startup recovery must repair the slot even when the platform
            // refuses a foreground start at launch.
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-dictionary-no-fgs",
                    installedCustomDictionaryValid = false,
                    installedCustomDictionaryRebuildPath =
                        "/data/user/0/files/dicts/fixture-dictionary/source.zip",
                    foregroundStartFailure = true,
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(harness.manager.state.value.dictionaries.single().schemaOk)
            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun aFrequencyOrPitchOnlyRebuildDoesNotTakeTheForegroundLease() =
        runTest {
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-pitch-no-lease",
                    installedPitchSourceId = "kanjium",
                    installedPitchSchemaOk = false,
                    installedPitchRebuildPath = "/data/user/0/files/pitch/kanjium/source.zip",
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(harness.foregroundLease.events.isEmpty())
        }

    @Test
    fun bridgeArchiveRejectionsNameTheLimitThatTripped() =
        runTest {
            // One Python code per limit class, so a rejection says which limit the
            // archive hit instead of a blanket message that reads as out-of-storage.
            val expectations =
                mapOf(
                    "resource_archive_member_oversized" to
                        R.string.resource_failure_archive_member_oversized,
                    "resource_archive_member_count" to
                        R.string.resource_failure_archive_member_count,
                    "resource_archive_expands_too_large" to
                        R.string.resource_failure_archive_expands,
                )
            for ((code, resourceId) in expectations) {
                val harness =
                    Harness(
                        rootName = "manager-$code",
                        sourceLabel = "audio-pack archive",
                        bridgeFailureCode = code,
                        autoRecover = false,
                    )

                assertNull(harness.manager.preflightAudioPack(INPUT_URI))

                val failure = requireNotNull(harness.manager.state.value.failure)
                assertEquals(code, failure.code)
                assertEquals("resource:$resourceId", failure.message)
            }
        }

    @Test
    fun knownWordImportRejectionsNameTheFailureClass() =
        runTest {
            // The parser distinguishes an undecodable file from an unrecognized one from a
            // recognized export with nothing marked known. One message for all three leaves
            // the user guessing which of the three they hit.
            val expectations =
                mapOf(
                    "known_words_unreadable" to
                        R.string.resource_failure_known_words_unreadable,
                    "known_words_unrecognized" to
                        R.string.resource_failure_known_words_unrecognized,
                    "known_words_none_known" to
                        R.string.resource_failure_known_words_none_known,
                )
            for ((code, resourceId) in expectations) {
                val harness =
                    Harness(
                        rootName = "manager-$code",
                        knownWordsPreviewFailureCode = code,
                    )

                harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)

                val failure = requireNotNull(harness.manager.state.value.failure)
                assertEquals(code, failure.code)
                assertEquals("resource:$resourceId", failure.message)
                assertEquals(ResourceFailureOrigin.KNOWN_WORDS, failure.origin)
                assertEquals(KnownWordsFailureOperation.PREVIEW, failure.knownWordsOperation)
                // Re-parsing the same rejected file cannot succeed, so the banner offers the
                // picker rather than a retry.
                assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure.retry.action)
                assertFalse(failure.retryable)
            }
        }

    @Test
    fun deletingAPitchSourceRefreshesInventoryAndDropsTheSlot() =
        runTest {
            val harness = Harness(rootName = "manager-delete-pitch", installedPitchSourceId = "kanjium")
            assertEquals(listOf("kanjium"), harness.manager.state.value.pitchSources.map { it.sourceId })

            harness.manager.deleteInstalledResource(InstalledResourceKind.PITCH, "kanjium")

            assertEquals(emptyList<String>(), harness.manager.state.value.pitchSources.map { it.sourceId })
            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.local.delete").size)
        }

    @Test
    fun aSchemaStalePitchSourceIsRebuiltAtStartupFromItsPersistedCopy() =
        runTest {
            // An engine upgrade moves an index schema, and every registry compares on
            // exact equality, so a source installed by an older build stops loading.
            // Startup treats that as fatal, which would strand anyone who had pitch
            // data - but the importer kept the file it was built from, so the rebuild
            // needs no picker and no SAF grant.
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-pitch",
                    installedPitchSourceId = "kanjium",
                    installedPitchSchemaOk = false,
                    installedPitchRebuildPath = "/data/user/0/files/pitch/kanjium/source.zip",
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertEquals(listOf("kanjium"), harness.manager.state.value.pitchSources.map { it.sourceId })
            assertTrue(harness.manager.state.value.pitchSources.single().schemaOk)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun deletingTheLastBrokenPitchSourceRestoresStartupReadiness() =
        runTest {
            // A broken slot is fatal at startup and has no other repair: if delete gated on
            // READY it would be unavailable in exactly the case it exists for.
            val harness =
                Harness(
                    rootName = "manager-delete-broken",
                    installedPitchSourceId = "broken",
                    installedPitchSchemaOk = false,
                    autoRecover = false,
                )
            val wordListRoot = File(harness.root, "resource-word-lists").apply { mkdirs() }
            val candidate = File(wordListRoot, "blacklist.txt.candidate").apply { writeText("ば\n") }

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.FAILED,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(candidate.isFile)

            harness.manager.deleteInstalledResource(InstalledResourceKind.PITCH, "broken")

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertEquals(emptyList<String>(), harness.manager.state.value.pitchSources.map { it.sourceId })
            assertFalse(candidate.exists())
            assertEquals("ば\n", File(wordListRoot, "blacklist.txt").readText())
            assertEquals(1, harness.manager.state.value.wordList(WordListKind.BLACKLIST)?.entryCount)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun catalogRepairRunsFromFailedReadinessButOrdinaryInstallRemainsBlocked() =
        runTest {
            val resource = FrozenResourceCatalog.value.dictionaries.first()
            val harness =
                Harness(
                    rootName = "manager-catalog-repair",
                    fakePinnedDownloads = true,
                    installedCatalogDictionaryValid = false,
                )
            assertEquals(ResourceStartupReadiness.FAILED, harness.manager.state.value.startupReadiness)

            harness.manager.installCatalogDictionary(resource.resourceId, replace = false)

            assertTrue(harness.bridge.requestsOfType("resource.dictionary.import").isEmpty())

            harness.manager.installCatalogDictionary(resource.resourceId, replace = true)

            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            assertTrue(harness.manager.state.value.catalogDictionaries.first().installed)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun aSchemaStaleDictionaryIsRebuiltAtStartupFromItsRetainedArchive() =
        runTest {
            // Issue #13: the schema 4->6 move flagged every pre-0.8.0 dictionary
            // stale, and the startup rebuild only covered frequency and pitch even
            // though every slot retains its source.zip.
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-dictionary",
                    installedCustomDictionaryValid = false,
                    installedCustomDictionaryRebuildPath =
                        "/data/user/0/files/dicts/fixture-dictionary/source.zip",
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(harness.manager.state.value.dictionaries.single().schemaOk)
            assertNull(harness.manager.state.value.failure)
            val request = harness.bridge.requestsOfType("resource.dictionary.import").single()
            assertTrue(request.contains("\"slotId\":\"fixture-dictionary\""))
            assertTrue(request.contains("\"overwrite\":true"))
        }

    @Test
    fun aFailedDictionaryRebuildDegradesToTheReplaceRetryInsteadOfWedgingRecovery() =
        runTest {
            // A retained archive the current importer refuses must not abort the
            // whole recovery pass: inventory still publishes and the failure maps
            // to the replace retry that can actually repair the slot.
            val harness =
                Harness(
                    rootName = "manager-rebuild-stale-dictionary-fails",
                    installedCustomDictionaryValid = false,
                    installedCustomDictionaryRebuildPath =
                        "/data/user/0/files/dicts/fixture-dictionary/source.zip",
                    failDictionaryImport = true,
                    autoRecover = false,
                )

            harness.manager.recoverAndRefresh()

            assertEquals(
                ResourceStartupReadiness.FAILED,
                harness.manager.state.value.startupReadiness,
            )
            assertTrue(harness.manager.state.value.dictionaries.isNotEmpty())
            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("dictionary_resource_invalid", failure.code)
            assertEquals(ResourceFailureOrigin.CUSTOM_DICTIONARY, failure.origin)
        }

    @Test
    fun customDictionaryReplaceRunsFromFailedReadinessButPlainImportRemainsBlocked() =
        runTest {
            val harness =
                Harness(
                    rootName = "manager-custom-replace-failed",
                    sourceLabel = "dictionary archive",
                    installedCustomDictionaryValid = false,
                )
            assertEquals(ResourceStartupReadiness.FAILED, harness.manager.state.value.startupReadiness)

            harness.manager.importCustomDictionary(INPUT_URI, "fixture-dictionary", replace = false)

            assertTrue(harness.bridge.requestsOfType("resource.dictionary.import").isEmpty())

            harness.manager.importCustomDictionary(INPUT_URI, "fixture-dictionary", replace = true)

            assertEquals(1, harness.bridge.requestsOfType("resource.dictionary.import").size)
            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun aZeroEntryDictionaryStaysOutOfTheChainWithoutFailingStartup() =
        runTest {
            // A 0-entry index is intact — startup recovery must not treat it as corruption —
            // but the engine's provider gate needs entry_count > 0, so the chain excludes it.
            val harness =
                Harness(
                    rootName = "manager-zero-entry-dictionary",
                    installedCustomDictionaryValid = true,
                    installedCustomDictionaryEntryCount = 0,
                )

            assertEquals(ResourceStartupReadiness.READY, harness.manager.state.value.startupReadiness)
            val dictionary = harness.manager.state.value.dictionaries.single()
            assertTrue(dictionary.isUsable)
            assertFalse(dictionary.isChainEligible)
            assertTrue(harness.manager.installedDictionaryIds().isEmpty())
        }

    @Test
    fun deleteDoesNotWriteTheRecoveryJournal() =
        runTest {
            // A journalled delete that succeeded before process death would be reported as an
            // interrupted operation on next launch: recovery only recognises UniDic and catalog
            // dictionary imports as already committed.
            val harness = Harness(rootName = "manager-delete-journal", installedPitchSourceId = "kanjium")

            harness.manager.deleteInstalledResource(InstalledResourceKind.PITCH, "kanjium")

            assertFalse(ResourceOperationJournal(harness.root, {}).exists())
        }

    @Test
    fun failedDeleteOffersRetryCarryingTheDeleteTarget() =
        runTest {
            val harness =
                Harness(
                    rootName = "manager-delete-failure",
                    installedPitchSourceId = "kanjium",
                    bridgeFailureCode = "resource_cleanup_failed",
                    autoRecover = false,
                )

            harness.manager.deleteInstalledResource(InstalledResourceKind.PITCH, "kanjium")

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_cleanup_failed", failure.code)
            assertTrue(failure.retryable)
            assertEquals(ResourceFailureOrigin.PITCH, failure.origin)
            assertEquals(
                ResourceDeleteTarget(InstalledResourceKind.PITCH, "kanjium"),
                failure.deleteTarget,
            )
        }

    @Test
    fun deleteWhileTheRuntimeLeaseIsHeldRecordsResourceBusy() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val harness =
                Harness(
                    rootName = "manager-delete-busy",
                    runtimeWorkCoordinator = coordinator,
                    installedPitchSourceId = "kanjium",
                )
            val lease = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))

            harness.manager.deleteInstalledResource(InstalledResourceKind.PITCH, "kanjium")

            assertEquals("resource_busy", harness.manager.state.value.failure?.code)
            assertEquals(listOf("kanjium"), harness.manager.state.value.pitchSources.map { it.sourceId })
            lease.close()
        }

    @Test
    fun aShortResourceImportRaisesNoNotification() =
        runTest {
            val harness = Harness()

            harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)

            assertTrue(harness.foregroundLease.events.isEmpty())
        }

    @Test
    fun theRecommendedSetInstallsDictionaryThenFrequencyThenPitchInOneOperation() =
        runTest {
            val harness = Harness(fakePinnedDownloads = true)

            harness.manager.installRecommendedResources()

            assertNull(harness.manager.state.value.failure)
            assertEquals(
                listOf(
                    "resource.dictionary.import",
                    "resource.frequency.import",
                    "resource.pitch.import",
                ),
                harness.bridge.requestTypes.filter { it.endsWith(".import") },
            )
            assertTrue(harness.manager.state.value.recommendedPlan.isSatisfied)
        }

    @Test
    fun theRecommendedSetHoldsOneForegroundLeaseAndOneJournalRecord() =
        runTest {
            val harness = Harness(fakePinnedDownloads = true)

            harness.manager.installRecommendedResources()

            assertEquals(1, harness.foregroundLease.events.count { it.startsWith("start:") })
            assertEquals(1, harness.foregroundLease.events.count { it == "stop" })
            assertFalse(harness.foregroundLease.isRunning())
            assertFalse(ResourceOperationJournal(harness.root).exists())
        }

    @Test
    fun aSatisfiedRecommendedSetDispatchesNothingOnASecondPress() =
        runTest {
            val harness = Harness(fakePinnedDownloads = true)
            harness.manager.installRecommendedResources()
            assertEquals(3, harness.bridge.requestTypes.count { it.endsWith(".import") })
            harness.bridge.clearRequests()
            harness.foregroundLease.events.clear()

            harness.manager.installRecommendedResources()

            assertTrue(harness.bridge.requestTypes.none { it.endsWith(".import") })
            assertTrue(harness.foregroundLease.events.isEmpty())
        }

    @Test
    fun theRecommendedSetRepairsWhatFailedStartupAndOverwritesOnlyThat() =
        runTest {
            // A schema-stale pitch index is one of the things that fails startup, and this button
            // is the wizard's only install affordance: gating it on READY would make the press a
            // silent no-op in the very state its REPLACE members exist to repair.
            val harness =
                Harness(
                    fakePinnedDownloads = true,
                    installedPitchSourceId = "kanjium",
                    installedPitchSchemaOk = false,
                )
            assertEquals(
                ResourceStartupReadiness.FAILED,
                harness.manager.state.value.startupReadiness,
            )

            harness.manager.installRecommendedResources()

            // The missing members are fresh installs; only the stale one is overwritten.
            assertTrue(
                harness.bridge.requestsOfType("resource.dictionary.import").single()
                    .contains("\"overwrite\":false"),
            )
            assertTrue(
                harness.bridge.requestsOfType("resource.pitch.import").single()
                    .contains("\"overwrite\":true"),
            )
            assertNull(harness.manager.state.value.failure)
            assertEquals(
                ResourceStartupReadiness.READY,
                harness.manager.state.value.startupReadiness,
            )
        }

    @Test
    fun oneFailedMemberNeitherAbortsTheBatchNorHidesItself() =
        runTest {
            val harness = Harness(fakePinnedDownloads = true, failFrequencyImport = true)

            harness.manager.installRecommendedResources()

            // The pitch member still ran even though frequency failed before it.
            assertEquals(1, harness.bridge.requestsOfType("resource.pitch.import").size)
            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("recommended_set_incomplete", failure.code)
            assertEquals(ResourceFailureOrigin.RECOMMENDED_SET, failure.origin)
            assertEquals(ResourceFailureAction.RETRY, failure.retry.action)
            assertTrue(failure.retryable)
            // Retry re-runs the batch and installs only what is still missing.
            assertEquals(
                listOf("jpdb-v2.2-kana-2024-10-13"),
                harness.manager.state.value.recommendedPlan.pending.map { it.resource.resourceId },
            )
        }

    @Test
    fun anInterruptedRecommendedSetReportsNothingWhenEveryMemberLanded() =
        runTest {
            // A kill after the last member still leaves the journal record behind, so replay must
            // read the satisfied plan rather than announce an interruption that changed nothing.
            val harness = Harness(fakePinnedDownloads = true)
            harness.manager.installRecommendedResources()
            assertTrue(harness.manager.state.value.recommendedPlan.isSatisfied)
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.RECOMMENDED_SET,
                    retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                ),
            )

            harness.manager.recoverAndRefresh()

            assertNull(harness.manager.state.value.failure)
        }

    @Test
    fun anInterruptedRecommendedSetOffersRetryWhileAMemberIsMissing() =
        runTest {
            val harness = Harness(autoRecover = false)
            ResourceOperationJournal(harness.root, syncDirectory = {}).write(
                PersistedResourceOperation(
                    origin = ResourceFailureOrigin.RECOMMENDED_SET,
                    retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                ),
            )

            harness.manager.recoverAndRefresh()

            val failure = requireNotNull(harness.manager.state.value.failure)
            assertEquals("resource_operation_interrupted", failure.code)
            assertEquals(ResourceFailureOrigin.RECOMMENDED_SET, failure.origin)
        }

    private inner class Harness(
        rootName: String = "manager",
        initialUserCount: Int = 0,
        runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
        sourceLabel: String = "known-word file",
        reportedSourceSizeBytes: Long? = 16,
        sourceDisplayName: String = "known-words.json",
        sourceMimeType: String? = "application/json",
        stagingAvailableBytes: Long = Long.MAX_VALUE / 2,
        stagingFailureCode: String? = null,
        stagingFailureDetail: String? = null,
        bridgeFailureCode: String? = null,
        installedPitchSourceId: String? = null,
        installedPitchSchemaOk: Boolean = true,
        installedPitchRebuildPath: String? = null,
        installedCatalogDictionaryValid: Boolean? = null,
        installedCustomDictionaryValid: Boolean? = null,
        installedCustomDictionaryRebuildPath: String? = null,
        installedCustomDictionaryEntryCount: Long = 1,
        failDictionaryImport: Boolean = false,
        failFrequencyImport: Boolean = false,
        autoRecover: Boolean = true,
        resourceExecutor: Executor = DIRECT_EXECUTOR,
        controlExecutor: Executor = DIRECT_EXECUTOR,
        fakePinnedDownloads: Boolean = false,
        failRefreshAfterDictionaryImport: Boolean = false,
        failRefreshAfterMutation: String? = null,
        wordListMover: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
        resourceDirectorySync: (File) -> Unit = {},
        failKnownWordsImportOnce: Boolean = false,
        knownWordsPreviewFailureCode: String? = null,
        failKnownWordsRemoveOnce: Boolean = false,
        failKnownWordsResetOnce: Boolean = false,
        failMinedWordsRemoveOnce: Boolean = false,
        failCancelDelivery: Boolean = false,
        onKnownWordsRemoveDispatch: () -> Unit = {},
        onFirstExportWrite: () -> Unit = {},
        onRetainReadAccess: () -> Unit = {},
        onReleaseReadAccess: (String) -> Unit = {},
        committedDictionaryDecodeFailure: Boolean = false,
        committedFrequencyDecodeFailure: Boolean = false,
        committedPitchDecodeFailure: Boolean = false,
        audioReadMode: AudioArchiveReadMode = AudioArchiveReadMode.RAW,
        audioContainer: AudioArchiveContainer = AudioArchiveContainer.ZIP,
        safSelectionInventory: SafSelectionInventory = TransientSafSelectionInventory(),
        foregroundStartFailure: Boolean = false,
    ) {
        val root = temporary.newFolder(rootName)
        val bridgeRoot = File(root, "bridge").apply { mkdirs() }
        val stagingRoot = File(root, "staging").apply { mkdirs() }
        val downloadRoot = File(root, "downloads")
        val pendingRoot = File(root, "resource-pending-known-words")
        val audioPendingRoot = File(root, "resource-pending-audio-pack")
        val broker =
            RecordingSafBroker(
                reportedSourceSizeBytes,
                sourceDisplayName,
                sourceMimeType,
                onRetainReadAccess,
                onReleaseReadAccess,
            )
        val stager =
            RecordingArchiveStager(
                stagingRoot,
                sourceLabel,
                stagingFailureCode,
                stagingFailureDetail,
                audioReadMode,
                audioContainer,
            )
        val writer = RecordingDocumentWriter(onFirstExportWrite)
        val foregroundLease = RecordingForegroundLease().also { it.failStart = foregroundStartFailure }
        val bridge =
            FakeResourceBridge(
                bridgeRoot,
                initialUserCount,
                bridgeFailureCode,
                installedPitchSourceId,
                installedPitchSchemaOk,
                installedPitchRebuildPath,
                installedCatalogDictionaryValid,
                installedCustomDictionaryValid,
                installedCustomDictionaryRebuildPath,
                installedCustomDictionaryEntryCount,
                failDictionaryImport,
                failFrequencyImport,
                failRefreshAfterDictionaryImport,
                failRefreshAfterMutation,
                failKnownWordsImportOnce,
                knownWordsPreviewFailureCode,
                failKnownWordsRemoveOnce,
                failKnownWordsResetOnce,
                failMinedWordsRemoveOnce,
                failCancelDelivery,
                onKnownWordsRemoveDispatch,
                committedDictionaryDecodeFailure,
                committedFrequencyDecodeFailure,
                committedPitchDecodeFailure,
            )
        val manager =
            AndroidResourceManager(
                safBroker = broker,
                bridge = bridge,
                tokenizerResources = { null },
                bridgeFilesRoot = bridgeRoot,
                stagingRoot = stagingRoot,
                resourceExecutor = resourceExecutor,
                controlExecutor = controlExecutor,
                runtimeWorkCoordinator = runtimeWorkCoordinator,
                downloader =
                    PinnedResourceDownloader(
                        downloadRoot,
                        connections = DownloadConnectionFactory { _, _ -> error("network not expected") },
                        availableBytes = { Long.MAX_VALUE / 2 },
                    ),
                safStager = stager,
                documentWriter = writer,
                safSelectionInventory = safSelectionInventory,
                foregroundLease = foregroundLease,
                strings = testStringResourceResolver,
                stagingAvailableBytes = { stagingAvailableBytes },
                pinnedArchiveProvider =
                    if (fakePinnedDownloads) {
                        PinnedArchiveProvider { archive, cancellation, _ ->
                            cancellation.check()
                            val file = File(root, "fake-${archive.sha256}.zip")
                            val frequency = FrozenResourceCatalog.value.frequencies.single().archive
                            if (archive == frequency) {
                                // validateFrequencyArchiveMetadata reads this before dispatch, so a
                                // placeholder would fail the member for the wrong reason.
                                file.writeBytes(fixtureFrequencyArchiveBytes())
                            } else {
                                file.writeText("fixture")
                            }
                            StagedArchive(file, archive.sha256, archive.sizeBytes)
                        }
                    } else {
                        null
                    },
                wordListMover = wordListMover,
                resourceDirectorySync = resourceDirectorySync,
            )

        init {
            if (autoRecover) {
                kotlinx.coroutines.runBlocking { manager.recoverAndRefresh() }
                bridge.clearRequests()
            }
        }
    }

    private class RecordingSafBroker(
        private val reportedSizeBytes: Long? = 16,
        private val displayName: String = "known-words.json",
        private val mimeType: String? = "application/json",
        private val onRetainReadAccess: () -> Unit = {},
        private val onReleaseReadAccess: (String) -> Unit = {},
    ) : SafBroker {
        val retained = mutableListOf<String>()
        val released = mutableListOf<String>()

        override suspend fun retainReadAccess(uri: String): SafDocument {
            onRetainReadAccess()
            retained += uri
            return SafDocument(uri, displayName, mimeType, reportedSizeBytes)
        }

        override suspend fun releaseReadAccess(uri: String) {
            onReleaseReadAccess(uri)
            released += uri
        }

        override fun releaseReadAccessEventually(uri: String) = Unit
    }

    private class RecordingResourceImportInventory(
        private val events: MutableList<String> = mutableListOf(),
        private val delegate: TransientSafSelectionInventory = TransientSafSelectionInventory(),
    ) : SafSelectionInventory by delegate {
        var failResourceImportClear = false

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            if (slot == SafSelectionSlot.RESOURCE_IMPORT && selection == null) {
                events += "clear:${slot.name}"
                if (failResourceImportClear) {
                    throw IOException("injected resource-import clear failure")
                }
            }
            delegate.putSelection(slot, selection)
        }
    }

    private class RecordingArchiveStager(
        private val stagingRoot: File,
        private val expectedSourceLabel: String = "known-word file",
        private val failureCode: String? = null,
        private val failureDetail: String? = null,
        private val audioReadMode: AudioArchiveReadMode = AudioArchiveReadMode.RAW,
        private val audioContainer: AudioArchiveContainer = AudioArchiveContainer.ZIP,
    ) : ResourceArchiveStager {
        val stagedFiles = mutableListOf<File>()
        var lastMaximumBytes: Long? = null
        var sourceText: String = "fixture"
        var sourceBytes: ByteArray? = null
        var lastSourceLabel: String? = null
        var genericStageCalls = 0
            private set
        var audioStageCalls = 0
            private set

        override suspend fun readLeadingBytes(
            sourceUri: String,
            maximumBytes: Int,
        ): ByteArray {
            val bytes = sourceText.encodeToByteArray()
            return bytes.copyOf(minOf(maximumBytes, bytes.size))
        }

        override fun stage(
            sourceUri: String,
            operationId: String,
            cancellation: ResourceCancellationSignal,
            fileSuffix: String,
            maximumBytes: Long,
            sourceLabel: String,
            onProgress: (Long, Long) -> Unit,
        ): StagedArchive {
            genericStageCalls++
            return writeStage(
                sourceUri,
                operationId,
                cancellation,
                fileSuffix,
                maximumBytes,
                sourceLabel,
                onProgress,
            )
        }

        override fun stageAudioArchive(
            sourceUri: String,
            operationId: String,
            cancellation: ResourceCancellationSignal,
            maximumBytes: Long,
            sourceLabel: String,
            onProgress: (Long, Long) -> Unit,
        ): StagedAudioArchive {
            audioStageCalls++
            return StagedAudioArchive(
                archive =
                    writeStage(
                        sourceUri,
                        operationId,
                        cancellation,
                        ".bin",
                        maximumBytes,
                        sourceLabel,
                        onProgress,
                    ),
                readMode = audioReadMode,
                container = audioContainer,
            )
        }

        private fun writeStage(
            sourceUri: String,
            operationId: String,
            cancellation: ResourceCancellationSignal,
            fileSuffix: String,
            maximumBytes: Long,
            sourceLabel: String,
            onProgress: (Long, Long) -> Unit,
        ): StagedArchive {
            assertEquals(INPUT_URI, sourceUri)
            assertEquals(expectedSourceLabel, sourceLabel)
            lastSourceLabel = sourceLabel
            failureCode?.let {
                throw ResourceDownloadException(
                    it,
                    "cancelled",
                    failureDetail?.let(::IOException),
                )
            }
            lastMaximumBytes = maximumBytes
            cancellation.check()
            val file = File(stagingRoot, "$operationId-custom$fileSuffix")
            val bytes = sourceBytes
            if (bytes == null) {
                file.writeText(sourceText, Charsets.UTF_8)
            } else {
                file.writeBytes(bytes)
            }
            stagedFiles += file
            onProgress(file.length(), file.length())
            return StagedArchive(file, "0".repeat(64), file.length())
        }
    }

    private class RecordingDocumentWriter(
        private val onFirstWrite: () -> Unit,
    ) : ResourceDocumentWriter {
        val openedUris = mutableListOf<String>()
        val deletedUris = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        var closeCount = 0
            private set
        private var notifiedWrite = false

        override fun open(uri: String): OutputStream {
            openedUris += uri
            return object : OutputStream() {
                override fun write(value: Int) {
                    notifyWrite()
                    output.write(value)
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    notifyWrite()
                    output.write(bytes, offset, length)
                }

                override fun flush() {
                    output.flush()
                }

                override fun close() {
                    closeCount += 1
                }
            }
        }

        override fun delete(uri: String): Boolean {
            deletedUris += uri
            return true
        }

        private fun notifyWrite() {
            if (!notifiedWrite) {
                notifiedWrite = true
                onFirstWrite()
            }
        }
    }

    private class FakeResourceBridge(
        private val bridgeFilesRoot: File,
        initialUserCount: Int,
        private val failureCode: String? = null,
        installedPitchSourceId: String?,
        installedPitchSchemaOk: Boolean = true,
        private val installedPitchRebuildPath: String? = null,
        installedCatalogDictionaryValid: Boolean?,
        installedCustomDictionaryValid: Boolean?,
        private val installedCustomDictionaryRebuildPath: String? = null,
        private val installedCustomDictionaryEntryCount: Long = 1,
        private val failDictionaryImport: Boolean = false,
        private val failFrequencyImport: Boolean = false,
        private val failRefreshAfterDictionaryImport: Boolean,
        private val failRefreshAfterMutation: String?,
        failKnownWordsImportOnce: Boolean,
        private val knownWordsPreviewFailureCode: String?,
        failKnownWordsRemoveOnce: Boolean,
        failKnownWordsResetOnce: Boolean,
        failMinedWordsRemoveOnce: Boolean,
        private val failCancelDelivery: Boolean,
        private val onKnownWordsRemoveDispatch: () -> Unit,
        private val committedDictionaryDecodeFailure: Boolean = false,
        private val committedFrequencyDecodeFailure: Boolean = false,
        private val committedPitchDecodeFailure: Boolean = false,
    ) : PyBridge {
        private val requests = mutableListOf<String>()
        var userCount = initialUserCount
            private set

        /** Mutable so a delete can drop the slot the next inventory reports. */
        private var installedPitchSourceId: String? = installedPitchSourceId
        private var installedPitchSchemaOk: Boolean = installedPitchSchemaOk
        private var installedFrequencySourceId: String? = null
        private var installedAudioPackId: String? = null
        private var catalogDictionaryInstalled = installedCatalogDictionaryValid != null

        /** Which catalog dictionary the inventory echoes; an import retargets it. */
        private var catalogDictionaryResourceId =
            FrozenResourceCatalog.value.dictionaries.first().resourceId
        private var catalogDictionaryValid = installedCatalogDictionaryValid ?: true
        private var customDictionaryInstalled = installedCustomDictionaryValid != null
        private var customDictionaryValid = installedCustomDictionaryValid ?: true
        private var failNextDictionaryList = false
        private var failNextLocalList = false
        private var knownWordsImportFailures = if (failKnownWordsImportOnce) 1 else 0
        private var knownWordsRemoveFailures = if (failKnownWordsRemoveOnce) 1 else 0
        private var knownWordsResetFailures = if (failKnownWordsResetOnce) 1 else 0
        private var minedWordsRemoveFailures = if (failMinedWordsRemoveOnce) 1 else 0
        var lastExportFile: File? = null
            private set
        var emptyAudioPackPreflight = false

        /** Invoked, when set, for every dispatch that receives a non-null callbacks object. */
        var progressInjector: ((rawRequest: String, callbacks: EngineCallbacks) -> Unit)? = null

        /** (request type, callbacks != null) for every dispatch, in call order. */
        private val callbackPresence = mutableListOf<Pair<String, Boolean>>()

        val requestTypes: List<String>
            get() = requests.map(::requestType)

        fun requestsOfType(type: String): List<String> =
            requests.filter { requestType(it) == type }

        /** Whether each dispatch of [type], in call order, carried a non-null callbacks object. */
        fun callbackPresenceForType(type: String): List<Boolean> =
            callbackPresence.filter { it.first == type }.map { it.second }

        fun clearRequests() {
            requests.clear()
            callbackPresence.clear()
        }

        override fun dispatch(rawRequest: String, callbacks: EngineCallbacks?): String {
            callbackPresence += requestType(rawRequest) to (callbacks != null)
            callbacks?.let { progressInjector?.invoke(rawRequest, it) }
            requests += rawRequest
            failureCode?.let { throw ResourceBridgeException(it, "cancelled") }
            return when (requestType(rawRequest)) {
                "resource.catalog.get" -> catalogResponse()
                "resource.dictionary.list" -> dictionaryListResponse()
                "resource.local.list" -> inventoryResponse()
                "resource.cleanup" ->
                    envelope("resource.cleanup.result", """{"clean":true}""")
                "resource.unidic.install" -> {
                    val expected = FrozenResourceCatalog.value.unidic
                    envelope(
                        "resource.unidic.installed",
                        """{"resourceId":"${expected.resourceId}","dicDir":"/data/user/0/files/dicdir","treeSha256":"${expected.install.treeSha256}","fileCount":${expected.install.fileCount},"sizeBytes":${expected.install.sizeBytes},"alreadyInstalled":false,"attribution":${attributionJson(expected.attribution)}}""",
                    )
                }
                "resource.dictionary.import" -> {
                    if (failDictionaryImport) {
                        throw ResourceBridgeException(
                            "dictionary_import_failed",
                            "simulated import failure",
                        )
                    }
                    val requestedCatalogId = nullableStringField(rawRequest, "catalogResourceId")
                    if (customDictionaryInstalled && requestedCatalogId == null) {
                        // A real reimport rebuilds the index at the current schema,
                        // so the slot stops being stale.
                        customDictionaryValid = true
                    } else {
                        requestedCatalogId?.let { catalogDictionaryResourceId = it }
                        catalogDictionaryInstalled = true
                        catalogDictionaryValid = true
                    }
                    failNextDictionaryList =
                        failRefreshAfterDictionaryImport ||
                            failRefreshAfterMutation == "resource.dictionary.import"
                    if (committedDictionaryDecodeFailure) {
                        // Python has already published the slot; the response carries a shape
                        // Kotlin refuses, which must not lose the committed install.
                        envelope(
                            "resource.dictionary.imported",
                            """{"slotId":"revisionless","catalogResourceId":null,"sourceName":"Revisionless","sourceRevision":"","entryCount":1,"skippedMalformed":0,"mediaWarnings":[],"archiveSha256":"${"0".repeat(64)}","attribution":[],"unexpected":true}""",
                        )
                    } else {
                        importedDictionaryResponse()
                    }
                }
                "resource.dictionary.preflight" ->
                    envelope(
                        "resource.dictionary.preflighted",
                        """{"slotId":"fixture-dictionary-2026-08"}""",
                    )
                "resource.frequency.import" -> {
                    if (failFrequencyImport) {
                        throw ResourceBridgeException(
                            "frequency_import_failed",
                            "simulated frequency import failure",
                        )
                    }
                    installedFrequencySourceId = stringField(rawRequest, "sourceId")
                    armLocalRefreshFailure("resource.frequency.import")
                    val revision = if (committedFrequencyDecodeFailure) "a".repeat(4097) else "1"
                    envelope(
                        "resource.frequency.imported",
                        """{"sourceId":"${stringField(rawRequest, "sourceId")}","sourceName":"${stringField(rawRequest, "sourceName")}","sourceRevision":"$revision","format":"csv","entryCount":1,"skippedDisplayOnly":0,"skippedMalformed":0,"convertedToRanks":false,"isCategorical":false,"archiveSha256":"${"0".repeat(64)}"}""",
                    )
                }
                "resource.pitch.import" -> {
                    installedPitchSourceId = stringField(rawRequest, "sourceId")
                    // A real reimport rebuilds the index at the current schema,
                    // so the slot stops being stale.
                    installedPitchSchemaOk = true
                    armLocalRefreshFailure("resource.pitch.import")
                    if (committedPitchDecodeFailure) {
                        // Same shape as the dictionary case: the slot is published before Kotlin
                        // rejects the response, so inventory must still reconcile.
                        envelope(
                            "resource.pitch.imported",
                            """{"sourceId":"fixture-pitch","sourceName":"Fixture Pitch","sourceRevision":"1","sourceFormat":"unknown-installed-format","entryCount":1,"skippedDisplayOnly":0,"skippedMalformed":0,"archiveSha256":"${"0".repeat(64)}"}""",
                        )
                    } else {
                        envelope(
                            "resource.pitch.imported",
                            """{"sourceId":"fixture-pitch","sourceName":"Fixture Pitch","sourceRevision":"1","sourceFormat":"yomitan-pitch","entryCount":1,"skippedDisplayOnly":0,"skippedMalformed":0,"archiveSha256":"${"0".repeat(64)}"}""",
                        )
                    }
                }
                "resource.knownwords.preview" -> {
                    knownWordsPreviewFailureCode?.let {
                        throw ResourceBridgeException(it, "simulated known-word parse rejection")
                    }
                    envelope(
                        "resource.knownwords.previewed",
                        """{"format":"migaku_json","importedCount":2,"totalEntries":3,"isGeneric":false,"sampleWords":["犬","猫"]}""",
                    )
                }
                "resource.knownwords.import" -> {
                    if (knownWordsImportFailures > 0) {
                        knownWordsImportFailures -= 1
                        error("simulated known-word import failure")
                    }
                    userCount = 2
                    armLocalRefreshFailure("resource.knownwords.import")
                    envelope(
                        "resource.knownwords.imported",
                        """{"format":"migaku_json","importedCount":2,"newRowCount":2,"totalEntries":3,"isGeneric":false}""",
                    )
                }
                "resource.local.delete" -> {
                    val slotId = stringField(rawRequest, "slotId")
                    val removed = installedPitchSourceId == slotId
                    if (removed) installedPitchSourceId = null
                    envelope(
                        "resource.local.deleted",
                        """{"kind":"${stringField(rawRequest, "kind")}","slotId":"$slotId","removed":$removed}""",
                    )
                }
                "resource.dictionary.delete" -> {
                    val slotId = stringField(rawRequest, "slotId")
                    val removed = catalogDictionaryInstalled
                    catalogDictionaryInstalled = false
                    envelope(
                        "resource.dictionary.deleted",
                        """{"slotId":"$slotId","removed":$removed}""",
                    )
                }
                "resource.knownwords.list" -> pageResponse(rawRequest)
                "resource.knownwords.remove" -> {
                    onKnownWordsRemoveDispatch()
                    if (knownWordsRemoveFailures > 0) {
                        knownWordsRemoveFailures -= 1
                        error("simulated known-word remove failure")
                    }
                    userCount = (userCount - 1).coerceAtLeast(0)
                    armLocalRefreshFailure("resource.knownwords.remove")
                    envelope("resource.knownwords.removed", """{"removedCount":1}""")
                }
                "resource.minedwords.remove" -> {
                    if (minedWordsRemoveFailures > 0) {
                        minedWordsRemoveFailures -= 1
                        error("simulated mined-word remove failure")
                    }
                    val requestedWords = wordsField(rawRequest)
                    armLocalRefreshFailure("resource.minedwords.remove")
                    envelope("resource.minedwords.removed", """{"removedCount":${requestedWords.size}}""")
                }
                "resource.knownwords.reset" -> {
                    if (knownWordsResetFailures > 0) {
                        knownWordsResetFailures -= 1
                        error("simulated known-word reset failure")
                    }
                    userCount = 0
                    armLocalRefreshFailure("resource.knownwords.reset")
                    envelope(
                        "resource.knownwords.reset",
                        """{"scope":"${stringField(rawRequest, "scope")}","removedCount":1}""",
                    )
                }
                "resource.knownwords.export" -> exportResponse(rawRequest)
                "resource.operation.cancel" -> {
                    if (failCancelDelivery) error("simulated cancel delivery failure")
                    envelope(
                        "resource.operation.cancel.result",
                        """{"operationId":"${stringField(rawRequest, "operationId")}","accepted":true}""",
                    )
                }
                "resource.audiopack.preflight" ->
                    envelope(
                        "resource.audiopack.preflighted",
                        if (emptyAudioPackPreflight) {
                            """{"packs":[]}"""
                        } else {
                            """{"packs":[{"packId":"jpod","packPath":"jpod_files","format":"ajt"}]}"""
                        },
                    )
                "resource.audiopack.import" -> {
                    installedAudioPackId = stringField(rawRequest, "packId")
                    armLocalRefreshFailure("resource.audiopack.import")
                    envelope(
                        "resource.audiopack.imported",
                        """{"packId":"jpod","sourceName":"jpod_files","format":"jpod_legacy","entryCount":12,"archiveSha256":"${"0".repeat(64)}"}""",
                    )
                }
                else -> error("Unexpected request: $rawRequest")
            }
        }

        private fun armLocalRefreshFailure(requestType: String) {
            if (failRefreshAfterMutation == requestType) failNextLocalList = true
        }

        private fun dictionaryListResponse(): String {
            if (failNextDictionaryList) {
                failNextDictionaryList = false
                error("simulated inventory failure after commit")
            }
            val entries = mutableListOf<String>()
            if (catalogDictionaryInstalled) {
                val resource = catalogDictionaryOf(catalogDictionaryResourceId)
                // The decoder checks an installed catalog dictionary against the frozen
                // catalog identity, attribution included, so echo the catalog's own list.
                entries +=
                    """{"slotId":"${resource.slotId}","occupied":true,"valid":$catalogDictionaryValid,"sourceName":"${resource.dictionary.title}","sourceRevision":"${resource.dictionary.revision}","format":"${if (catalogDictionaryValid) "yomitan" else "unknown"}","entryCount":${if (catalogDictionaryValid) 1 else 0},"schemaOk":$catalogDictionaryValid,"embeddedAttribution":{},"catalogResourceId":"${resource.resourceId}","attribution":${attributionJson(resource.attribution)},"rebuildSourcePath":null}"""
            }
            if (customDictionaryInstalled) {
                val rebuildPath =
                    installedCustomDictionaryRebuildPath?.let { "\"$it\"" } ?: "null"
                entries +=
                    """{"slotId":"fixture-dictionary","occupied":true,"valid":$customDictionaryValid,"sourceName":"Fixture Dictionary","sourceRevision":"1","format":"${if (customDictionaryValid) "yomitan" else "unknown"}","entryCount":${if (customDictionaryValid) installedCustomDictionaryEntryCount else 0},"schemaOk":$customDictionaryValid,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[],"rebuildSourcePath":$rebuildPath}"""
            }
            val dictionaries = entries.joinToString(",", prefix = "[", postfix = "]")
            return envelope(
                "resource.dictionary.listed",
                """{"dictionaries":$dictionaries}""",
            )
        }

        private fun catalogDictionaryOf(resourceId: String): YomitanCatalogResource =
            FrozenResourceCatalog.value.dictionary(resourceId)
                ?: error("fake asked for a dictionary outside the frozen catalog: $resourceId")

        private fun importedDictionaryResponse(): String {
            val resource = catalogDictionaryOf(catalogDictionaryResourceId)
            return envelope(
                "resource.dictionary.imported",
                """{"slotId":"${resource.slotId}","catalogResourceId":"${resource.resourceId}","sourceName":"${resource.dictionary.title}","sourceRevision":"${resource.dictionary.revision}","entryCount":1,"skippedMalformed":0,"mediaWarnings":[],"archiveSha256":"${resource.archive.sha256}","attribution":[]}""",
            )
        }

        private fun inventoryResponse(): String {
            if (failNextLocalList) {
                failNextLocalList = false
                error("simulated inventory failure after commit")
            }
            val frequencies =
                installedFrequencySourceId?.let { sourceId ->
                    """[{"sourceId":"$sourceId","sourceName":"Fixture Frequency","format":"csv","entryCount":1,"schemaOk":true,"schemaVersion":1,"isCategorical":false,"rebuildSourcePath":null}]"""
                } ?: "[]"
            val pitchSources =
                installedPitchSourceId?.let { sourceId ->
                    """[{"sourceId":"$sourceId","sourceName":"Kanjium","sourceRevision":"1","format":"yomitan","entryCount":10,"schemaOk":$installedPitchSchemaOk,"schemaVersion":1,"rebuildSourcePath":${installedPitchRebuildPath?.let { "\"$it\"" } ?: "null"}}]"""
                } ?: "[]"
            val audioPacks =
                installedAudioPackId?.let { packId ->
                    """[{"packId":"$packId","sourceName":"jpod_files","format":"jpod_legacy","entryCount":12,"contentAvailable":true}]"""
                } ?: "[]"
            return envelope(
                "resource.local.listed",
                """{"frequencies":$frequencies,"pitchSources":$pitchSources,"audioPacks":$audioPacks,"knownWords":{"totalCount":$userCount,"userCount":$userCount,"ankiCount":0,"minedCount":0,"schemaOk":true},"wordsets":[]}""",
            )
        }

        private fun pageResponse(rawRequest: String): String {
            val query = stringField(rawRequest, "query")
            val offset = intField(rawRequest, "offset")
            val words =
                when {
                    query == "食" -> listOf("食べる")
                    query == "mutable" -> List(userCount) { index -> "mutable$index" }
                    offset == 0 -> List(100) { index -> "word$index" }
                    else -> listOf("word100")
                }
            val totalCount =
                when (query) {
                    "食" -> 1
                    "mutable" -> userCount
                    else -> 101
                }
            val hasMore = query.isEmpty() && offset == 0
            val wordsJson = words.joinToString(separator = ",") { "\"$it\"" }
            return envelope(
                "resource.knownwords.listed",
                """{"query":"$query","offset":$offset,"totalCount":$totalCount,"words":[$wordsJson],"hasMore":$hasMore}""",
            )
        }

        private fun exportResponse(rawRequest: String): String {
            val operationId = stringField(rawRequest, "operationId")
            val operationRoot = File(bridgeFilesRoot, "resource-work/operations/$operationId")
            check(operationRoot.mkdirs())
            val export = File(operationRoot, "known_words.txt")
            export.writeText("犬\n猫\n", Charsets.UTF_8)
            lastExportFile = export
            return envelope(
                "resource.knownwords.exported",
                """{"exportPath":"${export.canonicalPath}","exportedCount":2,"sizeBytes":${export.length()}}""",
            )
        }

        private fun catalogResponse(): String {
            val payload =
                checkNotNull(
                    ResourceManagerTest::class.java.getResourceAsStream("/resource_catalog_v1.json"),
                ) { "resource catalog fixture missing" }
                    .bufferedReader()
                    .use { it.readText().trim() }
            return envelope("resource.catalog", payload)
        }
    }

    companion object {
        private const val INPUT_URI = "content://fixtures/known-words.json"
        private const val EXPORT_URI = "content://fixtures/known-words-export.txt"
        private val DIRECT_EXECUTOR = Executor { task -> task.run() }
        private val TYPE_FIELD = Regex("\"type\":\"([^\"]+)\"")

        private fun envelope(type: String, payload: String): String =
            """{"schemaVersion":1,"type":"$type","payload":$payload}"""

        private fun attributionJson(entries: List<ResourceAttribution>): String =
            entries.joinToString(prefix = "[", postfix = "]") { entry ->
                """{"name":"${entry.name}","copyright":"${entry.copyright}","license":"${entry.license}","url":"${entry.url}"}"""
            }

        private fun requestType(raw: String): String =
            checkNotNull(TYPE_FIELD.find(raw)?.groupValues?.get(1)) { "request type missing" }

        private fun stringField(raw: String, field: String): String =
            checkNotNull(Regex("\"$field\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)) {
                "$field missing"
            }

        /** Null when the field is JSON null, as `catalogResourceId` is for a custom import. */
        private fun nullableStringField(raw: String, field: String): String? =
            Regex("\"$field\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)

        private fun intField(raw: String, field: String): Int =
            checkNotNull(Regex("\"$field\":([0-9]+)").find(raw)?.groupValues?.get(1)) {
                "$field missing"
            }.toInt()

        private fun wordsField(raw: String): List<String> {
            val body =
                checkNotNull(Regex("\"words\":\\[(.*?)\\]").find(raw)?.groupValues?.get(1)) {
                    "words missing"
                }
            return if (body.isEmpty()) emptyList() else body.split(",").map { it.trim().removeSurrounding("\"") }
        }

        private fun frequencyArchiveBytes(revision: String): ByteArray =
            fixtureFrequencyArchiveBytes(revision)
    }

    private class QueuedExecutor : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runNext() = queued.removeFirst().run()
    }

    private class PausableExecutor : Executor {
        val queued = ArrayDeque<Runnable>()
        var paused = false

        override fun execute(command: Runnable) {
            if (paused) {
                queued.addLast(command)
            } else {
                command.run()
            }
        }

        fun runNext() = queued.removeFirst().run()
    }

    private class TrackingExecutor : Executor {
        private var depth = 0
        val executing: Boolean
            get() = depth > 0
        var afterTask: (() -> Unit)? = null

        override fun execute(command: Runnable) {
            depth += 1
            try {
                command.run()
            } finally {
                afterTask?.invoke()
                depth -= 1
            }
        }
    }
}

/** A minimal Yomitan frequency archive: enough for the real pre-dispatch metadata validation. */
private fun fixtureFrequencyArchiveBytes(revision: String = "1"): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { output ->
        output.putNextEntry(ZipEntry("index.json"))
        output.write(
            """{"title":"Fixture Frequency","revision":"$revision"}""".toByteArray(Charsets.UTF_8),
        )
        output.closeEntry()
    }
    return bytes.toByteArray()
}

