from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"


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


def test_initialize_sets_home_before_engine_import(tmp_path: Path) -> None:
    result = _run(
        """
import json, os, sys
from android_bridge.bootstrap import initialize
raw = initialize(sys.argv[1])
from anki_miner.config.paths import ANKI_MINER_HOME
print(json.dumps({"message": json.loads(raw), "env": os.environ["ANKI_MINER_HOME"], "home": str(ANKI_MINER_HOME)}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["message"]["type"] == "bootstrap.ready"
    assert data["env"] == data["home"] == str(tmp_path)


def test_initialize_detects_engine_imported_with_the_wrong_home(tmp_path: Path) -> None:
    wrong_home = tmp_path / "wrong"
    result = _run(
        f"""
import os, sys
os.environ["ANKI_MINER_HOME"] = {str(wrong_home)!r}
from anki_miner.config.paths import ANKI_MINER_HOME
from android_bridge.bootstrap import initialize
try:
    initialize(sys.argv[1])
except Exception as exc:
    print(getattr(exc, "code", ""))
    raise
""",
        tmp_path / "right",
    )

    assert result.returncode != 0
    assert "engine_imported_before_bootstrap" in result.stdout


def test_initialize_rejects_relative_home_without_mutating_environment() -> None:
    result = _run(
        """
import os
from android_bridge.bootstrap import initialize
try:
    initialize("relative/files")
except Exception as exc:
    print(getattr(exc, "code", ""), os.environ.get("ANKI_MINER_HOME"))
    raise
""",
        Path("/unused"),
    )

    assert result.returncode != 0
    assert "invalid_files_dir None" in result.stdout


def test_require_initialized_fails_without_importing_engine(tmp_path: Path) -> None:
    result = _run(
        """
import sys
from android_bridge.bootstrap import require_initialized
try:
    require_initialized()
except Exception as exc:
    print(getattr(exc, "code", ""), any(name.startswith("anki_miner") for name in sys.modules))
    raise
""",
        tmp_path,
    )

    assert result.returncode != 0
    assert "bootstrap_required False" in result.stdout


def test_require_initialized_uses_canonical_same_home_check(tmp_path: Path) -> None:
    real_home = tmp_path / "real"
    real_home.mkdir()
    alias_home = tmp_path / "alias"
    alias_home.symlink_to(real_home, target_is_directory=True)
    other_home = tmp_path / "other"
    result = _run(
        f"""
import sys
from android_bridge.bootstrap import initialize, require_initialized
initialize(sys.argv[1])
print(require_initialized({str(alias_home)!r}))
try:
    require_initialized({str(other_home)!r})
except Exception as exc:
    print(getattr(exc, "code", ""))
""",
        real_home,
    )

    assert result.returncode == 0, result.stderr
    assert str(real_home) in result.stdout
    assert "home_mismatch" in result.stdout


def test_require_initialized_detects_environment_mutation(tmp_path: Path) -> None:
    result = _run(
        """
import os, sys
from android_bridge.bootstrap import initialize, require_initialized
initialize(sys.argv[1])
os.environ["ANKI_MINER_HOME"] = "/different"
try:
    require_initialized()
except Exception as exc:
    print(getattr(exc, "code", ""))
    raise
""",
        tmp_path,
    )

    assert result.returncode != 0
    assert "home_mismatch" in result.stdout


def test_initialize_installs_capped_file_handler_capturing_engine_warnings(tmp_path: Path) -> None:
    result = _run(
        """
import json, logging, logging.handlers, sys
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
logging.getLogger("anki_miner.services.media_extractor").warning("ffmpeg exit code 1: probe")
handlers = [
    handler
    for handler in logging.getLogger().handlers
    if isinstance(handler, logging.handlers.RotatingFileHandler)
]
handler = handlers[0]
handler.flush()
print(json.dumps({
    "count": len(handlers),
    "file": handler.baseFilename,
    "maxBytes": handler.maxBytes,
    "backupCount": handler.backupCount,
    "content": open(handler.baseFilename, encoding="utf-8").read(),
}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["count"] == 1
    assert data["file"] == str(tmp_path / "anki_miner.log")
    assert data["maxBytes"] == 4_194_304
    assert data["backupCount"] == 1
    assert "ffmpeg exit code 1: probe" in data["content"]
    assert "anki_miner.services.media_extractor" in data["content"]


def test_run_warning_summary_resets_counts_between_runs(tmp_path: Path) -> None:
    result = _run(
        """
import json, logging, logging.handlers, sys
from android_bridge.bootstrap import initialize
from android_bridge.jobs import JobRegistry
initialize(sys.argv[1])
registry = JobRegistry()
first = registry.begin()
logging.getLogger("anki_miner.first").warning("first warning")
registry.finish(first.run_id)
second = registry.begin()
logging.getLogger("anki_miner.second").error("second error")
registry.finish(second.run_id)
handler = next(
    h for h in logging.getLogger().handlers if isinstance(h, logging.handlers.RotatingFileHandler)
)
handler.flush()
lines = open(handler.baseFilename, encoding="utf-8").read().splitlines()
print(json.dumps([line.split("android_bridge.bootstrap: ", 1)[-1] for line in lines if "run.summary " in line]))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == [
        "run.summary outcome=ok warnings=1 errors=0 by=anki_miner.first:1",
        "run.summary outcome=ok warnings=0 errors=1 by=anki_miner.second:1",
    ]


def test_installed_log_line_carries_a_stamped_run_id(tmp_path: Path) -> None:
    """A vendored-style logger name is stamped with no edit to anki_miner.

    The timestamp prefix must also match Kotlin's LogRecord.kt layout
    byte-for-byte, since `sort` depends on it to interleave the two files.
    """

    result = _run(
        r"""
import json, logging, logging.handlers, re, sys
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
logging.getLogger("anki_miner.services.media_extractor").warning("ffmpeg exit code 1: probe")
handler = next(
    h for h in logging.getLogger().handlers if isinstance(h, logging.handlers.RotatingFileHandler)
)
handler.flush()
content = open(handler.baseFilename, encoding="utf-8").read()
line = next(l for l in content.splitlines() if "ffmpeg exit code 1: probe" in l)
print(json.dumps({
    "line": line,
    "timestamp_ok": bool(re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z ", line)),
}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["timestamp_ok"], data["line"]
    assert "run=-" in data["line"]
    assert "anki_miner.services.media_extractor" in data["line"]


def test_third_party_noise_is_capped_but_first_party_debug_reaches_the_file(
    tmp_path: Path,
) -> None:
    """The actual threat model is a verbose toggle that lifts the root logger.

    Elevating only the ``anki_miner`` tree (as an earlier version of this test
    did) never exercises the third-party ceiling at all: root stays clamped at
    INFO from ``initialize()``, which already blocks a DEBUG record on
    ``urllib3.connectionpool`` on its own, pin or no pin. Root DEBUG is the
    condition under which the per-logger ceiling is the only thing left
    standing between a query string and the file.

    Also covers the leak the flat WARNING ceiling alone would miss: urllib3's
    connectionpool logs the retry URL, query string included, at WARNING
    itself (a flaky mobile network hitting Jisho is the common case, not an
    edge case), so ``urllib3.connectionpool`` needs its own ceiling above
    WARNING rather than inheriting the plain ``urllib3`` pin.

    The first-party trees are raised explicitly here because ``initialize()``
    now pins them at INFO explicitly too, which is what makes the shipped
    toggle unable to lift anything else. Root DEBUG alone no longer reaches
    them, so this scenario is deliberately worse than any the app can produce.
    """

    result = _run(
        """
import json, logging, logging.handlers, sys
from android_bridge import log_context
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
logging.getLogger().setLevel(logging.DEBUG)
log_context.set_first_party_log_level(logging.DEBUG)
logging.getLogger("anki_miner.services.media_extractor").debug("first party debug line")
logging.getLogger("urllib3.connectionpool").debug(
    "GET /api/v1/search/words?keyword=%E6%AE%BA%E3%81%99"
)
logging.getLogger("urllib3.connectionpool").warning(
    "Retrying (Retry(total=2)) after connection broken by "
    "'ProtocolError': /api/v1/search/words?keyword=%E6%AE%BA%E3%81%99"
)
handler = next(
    h for h in logging.getLogger().handlers if isinstance(h, logging.handlers.RotatingFileHandler)
)
handler.flush()
content = open(handler.baseFilename, encoding="utf-8").read()
print(json.dumps({"content": content}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    content = json.loads(result.stdout)["content"]
    assert "first party debug line" in content
    assert "keyword=" not in content


def test_the_verbose_toggle_reaches_the_file_without_reopening_the_url_leak(
    tmp_path: Path,
) -> None:
    """The same guarantee as the test above, but reached the way a tester reaches it.

    That test lifts the root logger by hand. This one goes through the real
    request path, which is what ships: ``diagnostics.loglevel.set`` must raise
    the first-party trees far enough that engine DEBUG lands in the file, and
    must leave root and the third-party ceilings exactly where bootstrap put
    them -- a bundle assembled right after the toggle is the worst possible
    place for a percent-encoded mined term to appear.
    """

    result = _run(
        """
import json, logging, logging.handlers, sys
from android_bridge import dispatch
from android_bridge.bootstrap import initialize
from android_bridge.protocol import encode_message
initialize(sys.argv[1])
applied = dispatch(encode_message("diagnostics.loglevel.set", {"level": "debug"}))
logging.getLogger("anki_miner.services.media_extractor").debug("first party debug line")
logging.getLogger("urllib3.connectionpool").warning(
    "Retrying (Retry(total=2)) after connection broken by "
    "'ProtocolError': /api/v1/search/words?keyword=%E6%AE%BA%E3%81%99"
)
handler = next(
    h for h in logging.getLogger().handlers if isinstance(h, logging.handlers.RotatingFileHandler)
)
handler.flush()
print(json.dumps({
    "applied": json.loads(applied),
    "root": logging.getLogger().level,
    "content": open(handler.baseFilename, encoding="utf-8").read(),
}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["applied"]["type"] == "diagnostics.loglevel.applied"
    assert data["root"] == logging.INFO
    assert "first party debug line" in data["content"]
    assert "keyword=" not in data["content"]


def test_install_failure_stashes_traceback_and_writes_stderr_without_breaking_bootstrap(
    tmp_path: Path,
) -> None:
    # A directory in the log file's place makes RotatingFileHandler's open()
    # fail (IsADirectoryError), forcing the except branch this test targets.
    (tmp_path / "anki_miner.log").mkdir()

    result = _run(
        """
import json, sys
from android_bridge import bootstrap
raw = bootstrap.initialize(sys.argv[1])
print(json.dumps({
    "message": json.loads(raw)["type"],
    "error": bootstrap.log_handler_install_error(),
}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    data = json.loads(result.stdout)
    assert data["message"] == "bootstrap.ready"
    assert data["error"] is not None
    assert "Traceback" in data["error"]
    assert "Traceback" in result.stderr


def test_initialize_twice_installs_exactly_one_handler(tmp_path: Path) -> None:
    result = _run(
        """
import json, logging, logging.handlers, sys
from android_bridge.bootstrap import initialize
initialize(sys.argv[1])
initialize(sys.argv[1])
handlers = [
    handler
    for handler in logging.getLogger().handlers
    if isinstance(handler, logging.handlers.RotatingFileHandler)
]
print(json.dumps({"count": len(handlers)}))
""",
        tmp_path,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {"count": 1}


def test_unwritable_log_destination_does_not_break_bootstrap(tmp_path: Path) -> None:
    home = tmp_path / "home"
    home.mkdir(mode=0o500)
    try:
        result = _run(
            """
import json, sys
from android_bridge.bootstrap import initialize
raw = initialize(sys.argv[1])
print(json.dumps({"message": json.loads(raw)["type"]}))
""",
            home,
        )
    finally:
        home.chmod(0o700)

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {"message": "bootstrap.ready"}


def test_bridge_modules_have_no_top_level_engine_imports() -> None:
    import ast

    bridge_root = PYTHON_ROOT / "android_bridge"
    offenders: list[str] = []
    for path in bridge_root.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in tree.body:
            if (
                isinstance(node, ast.Import)
                and any(alias.name.startswith("anki_miner") for alias in node.names)
                or isinstance(node, ast.ImportFrom)
                and (node.module or "").startswith("anki_miner")
            ):
                offenders.append(f"{path.name}:{node.lineno}")

    assert offenders == []
