package com.ankiminer.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningCpuWakeLeaseTest {
    @Test
    fun `acquire and close own one bounded non-reference-counted lease`() {
        val lock = FakeWakeLock()
        val lease = MiningCpuWakeLease(lock, timeoutMillis = 12_345)

        lease.acquire()
        lease.acquire()

        assertTrue(lease.isOwned())
        assertEquals(listOf(12_345L), lock.timeouts)
        assertEquals(0, lock.releaseCount)

        lease.close()
        lease.close()

        assertFalse(lease.isOwned())
        assertEquals(1, lock.releaseCount)
    }

    @Test
    fun `failed acquisition releases a lock which became held before failure`() {
        val lock = FakeWakeLock(failAfterHolding = true)
        val lease = MiningCpuWakeLease(lock, timeoutMillis = 1)

        val failure = runCatching(lease::acquire).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(lease.isOwned())
        assertFalse(lock.isHeld)
        assertEquals(1, lock.releaseCount)
    }

    @Test
    fun `platform timeout makes close a safe no-op`() {
        val lock = FakeWakeLock()
        val lease = MiningCpuWakeLease(lock, timeoutMillis = 1)
        lease.acquire()
        lock.isHeld = false

        lease.close()

        assertFalse(lease.isOwned())
        assertEquals(0, lock.releaseCount)
    }

    @Test
    fun `timeout race cannot make close throw or retain logical ownership`() {
        val lock = FakeWakeLock(throwOnRelease = true)
        val lease = MiningCpuWakeLease(lock, timeoutMillis = 1)
        lease.acquire()

        val result = runCatching(lease::close)

        assertTrue(result.isSuccess)
        assertFalse(lease.isOwned())
        assertEquals(1, lock.releaseAttempts)
    }

    private class FakeWakeLock(
        private val failAfterHolding: Boolean = false,
        private val throwOnRelease: Boolean = false,
    ) : MiningCpuWakeLock {
        override var isHeld: Boolean = false
        val timeouts = mutableListOf<Long>()
        var releaseCount = 0
        var releaseAttempts = 0

        override fun acquire(timeoutMillis: Long) {
            timeouts += timeoutMillis
            isHeld = true
            if (failAfterHolding) throw IllegalStateException("injected")
        }

        override fun release() {
            check(isHeld)
            releaseAttempts += 1
            if (throwOnRelease) throw IllegalStateException("timed out between isHeld and release")
            isHeld = false
            releaseCount += 1
        }
    }
}
