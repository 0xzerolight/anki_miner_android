#!/usr/bin/env python3
"""Generate the subtitle replacement-template parity corpus from live Python `re`.

The Kotlin `SubtitleRegexCheck` hand-implements Python's replacement-template
grammar so the settings screen can reject a bad (pattern, replacement) pair
before mining. That reimplementation is only safe if it agrees with CPython on
both axes: it must not accept what Python raises on, and — the parity risk that
matters more here — it must not reject what Python accepts.

Each case records what `compiled.sub(replacement, "")` does, which is exactly the
preflight the vendored engine performs.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

CASES: list[tuple[str, str, str]] = [
    # name, pattern, replacement
    ("plain-text", r"(\d+)", "x"),
    ("empty-replacement", r"(\d+)", ""),
    ("escaped-backslash", r"(\d+)", r"\\"),
    ("escaped-backslash-then-digit", r"(\d+)", r"\\1"),
    ("group-one", r"(\d+)", r"\1"),
    ("group-one-of-two", r"(\d+)(\w)", r"\1"),
    ("group-two-of-two", r"(\d+)(\w)", r"\2"),
    ("group-out-of-range", r"(\d+)", r"\2"),
    ("group-out-of-range-high", r"(\d+)", r"\9"),
    ("group-zero", r"(\d+)", r"\0"),
    ("two-digit-group-in-range", r"(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)", r"\12"),
    ("two-digit-group-out-of-range", r"(a)(b)", r"\12"),
    ("named-group", r"(?P<word>\w+)", r"\g<word>"),
    ("named-group-unknown", r"(?P<word>\w+)", r"\g<other>"),
    ("named-group-unicode", r"(?P<数字>[0-9])", r"\g<数字>"),
    ("named-group-unicode-unknown", r"(?P<数字>[0-9])", r"\g<文字>"),
    ("g-numeric-in-range", r"(\d+)", r"\g<1>"),
    ("g-numeric-out-of-range", r"(\d+)", r"\g<2>"),
    ("g-zero", r"(\d+)", r"\g<0>"),
    ("g-missing-close", r"(\d+)", r"\g<1"),
    ("g-missing-open", r"(\d+)", r"\g1"),
    ("g-empty", r"(\d+)", r"\g<>"),
    ("trailing-backslash", r"(\d+)", "\\"),
    ("escape-n", r"(\d+)", r"\n"),
    ("escape-t", r"(\d+)", r"\t"),
    ("escape-r", r"(\d+)", r"\r"),
    ("escape-a", r"(\d+)", r"\a"),
    ("escape-b", r"(\d+)", r"\b"),
    ("escape-f", r"(\d+)", r"\f"),
    ("escape-v", r"(\d+)", r"\v"),
    ("escape-unknown-letter-d", r"(\d+)", r"\d"),
    ("escape-unknown-letter-q", r"(\d+)", r"\q"),
    ("escape-unknown-letter-upper", r"(\d+)", r"\W"),
    ("escape-punctuation", r"(\d+)", r"\-"),
    ("escape-space", r"(\d+)", "\\ "),
    ("octal-three-digit", r"(\d+)", r"\101"),
    ("octal-overflow", r"(\d+)", r"\400"),
    ("octal-from-zero", r"(\d+)", r"\012"),
    ("octal-zero-short", r"(\d+)", r"\0"),
    ("octal-zero-one-digit", r"(\d+)", r"\07"),
    ("digits-not-octal", r"(\d+)", r"\189"),
    ("mixed-text-and-group", r"(\d+)(\w)", r"[\1-\2]"),
    ("noncapturing-group", r"(?:\d+)(\w)", r"\1"),
    ("noncapturing-group-out-of-range", r"(?:\d+)", r"\1"),
    ("lookahead-not-captured", r"(?=\d)(\w)", r"\1"),
    ("lookbehind-not-captured", r"(?<=a)(\w)", r"\1"),
    ("atomic-class-with-paren", r"[()](\w)", r"\1"),
    ("class-with-literal-close-bracket", r"[]()](\w)", r"\1"),
    ("negated-class-with-close-bracket", r"[^]()](\w)", r"\1"),
    ("class-with-escaped-bracket", r"[\]](\w)", r"\1"),
    ("escaped-paren-is-not-a-group", r"\((\w)\)", r"\1"),
    ("escaped-paren-out-of-range", r"\(\w\)", r"\1"),
    ("comment-group", r"(?#a comment)(\w)", r"\1"),
    ("comment-group-with-paren-text", r"(?#has ( paren)(\w)", r"\1"),
    ("inline-global-flags", r"(?i)(\w)", r"\1"),
    ("inline-scoped-flags", r"(?i:(\w))", r"\1"),
    ("inline-scoped-negated-flags", r"(?i-s:(\w))", r"\1"),
    ("verbose-comment-hides-paren", "(?x)(\\w)  # ( not a group\n", r"\1"),
    ("verbose-comment-hides-paren-out-of-range", "(?x)\\w  # ( not a group\n", r"\1"),
    ("verbose-escaped-hash", "(?x)(\\w)\\#(\\d)", r"\1\2"),
    ("verbose-class-hash-is-literal", "(?x)(\\w)[#](\\d)", r"\2"),
    ("nested-groups", r"((\d)(\w))", r"\1\2\3"),
    ("nested-groups-out-of-range", r"((\d)(\w))", r"\4"),
    ("named-and-numbered", r"(?P<a>\d)(\w)", r"\g<a>\2"),
    ("backreference-in-pattern", r"(\w)\1(\d)", r"\2"),
    ("group-then-literal-digit", r"(\w)", r"\1" + "0"),
    ("repeated-group-reference", r"(\w)", r"\1\1\1"),
    ("literal-brace", r"(\w)", r"{1}"),
    ("dollar-is-literal-in-python", r"(\w)", "$1"),
    ("ampersand-is-literal-in-python", r"(\w)", "&"),
]


def verdict(pattern: str, replacement: str) -> dict[str, object]:
    compiled = re.compile(pattern)
    try:
        compiled.sub(replacement, "")
    except re.error as error:
        return {"rejected": True, "detail": str(error)}
    except IndexError as error:
        # CPython raises IndexError, not re.error, for an unknown group NAME.
        return {"rejected": True, "detail": str(error)}
    return {"rejected": False, "detail": ""}


def main() -> int:
    out = pathlib.Path(sys.argv[1])
    seen: set[str] = set()
    cases = []
    for name, pattern, replacement in CASES:
        if name in seen:
            raise SystemExit(f"duplicate case name: {name}")
        seen.add(name)
        result = verdict(pattern, replacement)
        cases.append(
            {
                "name": name,
                "pattern": pattern,
                "replacement": replacement,
                "rejected": result["rejected"],
            }
        )
    document = {
        "schema_version": 1,
        "description": (
            "Python re replacement-template verdicts for the Kotlin SubtitleRegexCheck "
            "parity test. Each case records whether compiled.sub(replacement, '') raises "
            "re.error, which is the preflight the vendored subtitle parser performs. "
            "Regenerate with tools/anki-contract/generate_subtitle_regex_parity.py."
        ),
        "python_version": f"{sys.version_info.major}.{sys.version_info.minor}",
        "cases": cases,
    }
    out.write_text(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    rejected = sum(1 for case in cases if case["rejected"])
    print(f"{len(cases)} cases -> {out} ({rejected} rejected, {len(cases) - rejected} accepted)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
