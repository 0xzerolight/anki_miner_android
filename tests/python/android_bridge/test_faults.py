from __future__ import annotations

import logging
import re

import pytest
from android_bridge.faults import FAULT_ID_PATTERN, mint_fault_id, record_fault


def test_minted_ids_match_the_shape_both_sides_validate() -> None:
    ids = [mint_fault_id() for _ in range(256)]

    for fault_id in ids:
        assert re.fullmatch(FAULT_ID_PATTERN, fault_id), fault_id
        assert len(fault_id) == 9
    # 32 bits of entropy: a collision in 256 draws would mean the id is not random.
    assert len(set(ids)) == 256


def test_the_id_carries_nothing_about_the_exception_it_labels() -> None:
    # Same exception type, same message, same identity -- still different ids.
    # A hash or any other derivation of the failure would repeat here, and would
    # leak the message across a boundary that exists to redact it.
    error = RuntimeError("secret /storage/emulated/0/episode.mkv")
    logger = logging.getLogger("android_bridge.tests.faults")

    ids = {record_fault(logger, "Mining failed", error) for _ in range(64)}

    assert len(ids) == 64


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
