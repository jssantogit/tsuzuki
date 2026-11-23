#!/usr/bin/env python3
"""Strict, source-redacted three-work CI-only MangaALL Android gate.

This validates actual Android instrumentation records, not source content.
It never sees/prints cookies, external IDs, URLs or chapter titles.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

WORKS = ("punpun", "koe", "akame")
SOURCES = ("mangadex", "mangadot", "mangafire")
SOURCE = re.compile(
    r"MANGAALL_CI_SOURCE\|work=(punpun|koe|akame)"
    r"\|source=(mangadex|mangadot|mangafire)"
    r"\|candidates=([0-9]{1,2})\|lookup=(COMPLETE|PARTIAL)"
)
LOOKUP = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP"
    r"\|outcome=([A-Z_]+)\|pages=([0-8])\|candidates=([0-9]{1,2})"
)
API = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=API"
    r"\|signedUrl=(OBSERVED|NOT_OBSERVED)\|http=(NONE|200|403|OTHER)"
    r"\|transport=(HTTP|BROWSER|NONE)\|json=(VALIDATED|UNVERIFIED)"
)
CHAPTER = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)"
    r"\|outcome=(READY|INCONCLUSIVE)\|pages=([0-4])"
    r"\|count=([0-9]{1,3})\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)"
    r"\|reason=([A-Z_]+)"
)
SUMMARY = (
    "MANGAALL_CI_CHAPTER|outcome=CHAPTERS_ACCEPTED|ready=3|works=3"
    "|canonical=NOT_TESTED|readerPixels=NOT_TESTED"
)


def evaluate(lines: list[str]) -> tuple[bool, str]:
    """Report the earliest closed failure category, with no raw log disclosure."""
    records = [line.strip() for line in lines if line.strip()]
    source: dict[tuple[str, str], tuple[int, str]] = {}
    lookup: dict[str, tuple[str, int, int]] = {}
    api: dict[str, tuple[str, str, str, str]] = {}
    chapter: dict[str, tuple[str, int, int, str, str]] = {}
    for line in records:
        if (match := SOURCE.fullmatch(line)):
            work, src, count, state = match.groups()
            key = (work, src)
            if key in source:
                return False, "DUPLICATE_SOURCE_VERDICT"
            source[key] = (int(count), state)
        elif (match := LOOKUP.fullmatch(line)):
            work, state, pages, count = match.groups()
            if work in lookup:
                return False, "DUPLICATE_LOOKUP_VERDICT"
            lookup[work] = (state, int(pages), int(count))
        elif (match := API.fullmatch(line)):
            work, signed, http, transport, json_status = match.groups()
            if work in api:
                return False, "DUPLICATE_API_VERDICT"
            api[work] = (signed, http, transport, json_status)
        elif (match := CHAPTER.fullmatch(line)):
            work, state, pages, count, one, reason = match.groups()
            if work in chapter:
                return False, "DUPLICATE_CHAPTER_VERDICT"
            chapter[work] = (state, int(pages), int(count), one, reason)
    for work in WORKS:
        result = lookup.get(work)
        if result is None or result[0] != "COMPLETE" or result[1] < 1 or result[2] != 1:
            return False, "LOOKUP_NOT_COMPLETE_AND_UNIQUE"
        for src in SOURCES:
            entry = source.get((work, src))
            if entry is None or entry[1] != "COMPLETE":
                return False, "THREE_SOURCE_EVIDENCE_INCOMPLETE"
        if source[(work, "mangafire")][0] != 1:
            return False, "MANGAFIRE_CANDIDATE_NOT_UNIQUE"
        observed_api = api.get(work)
        if observed_api is None or observed_api[0] != "OBSERVED" or observed_api[3] != "VALIDATED":
            return False, "SIGNED_API_NOT_VALIDATED"
        # A 200 response can complete directly; a challenged source must
        # have its real same-profile Browser Host fallback accepted by the
        # unmodified signed provider. A synthetic navigation flag is not proof.
        http, transport = observed_api[1], observed_api[2]
        if transport == "NONE" or (transport == "HTTP" and http != "200"):
            return False, "SIGNED_API_TRANSPORT_INCONSISTENT"
        result = chapter.get(work)
        if result is None or result[0] != "READY" or result[1] < 1 or result[2] < 1 or (
            result[3], result[4]
        ) != ("YES", "NONE"):
            return False, "CHAPTER_PAGINATION_INCOMPLETE"
    if records.count(SUMMARY) != 1:
        return False, "THREE_WORK_SUMMARY_MISSING"
    return True, "SIGNED_CI_THREE_WORKS_ACCEPTED"


def main() -> int:
    if len(sys.argv) != 2:
        print("MANGAALL_CI_THREE_GATE|outcome=FAIL|reason=VERDICT_INPUT_REQUIRED")
        return 2
    verdict = Path(sys.argv[1])
    if not verdict.is_file() or verdict.stat().st_size > 20_000:
        print("MANGAALL_CI_THREE_GATE|outcome=FAIL|reason=INVALID_VERDICT_INPUT")
        return 2
    ok, reason = evaluate(verdict.read_text(encoding="utf-8").splitlines())
    print(f"MANGAALL_CI_THREE_GATE|outcome={'PASS' if ok else 'INCONCLUSIVE'}|reason={reason}")
    return 0 if ok else 3


if __name__ == "__main__":
    raise SystemExit(main())
