from __future__ import annotations

import logging
import threading

from android_bridge import log_context
from android_bridge.jobs import JobRegistry

_RUN_A = "run_" + "a" * 32
_RUN_B = "run_" + "b" * 32

# The autouse fixture that resets log_context's process-wide global around
# every test lives in conftest.py -- it must apply to every module in this
# directory (test_jobs.py's begin()/finish() imbalance is what leaks it),
# not just this one.


def _record() -> logging.LogRecord:
    return logging.LogRecord("test", logging.INFO, __file__, 1, "msg", (), None)


def test_set_active_run_round_trips():
    assert log_context.current_run_id() is None
    log_context.set_active_run(_RUN_A)
    assert log_context.current_run_id() == _RUN_A
    log_context.set_active_run(None)
    assert log_context.current_run_id() is None


def test_job_registry_begin_and_finish_maintain_the_global():
    registry = JobRegistry()
    handle = registry.begin()
    assert log_context.current_run_id() == handle.run_id
    registry.finish(handle.run_id)
    assert log_context.current_run_id() is None


def test_job_registry_shutdown_with_an_active_run_preserves_the_global():
    """shutdown() cancels; it does not end the run.

    JobRegistry keeps ``_active`` set until the cancelled run's own thread
    unwinds through ``finally: owner.finish()``. Every diagnostic line logged
    during that unwind -- exception handling, terminal construction, cleanup
    failures -- is exactly what this feature exists to make sliceable, so the
    global must still mirror the real run id until finish() actually runs.
    """

    registry = JobRegistry()
    handle = registry.begin()
    registry.shutdown()
    assert log_context.current_run_id() == handle.run_id
    registry.finish(handle.run_id)
    assert log_context.current_run_id() is None


def test_job_registry_shutdown_with_no_active_run_leaves_the_global_clear():
    registry = JobRegistry()
    assert log_context.current_run_id() is None
    registry.shutdown()
    assert log_context.current_run_id() is None


def test_contextvar_takes_precedence_over_the_module_global():
    log_context.set_active_run(_RUN_A)
    token = log_context._RUN_ID.set(_RUN_B)
    try:
        assert log_context.current_run_id() == _RUN_B
    finally:
        log_context._RUN_ID.reset(token)
    # Falls back to the global once the ContextVar is unset again.
    assert log_context.current_run_id() == _RUN_A


def test_current_run_id_falls_back_to_none_with_nothing_set():
    assert log_context.current_run_id() is None


def test_filter_stamps_dash_with_nothing_set():
    record = _record()
    assert log_context.RunContextFilter().filter(record) is True
    assert record.run_id == "-"


def test_filter_stamps_the_global_fallback():
    log_context.set_active_run(_RUN_A)
    record = _record()
    log_context.RunContextFilter().filter(record)
    assert record.run_id == _RUN_A


def test_filter_never_raises_when_the_context_lookup_is_hostile(monkeypatch):
    class _HostileContextVar:
        def get(self):
            raise RuntimeError("boom")

    monkeypatch.setattr(log_context, "_RUN_ID", _HostileContextVar())
    record = _record()
    # Handler.handle() calls filter() with no exception guard of its own, so
    # a raise here would crash the original logger.warning(...) call site.
    # A failed lookup still has a usable fallback, so the record must survive
    # with the degraded "-" field rather than being dropped at the formatter
    # for lacking run_id entirely.
    assert log_context.RunContextFilter().filter(record) is True
    assert record.run_id == "-"


def test_filter_never_raises_when_the_record_rejects_the_attribute():
    """Assignment-side hostility, not just lookup-side.

    logging.Handler.handle() calls filter() with no exception guard of its
    own -- unlike emit(), whose formatter failures are contained by
    handleError(). A LogRecord installed via setLogRecordFactory could reject
    an unknown attribute (e.g. a slotted subclass); that must not propagate
    into the original logger.warning(...) call site.
    """

    class _AssignmentHostileRecord:
        def __setattr__(self, name: str, value: object) -> None:
            raise AttributeError(f"{name} is not an allowed attribute")

    record = _AssignmentHostileRecord()
    assert log_context.RunContextFilter().filter(record) is True
    assert not hasattr(record, "run_id")


