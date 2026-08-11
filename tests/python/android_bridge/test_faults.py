from __future__ import annotations

import logging
import re

import android_bridge.faults as faults
import pytest
from android_bridge.faults import FAULT_ID_PATTERN, mint_fault_id, record_fault


def test_minted_id_matches_the_shape_both_sides_validate(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[int] = []
    monkeypatch.setattr(faults.secrets, "token_hex", lambda size: calls.append(size) or "0123abcd")

    fault_id = mint_fault_id()

    assert fault_id == "f0123abcd"
    assert re.fullmatch(FAULT_ID_PATTERN, fault_id)
    assert len(fault_id) == 9
    assert calls == [4]


def test_the_id_carries_nothing_about_the_exception_it_labels(monkeypatch: pytest.MonkeyPatch) -> None:
    # Same exception type, same message, same identity -- still different ids.
    # A hash or any other derivation of the failure would repeat here, and would
    # leak the message across a boundary that exists to redact it.
    error = RuntimeError("secret /storage/emulated/0/episode.mkv")
    logger = logging.getLogger("android_bridge.tests.faults")
    tokens = iter(("00000000", "ffffffff"))
    monkeypatch.setattr(faults.secrets, "token_hex", lambda _size: next(tokens))

    ids = [record_fault(logger, "Mining failed", error) for _ in range(2)]

    assert ids == ["f00000000", "fffffffff"]


def test_record_fault_logs_the_traceback_and_fields_beside_the_id(
    caplog: pytest.LogCaptureFixture,
) -> None:
    logger = logging.getLogger("android_bridge.tests.faults")
    error = RuntimeError("secret /storage/emulated/0/episode.mkv")

    with caplog.at_level("ERROR", logger=logger.name):
        fault_id = record_fault(logger, "Mining failed", error, code="internal_error", request=None)

    record = caplog.records[-1]
    assert record.levelno == logging.ERROR
    assert record.exc_info is not None
    assert record.exc_info[1] is error
    # Fields are sorted so a record's shape does not depend on kwargs order.
    assert record.getMessage() == f"Mining failed fault={fault_id} code=internal_error request=None"
