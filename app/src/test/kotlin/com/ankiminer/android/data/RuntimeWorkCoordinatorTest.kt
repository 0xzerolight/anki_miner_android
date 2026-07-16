package com.ankiminer.android.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeWorkCoordinatorTest {
    @Test
    fun miningSnapshotLeaseExcludesResourcePublicationUntilTerminalCleanup() {
        val coordinator = RuntimeWorkCoordinator()
        val mining = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
        assertNotNull(mining)

        assertEquals(RuntimeWorkCoordinator.Kind.MINING, coordinator.activeKind())
        assertNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE))

        requireNotNull(mining).close()
        val resource = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE)
        assertNotNull(resource)
        assertNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
        requireNotNull(resource).close()
        assertNull(coordinator.activeKind())
    }

    @Test
    fun simultaneousMiningAndResourceAdmissionNeverBothWin() {
        repeat(200) {
            val coordinator = RuntimeWorkCoordinator()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val winners = AtomicInteger()
            val executor = Executors.newFixedThreadPool(2)
            listOf(RuntimeWorkCoordinator.Kind.MINING, RuntimeWorkCoordinator.Kind.RESOURCE)
                .forEach { kind ->
                    executor.execute {
                        ready.countDown()
                        start.await()
                        coordinator.tryAcquire(kind)?.let { winners.incrementAndGet() }
                    }
                }
            check(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            check(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
        }
    }
}
