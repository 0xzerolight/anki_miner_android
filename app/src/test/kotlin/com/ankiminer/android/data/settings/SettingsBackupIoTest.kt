package com.ankiminer.android.data.settings

import com.ankiminer.android.media.ManualProviderIoDeadlineScheduler
import com.ankiminer.android.media.ProviderIoTimeoutException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupIoTest {
    @Test
    fun malformedUtf8IsRejectedInsteadOfDecodedWithReplacementCharacters() {
        val bytes =
            "{\"settings\":{\"tags\":\"".toByteArray() +
                byteArrayOf(0x80.toByte()) +
                "\"}}".toByteArray()
        val reader =
            AndroidSettingsDocumentReader(
                inputOpener = SettingsDocumentInputOpener { _, _ -> ByteArrayInputStream(bytes) },
            )

        val failure =
            assertThrows(SettingsBackupException::class.java) {
                runBlocking { reader.read(INPUT_URI) }
            }

        assertEquals(SettingsBackupFailure.MALFORMED, failure.reason)
    }

    @Test
    fun stalledProviderOpenHitsTheSharedDeadline() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val openStarted = CountDownLatch(1)
        val openCancelled = CountDownLatch(1)
        val reader =
            testReader(
                scope = scope,
                scheduler = scheduler,
                opener =
                    SettingsDocumentInputOpener { _, cancellation ->
                        cancellation.invokeOnCancellation { openCancelled.countDown() }.use {
                            openStarted.countDown()
                            check(openCancelled.await(5, TimeUnit.SECONDS)) {
                                "provider open was never cancelled"
                            }
                            throw IOException("provider open cancelled")
                        }
                    },
            )
        try {
            val result = executor.submit<String> { runBlocking { reader.read(INPUT_URI) } }
            assertTrue(openStarted.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    result.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            assertTrue(openCancelled.await(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun stalledProviderReadHitsTheSharedDeadlineAndClosesTheStream() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val source = StalledInputStream()
        val reader =
            testReader(
                scope = scope,
                scheduler = scheduler,
                opener = SettingsDocumentInputOpener { _, _ -> source },
            )
        try {
            val result = executor.submit<String> { runBlocking { reader.read(INPUT_URI) } }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()
            scheduler.fireArmedDeadline()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    result.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            assertEquals(1, source.closeCalls.get())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun stalledProviderWriteHitsTheSharedDeadlineAndClosesTheStream() {
        val scheduler = ManualProviderIoDeadlineScheduler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newSingleThreadExecutor()
        val sink = StalledOutputStream()
        val writer =
            SettingsDocumentWriter(
                outputOpener = SettingsDocumentOutputOpener { _, _ -> sink },
                providerIoScope = scope,
                providerIoTimeoutMillis = 5_000L,
                providerIoScheduler = scheduler,
            )
        try {
            val result =
                executor.submit<Unit> {
                    runBlocking { writer.write(OUTPUT_URI, "backup".toByteArray()) }
                }
            assertTrue(sink.writeStarted.await(1, TimeUnit.SECONDS))

            scheduler.fireArmedDeadline()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    result.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is ProviderIoTimeoutException)
            assertEquals(1, sink.closeCalls.get())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    private fun testReader(
        scope: CoroutineScope,
        scheduler: ManualProviderIoDeadlineScheduler,
        opener: SettingsDocumentInputOpener,
    ) =
        AndroidSettingsDocumentReader(
            inputOpener = opener,
            providerIoScope = scope,
            providerIoTimeoutMillis = 5_000L,
            providerIoScheduler = scheduler,
        )

    private class StalledInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "provider read was never closed" }
            throw IOException("provider stream closed")
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = read()

        override fun close() {
            if (closeCalls.incrementAndGet() == 1) closed.countDown()
        }
    }

    private class StalledOutputStream : OutputStream() {
        val writeStarted = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val closed = CountDownLatch(1)

        override fun write(value: Int) {
            writeStarted.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "provider write was never closed" }
            throw IOException("provider stream closed")
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) = write(buffer[offset].toInt())

        override fun close() {
            if (closeCalls.incrementAndGet() == 1) closed.countDown()
        }
    }

    private companion object {
        const val INPUT_URI = "content://settings/import.json"
        const val OUTPUT_URI = "content://settings/export.json"
    }
}
