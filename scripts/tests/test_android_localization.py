from __future__ import annotations

import importlib.util
import re
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
AUDIT = REPO_ROOT / "tools/localization/audit_android_localizations.py"


class AndroidLocalizationAuditTest(unittest.TestCase):
    @staticmethod
    def _source_strings() -> dict[str, str]:
        root = ET.parse(REPO_ROOT / "app/src/main/res/values/strings.xml").getroot()
        return {element.attrib["name"]: "".join(element.itertext()).strip() for element in root.findall("string")}

    @staticmethod
    def _load_engine_module(relative: str, name: str):  # noqa: ANN205
        spec = importlib.util.spec_from_file_location(name, REPO_ROOT / relative)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    @staticmethod
    def _rewriter_source() -> str:
        return (REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/localization/EngineNoticeRewriter.kt").read_text(
            encoding="utf-8"
        )

    def _assert_distinct_non_empty_resources(self, resources: list[str]) -> None:
        strings = self._source_strings()
        self.assertEqual(len(resources), len(set(resources)), resources)
        rendered = [strings.get(resource, "") for resource in resources]
        self.assertTrue(all(rendered), list(zip(resources, rendered, strict=True)))
        self.assertEqual(len(rendered), len(set(rendered)), list(zip(resources, rendered, strict=True)))

    def _run_audit(self, resource_root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(AUDIT), "--resource-root", str(resource_root)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    @staticmethod
    def _write_catalog(resource_root: Path, directory: str, body: str) -> None:
        catalog = resource_root / directory / "strings.xml"
        catalog.parent.mkdir(parents=True, exist_ok=True)
        catalog.write_text(
            f'<?xml version="1.0" encoding="utf-8"?>\n<resources>{body}</resources>\n',
            encoding="utf-8",
        )

    def test_repository_catalogs_are_format_safe_and_report_translation_backlog(self) -> None:
        result = self._run_audit(REPO_ROOT / "app/src/main/res")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("locale catalog(s) verified", result.stdout)
        self.assertIn("values-ja/strings.xml", result.stdout)
        self.assertIn("catalog(s) with untranslated keys", result.stdout)

    def test_untranslated_keys_are_advisory_and_never_fail(self) -> None:
        """Android resolves an absent key from ``values/``, so an incomplete locale still ships.

        This mirrors the desktop repository, where ``scripts/i18n.py`` writes
        ``type="unfinished"`` entries and only ``i18n_payload_check.py`` -- a regression gate --
        can fail. Completeness is a backlog, not a merge blocker.
        """
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(
                resource_root,
                "values",
                '<string name="alpha">Alpha</string><string name="beta">Beta</string>',
            )
            self._write_catalog(resource_root, "values-fr", '<string name="alpha">Alpha FR</string>')

            result = self._run_audit(resource_root)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(
                "advisory: values-fr/strings.xml: 1 untranslated key(s), English is used: beta",
                result.stdout,
            )
            self.assertIn("1 catalog(s) with untranslated keys", result.stdout)

    def test_orphan_keys_fail_with_exact_catalog_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(resource_root, "values", '<string name="alpha">Alpha</string>')
            self._write_catalog(
                resource_root,
                "values-fr",
                '<string name="alpha">Alpha FR</string><string name="gamma">Gamma FR</string>',
            )

            result = self._run_audit(resource_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("values-fr/strings.xml: extra keys: gamma", result.stderr)

    def test_duplicate_keys_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(resource_root, "values", '<string name="alpha">Alpha</string>')
            self._write_catalog(
                resource_root,
                "values-fr",
                '<string name="alpha">Alpha FR</string><string name="alpha">Alpha encore</string>',
            )

            result = self._run_audit(resource_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("duplicate key: alpha", result.stderr)

    def test_printf_placeholder_type_and_count_mismatches_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(
                resource_root,
                "values",
                # `spare` is untranslated below: an advisory must not stop the audit before it
                # reaches the placeholder check on the key that IS translated.
                '<string name="progress">%1$d of %2$d (%3$s)</string><string name="spare">Spare</string>',
            )
            self._write_catalog(
                resource_root,
                "values-de",
                '<string name="progress">%1$s von %2$d</string>',
            )

            result = self._run_audit(resource_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("values-de/strings.xml: format mismatch for progress", result.stderr)
            self.assertIn("source=", result.stderr)
            self.assertIn("translation=", result.stderr)

    def test_plural_placeholder_mismatches_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(
                resource_root,
                "values",
                (
                    '<string name="alpha">Alpha</string>'
                    '<plurals name="copies">'
                    '<item quantity="one">%1$d copy</item>'
                    '<item quantity="other">%1$d copies</item>'
                    "</plurals>"
                ),
            )
            self._write_catalog(
                resource_root,
                "values-ja",
                (
                    '<string name="alpha">アルファ</string>'
                    '<plurals name="copies">'
                    '<item quantity="other">%1$s 件</item>'
                    "</plurals>"
                ),
            )

            result = self._run_audit(resource_root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("format mismatch for copies[other]", result.stderr)

    def test_positional_reordering_and_escaped_percent_are_safe(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(
                resource_root,
                "values",
                '<string name="score">%1$.1f%% for %2$s</string>',
            )
            self._write_catalog(
                resource_root,
                "values-ja",
                '<string name="score">%2$s：%1$.1f%%</string>',
            )

            result = self._run_audit(resource_root)

            self.assertEqual(0, result.returncode, result.stderr)

    def test_non_locale_values_qualifiers_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            resource_root = Path(temporary)
            self._write_catalog(resource_root, "values", '<string name="alpha">Alpha</string>')
            self._write_catalog(resource_root, "values-night", '<string name="theme_only">Dark</string>')

            result = self._run_audit(resource_root)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("0 locale catalog(s) verified", result.stdout)

    def test_user_facing_kotlin_state_does_not_assign_raw_english(self) -> None:
        files = {
            "application": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/AnkiMinerApplication.kt",
            "anki setup": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/anki/AnkiSetupManager.kt",
            "resources": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceManager.kt",
            "admission": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/mining/MiningRunAdmission.kt",
            "video mining": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/mining/BridgeMiningRepository.kt",
            "reading mining": REPO_ROOT
            / "app/src/main/kotlin/com/ankiminer/android/reading/BridgeReadingMiningRepository.kt",
            "settings": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/vm/SettingsViewModel.kt",
            "setup": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/vm/SetupViewModel.kt",
            "Anki recovery UI": REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/settings/AnkiSections.kt",
            "Anki remediation model": REPO_ROOT
            / "app/src/main/kotlin/com/ankiminer/android/anki/provider/AnkiRemediationService.kt",
        }
        patterns = (
            re.compile(r'error\.value\s*=\s*"'),
            re.compile(r'runOperation\(\s*"'),
            re.compile(r'(?:MiningProgress|MiningFailure|ProtocolFault|Blocked)\(\s*"'),
            re.compile(r'(?:recordFault|recordFaultAndCancel|setRestartRequired)\([^)]*?"', re.DOTALL),
            re.compile(r'recordFailure\(\s*"[^"]+"\s*,\s*"', re.DOTALL),
            re.compile(r'ReadingSourceStageRole\.[A-Z_]+\s*->\s*"'),
            re.compile(r"private fun userMessage[\s\S]*?\n\s*private fun[^\n]*\{"),
        )
        failures = []
        for label, path in files.items():
            source = path.read_text(encoding="utf-8")
            for pattern in patterns[:-1]:
                match = pattern.search(source)
                if match:
                    failures.append(f"{label}: {match.group(0)[:100]}")
            if label == "resources":
                user_message = patterns[-1].search(source)
                if user_message and re.search(r'->\s*"', user_message.group(0)):
                    failures.append("resources: userMessage returns raw text")
        setup_source = files["setup"].read_text(encoding="utf-8")
        for raw_default in ("Imported frequency", "Imported pitch accent"):
            if f'"{raw_default}"' in setup_source:
                failures.append(f"setup: raw default {raw_default}")
        self.assertEqual([], failures)

    def test_resource_download_and_bridge_codes_keep_distinct_messages(self) -> None:
        resource_manager = (
            REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceManager.kt"
        ).read_text(encoding="utf-8")
        download_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (
                REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/ArchiveSizeBudget.kt",
                REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/PinnedResourceDownloader.kt",
                REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/SafArchiveStager.kt",
                REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/resources/ResourceManager.kt",
            )
        )
        emitted_codes = set(re.findall(r'ResourceDownloadException\(\s*"([a-z0-9_]+)"', download_sources))
        emitted_codes.update({"download_http_retryable", "download_http_rejected"})
        download_message_match = re.search(
            r"private fun downloadUserMessage\(.*?\n\s*private fun userMessage",
            resource_manager,
            re.DOTALL,
        )
        self.assertIsNotNone(download_message_match)
        code_map = dict(
            re.findall(
                r'"([a-z0-9_]+)"\s*->\s*strings\.resolve\(\s*R\.string\.([a-z0-9_]+)',
                download_message_match.group(0),
            )
        )

        self.assertEqual(emitted_codes, set(code_map))
        self._assert_distinct_non_empty_resources(list(code_map.values()))
        self.assertRegex(
            resource_manager,
            r"else\s*->\s*strings\.resolve\(\s*R\.string\.resource_failure_unknown_download_code,\s*listOf\(failure\.stableCode\)",
        )
        self.assertRegex(
            resource_manager,
            r"else\s*->\s*strings\.resolve\(R\.string\.resource_failure_unknown_bridge_code,\s*listOf\(code\)\)",
        )

    def test_settings_validation_codes_keep_distinct_resources_and_arguments(self) -> None:
        settings_model = (
            REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/data/settings/AppSettings.kt"
        ).read_text(encoding="utf-8")
        settings_vm = (REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/vm/SettingsViewModel.kt").read_text(
            encoding="utf-8"
        )
        code_match = re.search(
            r"enum class InvalidAppSettingCode\s*\{(?P<body>.*?)\n\}",
            settings_model,
            re.DOTALL,
        )
        self.assertIsNotNone(code_match)
        codes = set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]+),?$", code_match.group("body"), re.MULTILINE))
        resource_map = dict(
            re.findall(
                r"InvalidAppSettingCode\.([A-Z0-9_]+)\s*->\s*LocalizedStringResource\(\s*R\.string\.([a-z0-9_]+)",
                settings_vm,
            )
        )

        self.assertEqual(codes, set(resource_map))
        self._assert_distinct_non_empty_resources(list(resource_map.values()))
        self.assertIn("failure.arguments", settings_vm)
        self.assertNotRegex(
            settings_vm,
            r"catch \(_:\s*InvalidAppSettingException\)\s*\{\s*error\.value = R\.string\.settings_save_failed",
        )

    def test_provider_error_codes_keep_distinct_target_verification_messages(self) -> None:
        status_model = (
            REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/anki/provider/NoteTypeSetup.kt"
        ).read_text(encoding="utf-8")
        application = (REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/AnkiMinerApplication.kt").read_text(
            encoding="utf-8"
        )
        expected_reasons = {
            "API_DISABLED",
            "API_INCOMPATIBLE",
            "API_DISABLED_OR_INCOMPATIBLE",
            "PERMISSION_REQUIRED",
            "PROVIDER_UNAVAILABLE",
            "PROVIDER_BECAME_UNAVAILABLE",
            "QUERY_FAILED",
            "TIMEOUT",
            "CANCELLED",
            "UNKNOWN",
        }
        reason_map = dict(
            re.findall(
                r"NoteTypeProviderErrorReason\.([A-Z0-9_]+)\s*->\s*strings\.resolve\(\s*R\.string\.([a-z0-9_]+)",
                application,
            )
        )

        self.assertIn("val reason: NoteTypeProviderErrorReason", status_model)
        self.assertIn("val code: AnkiErrorCode", status_model)
        self.assertEqual(expected_reasons, set(reason_map))
        self._assert_distinct_non_empty_resources(list(reason_map.values()))
        self.assertIn("status.code.wireName", application)

    def test_engine_notice_rewriter_still_matches_the_vendored_template(self) -> None:
        """Pin both halves of the rewrite: the vendored engine literal and the Kotlin regex.

        ``EngineNoticeRewriter`` restates a warning whose wording is owned by the vendored engine,
        so an ``engine.lock`` re-pin that reworded it would leave the rule matching nothing and the
        old copy back on screen with no test failing. Rendering the real template through the real
        ``tr_format`` and running the Kotlin pattern over the result fails closed on either drift.
        """
        template = "Skipped %1 words with no definition found: %2%3"
        processor = (REPO_ROOT / "app/src/main/python/anki_miner/orchestration/episode_processor.py").read_text(
            encoding="utf-8"
        )
        self.assertIn(template, processor)

        rewriter = self._rewriter_source()
        pattern_match = re.search(r'Regex\("""(.+?)"""', rewriter)
        self.assertIsNotNone(pattern_match, "EngineNoticeRewriter no longer declares a raw-string Regex")
        assert pattern_match is not None
        self.assertIn("mining_notice_no_definition", rewriter)
        self.assertIn("mining_notice_no_definition", self._source_strings())

        i18n = self._load_engine_module("app/src/main/python/anki_miner/utils/i18n.py", "_engine_i18n")

        rendered = i18n.tr_format(template, 2, "本好き, 編み", " (+3 more)")
        matched = re.fullmatch(pattern_match.group(1), rendered, re.DOTALL)
        self.assertIsNotNone(matched, rendered)
        assert matched is not None
        self.assertEqual(("2", "本好き, 編み (+3 more)"), matched.groups())

    def test_engine_receipt_patterns_still_match_the_vendored_literals(self) -> None:
        """Pin every suppression rule against the string the vendored engine actually renders.

        A dropped receipt is invisible by design, so an ``engine.lock`` re-pin that reworded one
        would put it back on the result screen with nothing failing anywhere. Each pattern is run
        over the real rendered text, through the engine's own ``tr_format`` and Qt shim.
        """
        i18n = self._load_engine_module("app/src/main/python/anki_miner/utils/i18n.py", "_engine_i18n")
        qtcore = self._load_engine_module("app/src/main/python/PyQt6/QtCore.py", "_engine_qtcore")
        translate = qtcore.QCoreApplication.translate

        processor = "app/src/main/python/anki_miner/orchestration/episode_processor.py"
        mokuro = "app/src/main/python/anki_miner/services/reading/mokuro_source.py"
        epub = "app/src/main/python/anki_miner/services/reading/epub_source.py"

        ambiguous = "Ambiguous reading review required for %1 word(s); current readings kept"
        duplicates = "Skipped %n word(s) Anki flagged as duplicates (same Expression)"
        webp = "Using WebP for animated screenshots — this ffmpeg build has no AVIF (libsvtav1) encoder."
        text_only = "text-only volume: pages have no paired images"
        # The reading loaders build their warnings as f-strings, so the pin is the source expression.
        page_miss = 'f"page {page_num}: no image matched {img_path!r}"'
        gaiji = 'f"Skipped {gaiji_total} inline image(s) (gaiji) that carried no text."'

        # (vendored file, the literal that must still be in it, what the user would have seen)
        receipts = [
            (processor, ambiguous, i18n.tr_format(ambiguous, 3)),
            (processor, duplicates, translate("EpisodeProcessor", duplicates, "", 3)),
            (processor, webp, webp),
            (mokuro, text_only, text_only),
            (mokuro, page_miss, f"page {12}: no image matched {'volume01/012.jpg'!r}"),
            (epub, gaiji, f"Skipped {4} inline image(s) (gaiji) that carried no text."),
        ]

        # Declaration order is the contract: the no-definition restatement pinned by the test above
        # comes first, the receipts follow in list order. A pattern wrapped onto a second line drops
        # out of findall, which the count catches rather than silently skipping it.
        patterns = re.findall(r'Regex\("""(.+?)"""', self._rewriter_source())
        self.assertEqual(len(receipts) + 1, len(patterns), patterns)

        for (relative, literal, rendered), pattern in zip(receipts, patterns[1:], strict=True):
            self.assertIn(literal, (REPO_ROOT / relative).read_text(encoding="utf-8"), relative)
            self.assertIsNotNone(re.fullmatch(pattern, rendered), (pattern, rendered))

    def test_extracted_mokuro_progress_copy_is_exact(self) -> None:
        strings = self._source_strings()

        self.assertEqual(
            "Preparing mokuro sidecar",
            strings["reading_progress_preparing_mokuro_sidecar"],
        )
        self.assertEqual(
            "Preparing mokuro images",
            strings["reading_progress_preparing_mokuro_images"],
        )


if __name__ == "__main__":
    unittest.main()
