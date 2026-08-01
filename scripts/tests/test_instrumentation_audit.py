from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
AUDIT = REPO_ROOT / "tools/instrumentation/audit_instrumentation.py"

FAULT_PATTERN = r"^f[0-9a-f]{8}$"


class InstrumentationAuditTest(unittest.TestCase):
    def _new_repo(self) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self._write(root, "tools/instrumentation/bare_catch_allowlist.tsv", "path\tlines\treason\n")
        self._write(root, "app/src/main/kotlin/example/Safe.kt", "package example\n")
        self._write(root, "app/src/main/python/android_bridge/safe.py", "value = 1\n")
        self._write(root, "app/src/main/python/android_bridge/faults.py", f'FAULT_ID_PATTERN = r"{FAULT_PATTERN}"\n')
        self._write(
            root,
            "app/src/main/python/android_bridge/schemas/mining.schema.json",
            json.dumps(
                {"$defs": {"terminalError": {"properties": {"faultId": {"type": "string", "pattern": FAULT_PATTERN}}}}}
            ),
        )
        self._write(
            root,
            "app/src/main/kotlin/com/ankiminer/android/engine/BridgeJsonCodec.kt",
            self._codec("BridgeJsonCodec", "faultIdPattern"),
        )
        self._write(
            root,
            "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceBridgeCodec.kt",
            self._codec("ResourceBridgeCodec", "faultId"),
        )
        self._write(
            root,
            "app/src/test/resources/contracts/mining_protocol_v1.json",
            json.dumps(
                {
                    "version": 1,
                    "valid": [{"message": {"payload": {"error": {"faultId": "f0123abcd"}}}}],
                    "invalid": [{"message": {"payload": {"error": {"faultId": "fZZZZZZZZ"}}}}],
                }
            ),
        )
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        return root

    @staticmethod
    def _codec(object_name: str, pattern_name: str) -> str:
        return f"""object {object_name} {{
    private val {pattern_name} = Regex("f[0-9a-f]{{8}}")
    private fun readBridgeError(payload: Map<String, Any>) {{
        val required = setOf("code", "message")
        val accepted =
            setOf(
                required,
                required + "requestType",
                required + "faultId",
                required + "requestType" + "faultId",
            )
        if (payload.keys !in accepted) error("bridge.error")
    }}
}}
"""

    @staticmethod
    def _write(root: Path, relative: str, content: str | bytes) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")

    def _run_audit(self, root: Path) -> subprocess.CompletedProcess[str]:
        if root != REPO_ROOT:
            subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)
        return subprocess.run(
            [sys.executable, str(AUDIT), "--repo-root", str(root)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_repository_passes_with_measured_bare_catch_baseline(self) -> None:
        result = self._run_audit(REPO_ROOT)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("193 bare catch site(s) verified", result.stdout)

    def test_bare_catch_needs_an_annotation_or_reasoned_allowlist_entry(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/kotlin/example/Bare.kt"
        self._write(
            root,
            relative,
            """fun recover() {
    try {
        work()
    } catch (_: Exception) {
        Unit
    }
}
""",
        )

        unlisted = self._run_audit(root)

        self.assertNotEqual(0, unlisted.returncode)
        self.assertIn(f"{relative}:4: bare catch is not annotated or allowlisted", unlisted.stderr)

        self._write(
            root,
            relative,
            """fun recover() {
    try {
        work()
    // instrumentation: silent — parse failure maps to Unit
    } catch (_: Exception) {
        Unit
    }
}
""",
        )
        annotated = self._run_audit(root)
        self.assertEqual(0, annotated.returncode, annotated.stderr)

        self._write(root, relative, "try { work() } catch (_: Exception) { Unit }\n")
        self._write(
            root,
            "tools/instrumentation/bare_catch_allowlist.tsv",
            f"path\tlines\treason\n{relative}\t1\t\n",
        )
        missing_reason = self._run_audit(root)
        self.assertNotEqual(0, missing_reason.returncode)
        self.assertIn("allowlist reason is required", missing_reason.stderr)

        self._write(
            root,
            "tools/instrumentation/bare_catch_allowlist.tsv",
            f"path\tlines\treason\n{relative}\t1\tlegacy discard, pinned at audit introduction\n",
        )
        allowlisted = self._run_audit(root)
        self.assertEqual(0, allowlisted.returncode, allowlisted.stderr)

    def test_print_stack_trace_method_reference_fails(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/kotlin/example/Console.kt"
        self._write(root, relative, "fun leak(errors: List<Throwable>) = errors.forEach(Throwable::printStackTrace)\n")

        result = self._run_audit(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"{relative}:1: direct printStackTrace method reference", result.stderr)

    def test_non_rethrowing_bound_catch_logs_before_other_work(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/kotlin/example/Bound.kt"
        self._write(
            root,
            relative,
            """fun recover() {
    try { work() } catch (failure: Exception) {
        prepareRecovery()
        rememberRecovery()
        recover()
        AppLog.e(LogComponent.APP, "recover", failure)
    }
}
""",
        )

        delayed = self._run_audit(root)

        self.assertNotEqual(0, delayed.returncode)
        self.assertIn(f"{relative}:2: non-rethrowing bound catch must log among its first statements", delayed.stderr)

        self._write(
            root,
            relative,
            """fun recover() {
    try { work() } catch (failure: Exception) {
        AppLog.e(LogComponent.APP, "recover", failure)
        recover()
    }
    try { work() } catch (failure: Exception) {
        throw failure
    }
}
""",
        )
        logged_or_rethrown = self._run_audit(root)
        self.assertEqual(0, logged_or_rethrown.returncode, logged_or_rethrown.stderr)

    def test_run_catching_get_or_null_requires_on_failure(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/kotlin/example/Result.kt"
        self._write(root, relative, "fun recover() = runCatching { work() }.getOrNull()\n")

        missing = self._run_audit(root)

        self.assertNotEqual(0, missing.returncode)
        self.assertIn(f"{relative}:1: runCatching getOrNull chain requires onFailure", missing.stderr)

        self._write(
            root,
            relative,
            'fun recover() = runCatching { work() }.onFailure { AppLog.e(LogComponent.APP, "work", it) }.getOrNull()\n',
        )
        handled = self._run_audit(root)
        self.assertEqual(0, handled.returncode, handled.stderr)

    def test_python_silent_except_needs_reason_annotation(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/python/android_bridge/silent.py"
        self._write(root, relative, "try:\n    work()\nexcept Exception:\n    pass\n")

        silent = self._run_audit(root)

        self.assertNotEqual(0, silent.returncode)
        self.assertIn(f"{relative}:3: silent except requires an intentional-silence annotation", silent.stderr)

        self._write(
            root,
            relative,
            "try:\n    work()\nexcept Exception:\n    # instrumentation: intentionally silent — logging would recurse\n    pass\n",
        )
        annotated = self._run_audit(root)
        self.assertEqual(0, annotated.returncode, annotated.stderr)

    def test_python_warning_and_error_in_except_require_exc_info(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/python/android_bridge/except_log.py"
        self._write(
            root,
            relative,
            """try:
    work()
except Exception as error:
    logger.warning("failed")
    logger.error("also failed")
""",
        )

        missing = self._run_audit(root)

        self.assertNotEqual(0, missing.returncode)
        self.assertIn(f"{relative}:4: logger.warning inside except requires exc_info=", missing.stderr)
        self.assertIn(f"{relative}:5: logger.error inside except requires exc_info=", missing.stderr)

        self._write(
            root,
            relative,
            """try:
    work()
except Exception as error:
    logger.warning("failed", exc_info=error)
    logger.error("also failed", exc_info=True)
""",
        )
        traced = self._run_audit(root)
        self.assertEqual(0, traced.returncode, traced.stderr)

    def test_type_name_cannot_be_a_log_calls_only_argument(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/python/android_bridge/type_log.py"
        self._write(root, relative, "logger.error(type(error).__name__)\n")

        result = self._run_audit(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"{relative}:1: type(x).__name__ cannot be a log call's sole argument", result.stderr)

    def test_tracked_text_source_with_real_nul_fails_at_byte_offset(self) -> None:
        root = self._new_repo()
        relative = "notes/evidence.md"
        self._write(root, relative, b"alpha\x00omega\n")

        result = self._run_audit(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"{relative}: NUL byte at offset 5", result.stderr)

    def test_bridge_error_codec_key_sets_cannot_drift(self) -> None:
        root = self._new_repo()
        relative = "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceBridgeCodec.kt"
        source = (root / relative).read_text(encoding="utf-8")
        self._write(root, relative, source.replace('required + "faultId",', 'required + "retryable",'))

        result = self._run_audit(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("bridge.error accepted key sets disagree", result.stderr)
        self.assertIn("ResourceBridgeCodec.kt", result.stderr)

        source = (root / relative).read_text(encoding="utf-8")
        self._write(root, relative, source.replace('error("bridge.error")', 'error("other.error")'))
        missing_codec = self._run_audit(root)
        self.assertNotEqual(0, missing_codec.returncode)
        self.assertIn("bridge.error faultId codec inventory mismatch", missing_codec.stderr)

    def test_fault_id_patterns_must_match_across_python_schema_and_kotlin(self) -> None:
        mutations = {
            "app/src/main/python/android_bridge/faults.py": 'FAULT_ID_PATTERN = r"^f[0-9A-F]{8}$"\n',
            "app/src/main/python/android_bridge/schemas/mining.schema.json": json.dumps(
                {"properties": {"faultId": {"pattern": "^f[0-9a-f]{7}$"}}}
            ),
            "app/src/main/kotlin/com/ankiminer/android/engine/BridgeJsonCodec.kt": self._codec(
                "BridgeJsonCodec", "faultIdPattern"
            ).replace("[0-9a-f]{8}", "[0-9a-f]{9}"),
        }
        for relative, content in mutations.items():
            with self.subTest(relative=relative):
                root = self._new_repo()
                self._write(root, relative, content)

                result = self._run_audit(root)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("faultId pattern mismatch", result.stderr)
                self.assertIn(relative, result.stderr)

    def test_fault_id_contract_corpus_keeps_valid_and_invalid_controls(self) -> None:
        root = self._new_repo()
        relative = "app/src/test/resources/contracts/mining_protocol_v1.json"
        self._write(
            root,
            relative,
            json.dumps(
                {
                    "version": 1,
                    "valid": [{"message": {"payload": {"error": {"faultId": "f0123abcd"}}}}],
                    "invalid": [{"message": {"payload": {"error": {"faultId": "f12345678"}}}}],
                }
            ),
        )

        result = self._run_audit(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid contract faultId unexpectedly matches", result.stderr)


if __name__ == "__main__":
    unittest.main()
