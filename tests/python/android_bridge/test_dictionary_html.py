from __future__ import annotations

import pytest
from android_bridge.dictionary_html import sanitize_dictionary_html


def test_remote_renderer_image_loses_every_endpoint_copy_but_keeps_text() -> None:
    remote = "https://tracker.example.test/pixel.png?word=%E7%8C%AB"
    html = (
        '<div class="gloss-sc-div">before'
        f'<a class="gloss-image-link" data-path="{remote}" '
        f'href="{remote}" data-background="true">'
        '<span class="gloss-image-container">'
        f'<img class="gloss-image" src="{remote}" alt="remote art">'
        '<span class="gloss-image-background" '
        f'style="--image: url(&quot;{remote}&quot;)"></span>'
        "</span></a>after</div>"
    )

    sanitized = sanitize_dictionary_html(
        html,
        local_source_allowed=lambda _: False,
    )

    assert remote not in sanitized
    assert "https://" not in sanitized
    assert "before" in sanitized
    assert "after" in sanitized
    assert 'alt="remote art"' in sanitized
    assert " src=" not in sanitized
    assert " data-path=" not in sanitized
    assert " style=" not in sanitized
    assert " href=" not in sanitized


def test_only_renderer_marked_acknowledged_local_image_keeps_source() -> None:
    local = '<img class="gloss-image anki-miner-dict-media" src="dict__cat.png">'
    unmarked = '<img class="gloss-image" src="dict__cat.png">'
    missing = '<img class="anki-miner-dict-media" src="dict__missing.png">'

    sanitized = sanitize_dictionary_html(
        f"text{local}{unmarked}{missing}tail",
        local_source_allowed=lambda source: source == "dict__cat.png",
    )

    assert sanitized.startswith("text")
    assert sanitized.endswith("tail")
    assert sanitized.count('src="dict__cat.png"') == 1
    assert "dict__missing.png" not in sanitized


def test_entity_encoded_case_insensitive_remote_source_is_rejected() -> None:
    html = (
        '<IMG class="gloss-image" ' 'SRC="hTtPs&#58;//tracker.example.test/image.png" ' 'title="ordinary title">visible'
    )

    sanitized = sanitize_dictionary_html(
        html,
        local_source_allowed=lambda _: False,
    )

    assert "tracker.example.test" not in sanitized
    assert 'title="ordinary title"' in sanitized
    assert sanitized.endswith("visible")


def test_duplicate_src_cannot_hide_remote_url_behind_allowed_local_name() -> None:
    html = (
        '<img class="anki-miner-dict-media" src="dict__cat.png" ' 'src="https://tracker.example.test/hidden.png">text'
    )

    sanitized = sanitize_dictionary_html(
        html,
        local_source_allowed=lambda source: source == "dict__cat.png",
    )

    assert " src=" not in sanitized
    assert "tracker.example.test" not in sanitized
    assert sanitized.endswith("text")


@pytest.mark.parametrize(
    "target",
    [
        "http://dictionary.example.test/entry",
        "https://dictionary.example.test/entry",
        "//dictionary.example.test/entry",
    ],
)
def test_external_explicit_link_loses_href(target: str) -> None:
    html = f'<a href="{target}">ordinary text</a>'

    assert sanitize_dictionary_html(html, local_source_allowed=lambda _: False) == "<a>ordinary text</a>"


def test_external_overlay_link_loses_href_without_rewriting_scoped_css() -> None:
    html = (
        '<a class="cover" href="https://attacker.example.test/track">ordinary card text</a>'
        "<style>.yomitan-glossary .cover {position:fixed;inset:0;opacity:0;z-index:9999}</style>"
    )

    assert sanitize_dictionary_html(html, local_source_allowed=lambda _: False) == (
        '<a class="cover">ordinary card text</a>'
        "<style>.yomitan-glossary .cover {position:fixed;inset:0;opacity:0;z-index:9999}</style>"
    )
