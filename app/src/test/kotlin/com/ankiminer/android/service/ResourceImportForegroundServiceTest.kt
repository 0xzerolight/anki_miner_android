package com.ankiminer.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceImportForegroundServiceTest {
    @Test
    fun `accepted stop parks the wake lease so a later start can reacquire it`() {
        val wakeLock = RecordingWakeLock()
        val lease = MiningCpuWakeLease(wakeLock, timeoutMillis = 10_000)
        val events = mutableListOf<String>()
        lease.acquire()

        handleResourceImportStopCommand(
            startId = 4,
            stopSelfResult = { startId ->
                events += "stopSelfResult:$startId"
                true
            },
            parkCpuWakeLease = {
                events += "park"
                lease.park()
            },
            removeForeground = { events += "removeForeground" },
        )
        lease.acquire()

        assertEquals(
            listOf("stopSelfResult:4", "park", "removeForeground"),
            events,
        )
        assertTrue(lease.isOwned())
        assertEquals(2, wakeLock.acquireCount)
        assertEquals(1, wakeLock.releaseCount)
        lease.close()
    }

    @Test
    fun `stale stop leaves wake and foreground ownership for the newer start`() {
        val wakeLock = RecordingWakeLock()
        val lease = MiningCpuWakeLease(wakeLock, timeoutMillis = 10_000)
        val events = mutableListOf<String>()
        lease.acquire()

        handleResourceImportStopCommand(
            startId = 8,
            stopSelfResult = { startId ->
                events += "stopSelfResult:$startId"
                false
            },
            parkCpuWakeLease = {
                events += "park"
                lease.park()
            },
            removeForeground = { events += "removeForeground" },
        )

        assertEquals(listOf("stopSelfResult:8"), events)
        assertTrue(lease.isOwned())
        assertEquals(1, wakeLock.acquireCount)
        assertEquals(0, wakeLock.releaseCount)
        lease.close()
    }

    @Test
    fun `system timeout cancels active work and stops within the grace period`() {
        val events = mutableListOf<String>()

        handleResourceImportSystemTimeout(
            cancelActiveOperation = { events += "cancel" },
            closeCpuWakeLease = { events += "closeWake" },
            removeForeground = { events += "removeForeground" },
            stopService = { events += "stopSelf" },
        )

        assertEquals(
            listOf("cancel", "closeWake", "removeForeground", "stopSelf"),
            events,
        )
    }

    @Test
    fun `service overrides both platform timeout callbacks`() {
        val intType = requireNotNull(Int::class.javaPrimitiveType)

        val legacy =
            ResourceImportForegroundService::class.java.getDeclaredMethod(
                "onTimeout",
                intType,
            )
        val typed =
            ResourceImportForegroundService::class.java.getDeclaredMethod(
                "onTimeout",
                intType,
                intType,
            )

        assertEquals(ResourceImportForegroundService::class.java, legacy.declaringClass)
        assertEquals(ResourceImportForegroundService::class.java, typed.declaringClass)
    }

    private class RecordingWakeLock : MiningCpuWakeLock {
        override var isHeld = false
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set

        override fun acquire(timeoutMillis: Long) {
            acquireCount += 1
            isHeld = true
        }

        override fun release() {
            check(isHeld)
            releaseCount += 1
            isHeld = false
        }
    }
}
