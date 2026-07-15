"""Small, English-only subset of :mod:`PyQt6.QtCore` used by the engine."""

from __future__ import annotations

import re
from typing import Any


_PLACEHOLDER_RE = re.compile(r"%(\d+)")


class QCoreApplication:
    """Provide the sole Qt symbol imported by Android's vendored engine."""

    @staticmethod
    def translate(
        context: str,
        source: str,
        disambiguation: str | None = None,
        n: int = -1,
    ) -> str:
        del context, disambiguation
        text = source
        if n >= 0:
            text = text.replace("%n", str(n))
        return text


def substitute_args(template: str, *args: Any) -> str:
    """Apply Qt's positional ``%1``/``%2`` substitutions without Qt."""

    values = tuple(str(value) for value in args)

    def replace(match: re.Match[str]) -> str:
        index = int(match.group(1)) - 1
        return values[index] if 0 <= index < len(values) else match.group(0)

    return _PLACEHOLDER_RE.sub(replace, template)
