"""Debug-only functional probe for the embedded common Python runtime."""

from __future__ import annotations

from importlib import metadata, util
from io import BytesIO
import json
import sys


EXPECTED_VERSIONS = {
    "certifi": "2026.6.17",
    "charset-normalizer": "3.4.7",
    "idna": "3.18",
    "lxml": "6.1.1",
    "pillow": "12.2.0",
    "pysubs2": "1.8.1",
    "requests": "2.34.2",
    "urllib3": "2.7.0",
}


def _round_trip_image(format_name: str) -> dict[str, object]:
    from PIL import Image

    encoded = BytesIO()
    Image.new("RGB", (3, 2), (29, 71, 113)).save(encoded, format=format_name)
    payload = encoded.getvalue()
    if not payload:
        raise AssertionError(f"{format_name} encoder produced no bytes")
    with Image.open(BytesIO(payload)) as decoded:
        decoded.load()
        if decoded.size != (3, 2):
            raise AssertionError(f"{format_name} decoder returned the wrong dimensions")
        return {
            "bytes": len(payload),
            "format": decoded.format,
            "mode": decoded.mode,
            "size": list(decoded.size),
        }


def snapshot() -> str:
    """Import and functionally exercise every tokenizer-neutral dependency."""

    import certifi
    import charset_normalizer
    import idna
    from lxml import etree, html, objectify
    from PIL import features, ImageFont
    import pysubs2
    import requests
    import urllib3

    versions = {
        package: metadata.version(package) for package in EXPECTED_VERSIONS
    }
    if versions != EXPECTED_VERSIONS:
        raise AssertionError(f"runtime versions differ: {versions!r}")

    codec_support = {
        "freetype": features.check("freetype2"),
        "jpeg": features.check("jpg"),
        "webp": features.check("webp"),
        "zlib": features.check("zlib"),
    }
    if not all(codec_support.values()):
        raise AssertionError(f"required Pillow codecs unavailable: {codec_support!r}")
    images = {
        name: _round_trip_image(name) for name in ("JPEG", "PNG", "WEBP")
    }

    xml_root = etree.fromstring(b"<root><value>runtime</value></root>")
    if xml_root.findtext("value") != "runtime":
        raise AssertionError("lxml XML parsing failed")
    html_root = html.fromstring("<html><body><p>runtime</p></body></html>")
    if html_root.xpath("string(//p)") != "runtime":
        raise AssertionError("lxml HTML parsing failed")
    object_root = objectify.fromstring(b"<root><value>runtime</value></root>")
    if str(object_root.value) != "runtime":
        raise AssertionError("lxml objectify parsing failed")

    font = ImageFont.load_default(size=12)
    if font.getbbox("runtime")[2] <= 0:
        raise AssertionError("Pillow FreeType rendering failed")

    subtitles = pysubs2.SSAFile.from_string(
        "1\n00:00:00,000 --> 00:00:01,250\n字幕\n",
        format_="srt",
    )
    if len(subtitles) != 1 or subtitles[0].text != "字幕":
        raise AssertionError("pysubs2 SRT parsing failed")

    # Touch module-owned data and public objects so an import stub cannot satisfy this probe.
    if not certifi.where() or not charset_normalizer.from_bytes(b"runtime").best():
        raise AssertionError("requests dependency data is unavailable")
    if idna.decode(idna.encode("例え.テスト")) != "例え.テスト":
        raise AssertionError("IDNA codec round-trip failed")
    request = requests.Request("GET", "https://example.invalid/").prepare()
    if request.method != "GET" or urllib3.util.parse_url(request.url).host is None:
        raise AssertionError("HTTP dependency construction failed")

    forbidden = {
        package: util.find_spec(package) is not None
        for package in ("gtts", "unidic", "unidic_lite", "yt_dlp")
    }
    if any(forbidden.values()):
        raise AssertionError(f"cut or external-only dependency was bundled: {forbidden!r}")

    return json.dumps(
        {
            "codec_support": codec_support,
            "forbidden_present": forbidden,
            "images": images,
            "implementation": sys.implementation.name,
            "python": list(sys.version_info[:3]),
            "versions": versions,
        },
        sort_keys=True,
    )
