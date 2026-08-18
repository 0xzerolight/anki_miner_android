"""Tests for android_bridge.resource_progress."""

from __future__ import annotations

from android_bridge.resource_progress import ResourceProgressReporter, make_reporter
from conftest import FakeCallbacks


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now


def _reporter(cb, clock, phase="importing"):
    return ResourceProgressReporter(cb, "resource_" + "a" * 32, phase, clock=clock)


def test_report_emits_envelope():
    cb, clock = FakeCallbacks(), FakeClock()
    _reporter(cb, clock).report(3, 30)
    assert cb.messages == [
        {
            "schemaVersion": 1,
            "type": "resource.progress",
            "payload": {
                "operationId": "resource_" + "a" * 32,
                "phase": "importing",
                "kind": "items",
                "current": 3,
                "total": 30,
            },
        }
    ]


def test_throttle_suppresses_within_interval_and_emits_after():
    cb, clock = FakeCallbacks(), FakeClock()
    r = _reporter(cb, clock)
    r.report(1, 10)
    clock.now += 0.05
    r.report(2, 10)  # suppressed
    clock.now += 0.2
    r.report(3, 10)  # emitted
    assert [m["payload"]["current"] for m in cb.messages] == [1, 3]


def test_terminal_and_force_bypass_throttle():
    cb, clock = FakeCallbacks(), FakeClock()
    r = _reporter(cb, clock)
    r.report(1, 10)
    r.report(10, 10)  # current == total > 0: terminal, bypasses
    r.report(5, 10, force=True)  # explicit force bypasses
    assert [m["payload"]["current"] for m in cb.messages] == [1, 10, 5]


def test_set_phase_force_emits_zero_counts():
    cb, clock = FakeCallbacks(), FakeClock()
    r = _reporter(cb, clock)
    r.report(1, 10)
    r.set_phase("finalizing")
    assert cb.messages[-1]["payload"] == {
        "operationId": "resource_" + "a" * 32,
        "phase": "finalizing",
        "kind": "items",
        "current": 0,
        "total": 0,
    }


def test_callback_failure_disables_reporter_and_does_not_raise():
    cb, clock = FakeCallbacks(fail_after=1), FakeClock()
    r = _reporter(cb, clock)
    r.report(1, 10)
    r.report(2, 10, force=True)  # raises inside cb -> swallowed, disables
    r.report(3, 10, force=True)  # no further calls attempted
    assert len(cb.messages) == 1


def test_items_fn_maps_progress_fn_and_drops_stage_messages():
    cb, clock = FakeCallbacks(), FakeClock()
    fn = _reporter(cb, clock).items_fn()
    fn(0, 0, "Inserting entries")  # stage marker: dropped
    fn(3, 30, "Imported bank 3")  # counts forwarded, message dropped
    assert len(cb.messages) == 1
    assert cb.messages[0]["payload"]["current"] == 3
    assert "message" not in cb.messages[0]["payload"]


def test_bytes_fn_uses_bytes_kind():
    cb, clock = FakeCallbacks(), FakeClock()
    _reporter(cb, clock, phase="installing").bytes_fn()(1024, 2048)
    assert cb.messages[0]["payload"]["kind"] == "bytes"
    assert cb.messages[0]["payload"]["phase"] == "installing"


def test_null_reporter_when_callbacks_none():
    r = make_reporter(None, "resource_" + "a" * 32, "importing")
    r.report(1, 10)
    r.set_phase("finalizing")
    r.items_fn()(1, 2, "x")
    r.bytes_fn()(1, 2)  # all no-ops, nothing raises
