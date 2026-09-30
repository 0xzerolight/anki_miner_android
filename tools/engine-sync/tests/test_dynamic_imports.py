from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from engine_sync.core import EngineSyncError, build_snapshot


LANGUAGES_INIT = (
    "from __future__ import annotations\n"
    'AVAILABLE_LANGUAGES: tuple[str, ...] = ("ja", "ko", "zh")\n'
    'SHARED_PACK_CODES: tuple[str, ...] = ("_spacy",)\n'
)
REGISTRY = (
    "import importlib\n\n"
    "def load(code):\n"
    '    return importlib.import_module(f"anki_miner.languages.{code}")\n'
)
TAGGER_PROVIDER = (
    "def build(language):\n"
    "    import importlib\n\n"
    '    return importlib.import_module(f"anki_miner.languages.{language}.tokenizer")\n'
)
ROOT = "from anki_miner.languages import registry, tagger_provider\n"
REGISTRY_DECLARATION = """
[[dynamic_imports]]
importer = "anki_miner.languages.registry"
match = "f'anki_miner.languages.{code}'"
target = "anki_miner.languages.{code}"
"""
TOKENIZER_DECLARATION = """
[[dynamic_imports]]
importer = "anki_miner.languages.tagger_provider"
match = "f'anki_miner.languages.{language}.tokenizer'"
target = "anki_miner.languages.{code}.tokenizer"
"""
BOTH_DECLARATIONS = REGISTRY_DECLARATION + TOKENIZER_DECLARATION
LEGACY_MANIFEST_KEYS = {
    "composition_sha256",
    "engine_revision",
    "external_imports",
    "files",
    "format_version",
    "modules",
}


def _run(*args: str, cwd: Path) -> str:
    result = subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


class DynamicImportCompositionTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _snapshot(
        self,
        *,
        extra_keys: str = "",
        tables: str = "",
        root: str = ROOT,
        files: dict[str, str] | None = None,
    ):
        repo = self.root / "desktop"
        sources = {
            "anki_miner/__init__.py": "",
            "anki_miner/root.py": root,
            "anki_miner/languages/__init__.py": LANGUAGES_INIT,
            "anki_miner/languages/registry.py": REGISTRY,
            "anki_miner/languages/tagger_provider.py": TAGGER_PROVIDER,
            "anki_miner/languages/ja/__init__.py": "",
            "anki_miner/languages/ko/__init__.py": "",
            "anki_miner/languages/ko/tokenizer.py": "",
            "anki_miner/languages/zh/__init__.py": "",
            "anki_miner/languages/zh/tokenizer.py": "",
            "anki_miner/languages/_spacy/__init__.py": "",
            "anki_miner/services/__init__.py": "",
            "anki_miner/services/asr/__init__.py": "import numpy\n",
            "anki_miner/services/asr/transcriber.py": "import numpy\n",
            **(files or {}),
        }
        for path, content in sources.items():
            target = repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        _run("git", "init", "-q", cwd=repo)
        _run("git", "config", "user.email", "tests@example.invalid", cwd=repo)
        _run("git", "config", "user.name", "Engine Sync Tests", cwd=repo)
        _run("git", "add", ".", cwd=repo)
        _run("git", "commit", "-qm", "fixture", cwd=repo)

        tooling = self.root / "tooling"
        (tooling / "overrides").mkdir(parents=True)
        lock = tooling / "engine.lock"
        lock.write_text(
            _run("git", "rev-parse", "HEAD", cwd=repo) + "\n", encoding="ascii"
        )
        composition = tooling / "composition.toml"
        composition.write_text(
            "format_version = 1\n"
            'roots = ["anki_miner.root"]\n'
            "assets = []\n"
            "protected_verbatim = []\n"
            "allowed_external = []\n"
            'allowed_stdlib = ["__future__", "importlib"]\n'
            "local_only_imports = []\n"
            "forbidden_imports = []\n"
            "overlay_allowlist = []\n" + extra_keys + tables,
            encoding="utf-8",
        )
        return build_snapshot(
            source_repo=repo,
            lock_path=lock,
            composition_path=composition,
            overlays_path=tooling / "overrides",
        )

    def _manifest(self, snapshot) -> dict:
        return json.loads(snapshot.manifest_bytes())

    # Success paths

    def test_absent_keys_keep_the_legacy_manifest_shape(self) -> None:
        snapshot = self._snapshot(root="VALUE = 1\n")

        self.assertEqual(snapshot.modules, ("anki_miner", "anki_miner.root"))
        self.assertEqual(set(self._manifest(snapshot)), LEGACY_MANIFEST_KEYS)

    def test_languages_expand_declared_dynamic_imports(self) -> None:
        snapshot = self._snapshot(
            extra_keys='languages = ["ja", "ko"]\n', tables=BOTH_DECLARATIONS
        )

        self.assertIn("anki_miner.languages.ja", snapshot.modules)
        self.assertIn("anki_miner.languages.ko", snapshot.modules)
        self.assertIn("anki_miner.languages.ko.tokenizer", snapshot.modules)
        self.assertIn("anki_miner.languages._spacy", snapshot.modules)
        self.assertNotIn("anki_miner.languages.zh", snapshot.modules)
        self.assertNotIn("anki_miner.languages.zh.tokenizer", snapshot.modules)
        self.assertNotIn("anki_miner/languages/zh/__init__.py", snapshot.files)

        manifest = self._manifest(snapshot)
        self.assertEqual(manifest["languages"], ["ja", "ko"])
        self.assertEqual(
            manifest["dynamic_imports"],
            [
                {
                    "importer": "anki_miner.languages.registry",
                    "match": "f'anki_miner.languages.{code}'",
                    "target": "anki_miner.languages.{code}",
                    "modules": [
                        "anki_miner.languages._spacy",
                        "anki_miner.languages.ja",
                        "anki_miner.languages.ko",
                    ],
                },
                {
                    "importer": "anki_miner.languages.tagger_provider",
                    "match": "f'anki_miner.languages.{language}.tokenizer'",
                    "target": "anki_miner.languages.{code}.tokenizer",
                    "modules": ["anki_miner.languages.ko.tokenizer"],
                },
            ],
        )
        self.assertNotIn("deferred_unavailable", manifest)

    def test_declaration_may_expand_to_nothing_for_the_selected_languages(self) -> None:
        # ja ships no tokenizer; ko's proves the template names a real module.
        snapshot = self._snapshot(
            extra_keys='languages = ["ja"]\n', tables=BOTH_DECLARATIONS
        )

        self.assertNotIn("anki_miner.languages.ko.tokenizer", snapshot.modules)
        tokenizer = self._manifest(snapshot)["dynamic_imports"][1]
        self.assertEqual(tokenizer["importer"], "anki_miner.languages.tagger_provider")
        self.assertEqual(tokenizer["modules"], [])

    def test_languages_alone_vendor_nothing_extra(self) -> None:
        snapshot = self._snapshot(extra_keys='languages = ["ja"]\n', root="VALUE = 1\n")

        self.assertEqual(snapshot.modules, ("anki_miner", "anki_miner.root"))
        self.assertEqual(self._manifest(snapshot)["languages"], ["ja"])

    def test_expanded_targets_are_deferred_imports(self) -> None:
        snapshot = self._snapshot(
            extra_keys=(
                'languages = ["ja", "zh"]\n'
                'deferred_unavailable = ["anki_miner.languages.zh"]\n'
            ),
            tables=REGISTRY_DECLARATION,
            root="from anki_miner.languages import registry\n",
        )

        self.assertNotIn("anki_miner.languages.zh", snapshot.modules)
        self.assertEqual(
            self._manifest(snapshot)["deferred_unavailable"],
            [
                {
                    "importer": "anki_miner.languages.registry",
                    "module": "anki_miner.languages.zh",
                }
            ],
        )

    def test_deferred_unavailable_imports_are_recorded_not_followed(self) -> None:
        snapshot = self._snapshot(
            extra_keys='deferred_unavailable = ["anki_miner.services.asr"]\n',
            root=(
                "def transcribe():\n"
                "    from anki_miner.services.asr.transcriber import run\n"
                "    from anki_miner.services import asr\n"
                "    return run, asr\n"
            ),
        )

        self.assertIn("anki_miner.services", snapshot.modules)
        self.assertNotIn("anki_miner.services.asr", snapshot.modules)
        self.assertNotIn("anki_miner.services.asr.transcriber", snapshot.modules)
        self.assertEqual(
            self._manifest(snapshot)["deferred_unavailable"],
            [
                {"importer": "anki_miner.root", "module": "anki_miner.services.asr"},
                {
                    "importer": "anki_miner.root",
                    "module": "anki_miner.services.asr.transcriber",
                },
            ],
        )

    # Error paths

    def test_language_outside_available_languages_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError, r"languages not in AVAILABLE_LANGUAGES: xx"
        ):
            self._snapshot(extra_keys='languages = ["ja", "xx"]\n')

    def test_languages_need_a_literal_available_languages_tuple(self) -> None:
        cases = {
            "missing": "SHARED_PACK_CODES = ()\n",
            "computed": "AVAILABLE_LANGUAGES = tuple(sorted({'ja'}))\n",
            "rebound": "AVAILABLE_LANGUAGES = ('ja',)\nAVAILABLE_LANGUAGES += ('ko',)\n",
        }
        for label, source in cases.items():
            with self.subTest(label):
                self._temporary.cleanup()
                self._temporary = tempfile.TemporaryDirectory()
                self.root = Path(self._temporary.name)
                with self.assertRaisesRegex(EngineSyncError, "AVAILABLE_LANGUAGES"):
                    self._snapshot(
                        extra_keys='languages = ["ja"]\n',
                        files={"anki_miner/languages/__init__.py": source},
                    )

    def test_undeclared_non_literal_dynamic_import_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError,
            r"registry.py:4: dynamic import target must be .*"
            r"f'anki_miner.languages.\{code\}'",
        ):
            self._snapshot(
                extra_keys='languages = ["ja", "ko"]\n', tables=TOKENIZER_DECLARATION
            )

    def test_unused_declaration_is_rejected(self) -> None:
        stale = BOTH_DECLARATIONS + (
            "\n[[dynamic_imports]]\n"
            'importer = "anki_miner.languages.registry"\n'
            "match = \"f'anki_miner.languages.{lang}'\"\n"
            'target = "anki_miner.languages.{code}"\n'
        )
        with self.assertRaisesRegex(
            EngineSyncError,
            r"unused \[\[dynamic_imports\]\] declarations: "
            r"anki_miner.languages.registry: f'anki_miner.languages.\{lang\}'",
        ):
            self._snapshot(extra_keys='languages = ["ja", "ko"]\n', tables=stale)

    def test_declaration_for_an_unselected_importer_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError, "unused \\[\\[dynamic_imports\\]\\]"
        ):
            self._snapshot(
                extra_keys='languages = ["ja"]\n',
                tables=REGISTRY_DECLARATION,
                root="VALUE = 1\n",
            )

    def test_declaration_without_an_existing_module_is_rejected(self) -> None:
        missing = TOKENIZER_DECLARATION.replace(
            'target = "anki_miner.languages.{code}.tokenizer"',
            'target = "anki_miner.languages.{code}.pack"',
        )
        with self.assertRaisesRegex(
            EngineSyncError,
            r"anki_miner.languages.\{code\}.pack matches no module",
        ):
            self._snapshot(
                extra_keys='languages = ["ja", "ko"]\n',
                tables=REGISTRY_DECLARATION + missing,
            )

    def test_malformed_declarations_are_rejected(self) -> None:
        cases = {
            "missing key": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\n',
                "exactly importer, match and target",
            ),
            "extra key": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\n'
                'target = "x.{code}"\ncodes = []\n',
                "exactly importer, match and target",
            ),
            "no placeholder": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\ntarget = "x.y"\n',
                r"only the \{code\} placeholder",
            ),
            "other placeholder": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\n'
                'target = "x.{code}.{lang}"\n',
                r"only the \{code\} placeholder",
            ),
            "not a module name": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\n'
                'target = ".x.{code}"\n',
                "absolute dotted module name",
            ),
            "duplicate": (
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\ntarget = "x.{code}"\n'
                '[[dynamic_imports]]\nimporter = "a"\nmatch = "b"\ntarget = "y.{code}"\n',
                "duplicate",
            ),
        }
        for label, (tables, message) in cases.items():
            with self.subTest(label):
                self._temporary.cleanup()
                self._temporary = tempfile.TemporaryDirectory()
                self.root = Path(self._temporary.name)
                with self.assertRaisesRegex(EngineSyncError, message):
                    self._snapshot(tables=tables)

    def test_eager_import_of_deferred_unavailable_module_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError,
            r"root.py:1: eager import of deferred-unavailable anki_miner.services.asr$",
        ):
            self._snapshot(
                extra_keys='deferred_unavailable = ["anki_miner.services.asr"]\n',
                root="from anki_miner.services.asr import transcriber\n",
            )

    def test_unreached_deferred_unavailable_prefix_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError,
            "deferred_unavailable prefixes never reached: anki_miner.gui",
        ):
            self._snapshot(
                extra_keys='deferred_unavailable = ["anki_miner.gui"]\n',
                root="VALUE = 1\n",
            )

    def test_selected_module_cannot_be_deferred_unavailable(self) -> None:
        with self.assertRaisesRegex(
            EngineSyncError, "deferred-unavailable module is selected: anki_miner.root"
        ):
            self._snapshot(
                extra_keys='deferred_unavailable = ["anki_miner.root"]\n',
                root="VALUE = 1\n",
            )


if __name__ == "__main__":
    unittest.main()
