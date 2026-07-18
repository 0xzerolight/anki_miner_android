from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app/src/main/python"
DEBUG_PYTHON_ROOT = PROJECT_ROOT / "app/src/debug/python"


def _run(script: str, home: Path) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(PYTHON_ROOT)
    env.pop("ANKI_MINER_HOME", None)
    return subprocess.run(
        [sys.executable, "-c", script, str(home)],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )


def _run_debug(script: str, home: Path) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join((str(DEBUG_PYTHON_ROOT), str(PYTHON_ROOT)))
    env.pop("ANKI_MINER_HOME", None)
    return subprocess.run(
        [sys.executable, "-c", script, str(home)],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )


@pytest.mark.parametrize("backend", ["s1a", "s1b"])
def test_backend_reaches_real_engine_shared_tagger_seam(backend: str, tmp_path: Path) -> None:
    result = _run(
        f"""
import json, sys
from pathlib import Path
from types import ModuleType
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])

import android_bridge.unidic_resource as resource
registration = object()
resource.require_registered_unidic = lambda: registration

import android_bridge.tokenizer_{backend} as adapter
created = []
class FakeTagger:
    def __call__(self, text):
        return [text]
def create(received):
    assert received is registration
    created.append(received)
    return FakeTagger()
adapter.create_{backend}_tagger = create

# The primary bridge-test environment intentionally contains no engine pip
# dependencies. Load the real tagger submodule without executing the unrelated
# eager exports in anki_miner.services.__init__; the CPython 3.12 runtime lane
# exercises the complete package with its exact Android dependency closure.
import anki_miner
services = ModuleType("anki_miner.services")
services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
sys.modules["anki_miner.services"] = services

from android_bridge.tokenizer_selection import configure_tokenizer_backend, selected_tokenizer_backend
assert configure_tokenizer_backend({backend!r}) == {backend!r}
assert selected_tokenizer_backend() == {backend!r}
assert created == []

from anki_miner.services.tagger import get_shared_tagger
shared = get_shared_tagger()
print(json.dumps({{
    "created": len(created),
    "same": shared is get_shared_tagger(),
    "tokens": shared("猫"),
    "fugashi_loaded": "fugashi" in sys.modules,
    "idempotent": configure_tokenizer_backend({backend!r}),
}}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload == {
        "created": 1,
        "same": True,
        "tokens": ["猫"],
        "fugashi_loaded": False,
        "idempotent": backend,
    }


def test_selection_fails_closed_before_engine_import(tmp_path: Path) -> None:
    result = _run(
        """
import sys
from android_bridge.tokenizer_selection import configure_tokenizer_backend
try:
    configure_tokenizer_backend("s1b")
except Exception as exc:
    print(getattr(exc, "code", ""), "anki_miner.services.tagger" in sys.modules)
    raise
""",
        tmp_path,
    )

    assert result.returncode != 0
    assert "bootstrap_required False" in result.stdout


def test_selection_requires_registered_unidic_and_cannot_switch(tmp_path: Path) -> None:
    result = _run(
        """
import sys
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
from android_bridge.tokenizer_selection import configure_tokenizer_backend
try:
    configure_tokenizer_backend("s1b")
except Exception as exc:
    print(getattr(exc, "code", ""), "anki_miner.services.tagger" in sys.modules)
""",
        tmp_path,
    )
    assert result.returncode == 0, result.stderr
    assert "unidic_registration_required False" in result.stdout

    switched = _run(
        """
import sys
from pathlib import Path
from types import ModuleType
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
import android_bridge.unidic_resource as resource
resource.require_registered_unidic = lambda: object()
import anki_miner
services = ModuleType("anki_miner.services")
services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
sys.modules["anki_miner.services"] = services
from android_bridge.tokenizer_selection import configure_tokenizer_backend
configure_tokenizer_backend("s1b")
try:
    configure_tokenizer_backend("s1a")
except Exception as exc:
    print(getattr(exc, "code", ""))
""",
        tmp_path,
    )
    assert switched.returncode == 0, switched.stderr
    assert switched.stdout.strip() == "tokenizer_already_configured"


def test_selection_rejects_unknown_backend_without_importing_engine(
    tmp_path: Path,
) -> None:
    result = _run(
        """
import sys
from android_bridge.tokenizer_selection import configure_tokenizer_backend
try:
    configure_tokenizer_backend("other")
except Exception as exc:
    print(getattr(exc, "code", ""), "anki_miner.services.tagger" in sys.modules)
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "invalid_tokenizer_backend False"


@pytest.mark.parametrize(
    ("first", "second"),
    (("s1a", "s1b"), ("s1b", "s1a")),
)
def test_debug_instrumentation_uses_engine_then_direct_candidate_without_switching(
    first: str,
    second: str,
    tmp_path: Path,
) -> None:
    result = _run_debug(
        f"""
import importlib, json, sys
from pathlib import Path
from types import ModuleType
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])

import android_bridge.unidic_resource as resource
registration = object()
resource.require_registered_unidic = lambda: registration

created = []
class FakeTagger:
    def __init__(self, backend):
        self.backend = backend
    def __call__(self, text):
        return [self.backend, text]

for backend in ("s1a", "s1b"):
    adapter = importlib.import_module(f"android_bridge.tokenizer_{{backend}}")
    def create(received, backend=backend):
        assert received is registration
        created.append(backend)
        return FakeTagger(backend)
    setattr(adapter, f"create_{{backend}}_tagger", create)

import anki_miner
services = ModuleType("anki_miner.services")
services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
sys.modules["anki_miner.services"] = services

from tokenizer_instrumented_selection import acquire_tagger_for_instrumentation
first_tagger, first_path, first_selected = acquire_tagger_for_instrumentation(
    {first!r}, registration
)
second_tagger, second_path, second_selected = acquire_tagger_for_instrumentation(
    {second!r}, registration
)
from android_bridge.tokenizer_selection import selected_tokenizer_backend
from anki_miner.services.tagger import get_shared_tagger
print(json.dumps({{
    "created": created,
    "first_path": first_path,
    "first_selected": first_selected,
    "first_tokens": first_tagger("first"),
    "second_path": second_path,
    "second_selected": second_selected,
    "second_tokens": second_tagger("second"),
    "selected": selected_tokenizer_backend(),
    "shared_is_first": get_shared_tagger() is first_tagger,
}}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {
        "created": [first, second],
        "first_path": "engine_shared_tagger",
        "first_selected": first,
        "first_tokens": [first, "first"],
        "second_path": f"debug_direct_fallback_after_{first}",
        "second_selected": first,
        "second_tokens": [second, "second"],
        "selected": first,
        "shared_is_first": True,
    }


def test_debug_harnesses_and_isolated_runners_make_selection_path_observable() -> None:
    helper = (DEBUG_PYTHON_ROOT / "tokenizer_instrumented_selection.py").read_text(encoding="utf-8")
    assert "configure_tokenizer_backend(backend)" in helper
    assert "get_shared_tagger()" in helper
    assert "debug_direct_fallback_after_" in helper

    for backend, kotlin_relative in (
        (
            "s1a",
            "com/ankiminer/android/TokenizerS1aInstrumentedTest.kt",
        ),
        (
            "s1b",
            "com/ankiminer/android/tokenizer/MecabNativeTokenizerInstrumentedTest.kt",
        ),
    ):
        harness = (DEBUG_PYTHON_ROOT / f"tokenizer_{backend}_instrumented.py").read_text(encoding="utf-8")
        kotlin = (PROJECT_ROOT / "app/src/androidTest/kotlin" / kotlin_relative).read_text(encoding="utf-8")
        compact_harness = " ".join(harness.split())
        assert f'acquire_tagger_for_instrumentation( "{backend}", registration )' in compact_harness
        assert 'result.getString("tagger_path")' in kotlin
