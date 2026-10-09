package com.ankiminer.android.data.resources

import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Known-words paging and language-scoped retries, over a bridge fake that keeps one word table. */
class KnownWordsResourceManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `load more after words were added ahead of the loaded page restarts from the top`() =
        runTest {
            val harness = Harness()
            harness.bridge.words += (0 until 101).map { "w%03d".format(it) }
            harness.manager.searchKnownWords("")
            // A curation's mark-known writes user words without dropping the loaded page.
            harness.bridge.words += (0 until 100).map { "a%03d".format(it) }

            harness.manager.searchKnownWords("", loadMore = true)

            // Every loaded offset moved, so the list starts over instead of repeating its tail.
            assertEquals(harness.bridge.words.take(100), harness.page().words)

            harness.manager.searchKnownWords("", loadMore = true)

            assertEquals(harness.bridge.words.take(200), harness.page().words)
        }

    @Test
    fun `a continuation that repeats the loaded tail adds no word twice`() =
        runTest {
            val harness = Harness()
            harness.bridge.words += (0 until 101).map { "w%03d".format(it) }
            harness.manager.searchKnownWords("")
            // One word in ahead of the page and one out behind it: the total holds, the tail shifts.
            harness.bridge.words += "a-new"
            harness.bridge.words -= "w100"

            harness.manager.searchKnownWords("", loadMore = true)

            // The list keys rows by word: a repeat crashes it.
            val words = harness.page().words
            assertEquals(words.distinct(), words)
        }

    @Test
    fun `confirming an import drops the loaded page`() =
        runTest {
            val harness = Harness()
            harness.bridge.words += "w000"
            harness.manager.searchKnownWords("")
            assertNotNull(harness.manager.state.value.knownWordsPage)

            harness.manager.previewKnownWords(INPUT_URI, ResourceImportFileKind.JSON)
            harness.manager.confirmKnownWordsImport()

            // Imported words shift every offset after them, as a removal's do.
            assertNull(harness.manager.state.value.knownWordsPage)
        }

    /** [recover] false leaves startup recovery to the test. */
    private inner class Harness(root: File = temporary.newFolder(), recover: Boolean = true) {
        var language: String = JAPANESE
        val bridge = WordTableBridge()
        val pendingRoot = File(root, "resource-pending-known-words")
        private val stagingRoot = File(root, "staging").apply { mkdirs() }
        val stager = FixtureStager(stagingRoot)
        val manager =
            AndroidResourceManager(
                safBroker = FixtureSafBroker,
                bridge = bridge,
                tokenizerResources = { null },
                bridgeFilesRoot = File(root, "bridge").apply { mkdirs() },
                stagingRoot = stagingRoot,
                resourceExecutor = DIRECT,
                controlExecutor = DIRECT,
                downloader =
                    PinnedResourceDownloader(
                        File(root, "downloads"),
                        connections = DownloadConnectionFactory { _, _ -> error("network not expected") },
                        availableBytes = { Long.MAX_VALUE / 2 },
                    ),
                safStager = stager,
                documentWriter = ResourceDocumentWriter { null },
                strings = testStringResourceResolver,
                activeLanguage = { language },
                resourceDirectorySync = {},
            )

        init {
            if (recover) runBlocking { manager.recoverAndRefresh() }
        }

        fun page(): KnownWordsPage = requireNotNull(manager.state.value.knownWordsPage)
    }

    /** Answers what these tests send, from one sorted table of user words. */
    private class WordTableBridge : PyBridge {
        val words = sortedSetOf<String>()

        /** Request types that fail once, then succeed. */
        val failOnce = mutableSetOf<String>()
        private val requests = mutableListOf<String>()

        fun languagesOf(type: String): List<String> =
            requests.filter { field(it, "type") == type }.map { field(it, "language") }

        override fun dispatch(rawRequest: String, callbacks: EngineCallbacks?): String {
            requests += rawRequest
            val type = field(rawRequest, "type")
            if (failOnce.remove(type)) error("simulated $type failure")
            return when (type) {
                "resource.catalog.get" -> CommittedCatalogs.envelope()
                "resource.cleanup" -> envelope("resource.cleanup.result", """{"clean":true}""")
                "resource.dictionary.list" -> envelope("resource.dictionary.listed", """{"dictionaries":[]}""")
                "resource.local.list" ->
                    envelope(
                        "resource.local.listed",
                        """{"frequencies":[],"pitchSources":[],"audioPacks":[],"knownWords":{"totalCount":${words.size},"userCount":${words.size},"ankiCount":0,"minedCount":0,"schemaOk":true},"wordsets":[],"languageData":[]}""",
                    )
                "resource.knownwords.list" -> page(rawRequest)
                "resource.knownwords.preview" ->
                    envelope(
                        "resource.knownwords.previewed",
                        """{"format":"migaku_json","importedCount":2,"totalEntries":3,"isGeneric":false,"sampleWords":["犬","猫"]}""",
                    )
                "resource.knownwords.import" -> {
                    words += listOf("犬", "猫")
                    envelope(
                        "resource.knownwords.imported",
                        """{"format":"migaku_json","importedCount":2,"newRowCount":2,"totalEntries":3,"isGeneric":false}""",
                    )
                }
                "resource.knownwords.remove" -> envelope("resource.knownwords.removed", """{"removedCount":1}""")
                "resource.minedwords.remove" -> envelope("resource.minedwords.removed", """{"removedCount":1}""")
                "resource.knownwords.reset" ->
                    envelope(
                        "resource.knownwords.reset",
                        """{"scope":"${field(rawRequest, "scope")}","removedCount":1}""",
                    )
                else -> error("unexpected request $type")
            }
        }

        private fun page(raw: String): String {
            val query = field(raw, "query")
            val offset = number(raw, "offset")
            val matching = words.filter { query in it }
            val slice = matching.drop(offset).take(number(raw, "limit"))
            val json = slice.joinToString(",") { "\"$it\"" }
            return envelope(
                "resource.knownwords.listed",
                """{"query":"$query","offset":$offset,"totalCount":${matching.size},"words":[$json],"hasMore":${offset + slice.size < matching.size}}""",
            )
        }
    }

    private object FixtureSafBroker : SafBroker {
        override suspend fun retainReadAccess(uri: String) = SafDocument(uri, "known-words.json", "application/json", 16)

        override suspend fun releaseReadAccess(uri: String) = Unit

        override fun releaseReadAccessEventually(uri: String) = Unit
    }

    private class FixtureStager(private val stagingRoot: File) : ResourceArchiveStager {
        /** Runs while a file stages, before any dispatch. */
        var onStage: () -> Unit = {}

        override suspend fun readLeadingBytes(sourceUri: String, maximumBytes: Int) = ByteArray(0)

        override fun stage(
            sourceUri: String,
            operationId: String,
            cancellation: ResourceCancellationSignal,
            fileSuffix: String,
            maximumBytes: Long,
            sourceLabel: String,
            onProgress: (Long, Long) -> Unit,
        ): StagedArchive {
            onStage()
            val file = File(stagingRoot, "$operationId$fileSuffix").apply { writeText("犬\n猫\n") }
            return StagedArchive(file, "0".repeat(64), file.length())
        }

        override fun stageAudioArchive(
            sourceUri: String,
            operationId: String,
            cancellation: ResourceCancellationSignal,
            maximumBytes: Long,
            sourceLabel: String,
            onProgress: (Long, Long) -> Unit,
        ): StagedAudioArchive = error("audio not expected")
    }

    private companion object {
        const val INPUT_URI = "content://fixtures/known-words.json"
        val DIRECT = Executor { it.run() }

        fun envelope(type: String, payload: String): String = """{"schemaVersion":1,"type":"$type","payload":$payload}"""

        fun field(raw: String, name: String): String =
            checkNotNull(Regex("\"$name\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)) { "$name missing" }

        fun number(raw: String, name: String): Int =
            checkNotNull(Regex("\"$name\":([0-9]+)").find(raw)?.groupValues?.get(1)) { "$name missing" }.toInt()
    }
}
