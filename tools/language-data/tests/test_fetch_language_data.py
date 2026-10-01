from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import fetch_language_data as fetcher  # noqa: E402

_PAYLOAD = b"model data\n" * 100


class FetchLanguageDataTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        root = Path(self._temporary.name)
        self.repo, self.cache, self.source = root / "repo", root / "cache", root / "source"
        self.source.mkdir()
        (self.source / "xx_model-1.0-py3-none-any.whl").write_bytes(_PAYLOAD)
        self.sha = hashlib.sha256(_PAYLOAD).hexdigest()
        self._catalog(url="https://example.invalid/releases/xx_model-1.0-py3-none-any.whl")

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _catalog(self, *, url: str, size: int = len(_PAYLOAD)) -> None:
        catalog = {
            "schemaVersion": 3,
            "language": "xx",
            "resources": [
                {"resourceId": "xx-dict", "kind": "yomitan-dictionary"},
                {
                    "resourceId": "xx-xx-model",
                    "kind": "language-data",
                    "archive": {"url": url, "sha256": self.sha, "sizeBytes": size, "format": "wheel"},
                },
            ],
            "recommended": [],
        }
        path = self.repo / fetcher.CATALOG_DIR / "xx.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(catalog), encoding="utf-8")

    def test_a_pinned_archive_is_copied_from_the_source_directory_under_its_digest(self) -> None:
        paths = fetcher.fetch(self.repo, ["xx"], self.cache, self.source)

        self.assertEqual([self.cache / f"{self.sha}.whl"], paths)
        self.assertEqual(_PAYLOAD, paths[0].read_bytes())

    def test_an_archive_already_cached_is_not_fetched_again(self) -> None:
        fetcher.fetch(self.repo, ["xx"], self.cache, self.source)
        (self.source / "xx_model-1.0-py3-none-any.whl").unlink()

        self.assertEqual([self.cache / f"{self.sha}.whl"], fetcher.fetch(self.repo, ["xx"], self.cache))

    def test_bytes_that_do_not_match_the_pin_never_land(self) -> None:
        (self.source / "xx_model-1.0-py3-none-any.whl").write_bytes(_PAYLOAD[:-1] + b"!")

        with self.assertRaises(fetcher.FetchError):
            fetcher.fetch(self.repo, ["xx"], self.cache, self.source)
        self.assertEqual([], list(self.cache.iterdir()))

    def test_more_bytes_than_pinned_are_refused(self) -> None:
        self._catalog(url="https://example.invalid/releases/xx_model-1.0-py3-none-any.whl", size=len(_PAYLOAD) - 1)

        with self.assertRaises(fetcher.FetchError):
            fetcher.fetch(self.repo, ["xx"], self.cache, self.source)
        self.assertEqual([], list(self.cache.iterdir()))

    def test_an_archive_missing_from_the_source_directory_is_downloaded_from_its_pin(self) -> None:
        self._catalog(url=(self.source / "xx_model-1.0-py3-none-any.whl").as_uri())

        paths = fetcher.fetch(self.repo, ["xx"], self.cache)

        self.assertEqual(_PAYLOAD, paths[0].read_bytes())

    def test_the_cache_follows_the_environment(self) -> None:
        self.assertEqual(Path("/a"), fetcher.cache_root({fetcher.CACHE_ENV: "/a", fetcher.TOOLCHAIN_ENV: "/t"}))
        self.assertEqual(Path("/t/language-data"), fetcher.cache_root({fetcher.TOOLCHAIN_ENV: "/t"}))
        with self.assertRaises(fetcher.FetchError):
            fetcher.cache_root({})


if __name__ == "__main__":
    unittest.main()
