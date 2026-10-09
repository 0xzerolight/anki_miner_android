package com.ankiminer.android.service

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The foreground handoff against the real ActivityManager: the type check and the rule that a
 * startForegroundService start calls startForeground before it stops are platform behaviour no host
 * test sees. Both methods drive the controller the mining repositories use after curation confirm.
 */
@RunWith(AndroidJUnit4::class)
class MiningForegroundHandoffInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val listener = MiningForegroundSessionListener { _, _ -> }

    @Test
    fun aStartedSessionEntersTheForegroundAndStopsOnClose() {
        val lease =
            MiningForegroundSessionController(context)
                .startSession(RUN_ID, 1L, listener)
                .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            assertTrue(
                "MiningForegroundService is not in the foreground",
                runningMiningServices().any { it.foreground },
            )
        } finally {
            lease.close()
        }
        awaitMiningServiceStopped()
    }

    @Test
    fun cancellingAStartBeforeTheServiceRunsDoesNotCrashTheApp() {
        val controller = MiningForegroundSessionController(context)
        val mainHeld = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        // Holds the main thread so the service's START is still queued when the run cancels.
        Handler(Looper.getMainLooper()).post {
            mainHeld.countDown()
            releaseMain.await(MAIN_HOLD_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(mainHeld.await(MAIN_HOLD_SECONDS, TimeUnit.SECONDS))
        try {
            val start = controller.startSession(RUN_ID, 2L, listener)
            assertTrue("the start finished while the main thread was held", start.cancel(false))
        } finally {
            releaseMain.countDown()
        }
        // A stop before startForeground crashes this process on its main thread; let that land.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(CRASH_GRACE_MS)
        instrumentation.waitForIdleSync()
        awaitMiningServiceStopped()
    }

    @Suppress("DEPRECATION")
    private fun runningMiningServices(): List<ActivityManager.RunningServiceInfo> =
        context
            .getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .filter { it.service.className == MiningForegroundService::class.java.name }

    private fun awaitMiningServiceStopped() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS)
        while (runningMiningServices().isNotEmpty()) {
            assertTrue("MiningForegroundService is still running", System.nanoTime() < deadline)
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val RUN_ID = "run_foreground_handoff"
        const val START_TIMEOUT_SECONDS = 15L
        const val MAIN_HOLD_SECONDS = 10L
        const val CRASH_GRACE_MS = 1_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
