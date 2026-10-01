"""Debug-only language smoke: tokenise each profile's ``smoke_sentence`` through the packaged engine.

A token row is ``[surface, pos1, pos2, lemma]``, the shape desktop's per-language tokenizer tests
compare. Expected rows, by provenance (desktop SHA = ``tools/engine-sync/engine.lock``,
a1259f4e5b8f97385660389eef6d76fb403c1e38):

* he: desktop ``tests/fixtures/he/tokens.jsonl`` row ``he01``, verbatim.
* id: desktop ``tests/fixtures/id/tokens.jsonl`` row ``id01`` plus the trailing ``.`` PUNCT row
  that desktop's own test filters out before comparing.
* tr: desktop ``tests/fixtures/tr/lemma_gold.jsonl`` row ``a01`` pins every surface and lemma;
  the POS column and the trailing ``.`` PUNCT row come from the host exporter.
* The 21 spaCy codes, ko, vi and yue: the smoke-sentence row of the desktop-exported
  ``tests/python/android_bridge/languages/fixtures/<code>/tokens.jsonl``, first four fields
  (``test_language_smoke_harness`` holds them equal).
* th, ar, fa, zh: no desktop row pins the whole smoke sentence with the full hazm data. th
  ``pos_corpus.jsonl`` ``th01`` and ar ``ar01`` list only the lemmas to mine, which these rows
  contain; fa ``tokens.jsonl``'s first row lists these content rows, but over a trimmed lexicon;
  zh's rows equal its fixture's ``smoke`` case. Exported on the host from the vendored engine at
  the pinned versions, every local code reading its pinned catalog archives::

      PYTHONPATH=app/src/debug/python:app/src/main/python \\
        "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests/bin/python" \\
        -c 'import language_smoke_instrumented as s; print(s.export("<home>", "<archive dir>"))'

A code whose catalog pins no ``language-data`` tokenises with the APK alone, so the CI lane runs it
(``CI_CODES``). Every other code reads downloaded data and runs only on a local lane that pushes
its pinned archives first (``LOCAL_CODES``), named as the catalog URL's last segment.

Engine imports stay function-local: ``bootstrap`` must set ``ANKI_MINER_HOME`` first.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

CI_CODES = ("he", "id", "th", "tr", "zh")
#: Codes whose tagger reads downloaded data: every ``language-data`` entry their catalog pins.
LOCAL_CODES = tuple("ar fa en ca de pt fr es it nl nb ro el fi hu hr sv pl lt da sl ru uk ko vi yue".split())

#: zh's smoke sentence proves jieba's dictionary, but never reaches pypinyin, and OpenCC only
#: degrades silently (an unloadable config logs and is skipped; these rows come out the same
#: without it). D5 dropped Chaquopy's ``extractPackages`` for all three, so :func:`zh_package_data`
#: reads their data files: it opens every OpenCC config the engine uses, converts this traditional
#: spelling back to the smoke sentence, and adds each row's pypinyin reading. Rows: the
#: ``smoke-traditional`` case of ``tests/python/android_bridge/languages/fixtures/zh/tokens.json``
#: (desktop ``build_tagger()`` + ``ZhReadingSupport``).
ZH_TRADITIONAL_SENTENCE = "我今天早上吃了三個蘋果。"
ZH_SIMPLIFIED_SENTENCE = "我今天早上吃了三个苹果。"
ZH_TRADITIONAL_EXPECTED = [
    ["我", "r", "", "我", "wǒ"],
    ["今天", "t", "", "今天", "jīn tiān"],
    ["早上", "t", "", "早上", "zǎo shàng"],
    ["吃", "v", "", "吃", "chī"],
    ["了", "u", "ul", "了", "le"],
    ["三個", "m", "", "三個", "sān gè"],
    ["蘋果", "n", "", "蘋果", "píng guǒ"],
    ["。", "x", "", "。", ""],
]

EXPECTED: dict[str, list[list[str]]] = {
    "he": [
        ["הילד", "WORD", "", "הילד"],
        ["קרא", "WORD", "", "קרא"],
        ["ספר", "WORD", "", "ספר"],
        ["מעניין", "WORD", "", "מעניין"],
        ["אתמול", "WORD", "", "אתמול"],
        [".", "PUNCT", "", "."],
    ],
    "id": [
        ["Saya", "WORD", "stopword", "saya"],
        ["sedang", "WORD", "stopword", "sedang"],
        ["membaca", "WORD", "", "membaca"],
        ["buku", "WORD", "", "buku"],
        ["di", "WORD", "stopword", "di"],
        ["rumah", "WORD", "", "rumah"],
        [".", "PUNCT", "", "."],
    ],
    "th": [
        ["วันนี้", "NOUN", "", "วันนี้"],
        ["อากาศ", "NOUN", "", "อากาศ"],
        ["ดีมาก", "ADV", "", "ดีมาก"],
    ],
    "tr": [
        ["Öğrenci", "NOUN", "", "öğrenci"],
        ["dün", "ADV", "", "dün"],
        ["ilginç", "ADJ", "", "ilginç"],
        ["bir", "DET", "", "bir"],
        ["kitap", "NOUN", "", "kitap"],
        ["okudu", "VERB", "", "okumak"],
        [".", "PUNCT", "", "."],
    ],
    "zh": [
        ["我", "r", "", "我"],
        ["今天", "t", "", "今天"],
        ["早上", "t", "", "早上"],
        ["吃", "v", "", "吃"],
        ["了", "u", "ul", "了"],
        ["三个", "m", "", "三个"],
        ["苹果", "n", "", "苹果"],
        ["。", "x", "", "。"],
    ],
    "ar": [
        ["ذهب", "verb", "", "ذهب"],
        ["الطالب", "noun", "clitic", "طالب"],
        ["إلى", "prep", "", "إلى"],
        ["المدرسة", "noun", "clitic", "مدرسة"],
        ["صباحا\N{ARABIC FATHATAN}", "noun", "", "صباح"],
        [".", "punc", "", "."],
    ],
    "fa": [
        ["من", "N", "stopword", "من"],
        ["هر", "DET", "stopword", "هر"],
        ["روز", "N", "", "روز"],
        ["به", "P", "stopword", "به"],
        ["مدرسه", "N", "", "مدرسه"],
        ["می\N{ZERO WIDTH NON-JOINER}روم", "V", "", "رفتن"],
        [".", "PUNCT", "", "."],
    ],
    "en": [
        ["The", "DET", "DT", "the"],
        ["quick", "ADJ", "JJ", "quick"],
        ["brown", "ADJ", "JJ", "brown"],
        ["fox", "NOUN", "NN", "fox"],
        ["jumps", "VERB", "VBZ", "jump"],
        ["over", "ADP", "IN", "over"],
        ["the", "DET", "DT", "the"],
        ["lazy", "ADJ", "JJ", "lazy"],
        ["dog", "NOUN", "NN", "dog"],
        [".", "PUNCT", ".", "."],
    ],
    "ca": [
        ["L'", "DET", "", "el"],
        ["estudiant", "NOUN", "", "estudiant"],
        ["va", "AUX", "", "anar"],
        ["llegir", "VERB", "", "llegir"],
        ["un", "DET", "", "un"],
        ["llibre", "NOUN", "", "llibre"],
        ["interessant", "ADJ", "", "interessant"],
        ["ahir", "ADV", "", "ahir"],
        [".", "PUNCT", "", "."],
    ],
    "de": [
        ["Er", "PRON", "PPER", "er"],
        ["sieht", "VERB", "VVFIN", "sehen"],
        ["sich", "PRON", "PRF", "sich"],
        ["den", "DET", "ART", "der"],
        ["Film", "NOUN", "NN", "Film"],
        ["an", "ADP", "PTKVZ", "an"],
        [".", "PUNCT", "$.", "--"],
    ],
    "pt": [
        ["O", "DET", "", "o"],
        ["estudante", "NOUN", "", "estudante"],
        ["leu", "VERB", "", "ler"],
        ["um", "DET", "", "um"],
        ["livro", "NOUN", "", "livro"],
        ["interessante", "ADJ", "", "interessante"],
        ["ontem", "ADV", "", "ontem"],
        [".", "PUNCT", "", "."],
    ],
    "fr": [
        ["Le", "DET", "", "le"],
        ["chat", "NOUN", "", "chat"],
        ["dort", "ADJ", "", "dort"],
        ["sur", "ADP", "", "sur"],
        ["la", "DET", "", "le"],
        ["chaise", "NOUN", "", "chaise"],
        [".", "PUNCT", "", "."],
    ],
    "es": [
        ["El", "DET", "", "el"],
        ["perro", "PROPN", "", "perro"],
        ["corre", "VERB", "", "correr"],
        ["por", "ADP", "", "por"],
        ["el", "DET", "", "el"],
        ["parque", "NOUN", "", "parque"],
        [".", "PUNCT", "", "."],
    ],
    "it": [
        ["Il", "DET", "RD", "il"],
        ["gatto", "NOUN", "S", "gatto"],
        ["dorme", "VERB", "V", "dormire"],
        ["sulla", "ADP", "E_RD", "su"],
        ["sedia", "NOUN", "S", "sedia"],
        [".", "PUNCT", "FS", "."],
    ],
    "nl": [
        ["De", "DET", "LID|bep|stan|rest", "de"],
        ["student", "NOUN", "N|soort|ev|basis|zijd|stan", "student"],
        ["las", "VERB", "WW|pv|verl|ev", "las"],
        ["gisteren", "ADV", "BW", "gisteren"],
        ["een", "DET", "LID|onbep|stan|agr", "een"],
        ["interessant", "ADJ", "ADJ|prenom|basis|zonder", "interessant"],
        ["boek", "NOUN", "N|soort|ev|basis|onz|stan", "boek"],
        [".", "PUNCT", "LET", "."],
    ],
    "nb": [
        ["Studenten", "NOUN", "", "student"],
        ["leste", "VERB", "", "lese"],
        ["en", "DET", "", "en"],
        ["interessant", "ADJ", "", "interessant"],
        ["bok", "NOUN", "", "bok"],
        ["i", "ADP", "", "i"],
        ["går", "NOUN", "", "går"],
        [".", "PUNCT", "", "$."],
    ],
    "ro": [
        ["Studentul", "NOUN", "Ncmsry", "student"],
        ["a", "AUX", "Va--3s", "avea"],
        ["citit", "VERB", "Vmp--sm", "citi"],
        ["o", "DET", "Tifsr", "un"],
        ["carte", "NOUN", "Ncfsrn", "carte"],
        ["interesantă", "ADJ", "Afpfsrn", "interesant"],
        ["ieri", "ADV", "Rgp", "ieri"],
        [".", "PUNCT", "PERIOD", "."],
    ],
    "el": [
        ["Το", "DET", "", "ο"],
        ["βιβλίο", "NOUN", "", "βιβλίο"],
        ["είναι", "AUX", "", "είμαι"],
        ["στο", "ADP", "", "σε ο"],
        ["σπίτι", "NOUN", "", "σπίτι"],
        [".", "PUNCT", "", "."],
    ],
    "fi": [
        ["Opiskelija", "NOUN", "N", "opiskelija"],
        ["luki", "VERB", "V", "lukea"],
        ["mielenkiintoisen", "ADJ", "A", "mielenkiintoinen"],
        ["kirjan", "NOUN", "N", "kirja"],
        ["eilen", "ADV", "Adv", "eilen"],
        [".", "PUNCT", "Punct", "."],
    ],
    "hu": [
        ["A", "DET", "", "a"],
        ["diák", "NOUN", "", "diák"],
        ["tegnap", "ADV", "", "tegnap"],
        ["elolvasott", "VERB", "", "elolvas"],
        ["egy", "DET", "", "egy"],
        ["érdekes", "ADJ", "", "érdekes"],
        ["könyvet", "NOUN", "", "könyv"],
        [".", "PUNCT", "", "."],
    ],
    "hr": [
        ["Student", "NOUN", "Ncmsn", "student"],
        ["je", "AUX", "Var3s", "biti"],
        ["jučer", "ADV", "Rgp", "jučer"],
        ["pročitao", "VERB", "Vmp-sm", "pročitati"],
        ["zanimljivu", "ADJ", "Agpfsay", "zanimljiv"],
        ["knjigu", "NOUN", "Ncfsa", "knjiga"],
        [".", "PUNCT", "Z", "."],
    ],
    "sv": [
        ["Studenten", "NOUN", "NN|UTR|SIN|DEF|NOM", "student"],
        ["läste", "VERB", "VB|PRT|AKT", "läsa"],
        ["en", "DET", "DT|UTR|SIN|IND", "en"],
        ["intressant", "ADJ", "JJ|POS|UTR|SIN|IND|NOM", "intressant"],
        ["bok", "NOUN", "NN|NEU|SIN|IND|NOM", "bok"],
        [".", "PUNCT", "MAD", "."],
    ],
    "pl": [
        ["Student", "NOUN", "SUBST", "student"],
        ["przeczytał", "VERB", "PRAET", "przeczytać"],
        ["wczoraj", "ADV", "", "wczoraj"],
        ["ciekawą", "ADJ", "", "ciekawy"],
        ["książkę", "NOUN", "SUBST", "książka"],
        [".", "PUNCT", "SUBST", "."],
    ],
    "lt": [
        ["Knyga", "NOUN", "dkt.mot.vns.V.", "knyga"],
        ["yra", "AUX", "vksm.asm.tiesiog.es.vns.3.", "būti"],
        ["ant", "ADP", "prl.K.", "ant"],
        ["stalo", "NOUN", "dkt.vyr.vns.K.", "stalas"],
        [".", "PUNCT", "skyr.", "."],
    ],
    "da": [
        ["Den", "DET", "", "den"],
        ["studerende", "VERB", "", "studerende"],
        ["læste", "VERB", "", "læse"],
        ["en", "DET", "", "en"],
        ["interessant", "ADJ", "", "interessant"],
        ["bog", "NOUN", "", "bog"],
        ["i", "ADP", "", "i"],
        ["går", "NOUN", "", "går"],
        [".", "PUNCT", "", "."],
    ],
    "sl": [
        ["Študent", "NOUN", "Ncmsn", "študent"],
        ["je", "AUX", "Va-r3s-n", "biti"],
        ["včeraj", "ADV", "Rgp", "včeraj"],
        ["prebral", "VERB", "Vmep-sm", "prebrati"],
        ["zanimivo", "ADJ", "Agpfsa", "zanimiv"],
        ["knjigo", "NOUN", "Ncfsa", "knjiga"],
        [".", "PUNCT", "Z", "."],
    ],
    "ru": [
        ["Студент", "NOUN", "", "студент"],
        ["вчера", "ADV", "", "вчера"],
        ["прочитал", "VERB", "", "прочитать"],
        ["интересную", "ADJ", "", "интересный"],
        ["книгу", "NOUN", "", "книга"],
        [".", "PUNCT", "", "."],
    ],
    "uk": [
        ["Студент", "NOUN", "", "студент"],
        ["учора", "ADV", "", "учора"],
        ["прочитав", "VERB", "", "прочитати"],
        ["цікаву", "ADJ", "", "цікавий"],
        ["книжку", "NOUN", "", "книжка"],
        [".", "PUNCT", "", "."],
    ],
    "ko": [
        ["학생", "NN", "NNG", "학생"],
        ["이", "JK", "JKS", "이"],
        ["밥", "NN", "NNG", "밥"],
        ["을", "JK", "JKO", "을"],
        ["먹", "VV", "", "먹다"],
        ["었", "EP", "", "었"],
        ["어요", "EF", "", "어요"],
        [".", "SF", "", "."],
    ],
    "vi": [
        ["Hôm nay", "N", "", "hôm nay"],
        ["trời", "N", "", "trời"],
        ["đẹp", "A", "", "đẹp"],
        ["quá", "R", "", "quá"],
        [".", "CH", "", "."],
    ],
    "yue": [
        ["我", "PRON", "stopword", "我"],
        ["今日", "ADV", "", "今日"],
        ["睇", "VERB", "stopword", "睇"],
        ["咗", "PART", "stopword", "咗"],
        ["一", "NUM", "", "一"],
        ["套", "NOUN", "", "套"],
        ["好", "ADV", "stopword", "好"],
        ["好睇", "ADJ", "", "好睇"],
        ["嘅", "PART", "stopword", "嘅"],
        ["戲", "NOUN", "", "戲"],
        ["。", "PUNCT", "", "。"],
    ],
}


def smoke(code: str) -> str:
    """Tokenise *code*'s smoke sentence; report the rows next to the expected ones."""

    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger

    profile = get_profile(code)
    reason = None if profile.unavailable_reason is None else profile.unavailable_reason()
    tokens = [
        [token.surface, token.feature.pos1, token.feature.pos2, token.feature.lemma]
        for token in get_tagger(code)(profile.smoke_sentence)
    ]
    return json.dumps(
        {
            "code": code,
            "sentence": profile.smoke_sentence,
            "unavailable_reason": reason,
            "tokens": tokens,
            "expected": EXPECTED.get(code),
        },
        ensure_ascii=False,
    )


