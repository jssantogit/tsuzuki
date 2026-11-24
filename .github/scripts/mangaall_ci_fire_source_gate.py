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
BROWSER = re.compile(
    r"MANGAALL_CI_BROWSER_FALLBACK\|work=(punpun|koe|akame)"
    r"\|attempts=([0-9])\|outcome=(NOT_USED|SUCCEEDED|UPSTREAM_CHALLENGE|"
    r"HTTP_REJECTED|TIMEOUT|NAVIGATION|TLS|RENDERER_GONE|WEBVIEW_UNSUPPORTED|"
    r"SELECTOR_MISSING|CANCELLED|UNKNOWN|NO_RESULT)"
)
FAILURE = re.compile(
    r"MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP_FAILURE"
    r"\|cause=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|UNCLASSIFIED_ERROR)"
    r"\|http=(NONE|200|403|OTHER)"
)
BUDGET = re.compile(
    r"MANGAALL_CI_LOOKUP_BLOCKER\|work=(punpun|koe|akame)"
    r"\|source=MANGADOT\|reason=PAGE_FOUR_PENDING"
)
CHALLENGE403 = re.compile(
    r"MANGAALL_CI_FIRE_403\|work=(punpun|koe|akame)"
    r"\|kind=(SHAPE_REQUIRED|OTHER_JSON|UNCLASSIFIED|NOT_OBSERVED)"
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
NATIVE = re.compile(
    r"MANGAALL_CI_NATIVE\|work=(punpun|koe|akame)"
    r"\|search=([0-9]{1,2})\|chapterReads=([0-9]{1,2})\|rejected=(YES|NO)"
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
    failed: dict[str, tuple[str, str]] = {}
    browser: dict[str, tuple[int, str]] = {}
    budget: set[str] = set()
    challenge403: dict[str, str] = {}
    native: dict[str, tuple[int, int, str]] = {}
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
        elif match := NATIVE.fullmatch(item):
            work, searches, reads, rejected = match.groups()
            if work in native:
                return False, "DUPLICATE_NATIVE_PROOF"
            native[work] = (int(searches), int(reads), rejected)
        elif match := API.fullmatch(item):
            work, *data = match.groups()
            if work in api:
                return False, "DUPLICATE_API"
            api[work] = tuple(data)
        elif match := BROWSER.fullmatch(item):
            work, attempts, outcome = match.groups()
            if work in browser:
                return False, "DUPLICATE_BROWSER_FALLBACK"
            browser[work] = (int(attempts), outcome)
        elif match := FAILURE.fullmatch(item):
            work, cause, http = match.groups()
            if work in failed:
                return False, "DUPLICATE_LOOKUP_FAILURE"
            failed[work] = (cause, http)
        elif match := BUDGET.fullmatch(item):
            work = match.group(1)
            if work in budget:
                return False, "DUPLICATE_SOURCE_BUDGET_DIAGNOSTIC"
            budget.add(work)
        elif match := CHALLENGE403.fullmatch(item):
            work, kind = match.groups()
            if work in challenge403:
                return False, "DUPLICATE_FIRE_403_CLASSIFICATION"
            challenge403[work] = kind
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
    # A challenge diagnosis must come only from a real HTTP403 already
    # observed for that work, not a browser callback or an unrelated status.
    # It cannot make a source/chapters gate pass.
    if any(
        api.get(work, (None, None))[1] != "403" or kind == "NOT_OBSERVED"
        for work, kind in challenge403.items()
    ):
        return False, "FIRE_403_CLASSIFICATION_INCONSISTENT"
    if len(sources) != 9 or any((work, source) not in sources
                                for work in WORKS for source in SOURCES):
        return False, "THREE_SOURCE_MATRIX_MISSING"
    if records.count(SUMMARY) != 1:
        # Preserve the actual source failure instead of hiding the real
        # HTTP403 behind a generic missing PASS summary. Never treat a
        # blocked API as an empty MangaFire catalog or a chapter defect.
        blocked = [
            work for work in WORKS
            if api.get(work) == ("OBSERVED", "403", "NONE", "UNVERIFIED") and
            failed.get(work) == ("CHALLENGE_BLOCKED", "403")
        ]
        # Match Keiyoushi's narrow upstream predicate: not every 403
        # denotes a shape CAPTCHA. No retry, bypass or source PASS follows.
        if any(challenge403.get(work) == "SHAPE_REQUIRED" for work in blocked):
            return False, "MANGAFIRE_SHAPE_REQUIRED_HTTP403"
        for work in blocked:
            attempts, outcome = browser.get(work, (0, "NOT_USED"))
            if attempts > 0 and outcome == "UPSTREAM_CHALLENGE":
                return False, "MANGAFIRE_BROWSER_UPSTREAM_CHALLENGE"
            if attempts > 0 and outcome == "HTTP_REJECTED":
                return False, "MANGAFIRE_BROWSER_HTTP_REJECTED"
            if attempts > 0 and outcome == "WEBVIEW_UNSUPPORTED":
                return False, "MANGAFIRE_BROWSER_UNSUPPORTED"
            if attempts == 0:
                return False, "MANGAFIRE_BROWSER_NOT_CALLED"
        if blocked:
            return False, "MANGAFIRE_UPSTREAM_HTTP403"
        if any(
            work in budget and failed.get(work) == ("UNCLASSIFIED_ERROR", "NONE")
            for work in WORKS
        ):
            return False, "MANGADOT_PAGE_FOUR_PENDING"
        return False, "FIRE_SOURCE_SUMMARY_MISSING"
    if len(proven) != 3 or len(api) != 3 or len(chapter) != 3 or len(lookup) != 3:
        return False, "SOURCE_EVIDENCE_MISSING"
    if len(browser) != 3:
        return False, "BROWSER_FALLBACK_EVIDENCE_MISSING"
    for work in WORKS:
        if proven[work] != ("COMPLETE", "VALIDATED", "COMPLETE", "YES", "SOURCE_READY"):
            return False, "FIRE_SOURCE_NOT_READY"
        if sources[(work, "mangafire")][0] != 1:
            return False, "FIRE_CANDIDATE_NOT_UNIQUE"
        signed, http, transport, json_state = api[work]
        attempts, fallback_outcome = browser[work]
        if (attempts == 0) != (fallback_outcome == "NOT_USED"):
            return False, "BROWSER_FALLBACK_INCONSISTENT"
        if transport == "BROWSER" and (attempts == 0 or fallback_outcome != "SUCCEEDED"):
            return False, "BROWSER_SOURCE_NOT_PROVEN"
        if signed != "OBSERVED" or json_state != "VALIDATED" or (
            not (http == "200" and transport == "HTTP") and transport != "BROWSER"
        ):
            return False, "SOURCE_JSON_NOT_PROVEN"
        if transport == "BROWSER" and http == "NONE":
            # Direct native transport has no observed HTTP status. Require
            # actual native search and chapter reads, never a synthetic 200.
            n = native.get(work)
            if n is None or n[0] < 1 or n[1] < 1 or n[2] != "NO":
                return False, "NATIVE_BROWSER_NOT_PROVEN"
        elif transport == "BROWSER" and http not in ("403", "200", "OTHER"):
            return False, "SOURCE_TRANSPORT_INCONSISTENT"
        if native and (http != "NONE" or transport != "BROWSER" or work not in native):
            return False, "NATIVE_TRANSPORT_NOT_EXCLUSIVE"
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
