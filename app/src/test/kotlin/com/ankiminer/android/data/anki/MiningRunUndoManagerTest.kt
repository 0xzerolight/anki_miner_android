package com.ankiminer.android.data.anki

import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.data.RuntimeWorkCoordinator
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningRunUndoManagerTest {
    @Test
    fun `undo reports busy while another runtime lease is held`() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val mining = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
            val backend = RecordingBackend()
            val reverter = RecordingReverter()
            val manager = undoManager(backend, Executor(Runnable::run), coordinator, reverter)

            val outcome = manager.undoRun("run-1", listOf(11L, 12L), listOf("猫"))

            assertEquals(UndoRunOutcome.Busy, outcome)
            assertTrue(backend.calls.isEmpty())
            assertTrue(reverter.calls.isEmpty())
            assertTrue(manager.undoneRuns.value.isEmpty())
            assertFalse(manager.undoActive.value)
            mining.close()
        }

    @Test
    fun `second undo while one is in flight reports busy`() =
        runTest {
            val executor = QueuedExecutor()
            val backend = RecordingBackend()
            val reverter = RecordingReverter()
            val manager = undoManager(backend, executor, RuntimeWorkCoordinator(), reverter)

            val first = async { manager.undoRun("run-1", listOf(11L), listOf("猫")) }
            runCurrent()
            assertTrue(manager.undoActive.value)

            val second = manager.undoRun("run-2", listOf(12L), listOf("犬"))

            assertEquals(UndoRunOutcome.Busy, second)
            assertEquals(1, executor.queued.size)
            executor.runNext()
            assertEquals(
                UndoRunOutcome.Undone(UndoneRunReceipt("run-1", deletedNotes = 1, knownWordsReverted = true)),
                first.await(),
            )
            assertEquals(listOf(listOf(11L)), backend.calls)
            assertEquals(listOf(listOf("猫")), reverter.calls)
            assertFalse(manager.undoActive.value)
        }

    @Test
    fun `undoing an already undone run returns the recorded receipt without work`() =
        runTest {
            val backend = RecordingBackend()
            val reverter = RecordingReverter()
            val manager = undoManager(backend, Executor(Runnable::run), RuntimeWorkCoordinator(), reverter)

            val first = manager.undoRun("run-1", listOf(11L, 12L), listOf("猫"))
            val second = manager.undoRun("run-1", listOf(11L, 12L), listOf("猫"))

            assertEquals(
                UndoRunOutcome.Undone(UndoneRunReceipt("run-1", deletedNotes = 2, knownWordsReverted = true)),
                first,
            )
            assertEquals(first, second)
            assertEquals(1, backend.calls.size)
            assertEquals(1, reverter.calls.size)
        }

    @Test
    fun `the setup lease is released before the mined words are reverted`() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val events = mutableListOf<String>()
            val backend = RecordingBackend(onDelete = { events += "delete:${coordinator.activeKind.value}" })
            val reverter =
                RecordingReverter(
                    onRevert = {
                        val resource = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE)
                        events += if (resource == null) "revert:blocked" else "revert:resource"
                        resource?.close()
                    },
                )
            val manager = undoManager(backend, Executor(Runnable::run), coordinator, reverter)

            manager.undoRun("run-1", listOf(11L), listOf("猫"))

            assertEquals(listOf("delete:ANKI_SETUP", "revert:resource"), events)
        }

    @Test
    fun `a failed delete reports delete failed and leaves the mined words alone`() =
        runTest {
            val backend = RecordingBackend(failure = IllegalStateException("provider gone"))
            val reverter = RecordingReverter()
            val manager = undoManager(backend, Executor(Runnable::run), RuntimeWorkCoordinator(), reverter)

            val outcome = manager.undoRun("run-1", listOf(11L, 12L), listOf("猫"))

            assertEquals(UndoRunOutcome.DeleteFailed, outcome)
            assertTrue(reverter.calls.isEmpty())
            assertTrue(manager.undoneRuns.value.isEmpty())
            assertFalse(manager.undoActive.value)
        }

    @Test
    fun `a failed delete releases the setup lease`() =
        runTest {
            val coordinator = RuntimeWorkCoordinator()
            val backend = RecordingBackend(failure = IllegalStateException("provider gone"))
            val manager = undoManager(backend, Executor(Runnable::run), coordinator, RecordingReverter())

            manager.undoRun("run-1", listOf(11L), listOf("猫"))

            val next = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.ANKI_SETUP)
            assertNotNull(next)
            next?.close()
        }

    @Test
    fun `a refused word revert still reports the run undone`() =
        runTest {
            val backend = RecordingBackend()
            val reverter = RecordingReverter(result = false)
            val manager = undoManager(backend, Executor(Runnable::run), RuntimeWorkCoordinator(), reverter)

            val outcome = manager.undoRun("run-1", listOf(11L, 12L), listOf("猫"))

            val expected = UndoneRunReceipt("run-1", deletedNotes = 2, knownWordsReverted = false)
            assertEquals(UndoRunOutcome.Undone(expected), outcome)
            assertEquals(expected, manager.undoneRuns.value["run-1"])
        }

    @Test
    fun `the receipt survives a caller cancelled during the delete`() =
        runTest {
            val executor = QueuedExecutor()
            val backend = RecordingBackend()
            val reverter = RecordingReverter()
            val manager = undoManager(backend, executor, RuntimeWorkCoordinator(), reverter)

            val caller = launch { manager.undoRun("run-1", listOf(11L, 12L, 13L), listOf("猫")) }
            runCurrent()
            caller.cancel()
            caller.join()

            executor.runNext()

            assertEquals(
                UndoneRunReceipt("run-1", deletedNotes = 3, knownWordsReverted = false),
                manager.undoneRuns.value["run-1"],
            )
            assertTrue(reverter.calls.isEmpty())
            assertFalse(manager.undoActive.value)
        }

    @Test
    fun `the receipt map keeps the eight newest runs`() =
        runTest {
            val manager =
                undoManager(
                    RecordingBackend(),
                    Executor(Runnable::run),
                    RuntimeWorkCoordinator(),
                    RecordingReverter(),
                )

            repeat(9) { index ->
                manager.undoRun("run-$index", listOf(index.toLong()), listOf("word-$index"))
            }

            assertEquals((1..8).map { "run-$it" }, manager.undoneRuns.value.keys.toList())
        }

    private fun undoManager(
        backend: MiningRunUndoBackend,
        executor: Executor,
        coordinator: RuntimeWorkCoordinator,
        reverter: MinedWordsReverter,
    ): MiningRunUndoManager = ProcessMiningRunUndoManager(backend, executor, coordinator, reverter)

    private class QueuedExecutor : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runNext() = queued.removeFirst().run()
    }

    private class RecordingBackend(
        private val failure: RuntimeException? = null,
        private val onDelete: (() -> Unit)? = null,
    ) : MiningRunUndoBackend {
        val calls = mutableListOf<List<Long>>()

        override fun deleteNotes(
            noteIds: List<Long>,
            cancellation: AnkiCancellation,
        ): Int {
            calls += noteIds
            onDelete?.invoke()
            failure?.let { throw it }
            return noteIds.size
        }
    }

    private class RecordingReverter(
        private val result: Boolean = true,
        private val onRevert: ((List<String>) -> Unit)? = null,
    ) : MinedWordsReverter {
        val calls = mutableListOf<List<String>>()

        override suspend fun removeMinedWords(words: List<String>): Boolean {
            calls += words
            onRevert?.invoke(words)
            return result
        }
    }
}
