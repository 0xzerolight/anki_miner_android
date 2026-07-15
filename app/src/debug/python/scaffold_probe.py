"""Minimal debug-only module proving Chaquopy source packaging."""

import json
import platform
import sys


def snapshot() -> str:
    return json.dumps(
        {
            "implementation": platform.python_implementation(),
            "major": sys.version_info.major,
            "minor": sys.version_info.minor,
        },
        sort_keys=True,
    )
