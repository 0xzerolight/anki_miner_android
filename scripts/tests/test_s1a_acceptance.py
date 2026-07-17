from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools.wheels import s1a_acceptance as acceptance


SOURCE = {"commit": "a" * 40, "tree": "b" * 40}
RECIPE_KEY = "c" * 64
BUILD_KEY = "d" * 64
CORPUS_SHA = "e" * 64


class S1aAcceptanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(
            json.dumps({"schema": 2, "recipe_key": RECIPE_KEY, "build_key": BUILD_KEY}),
            encoding="utf-8",
        )
        self.golden = self.root / "engine-v1.json"
        self.golden.write_text(
            json.dumps({"provenance": {"data": {"corpus_sha256": CORPUS_SHA}}}),
            encoding="utf-8",
        )
        self.apk = self.root / "app-device-debug.apk"
        self.apk.write_bytes(b"exact tested apk")
        self.receipt = self.root / "acceptance.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _sha(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _document(self) -> dict[str, object]:
        document: dict[str, object] = {
            "schema": acceptance.SCHEMA,
            "source": SOURCE,
            "publication": {
                "manifest_sha256": self._sha(self.manifest),
                "recipe_key": RECIPE_KEY,
                "build_key": BUILD_KEY,
            },
            "artifact": {
                "filename": self.apk.name,
                "sha256": self._sha(self.apk),
                "size_bytes": self.apk.stat().st_size,
                "application_id": "com.ankiminer.android",
                "variant": "deviceDebug",
                "abi": "arm64-v8a",
                "source_commit": SOURCE["commit"],
                "release_channel": acceptance.ACCEPTANCE_APK_CHANNEL,
            },
            "device": {
                "manufacturer": "Example",
                "model": "Midrange",
                "build_fingerprint": "example/device/build:36/id/release-keys",
                "api_level": 36,
                "abi": "arm64-v8a",
                "page_size_bytes": 16384,
                "total_memory_bytes": 4 * 1024**3,
            },
            "measurements": {
                "cold_init_ms": [2100.0, 2200.0, 2050.0],
            },
            "tokenizer_parity": {
                "passed": True,
                "test_class": acceptance.EXPECTED_TEST_CLASS,
                "corpus_sha256": CORPUS_SHA,
                "assertion_count": 10,
            },
            "novel_throughput": {
                "corpus_sha256": "f" * 64,
                "japanese_character_count": 100_000,
                "elapsed_ms": 2000.0,
                "characters_per_second": 50_000.0,
                "text_unit_count": 500,
                "word_count": 400,
                "lemma_count": 300,
            },
            "representative_mining": {
                "workload_id": acceptance.REPRESENTATIVE_WORKLOAD_ID,
                "corpus_sha256": "f" * 64,
                "total_words_found": 400,
                "candidate_count": 250,
                "selected_count": acceptance.REPRESENTATIVE_SELECTION_COUNT,
                "card_payload_count": acceptance.REPRESENTATIVE_SELECTION_COUNT,
                "cards_created": acceptance.REPRESENTATIVE_SELECTION_COUNT,
                "elapsed_ms": 2500.0,
                "peak_rss_bytes": 300 * 1024 * 1024,
                "completed": True,
            },
            "thresholds": {
                "cold_init_max_ms_exclusive": acceptance.COLD_INIT_LIMIT_MS,
                "peak_rss_max_bytes_inclusive": acceptance.PEAK_RSS_LIMIT_BYTES,
            },
        }
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        return document

    def _write(self, document: dict[str, object]) -> None:
        self.receipt.write_text(json.dumps(document), encoding="utf-8")

    def _validate(self) -> dict[str, object]:
        with mock.patch.object(acceptance, "_source_identity", return_value=SOURCE):
            return acceptance.validate(
                self.receipt,
                self.manifest,
                self.apk,
                self.root,
                self.golden,
            )

    def test_exact_physical_acceptance_receipt_passes(self) -> None:
        schema = json.loads(acceptance.SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual(acceptance.SCHEMA, schema["properties"]["schema"]["const"])
        measurements = schema["properties"]["measurements"]["properties"]
        self.assertEqual(
            4000,
            measurements["cold_init_ms"]["items"]["exclusiveMaximum"],
        )
        self.assertEqual(
            402653184,
            schema["properties"]["representative_mining"]["properties"]
            ["peak_rss_bytes"]["maximum"],
        )
        self._write(self._document())
        result = self._validate()
        self.assertEqual(BUILD_KEY, result["publication_build_key"])
        self.assertEqual(36, result["device_api_level"])
        rendered = self.receipt.read_text(encoding="utf-8")
        self.assertNotIn(str(self.manifest), rendered)
        self.assertNotIn(str(self.apk), rendered)
        self.assertNotIn("physical-device-1", rendered)

    def test_artifact_filename_must_match_explicit_apk(self) -> None:
        document = self._document()
        document["artifact"]["filename"] = "some-other.apk"  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "APK identity"):
            self._validate()

    def test_apk_metadata_must_bind_the_receipt_source(self) -> None:
        document = self._document()
        document["artifact"]["source_commit"] = "9" * 40  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "APK identity"):
            self._validate()

    def test_threshold_or_parity_failure_is_rejected(self) -> None:
        document = self._document()
        document["measurements"]["cold_init_ms"][1] = 4000.0  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "cold initialization"):
            self._validate()

        document = self._document()
        document["tokenizer_parity"]["passed"] = False  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "parity"):
            self._validate()

        document = self._document()
        document["novel_throughput"]["japanese_character_count"] = 49_999  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "corpus identity"):
            self._validate()

        document = self._document()
        document["representative_mining"]["cards_created"] = 99  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "representative mining"):
            self._validate()

        document = self._document()
        document["representative_mining"]["corpus_sha256"] = "9" * 64  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(
            acceptance._canonical_payload(document)
        ).hexdigest()
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "representative mining"):
            self._validate()

    def test_artifact_publication_and_payload_are_immutable(self) -> None:
        document = self._document()
        self._write(document)
        self.apk.write_bytes(b"different apk")
        with self.assertRaisesRegex(acceptance.AcceptanceError, "APK identity"):
            self._validate()

        self.apk.write_bytes(b"exact tested apk")
        document["device"]["api_level"] = 35  # type: ignore[index]
        self._write(document)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "payload hash"):
            self._validate()


if __name__ == "__main__":
    unittest.main()
