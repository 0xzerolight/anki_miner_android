"""One real AndroidAnkiAdapter round trip for the S2 capability spike."""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path


def run(
    files_dir: str,
    callbacks: object,
    run_id: str,
    deck_name: str,
    model_name: str,
    audio_path: str,
    image_path: str,
) -> str:
    from android_bridge.anki_adapter import AndroidAnkiAdapter
    from android_bridge.callbacks import AndroidAnkiCallbacks
    from anki_miner.config import AnkiMinerConfig
    from anki_miner.models import CardPayload, MediaData, TokenizedWord

    home = Path(files_dir)
    base = AnkiMinerConfig(dicts_root=home / "s2-probe-dicts")
    fields = {key: "" for key in base.anki_fields}
    fields.update(
        {
            "word": "Expression",
            "picture": "Picture",
            "audio": "SentenceAudio",
        }
    )
    config = replace(
        base,
        anki_deck_name=deck_name,
        anki_note_type=model_name,
        anki_fields=fields,
        anki_tags="anki_miner_s2_probe",
    )
    media = MediaData(
        screenshot_path=Path(image_path),
        audio_path=Path(audio_path),
        screenshot_filename="s2_adapter_image.webp",
        audio_filename="s2_adapter_audio.mp3",
    )
    word = TokenizedWord(
        surface="猫",
        lemma="猫",
        reading="ねこ",
        sentence="猫だ",
        start_time=0.0,
        end_time=1.0,
        duration=1.0,
        expression_furigana="猫[ねこ]",
        expression_reading="ねこ",
        sentence_furigana="猫[ねこ]だ",
        sentence_reading="ねこだ",
        pos="名詞",
    )
    adapter = AndroidAnkiAdapter(
        config,
        AndroidAnkiCallbacks(callbacks, run_id),
    )
    try:
        adapter.verify_card_target()
        before = sorted(adapter.get_existing_vocabulary())
        created = adapter.create_cards_batch(
            [CardPayload(word=word, media=media, definition="cat")]
        )
        adapter.invalidate_existing_vocabulary_cache()
        after = sorted(adapter.get_existing_vocabulary())
        return json.dumps(
            {
                "created": created,
                "noteIds": adapter.last_created_note_ids,
                "knownBefore": before,
                "knownAfter": after,
                "audioFilename": media.audio_filename,
                "imageFilename": media.screenshot_filename,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    finally:
        adapter.close()