def zh_package_data() -> str:
    """zh beyond jieba: OpenCC's configs and dictionaries, then :data:`ZH_TRADITIONAL_SENTENCE` with readings.

    ``opencc.OpenCC`` raises here on a config whose files never left the APK, where the engine
    would only log it. Same result shape as :func:`smoke`, each row ending in its reading.
    """

    import opencc

    from anki_miner.languages.registry import get_profile
    from anki_miner.languages.tagger_provider import get_tagger
    from anki_miner.languages.zh.variants import _ALL_CONFIGS

    configs = sorted(_ALL_CONFIGS)
    converters = {name: opencc.OpenCC(name) for name in configs}
    simplified = converters["tw2s"].convert(ZH_TRADITIONAL_SENTENCE)
    if simplified != ZH_SIMPLIFIED_SENTENCE:
        raise ValueError(f"OpenCC tw2s converted {ZH_TRADITIONAL_SENTENCE} to {simplified}")
    profile = get_profile("zh")
    tokens = [
        [
            token.surface,
            token.feature.pos1,
            token.feature.pos2,
            token.feature.lemma,
            profile.reading.word_reading(token),
        ]
        for token in get_tagger("zh")(ZH_TRADITIONAL_SENTENCE)
    ]
    return json.dumps(
        {
            "code": "zh",
            "sentence": ZH_TRADITIONAL_SENTENCE,
            "unavailable_reason": None,
            "opencc_configs": configs,
            "tokens": tokens,
            "expected": ZH_TRADITIONAL_EXPECTED,
        },
        ensure_ascii=False,
    )


