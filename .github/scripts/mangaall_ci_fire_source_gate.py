#!/usr/bin/env python3
"""Scoped Android signed-code10 MangaFire source gate, NOT a MangaALL global PASS.

Requires proof from one combined three-adapter router lookup per title,
including a terminal MangaFire source cursor and complete MangaFire chapters.
Unfinished MangaDex/MangaDot lookup remains explicitly NOT_PROVEN.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

WORKS = ("punpun", "koe", "akame")
SOURCES = ("mangadex", "mangadot", "mangafire")
SCOPE = re.compile(
    r"MANGAALL_CI_SOURCE\|work=(punpun|koe|akame)"
    r"\|source=(mangadex|mangadot|mangafire)"
    r"\|candidates=([0-9]{1,2})\|lookup=(COMPLETE|PARTIAL)"
)
SOURCE = re.compile(
    r"MANGAALL_CI_FIRE_SOURCE\|work=(punpun|koe|akame)"
    r"\|lookup=(COMPLETE|INCOMPLETE)\|api=(VALIDATED|UNVERIFIED)"
    r"\|chapters=(COMPLETE|INCOMPLETE)"
    r"\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)"
    r"\|outcome=(SOURCE_READY|INCONCLUSIVE)"
)
API = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=API"
    r"\|signedUrl=(OBSERVED|NOT_OBSERVED)\|http=(NONE|200|403|OTHER)"
    r"\|transport=(HTTP|BROWSER|NONE)\|json=(VALIDATED|UNVERIFIED)"
)
CHAPTERS = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)"
    r"\|outcome=(READY|INCONCLUSIVE)\|pages=([0-4])"
    r"\|count=([0-9]{1,3})\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)"
    r"\|reason=([A-Z_]+)"
)
LOOKUP = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP"
    r"\|outcome=([A-Z_]+)\|pages=([0-8])\|candidates=([0-9]{1,2})"
)
SUMMARY = (
    "MANGAALL_CI_FIRE_SOURCE|outcome=SOURCE_CHAPTERS_ACCEPTED"
    "|ready=3|works=3|canonical=NOT_TESTED|unified=NOT_PROVEN"
)

def evaluate(lines: list[str]) -> tuple[bool, str]:
    sources: dict[tuple[str, str], tuple[int, str]] = {}
    proven: dict[str, tuple[str, ...]] = {}
    api: dict[str, tuple[str, ...]] = {}
    chapter: dict[str, tuple[str, ...]] = {}
    lookup: dict[str, tuple[str, ...]] = {}
    records = [s.strip() for s in lines if s.strip()]
    for item in records:
        if match := SCOPE.fullmatch(item):
            work, source, count, state = match.groups()
            key = (work, source)
            if key in sources:
                return False, "DUPLICATE_SOURCE"
            sources[key] = (int(count), state)
        elif match := SOURCE.fullmatch(item):
            work, *data = match.groups()
            if work in proven:
                return False, "DUPLICATE_FIRE_PROOF"
            proven[work] = tuple(data)
        elif match := API.fullmatch(item):
            work, *data = match.groups()
            if work in api:
                return False, "DUPLICATE_API"
            api[work] = tuple(data)
        elif match := CHAPTERS.fullmatch(item):
            work, *data = match.groups()
            if work in chapter:
                return False, "DUPLICATE_CHAPTER"
            chapter[work] = tuple(data)
        elif match := LOOKUP.fullmatch(item):
            work, *data = match.groups()
            if work in lookup:
                return False, "DUPLICATE_LOOKUP"
            lookup[work] = tuple(data)
    if len(sources) != 9 or any((work, source) not in sources
                                for work in WORKS for source in SOURCES):
        return False, "THREE_SOURCE_MATRIX_MISSING"
    if records.count(SUMMARY) != 1:
        return False, "FIRE_SOURCE_SUMMARY_MISSING"
    if len(proven) != 3 or len(api) != 3 or len(chapter) != 3 or len(lookup) != 3:
        return False, "SOURCE_EVIDENCE_MISSING"
    for work in WORKS:
        if proven[work] != ("COMPLETE", "VALIDATED", "COMPLETE", "YES", "SOURCE_READY"):
            return False, "FIRE_SOURCE_NOT_READY"
        if sources[(work, "mangafire")][0] != 1:
            return False, "FIRE_CANDIDATE_NOT_UNIQUE"
        signed, http, transport, json_state = api[work]
        if signed != "OBSERVED" or json_state != "VALIDATED" or (
            not (http == "200" and transport == "HTTP") and transport != "BROWSER"
        ):
            return False, "SOURCE_JSON_NOT_PROVEN"
        if transport == "BROWSER" and http not in ("403", "200", "OTHER"):
            return False, "SOURCE_TRANSPORT_INCONSISTENT"
        outcome, pages, count, chapter_one, _ = chapter[work]
        if outcome not in ("READY", "INCONCLUSIVE") or int(pages) < 1 or (
            int(count) < 1 or chapter_one != "YES"
        ):
            return False, "SOURCE_CHAPTERS_NOT_ATTESTED"
        if int(lookup[work][1]) < 1 or int(lookup[work][2]) != 1:
            return False, "FIRE_LOOKUP_NOT_UNIQUE"
    return True, "SIGNED_MANGAFIRE_3_SOURCE_CHAPTERS_ACCEPTED_UNIFIED_NOT_PROVEN"

def main() -> int:
    if len(sys.argv) != 2:
        print("MANGAALL_CI_FIRE_GATE|outcome=FAIL|reason=INPUT_REQUIRED")
        return 2
    path = Path(sys.argv[1])
    if not path.is_file() or path.stat().st_size > 20_000:
        print("MANGAALL_CI_FIRE_GATE|outcome=FAIL|reason=INVALID_VERDICT_INPUT")
        return 2
    ok, reason = evaluate(path.read_text(encoding="utf-8").splitlines())
    print(
        f"MANGAALL_CI_FIRE_GATE|outcome={'PASS' if ok else 'INCONCLUSIVE'}"
        f"|reason={reason}|unified=NOT_PROVEN|canonical=NOT_TESTED"
    )
    return 0 if ok else 3

if __name__ == "__main__":
    raise SystemExit(main())
