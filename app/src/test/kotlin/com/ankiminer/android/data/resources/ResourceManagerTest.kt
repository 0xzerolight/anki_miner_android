package com.ankiminer.android.data.resources

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.localization.testStringResourceResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executor
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
                Harness(rootName = "manager-bridge", bridgeFailureCode = "resource_operation_cancelled")
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
            val lease =
                requireNotNull(
                    coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING),
                )
            val harness = Harness(runtimeWorkCoordinator = coordinator)

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
            val lease =
                requireNotNull(
                    coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING),
                )
            val harness = Harness(runtimeWorkCoordinator = coordinator)

            harness.manager.importKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
            assertEquals(
                KnownWordsFailureOperation.IMPORT,
                harness.manager.state.value.failure?.knownWordsOperation,
            )

            harness.manager.dismissFailure()
            harness.manager.previewKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
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
    fun previewLifecycleRetainsOneCopyAndConfirmRefreshesInventory() =
        runTest {
            val harness = Harness()

            harness.manager.previewKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)

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
            assertFalse(harness.stager.stagedFiles.single().exists())

            harness.manager.dismissKnownWordsImportPreview()

            assertNull(harness.manager.state.value.knownWordsImportPreview)
            assertFalse(harness.pendingRoot.exists())

            harness.manager.previewKnownWords(INPUT_URI, KnownWordsSourceFormat.JSON)
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
                    "resource.catalog.get",
                    "resource.dictionary.list",
                    "resource.local.list",
                ),
                harness.bridge.requestTypes,
            )
            assertEquals(listOf(INPUT_URI, INPUT_URI), harness.broker.retained)
            assertEquals(listOf(INPUT_URI, INPUT_URI), harness.broker.released)
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
    fun audioPackBudgetTracksFreeSpaceInsteadOfAFixedTwoGigabyteCap() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack ZIP",
                    reportedSourceSizeBytes = 3L * 1024 * 1024 * 1024,
                    stagingAvailableBytes = 64L * 1024 * 1024 * 1024,
                )

            harness.manager.importAudioPack(INPUT_URI, "jpod", replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.bridge.requestsOfType("resource.audiopack.import").size)
            assertTrue(harness.stager.lastMaximumBytes!! > 3L * 1024 * 1024 * 1024)
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun audioPackTooBigForTheDeviceIsRejectedBeforeAnythingIsCopied() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack ZIP",
                    reportedSourceSizeBytes = 8L * 1024 * 1024 * 1024,
                    stagingAvailableBytes = 4L * 1024 * 1024 * 1024,
                )

            harness.manager.importAudioPack(INPUT_URI, "jpod", replace = false)

            val failure = harness.manager.state.value.failure
            assertEquals(ResourceFailureOrigin.AUDIO, failure?.origin)
            assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, failure?.retry?.action)
            // The staged copy never starts, and the message carries both sizes.
            assertTrue(harness.stager.stagedFiles.isEmpty())
            assertNull(harness.stager.lastMaximumBytes)
            assertTrue(harness.bridge.requestTypes.none { it == "resource.audiopack.import" })
            assertTrue(failure!!.message.contains("audio-pack ZIP,8.0 GB,2.0 GB"))
            assertEquals(listOf(INPUT_URI), harness.broker.released)
        }

    @Test
    fun audioPackWithNoReportedSizeStillReachesTheStreamingLimit() =
        runTest {
            val harness =
                Harness(
                    sourceLabel = "audio-pack ZIP",
                    reportedSourceSizeBytes = null,
                    stagingAvailableBytes = 4L * 1024 * 1024 * 1024,
                )

            harness.manager.importAudioPack(INPUT_URI, "jpod", replace = false)

            assertNull(harness.manager.state.value.failure)
            assertEquals(1, harness.stager.stagedFiles.size)
            assertEquals(
                audioArchiveBudget(4L * 1024 * 1024 * 1024),
                harness.stager.lastMaximumBytes,
            )
        }

    private inner class Harness(
        rootName: String = "manager",
        initialUserCount: Int = 0,
        runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
        sourceLabel: String = "known-word file",
        reportedSourceSizeBytes: Long? = 16,
        stagingAvailableBytes: Long = Long.MAX_VALUE / 2,
        stagingFailureCode: String? = null,
        bridgeFailureCode: String? = null,
    ) {
        private val root = temporary.newFolder(rootName)
        val bridgeRoot = File(root, "bridge").apply { mkdirs() }
        val stagingRoot = File(root, "staging").apply { mkdirs() }
        val pendingRoot = File(root, "resource-pending-known-words")
        val broker = RecordingSafBroker(reportedSourceSizeBytes)
        val stager = RecordingArchiveStager(stagingRoot, sourceLabel, stagingFailureCode)
        val writer = RecordingDocumentWriter()
        val bridge = FakeResourceBridge(bridgeRoot, initialUserCount, bridgeFailureCode)
        val manager =
            AndroidResourceManager(
                safBroker = broker,
                bridge = bridge,
                tokenizerResources = { null },
                bridgeFilesRoot = bridgeRoot,
                stagingRoot = stagingRoot,
                resourceExecutor = DIRECT_EXECUTOR,
                controlExecutor = DIRECT_EXECUTOR,
                runtimeWorkCoordinator = runtimeWorkCoordinator,
                downloader =
                    PinnedResourceDownloader(
                        File(root, "downloads"),
                        connections = DownloadConnectionFactory { _, _ -> error("network not expected") },
                        availableBytes = { Long.MAX_VALUE / 2 },
                    ),
                safStager = stager,
                documentWriter = writer,
                strings = testStringResourceResolver,
                stagingAvailableBytes = { stagingAvailableBytes },
            )
    }

    private class RecordingSafBroker(
        private val reportedSizeBytes: Long? = 16,
    ) : SafBroker {
        val retained = mutableListOf<String>()
        val released = mutableListOf<String>()

        override suspend fun retainReadAccess(uri: String): SafDocument {
            retained += uri
            return SafDocument(uri, "known-words.json", "application/json", reportedSizeBytes)
        }

        override suspend fun releaseReadAccess(uri: String) {
            released += uri
        }

        override fun releaseReadAccessEventually(uri: String) = Unit
    }

    private class RecordingArchiveStager(
        private val stagingRoot: File,
        private val expectedSourceLabel: String = "known-word file",
        private val failureCode: String? = null,
    ) : ResourceArchiveStager {
        val stagedFiles = mutableListOf<File>()
        var lastMaximumBytes: Long? = null

        override fun stage(
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
            failureCode?.let { throw ResourceDownloadException(it, "cancelled") }
            lastMaximumBytes = maximumBytes
            cancellation.check()
            val file = File(stagingRoot, "$operationId-custom$fileSuffix")
            file.writeText("fixture", Charsets.UTF_8)
            stagedFiles += file
            onProgress(file.length(), file.length())
            return StagedArchive(file, "0".repeat(64), file.length())
        }
    }

    private class RecordingDocumentWriter : ResourceDocumentWriter {
        val openedUris = mutableListOf<String>()
        val output = ByteArrayOutputStream()

        override fun open(uri: String): OutputStream {
            openedUris += uri
            return output
        }
    }

    private class FakeResourceBridge(
        private val bridgeFilesRoot: File,
        initialUserCount: Int,
        private val failureCode: String? = null,
    ) : PyBridge {
        private val requests = mutableListOf<String>()
        private var userCount = initialUserCount
        var lastExportFile: File? = null
            private set

        val requestTypes: List<String>
            get() = requests.map(::requestType)

        fun requestsOfType(type: String): List<String> =
            requests.filter { requestType(it) == type }

        override fun dispatch(rawRequest: String, callbacks: EngineCallbacks?): String {
            assertNull(callbacks)
            requests += rawRequest
            failureCode?.let { throw ResourceBridgeException(it, "cancelled") }
            return when (requestType(rawRequest)) {
                "resource.catalog.get" -> catalogResponse()
                "resource.dictionary.list" ->
                    envelope("resource.dictionary.listed", """{"dictionaries":[]}""")
                "resource.local.list" -> inventoryResponse()
                "resource.knownwords.preview" ->
                    envelope(
                        "resource.knownwords.previewed",
                        """{"format":"migaku_json","importedCount":2,"totalEntries":3,"isGeneric":false,"sampleWords":["犬","猫"]}""",
                    )
                "resource.knownwords.import" -> {
                    userCount = 2
                    envelope(
                        "resource.knownwords.imported",
                        """{"format":"migaku_json","importedCount":2,"newRowCount":2,"totalEntries":3,"isGeneric":false}""",
                    )
                }
                "resource.knownwords.list" -> pageResponse(rawRequest)
                "resource.knownwords.remove" -> {
                    userCount = (userCount - 1).coerceAtLeast(0)
                    envelope("resource.knownwords.removed", """{"removedCount":1}""")
                }
                "resource.knownwords.reset" -> {
                    userCount = 0
                    envelope(
                        "resource.knownwords.reset",
                        """{"scope":"user","removedCount":1}""",
                    )
                }
                "resource.knownwords.export" -> exportResponse(rawRequest)
                "resource.audiopack.import" ->
                    envelope(
                        "resource.audiopack.imported",
                        """{"packId":"jpod","sourceName":"jpod_files","format":"jpod_legacy","entryCount":12,"archiveSha256":"${"0".repeat(64)}"}""",
                    )
                else -> error("Unexpected request: $rawRequest")
            }
        }

        private fun inventoryResponse(): String =
            envelope(
                "resource.local.listed",
                """{"frequencies":[],"pitchSources":[],"audioPacks":[],"knownWords":{"totalCount":$userCount,"userCount":$userCount,"ankiCount":0,"minedCount":0,"schemaOk":true},"wordsets":[]}""",
            )

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

        private fun requestType(raw: String): String =
            checkNotNull(TYPE_FIELD.find(raw)?.groupValues?.get(1)) { "request type missing" }

        private fun stringField(raw: String, field: String): String =
            checkNotNull(Regex("\"$field\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)) {
                "$field missing"
            }

        private fun intField(raw: String, field: String): Int =
            checkNotNull(Regex("\"$field\":([0-9]+)").find(raw)?.groupValues?.get(1)) {
                "$field missing"
            }.toInt()
    }
}