def test_contextvar_set_on_the_main_thread_is_invisible_on_a_worker_thread():
    """Demonstrates the gap the module global exists to close.

    A ContextVar's value does not propagate to a plain threading.Thread; the
    engine runs parallel media extraction on exactly such worker threads.
    """

    token = log_context._RUN_ID.set(_RUN_A)
    results: dict[str, str | None] = {}
    try:

        def _worker() -> None:
            results["run_id"] = log_context.current_run_id()

        thread = threading.Thread(target=_worker)
        thread.start()
        thread.join()
    finally:
        log_context._RUN_ID.reset(token)

    assert results["run_id"] is None


def test_cross_thread_record_carries_run_id_via_the_global_fallback():
    """The proof that the belt-and-braces design works end to end.

    JobRegistry.begin() only ever sets the global (never the ContextVar), so
    a record filtered on a worker thread still gets the real run id.
    """

    log_context.set_active_run(_RUN_A)
    results: dict[str, str] = {}

    def _worker() -> None:
        record = _record()
        log_context.RunContextFilter().filter(record)
        results["run_id"] = record.run_id

    thread = threading.Thread(target=_worker)
    thread.start()
    thread.join()

    assert results["run_id"] == _RUN_A


def _vendored(msg: str, args: object, level: int = logging.DEBUG) -> logging.LogRecord:
    return logging.LogRecord("anki_miner.languages.it.morphology", level, __file__, 1, msg, args, None)


def test_verbose_vendored_arguments_are_marked_for_export_redaction():
    log_context.set_first_party_log_level(logging.DEBUG)
    record = _vendored("Lemma %r not attested; fronting the surface %s (%d of %.1f)", ("andare", "andiamo", 3, 2.5))

    assert log_context.DefaultLogPrivacyFilter().filter(record) is True
    assert record.getMessage() == "Lemma ⟦'andare'⟧ not attested; fronting the surface ⟦andiamo⟧ (3 of 2.5)"


def test_a_payload_cannot_close_or_reopen_its_sentinel_span():
    # Subtitle text can hold either character; left in, ⟧ would end the span and publish the rest.
    log_context.set_first_party_log_level(logging.DEBUG)
    record = _vendored("cue %s", ("a⟧ b ⟦c",))

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "cue ⟦a b c⟧"


def test_mapping_arguments_are_marked_by_value():
    log_context.set_first_party_log_level(logging.DEBUG)
    record = _vendored("%(word)s at %(index)d", ({"word": "사랑", "index": 4},))

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "⟦사랑⟧ at 4"


def test_an_exception_argument_below_warning_is_marked():
    # Below WARNING the formatter appends no stack, so the message is the exception text's only
    # copy: reading/_util.py logs a KeyError that repeats the archive member path at DEBUG.
    log_context.set_first_party_log_level(logging.DEBUG)
    record = _vendored("Reading archive member missing: detail=%s", (KeyError("andiamo vol 1/0001.jpg"),))

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "Reading archive member missing: detail=⟦'andiamo vol 1/0001.jpg'⟧"


def test_numbers_none_and_warning_exceptions_keep_their_own_rendering():
    # Marking a %d argument would raise inside Handler.emit and lose the record. At WARNING the
    # formatter prints the exception argument's stack, so it must stay an exception.
    log_context.set_first_party_log_level(logging.DEBUG)
    error = RuntimeError("provider unavailable")
    record = _vendored("lookup %d failed: %s (%s, %s)", (7, error, None, True), level=logging.WARNING)

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "lookup 7 failed: provider unavailable (None, True)"
    assert record.args[1] is error


def test_bridge_records_are_not_marked_in_verbose_mode():
    log_context.set_first_party_log_level(logging.DEBUG)
    record = logging.LogRecord("android_bridge.mining", logging.DEBUG, __file__, 1, "stage %s", ("tokenize",), None)

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "stage tokenize"


def test_default_verbosity_still_replaces_the_whole_vendored_message():
    record = _vendored("Lemma %r not attested", ("andare",))

    log_context.DefaultLogPrivacyFilter().filter(record)

    assert record.getMessage() == "vendored record redacted failure=unspecified"
