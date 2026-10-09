"""Blacklist and whitelist imports for a non-Japanese mining language.

Kotlin stages the picked file and publishes it. A run of any language but
Japanese reads that file with its profile's ``import_encodings`` ladder, so this
rewrites the staged copy as UTF-8 through the engine's own ladder decoder first:
the installed file is then the text the run would have read from the original,
and Kotlin's UTF-8 normaliser and word count apply unchanged. Japanese never
comes here: its runs read UTF-8 alone (``WordListService``'s default, not the ja
profile's cp932/euc_jp ladder) and Kotlin keeps a strict UTF-8 gate for it.
Engine imports stay function-local; see ``bootstrap.py``.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from . import resources as core
from .languages import JAPANESE, get_profile, validated_language
from .protocol import BridgeProtocolError, encode_message

logger = logging.getLogger(__name__)


def transcode_word_list(payload: Mapping[str, object]) -> str:
    """Rewrite the staged list at ``sourcePath`` as UTF-8, decoded with ``language``'s ladder."""

    core._exact(payload, {"sourcePath", "language"}, code="invalid_resource_request")
    language = validated_language(payload["language"])
    if language == JAPANESE:
        raise BridgeProtocolError(
            "invalid_resource_request",
            "Japanese word lists are read as UTF-8 and are never transcoded",
        )
    source = core._absolute_path(payload["sourcePath"], name="sourcePath")

    from anki_miner.exceptions import SetupError
    from anki_miner.services.reading._util import decode_with_ladder
    from anki_miner.utils.subtitle_encoding import script_check_kwarg

    # The decode half of mining._word_list_seams: the ladder and script check
    # the run hands WordListService for this language.
    profile = get_profile(language)
    ladder = profile.import_encodings
    try:
        text, encoding = decode_with_ladder(
            source.read_bytes(),
            encodings=ladder,
            **script_check_kwarg(ladder, profile.script),
        )
    except SetupError as exc:
        # Every ladder starts with utf-8-sig, so in every language the fix the
        # user is told about is the same: save the list as UTF-8.
        raise BridgeProtocolError(
            "word_list_not_utf8",
            "No encoding in the mining language's ladder decodes the word list",
        ) from exc
    with source.open("wb") as output:
        output.write(text.encode("utf-8"))
        output.flush()
        os.fsync(output.fileno())
    # Mojibake reads as "the words are wrong"; only the winning leg explains it.
    logger.info("Word list transcoded: language=%s encoding=%s", language, encoding)
    return encode_message("resource.wordlist.transcoded", {})
