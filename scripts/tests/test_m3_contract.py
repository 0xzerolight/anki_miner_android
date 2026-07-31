from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class M3ContractTest(unittest.TestCase):
    def test_foreground_phase_owns_a_bounded_non_refcounted_partial_wake_lock(self) -> None:
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        service = (ROOT / "app/src/main/kotlin/com/ankiminer/android/service/MiningForegroundService.kt").read_text(
            encoding="utf-8"
        )
        wake = (ROOT / "app/src/main/kotlin/com/ankiminer/android/service/MiningCpuWakeLease.kt").read_text(
            encoding="utf-8"
        )

        self.assertIn("android.permission.WAKE_LOCK", manifest)
        self.assertIn("PowerManager.PARTIAL_WAKE_LOCK", wake)
        self.assertIn("setReferenceCounted(false)", wake)
        self.assertIn("TimeUnit.HOURS.toMillis(6)", wake)

        handle_start = service[service.index("private fun handleStart") :]
        foreground = handle_start.index("startForegroundTyped")
        acquire = handle_start.index("cpuWakeLease.acquire()")
        handshake = handle_start.index("registry.foregroundStarted")
        self.assertLess(foreground, acquire)
        self.assertLess(acquire, handshake)

        stop = service[service.index("private fun stopImmediately") :]
        self.assertLess(stop.index("cpuWakeLease.close()"), stop.index("stopForeground"))
        self.assertLess(stop.index("stopForeground"), stop.index("stopSelf()"))
        destroy = service[service.index("override fun onDestroy") : service.index("private fun handleStart")]
        self.assertIn("cpuWakeLease.close()", destroy)
        self.assertIn("registry.serviceDestroyed", destroy)
        self.assertIn("super.onDestroy()", destroy)

    def test_proactive_saf_reconciliation_cannot_crash_application_startup(self) -> None:
        application = (ROOT / "app/src/main/kotlin/com/ankiminer/android/AnkiMinerApplication.kt").read_text(
            encoding="utf-8"
        )
        launch = application[application.index("safBroker.reconcileStartup()") - 120 :]
        self.assertIn("try {", launch[:160])
        self.assertIn("catch (failure: Exception)", launch[:320])
        self.assertIn("AppLog.w(", launch[:640])
        self.assertIn("LogComponent.SAF", launch[:640])
        self.assertIn('"startup.reconcile"', launch[:640])
        self.assertIn('"outcome" to "fail"', launch[:640])


if __name__ == "__main__":
    unittest.main()
