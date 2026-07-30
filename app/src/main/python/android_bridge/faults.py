"""Mint opaque correlation ids for failures whose detail must not cross the bridge.

The bridge deliberately collapses every unexpected exception to a generic code
and message so filesystem paths, provider names, and raw exception text stay on
device. That leaves a maintainer holding a user-reported error string with no
way to find the matching traceback in an exported ``anki_miner.log``. A fault id
is the missing join key: it is minted here, logged next to the full traceback,
and carried verbatim in an additive wire field.

The id is drawn from ``secrets.token_hex`` rather than derived from the
exception, so it carries no information about the failure it labels. Stdlib
only, and no module-scope ``anki_miner`` import (see ``bootstrap.py``).
"""

from __future__ import annotations

import logging
import secrets

# Kotlin validates the same shape (BridgeJsonCodec.faultIdPattern), and both
# JSON schemas pin it, so a malformed id fails closed on either side.
FAULT_ID_PATTERN = r"^f[0-9a-f]{8}$"


def mint_fault_id() -> str:
    """Return a fresh opaque fault id, e.g. ``f0123abcd``."""

    return f"f{secrets.token_hex(4)}"


def record_fault(
    logger: logging.Logger,
    event: str,
    error: BaseException,
    **fields: object,
) -> str:
    """Log ``error`` with its traceback under a fresh fault id and return the id.

    Callers pass the id to the wire encoder; the traceback never leaves the
    device. Only machine-stable values (codes, request types, run ids) belong in
    ``fields`` -- this record is written to a log a user may export.
    """

    fault_id = mint_fault_id()
    details = "".join(f" {name}={value}" for name, value in sorted(fields.items()))
    logger.error("%s fault=%s%s", event, fault_id, details, exc_info=error)
    return fault_id
