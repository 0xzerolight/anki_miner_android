package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.journal.StagingDraft
import com.ankiminer.android.anki.journal.StagingRecord
import com.ankiminer.android.anki.journal.StagingState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AnkiMediaStagingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stage copies once verifies the same stream and flushes before sync`() {
        val harness = harness("verified bytes".toByteArray())

        val record = harness.staging.stage(harness.request())

        assertEquals(StagingState.STAGED, record.state)
        assertEquals(1, harness.platform.sourceOpenCount)
        assertArrayEquals(harness.sourceBytes, harness.platform.destination(record).readBytes())
        assertBefore(harness.events, "record", "create")
        assertBefore(harness.events, "flush", "sync")
        assertTrue(harness.events.last() == "close")
    }

    @Test
    fun `stage rejects an exact size mismatch and removes its private copy`() {
        val harness = harness("four".toByteArray())

        val error =
            expectFailure(AnkiMediaStagingFailure.VERIFICATION_FAILED) {
                harness.staging.stage(harness.request(expectedSizeBytes = 3))
            }

        assertFalse(error.message.orEmpty().contains(harness.source.absolutePath))
        assertTrue(harness.journal.records.isEmpty())
        assertBefore(harness.events, "revoke", "delete")
    }

    @Test
    fun `stage rejects a digest mismatch and removes its private copy`() {
        val harness = harness("digest".toByteArray())

        expectFailure(AnkiMediaStagingFailure.VERIFICATION_FAILED) {
            harness.staging.stage(harness.request(expectedSha256 = "0".repeat(64)))
        }

        assertTrue(harness.journal.records.isEmpty())
        assertTrue(harness.platform.stagingRoot.walkTopDown().none { it.isFile })
    }

    @Test
    fun `stage rejects the aggregate byte bound before opening or recording`() {
        val harness = harness("bounded".toByteArray())

        expectFailure(AnkiMediaStagingFailure.CAPACITY_EXCEEDED) {
            harness.staging.stage(
                harness.request(aggregateRemainingBytes = harness.sourceBytes.size.toLong() - 1),
            )
        }

        assertEquals(0, harness.platform.sourceOpenCount)
        assertTrue(harness.journal.records.isEmpty())
        assertTrue(harness.events.isEmpty())
    }

    @Test
    fun `stage enforces the protocol asset and aggregate hard caps locally`() {
        val harness = harness("bounded".toByteArray())
        val assetLimit = AnkiLimitsV1.StoreMedia.MAX_ASSET_BYTES.toLong()
        val aggregateLimit = AnkiLimitsV1.StoreMedia.MAX_TOTAL_BYTES.toLong()

        expectFailure(AnkiMediaStagingFailure.INVALID_REQUEST) {
            harness.staging.stage(
                harness.request(
                    expectedSizeBytes = assetLimit + 1,
                    aggregateRemainingBytes = aggregateLimit,
                ),
            )
        }
        expectFailure(AnkiMediaStagingFailure.INVALID_REQUEST) {
            harness.staging.stage(
                harness.request(aggregateRemainingBytes = aggregateLimit + 1),
            )
        }

        assertEquals(0, harness.platform.sourceOpenCount)
        assertTrue(harness.journal.records.isEmpty())
        assertTrue(harness.events.isEmpty())
    }

    @Test
    fun `durable draft is recorded before a destination creation crash`() {
        val harness = harness("crash order".toByteArray())
        harness.platform.failCreate = true

        expectFailure(AnkiMediaStagingFailure.PREPARATION_FAILED) {
            harness.staging.stage(harness.request())
        }

        assertBefore(harness.events, "record", "create")
        assertTrue(harness.journal.records.isEmpty())
    }

    @Test
    fun `grant is restricted to the exact AnkiDroid package`() {
        val harness = harness("grant".toByteArray())
        val record = harness.staging.stage(harness.request())

        expectFailure(AnkiMediaStagingFailure.UNSAFE_JOURNAL) {
            harness.staging.grantRead(record.copy(packageName = "com.example.not-anki"))
        }
        assertTrue(harness.platform.grants.isEmpty())

        val granted = harness.staging.grantRead(record)
        assertEquals(StagingState.GRANTED, granted.state)
        assertEquals(listOf(ANKIDROID_PACKAGE to record.contentUri), harness.platform.grants)
    }

    @Test
    fun `cleanup always revokes the exact grant before deleting`() {
        val harness = harness("cleanup".toByteArray())
        val staged = harness.staging.stage(harness.request())
        val granted = harness.staging.grantRead(staged)

        assertEquals(AnkiMediaCleanupOutcome.CLEANED, harness.staging.cleanup(granted))

        assertBefore(harness.events, "revoke", "delete")
        assertTrue(harness.journal.records.isEmpty())
        assertFalse(harness.platform.destination(staged).exists())
    }

    @Test
    fun `cleanup failure remains quarantined with visible remediation`() {
        val harness = harness("quarantine".toByteArray())
        val staged = harness.staging.stage(harness.request())
        harness.platform.failDelete = true

        assertEquals(AnkiMediaCleanupOutcome.QUARANTINED, harness.staging.cleanup(staged))

        assertEquals(StagingState.QUARANTINED, harness.journal.records.single().state)
        assertEquals(setOf(staged.id), harness.journal.openQuarantines)
        assertTrue(harness.platform.destination(staged).isFile)
        assertFalse(harness.events.contains("complete"))
    }

    @Test
    fun `cleanup still removes private bytes when grant revocation fails`() {
        val harness = harness("revoke failure".toByteArray())
        val staged = harness.staging.stage(harness.request())
        val granted = harness.staging.grantRead(staged)
        harness.platform.failRevoke = true

        assertEquals(AnkiMediaCleanupOutcome.QUARANTINED, harness.staging.cleanup(granted))

        assertBefore(harness.events, "revoke", "delete")
        assertFalse(harness.platform.destination(staged).exists())
        assertEquals(StagingState.QUARANTINED, harness.journal.records.single().state)
        assertEquals(setOf(staged.id), harness.journal.openQuarantines)
    }

    @Test
    fun `recovery fails closed on unsafe journal path or URI`() {
        listOf("path", "uri").forEach { corruption ->
            val harness = harness("unsafe".toByteArray())
            val staged = harness.staging.stage(harness.request())
            harness.events.clear()
            harness.journal.records[0] =
                when (corruption) {
                    "path" -> staged.copy(relativePath = "../outside.stage")
                    else -> staged.copy(contentUri = "content://attacker.invalid/stolen")
                }

            expectFailure(AnkiMediaStagingFailure.UNSAFE_JOURNAL) {
                harness.staging.recover()
            }

            assertFalse(harness.events.contains("revoke"))
            assertFalse(harness.events.contains("delete"))
            assertFalse(harness.events.contains("sweep"))
        }
    }

    @Test
    fun `recovery revokes and removes every journaled private copy`() {
        val harness = harness("recover".toByteArray())
        val staged = harness.staging.stage(harness.request())
        harness.staging.grantRead(staged)
        harness.events.clear()

        val report = harness.staging.recover()

        assertEquals(AnkiMediaRecoveryReport(1, 0, 1), report)
        assertTrue(report.isClean)
        assertBefore(harness.events, "revoke", "delete")
        assertTrue(harness.journal.records.isEmpty())
        assertFalse(harness.platform.destination(staged).exists())
    }

    @Test
    fun `recovery retries quarantined records and resolves remediation`() {
        val harness = harness("retry".toByteArray())
        val staged = harness.staging.stage(harness.request())
        harness.platform.failDelete = true
        harness.staging.cleanup(staged)
        harness.platform.failDelete = false
        harness.events.clear()

        val report = harness.staging.recover()

        assertEquals(1, report.cleanedRecords)
        assertEquals(0, report.quarantinedRecords)
        assertTrue(harness.journal.openQuarantines.isEmpty())
        assertTrue(harness.journal.records.isEmpty())
        assertBefore(harness.events, "revoke", "delete")
    }

    @Test
    fun `failed atomic cleanup leaves a recoverable pending row`() {
        val harness = harness("finalize".toByteArray())
        val staged = harness.staging.stage(harness.request())
        harness.journal.failComplete = true

        expectFailure(AnkiMediaStagingFailure.JOURNAL_FAILED) {
            harness.staging.cleanup(staged)
        }

        assertEquals(StagingState.CLEANUP_PENDING, harness.journal.records.single().state)
        assertFalse(harness.platform.destination(staged).exists())
        harness.journal.failComplete = false
        harness.events.clear()

        val report = harness.staging.recover()

        assertEquals(AnkiMediaRecoveryReport(1, 0, 1), report)
        assertTrue(harness.journal.records.isEmpty())
        assertBefore(harness.events, "revoke", "delete")
    }

    @Test
    fun `recovery sweeps only unjournaled entries below the private root`() {
        val harness = harness("orphan".toByteArray())
        val outside = File(harness.platform.stagingRoot.parentFile, "must-remain").apply { writeText("safe") }
        val orphanFile = File(harness.platform.stagingRoot, "v1/orphan.tmp")
        val orphanDirectory = File(harness.platform.stagingRoot, "unexpected/nested")
        requireNotNull(orphanFile.parentFile).mkdirs()
        orphanFile.writeText("orphan")
        orphanDirectory.mkdirs()
        File(orphanDirectory, "bytes").writeText("orphan")

        val report = harness.staging.recover()

        assertTrue(report.sweptOrphans >= 4)
        assertTrue(outside.isFile)
        assertTrue(harness.platform.stagingRoot.walkTopDown().none { it.isFile })
        assertTrue(harness.events.contains("sweep"))
    }

    @Test
    fun `process lock prevents recovery from sweeping a concurrent stage`() {
        val harness = harness("concurrent".toByteArray())
        val sharedLock = AnkiMediaStagingProcessLock()
        val recovery =
            AnkiMediaStaging(
                journal = harness.journal,
                platform = harness.platform,
                nonceSource = AnkiMediaStagingNonceSource { "1".repeat(32) },
                processLock = sharedLock,
            )
        val writer =
            AnkiMediaStaging(
                journal = harness.journal,
                platform = harness.platform,
                nonceSource = AnkiMediaStagingNonceSource { "2".repeat(32) },
                processLock = sharedLock,
            )
        harness.platform.blockSweep = true
        val executor = Executors.newFixedThreadPool(2)
        try {
            val recoveryFuture = executor.submit<AnkiMediaRecoveryReport> { recovery.recover() }
            assertTrue(harness.platform.sweepEntered.await(5, TimeUnit.SECONDS))
            val stageStarted = CountDownLatch(1)
            val stageFuture =
                executor.submit<StagingRecord> {
                    stageStarted.countDown()
                    writer.stage(harness.request())
                }
            assertTrue(stageStarted.await(5, TimeUnit.SECONDS))
            try {
                stageFuture.get(100, TimeUnit.MILLISECONDS)
                fail("Stage must wait for the recovery sweep")
            } catch (_: TimeoutException) {
                // Expected: both instances share the same process lock.
            }

            harness.platform.allowSweep.countDown()
            recoveryFuture.get(5, TimeUnit.SECONDS)
            val staged = stageFuture.get(5, TimeUnit.SECONDS)

            assertTrue(harness.platform.destination(staged).isFile)
            assertEquals(staged.id, harness.journal.records.single().id)
        } finally {
            harness.platform.allowSweep.countDown()
            executor.shutdownNow()
        }
    }

    private fun harness(sourceBytes: ByteArray): Harness {
        val base = temporaryFolder.newFolder()
        val source = File(base, "engine-output.bin").apply { writeBytes(sourceBytes) }
        val events = mutableListOf<String>()
        val journal = FakeStagingJournal(events)
        val platform = FakeStagingPlatform(base, events)
        val staging =
            AnkiMediaStaging(
                journal = journal,
                platform = platform,
                nonceSource = AnkiMediaStagingNonceSource { "1".repeat(32) },
            )
        return Harness(sourceBytes, source, events, journal, platform, staging)
    }

    private fun expectFailure(
        expected: AnkiMediaStagingFailure,
        block: () -> Unit,
    ): AnkiMediaStagingException {
        try {
            block()
            fail("Expected $expected")
        } catch (error: AnkiMediaStagingException) {
            assertEquals(expected, error.failure)
            return error
        }
        error("unreachable")
    }

    private fun assertBefore(
        events: List<String>,
        first: String,
        second: String,
    ) {
        assertTrue("Missing $first in $events", events.indexOf(first) >= 0)
        assertTrue("Missing $second in $events", events.indexOf(second) >= 0)
        assertTrue("Expected $first before $second in $events", events.indexOf(first) < events.indexOf(second))
    }

    private data class Harness(
        val sourceBytes: ByteArray,
        val source: File,
        val events: MutableList<String>,
        val journal: FakeStagingJournal,
        val platform: FakeStagingPlatform,
        val staging: AnkiMediaStaging,
    ) {
        fun request(
            expectedSizeBytes: Long = sourceBytes.size.toLong(),
            expectedSha256: String = sha256(sourceBytes),
            aggregateRemainingBytes: Long = sourceBytes.size.toLong(),
        ) =
            AnkiMediaStagingRequest(
                runId = TEST_RUN_ID,
                requestId = TEST_REQUEST_ID,
                assetId = TEST_ASSET_ID,
                absoluteSourcePath = source.absolutePath,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                aggregateRemainingBytes = aggregateRemainingBytes,
            )
    }
}

private class FakeStagingJournal(
    private val events: MutableList<String>,
) : AnkiMediaStagingJournal {
    val records = mutableListOf<StagingRecord>()
    val openQuarantines = mutableSetOf<Long>()
    var failComplete = false
    private var nextId = 1L

    override fun record(draft: StagingDraft): StagingRecord {
        events += "record"
        return StagingRecord(
            id = nextId++,
            runId = draft.runId,
            requestId = draft.requestId,
            assetId = draft.assetId,
            relativePath = draft.relativePath,
            contentUri = draft.contentUri,
            packageName = draft.packageName,
            sizeBytes = draft.sizeBytes,
            sha256 = draft.sha256,
            state = StagingState.STAGED,
            compactEvidence = null,
            createdAtMs = 1,
            updatedAtMs = 1,
        ).also(records::add)
    }

    override fun transition(
        stagingId: Long,
        state: StagingState,
        compactEvidence: String,
    ): StagingRecord {
        events += "state:$state"
        val index = records.indexOfFirst { it.id == stagingId }
        check(index >= 0)
        return records[index]
            .copy(
                state = state,
                compactEvidence = compactEvidence,
                updatedAtMs = records[index].updatedAtMs + 1,
            ).also { records[index] = it }
    }

    override fun recoveryRecords(): List<StagingRecord> = records.toList()

    override fun addQuarantineRemediation(stagingId: Long) {
        events += "remediate"
        openQuarantines += stagingId
    }

    override fun completeCleanup(
        stagingId: Long,
        compactEvidence: String,
    ) {
        events += "complete"
        if (failComplete) throw IOException("simulated atomic cleanup failure")
        check(compactEvidence.isNotBlank())
        val index = records.indexOfFirst { it.id == stagingId }
        check(index >= 0)
        check(records[index].state in setOf(StagingState.CLEANUP_PENDING, StagingState.QUARANTINED, StagingState.CLEANED))
        openQuarantines -= stagingId
        records.removeAt(index)
    }
}

private class FakeStagingPlatform(
    base: File,
    private val events: MutableList<String>,
) : AnkiMediaStagingPlatform {
    override val authority = "com.ankiminer.android.anki-media"
    val stagingRoot = File(base, ANKI_MEDIA_STAGING_ROOT)
    val grants = mutableListOf<Pair<String, String>>()
    var sourceOpenCount = 0
    var failCreate = false
    var failDelete = false
    var failRevoke = false
    var blockSweep = false
    val sweepEntered = CountDownLatch(1)
    val allowSweep = CountDownLatch(1)

    override fun contentUriFor(relativePath: String): String =
        "content://$authority/anki_media_staging/$relativePath"

    override fun destinationExists(relativePath: String): Boolean = destination(relativePath).exists()

    override fun openSource(absolutePath: String): InputStream {
        events += "open"
        sourceOpenCount += 1
        return FileInputStream(absolutePath)
    }

    override fun createDestination(relativePath: String): AnkiMediaStagingOutput {
        events += "create"
        if (failCreate) throw IOException("simulated create failure")
        val destination = destination(relativePath)
        requireNotNull(destination.parentFile).mkdirs()
        val fileOutput = FileOutputStream(destination)
        val tracked =
            object : FilterOutputStream(fileOutput) {
                override fun flush() {
                    events += "flush"
                    super.flush()
                }
            }
        return object : AnkiMediaStagingOutput {
            override val stream: OutputStream = tracked

            override fun sync() {
                events += "sync"
                fileOutput.fd.sync()
            }

            override fun close() {
                stream.close()
                events += "close"
            }
        }
    }

    override fun grantRead(
        packageName: String,
        contentUri: String,
    ) {
        events += "grant"
        grants += packageName to contentUri
    }

    override fun revokeRead(
        packageName: String,
        contentUri: String,
    ) {
        check(packageName == ANKIDROID_PACKAGE)
        events += "revoke"
        if (failRevoke) throw IOException("simulated revoke failure")
        grants -= packageName to contentUri
    }

    override fun deleteDestination(relativePath: String) {
        events += "delete"
        if (failDelete) throw IOException("simulated delete failure")
        destination(relativePath).delete()
    }

    override fun sweepUnjournaled(journaledRelativePaths: Set<String>): Int {
        events += "sweep"
        if (blockSweep) {
            sweepEntered.countDown()
            check(allowSweep.await(5, TimeUnit.SECONDS)) { "timed out waiting to finish sweep" }
        }
        if (!stagingRoot.exists()) return 0
        val retained = journaledRelativePaths.map(::destination).toSet()
        var removed = 0
        stagingRoot.walkBottomUp().forEach { entry ->
            if (entry != stagingRoot && entry !in retained && entry.delete()) removed += 1
        }
        return removed
    }

    fun destination(record: StagingRecord): File = destination(record.relativePath)

    private fun destination(relativePath: String): File = File(stagingRoot, relativePath)
}

private const val TEST_RUN_ID = "run_00000000000000000000000000000000"
private const val TEST_REQUEST_ID = "anki_11111111111111111111111111111111"
private const val TEST_ASSET_ID = "asset_22222222222222222222222222222222"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