def _language_data(code: str) -> tuple:
    from android_bridge.resource_catalog import load_resource_catalog

    return tuple(load_resource_catalog(code).language_data)


def archive_names(code: str) -> list[str]:
    """The file names a local lane pushes *code*'s pinned archives under: each catalog URL's last segment."""

    return [resource.archive.url.rsplit("/", 1)[1] for resource in _language_data(code)]


def install(code: str, archive_dir: str) -> list[str]:
    """Install every pinned ``language-data`` archive of *code* from *archive_dir*, as the app does."""

    from android_bridge.language_data import install_language_data

    return [
        install_language_data(
            {
                "operationId": f"language-smoke-{resource.resource_id}",
                "resourceId": resource.resource_id,
                "archivePath": str(Path(archive_dir) / name),
            }
        )
        for resource, name in zip(_language_data(code), archive_names(code), strict=True)
    ]


def thai_footprint(home: str) -> str:
    """Where pythainlp lives once Thai has tokenised, and whether it made a data directory.

    ``th/_engine.py`` sets ``PYTHAINLP_READ_ONLY`` so the library never creates
    ``$HOME/pythainlp-data``; the package itself is whatever the runtime extracted to disk.
    """

    import pythainlp

    package = Path(pythainlp.__file__).resolve().parent
    files = [path for path in package.rglob("*") if path.is_file()]
    suffixes: dict[str, int] = {}
    for path in files:
        suffixes[path.suffix or "<none>"] = suffixes.get(path.suffix or "<none>", 0) + 1
    data_dirs = [
        str(candidate)
        for candidate in (
            Path(os.path.expanduser("~")) / "pythainlp-data",
            Path(home) / "pythainlp-data",
        )
        if candidate.exists()
    ]
    return json.dumps(
        {
            "package_dir": str(package),
            "file_count": len(files),
            "size_bytes": sum(path.stat().st_size for path in files),
            "suffixes": dict(sorted(suffixes.items())),
            "pythainlp_data_dirs": data_dirs,
            "env_read_only": os.environ.get("PYTHAINLP_READ_ONLY"),
            "env_offline": os.environ.get("PYTHAINLP_OFFLINE"),
        },
        sort_keys=True,
    )


def evict(code: str) -> bool:
    """Drop *code*'s cached tagger, so the next language's peak is not stacked on it."""

    import gc

    from anki_miner.languages.tagger_provider import evict as evict_tagger

    evicted = evict_tagger(code)
    gc.collect()
    return evicted


def uninstall(code: str) -> None:
    """Evict *code*'s tagger and delete its installed data, so 26 local codes never pile up on the device."""

    import shutil

    from anki_miner.services.language_pack_installer import language_pack_root

    evict(code)
    shutil.rmtree(language_pack_root(code), ignore_errors=True)


def export(home: str, archive_dir: str, codes: tuple[str, ...] = (*CI_CODES, *LOCAL_CODES)) -> str:
    """Host exporter for :data:`EXPECTED`: each code's rows, local codes from their pinned archives."""

    from android_bridge.bootstrap import initialize

    initialize(home)
    rows = {}
    for code in codes:
        if code in LOCAL_CODES:
            install(code, archive_dir)
        rows[code] = json.loads(smoke(code))["tokens"]
        if code in LOCAL_CODES:
            uninstall(code)
    return json.dumps(rows, ensure_ascii=False)
