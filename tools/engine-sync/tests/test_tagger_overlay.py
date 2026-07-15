from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import threading
import time
import unittest

TAGGER_PATH = (
    Path(__file__).parents[3] / "app/src/main/python/anki_miner/services/tagger.py"
)


def _load_tagger():
    spec = importlib.util.spec_from_file_location(
        f"android_tagger_probe_{id(object())}", TAGGER_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TaggerOverlayTests(unittest.TestCase):
    def test_neutral_engine_closure_has_no_fugashi_dependency(self) -> None:
        manifest = json.loads(
            (TAGGER_PATH.parents[2] / ".engine-sync-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        external = manifest["external_imports"]
        self.assertNotIn("fugashi", external["eager"])
        self.assertNotIn("fugashi", external["deferred"])
        self.assertEqual(
            "overlay",
            manifest["files"]["anki_miner/services/tagger.py"]["origin"],
        )

    def test_backend_is_mandatory_and_process_immutable(self) -> None:
        tagger = _load_tagger()
        with self.assertRaisesRegex(RuntimeError, "has not been configured"):
            tagger.get_shared_tagger()

        factory = lambda: object()
        tagger.configure_tagger_factory(factory)
        tagger.configure_tagger_factory(factory)
        shared = tagger.get_shared_tagger()
        self.assertIs(shared, tagger.get_shared_tagger())
        with self.assertRaisesRegex(RuntimeError, "after first use"):
            tagger.configure_tagger_factory(factory)

    def test_concurrent_first_use_builds_once_and_serializes_calls(self) -> None:
        tagger = _load_tagger()
        guard = threading.Lock()
        builds = 0
        active = 0
        maximum = 0

        class SlowTagger:
            def __call__(self, text: str) -> str:
                nonlocal active, maximum
                with guard:
                    active += 1
                    maximum = max(maximum, active)
                time.sleep(0.01)
                with guard:
                    active -= 1
                return text

        def factory() -> SlowTagger:
            nonlocal builds
            builds += 1
            return SlowTagger()

        tagger.configure_tagger_factory(factory)
        results: list[str] = []
        threads = [
            threading.Thread(
                target=lambda: results.append(tagger.get_shared_tagger()("猫"))
            )
            for _ in range(6)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(1, builds)
        self.assertEqual(1, maximum)
        self.assertEqual(["猫"] * 6, sorted(results))


if __name__ == "__main__":
    unittest.main()
