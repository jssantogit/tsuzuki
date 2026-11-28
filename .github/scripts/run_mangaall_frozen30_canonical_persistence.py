#!/usr/bin/env python3
"""Nonshipping Android collector/SQLite restart gate; sanitized evidence only."""
import argparse
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import DIGEST, locked_works
from run_mangaall_frozen30_catalog_plan import plan as load_plan
from mangaall_research_cohort import load_cohort

APP = "app.tsuzuki.dev"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = ("eu.kanade.tachiyomi.data.tsuzuki.instrumentation."
        "MangaAllFrozen30CanonicalPersistenceInstrumentedTest#frozen30CanonicalPersistence")
OUT = Path(".github/results/mangaall-frozen30-canonical-persistence")
FOCUSED_OUT = Path(".github/results/mangaall-frozen30-pending14-persistence")
PENDING14 = frozenset((5, 6, 8, 11, 15, 16, 19, 20, 21, 23, 25, 27, 28, 29))
REMAINING6 = frozenset((8, 15, 21, 27, 28, 29))
REMAINING_OUT = Path(".github/results/mangaall-frozen30-remaining6-persistence")


JAPANESE28 = frozenset(range(1,31)) - {3,5}
JAPANESE2 = frozenset((5, 13))


def focused_indices(focused):
    if isinstance(focused, frozenset):
        if not focused or not focused <= frozenset(range(1, 31)):
            raise ValueError("INVALID_EXPLICIT_RESEARCH_INDICES")
        return focused
    if focused == "japanese2":
        return JAPANESE2
    if focused == "japanese28":
        return JAPANESE28
    if focused == "remaining6":
        return REMAINING6
    return PENDING14 if focused else frozenset(range(1, 31))


def report_root(focused, cohort=None):
    if cohort and cohort.key == "manga":
        assert focused in (False, "japanese28", "japanese2")
        suffix = {"japanese28": "28-reading-diagnostic", "japanese2": "2-reading-diagnostic",
                  False: "30-canonical-persistence"}[focused]
        return Path(".github/results/mangaall-japanese-manga-" + suffix)
    if focused == "remaining6":
        return REMAINING_OUT
    return FOCUSED_OUT if focused else OUT


def expected_indices(shard, focused=False):
    indices = set(range(shard * 10 + 1, shard * 10 + 11))
    return indices & focused_indices(focused)


def receipt_scope(focused, cohort=None):
    if cohort and cohort.key == "manga":
        assert focused in (False, "japanese28", "japanese2")
        if focused == "japanese2":
            return "JAPANESE2_READING_DIAGNOSTIC_NOT_FINAL_GATE"
        if focused == "japanese28":
            return "JAPANESE28_READING_DIAGNOSTIC_NOT_FINAL_GATE"
        return "CANONICAL_JAPANESE_MANGA30_SQLITE_SELECTED_OPTION_RESTART"
    if focused == "remaining6":
        return "CANONICAL_FROZEN30_REMAINING6_RESTART"
    return ("CANONICAL_FROZEN30_PENDING14_RESTART" if focused
            else "CANONICAL_COLLECTOR_SQLITE_SELECTED_OPTION_RESTART")

STATUS = {
    "DIRTY_DATABASE", "CATALOG_AMBIGUOUS", "CATALOG_NOT_FOUND",
    "CATALOG_UNAVAILABLE", "CATALOG_INCOMPLETE", "CATALOG_UNVERIFIED",
    "CATALOG_FORMAT_UNSUPPORTED", "CATALOG_PROVENANCE_MISSING", "CATALOG_ATTESTED_OWNER_CHANGED",
    "CATALOG_FORMAT_OR_CREATOR_UNVERIFIED", "EDITION_CREDITS_UNVERIFIED", "DUPLICATE_CATALOG_OWNER",
    "REFRESH_FAILED", "CANONICAL_CHAPTER_ONE_MISSING_OR_AMBIGUOUS",
    "READER_OPTION_MISSING_OR_AMBIGUOUS", "SOURCE_ASSOCIATION_MISSING",
    "SOURCE_AUTHOR_OR_EDITION_UNVERIFIED", "SOURCE_SELECTION_FAILED",
    "SQLITE_INVARIANT_FAILED", "COLLECTED", "NO_COLLECTED_RECEIPT",
    "SQLITE_RESTORE_MISMATCH", "RESTORED", "UNCLASSIFIED_FAILURE",
}


def adb(*args, check=True, timeout=120):
    return subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=check)


def parse_phase(log, shard, phase, process_ok, catalog="mangaupdates", owners=None, focused=False):
    indices = expected_indices(shard, focused)
    rows = {}
    invalid = 0
    for match in re.finditer(
        r"MANGAALL_CANONICAL_PERSISTENCE\|phase=(COLLECT|RESTORE)\|index=(\d+)\|outcome=([A-Z_0-9]+)\|catalog=(mangaupdates|shikimori|mal|kitsu)",
        log,
    ):
        observed_phase, index, status, observed_owner = match.groups()
        index = int(index)
        if (observed_phase != phase or index not in indices
                or index in rows or status not in STATUS
                or observed_owner != (owners.get(index) if owners is not None else catalog)
                or (phase == "COLLECT" and status == "RESTORED")
                or (phase == "RESTORE" and status == "COLLECTED")):
            invalid += 1
            continue
        rows[index] = status
    pids = re.findall(r"MANGAALL_CANONICAL_PROCESS\|phase=(COLLECT|RESTORE)\|shard=([012])\|pid=(\d+)", log)
    pid = None
    if len(pids) == 1 and pids[0][:2] == (phase, str(shard)) and int(pids[0][2]) > 0:
        pid = int(pids[0][2])
    else:
        invalid += 1
    expected = "COLLECTED" if phase == "COLLECT" else "RESTORED"
    complete = (process_ok and invalid == 0 and set(rows) == indices
                and all(status == expected for status in rows.values()))
    return {"phase": phase, "processOk": process_ok, "invalid": invalid, "pid": pid,
            "complete": complete,
            "works": [{"index": i, "catalog": owners.get(i) if owners is not None else catalog,
                       "status": rows.get(i, "NOT_OBSERVED")} for i in sorted(indices)]}


READER_STATUS = {
    "CHAPTER_MISSING", "SELECTED_OPTION_MISSING", "PREPARATION_FAILED",
    "UNEXPECTED_DELIVERY", "INVALID_PAGE_AUTHORITY", "LOADER_FAILED",
    "IMAGE_LIMIT", "BITMAP_FAILED", "FIRST_PAGE_DECODED",
    "UNCLASSIFIED_FAILURE", "PERSISTENCE_NOT_VERIFIED",
}


def parse_reader(log, shard, restored, process_ok, catalog, owners, focused=False):
    indices = expected_indices(shard, focused)
    rows = {}
    invalid = 0
    restored_by_index = {row["index"]: row["status"] for row in restored["works"]}
    for match in re.finditer(
        r"MANGAALL_CANONICAL_READER\|index=(\d+)\|outcome=([A-Z_0-9]+)\|catalog=(mangaupdates|shikimori|mal|kitsu)\|ui=NOT_TESTED",
        log,
    ):
        index, status, owner = match.groups()
        index = int(index)
        if (index not in indices or index in rows or status not in READER_STATUS
                or owner != (owners.get(index) if owners is not None else catalog)
                or (status == "FIRST_PAGE_DECODED" and restored_by_index.get(index) != "RESTORED")):
            invalid += 1
            continue
        rows[index] = status
    return {"processOk": process_ok, "invalid": invalid,
            "complete": process_ok and invalid == 0 and set(rows) == indices
                and all(s == "FIRST_PAGE_DECODED" for s in rows.values()),
            "works": [{"index": i, "catalog": owners.get(i) if owners is not None else catalog,
                       "status": rows.get(i, "NOT_OBSERVED")} for i in sorted(indices)]}


def collect_catalog_search_failures(log, shard, focused=False):
    pattern = (r"MANGAALL_CATALOG_SEARCH_FAILURE\|index=(\d+)\|attempt=([123])"
               r"\|reason=(REQUEST_FAILED|INVALID_RESPONSE|TIMEOUT)\|count=(\d{1,2})(?=\s|$)")
    rows = {}
    for match in re.finditer(pattern, log):
        index, attempt, reason, count = match.groups()
        index, attempt, count = int(index), int(attempt), int(count)
        key = (index, attempt, reason)
        if index not in expected_indices(shard, focused) or key in rows or not 0 < count <= 32:
            return []
        rows[key] = dict(index=index, attempt=attempt, reason=reason, count=count)
    return [rows[k] for k in sorted(rows)]


# These are diagnostic-only rows. They cannot grant a chapter or a Reader verdict.
PROVIDER_ERROR_CODES = {
    "UNAVAILABLE", "HOST_ERROR", "PACKAGE_INVALID", "MALFORMED_REQUEST",
    "SOURCE_TOO_LARGE", "SOURCE_READ_ERROR", "INVOCATION_CONFLICT",
    "PERMISSION_DENIED", "HOST_API_UNSUPPORTED", "SCRIPT_ERROR", "TIMEOUT",
    "RUNTIME_DIED", "NETWORK_POLICY", "CHAPTER_IDENTITY_UNVERIFIED",
    "NETWORK_ERROR", "BROWSER_ERROR", "RESOURCE_LIMIT", "MALFORMED_RESULT",
    "AUTH_REQUIRED", "ACQUISITION_FAILED",
}


def collect_lookup_failures(log, shard, focused=False, chapter=False):
    prefix = "MANGAALL_CHAPTER_FAILURE" if chapter else "MANGAALL_LOOKUP_FAILURE"
    pattern = (prefix + r"\|index=(\d+)\|source=(MANGADEX|MANGADOT|UNKNOWN)"
               r"\|code=([A-Z_]{1,48})\|category=(NONE|CAPTCHA|HTTP|MALFORMED_RESPONSE|UNKNOWN)"
               r"\|http=(\d{1,3})\|count=(\d{1,4})(?=\s|$)")
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, code, category, http, count = match.groups()
        index, http, count = int(index), int(http), int(count)
        key = (index, source, code, category, http)
        if (index not in expected_indices(shard, focused) or key in rows
                or code not in PROVIDER_ERROR_CODES or not (http == 0 or 100 <= http <= 599)
                or not 0 < count <= 2048 or len(rows) >= 2048):
            return []
        rows[key] = dict(index=index, source=source, code=code, category=category,
                         http=http, count=count)
    return [rows[k] for k in sorted(rows)]


def collect_reader_failures(log, shard, reader, focused=False):
    pattern = (r"MANGAALL_READER_FAILURE\|index=(\d+)\|code=([A-Z_]{1,48})"
               r"\|retryable=(true|false)\|http=(\d{1,3})"
               r"\|category=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|UNCLASSIFIED_ERROR)"
               r"\|stage=([A-Z_]{1,64})(?=\s|$)")
    failed = {r["index"] for r in reader["works"] if r["status"] == "PREPARATION_FAILED"}
    rows = {}
    for match in re.finditer(pattern, log):
        index, code, retryable, http, category, stage = match.groups()
        index, http = int(index), int(http)
        if (index not in expected_indices(shard, focused) or index not in failed or index in rows
                or code not in PROVIDER_ERROR_CODES or not (http == 0 or 100 <= http <= 599)
                or (http != 0 and category == "NONE")
                or (stage != "NONE" and code != "SOURCE_READ_ERROR")):
            return []
        rows[index] = dict(index=index, code=code, retryable=retryable, http=http,
                           category=category, stage=stage)
    return [rows[k] for k in sorted(rows)]


def collect_diagnostics(log, shard, focused=False):
    fields = ("chapters", "chapterOne", "evidence", "associations", "verified",
              "dexOptions", "dotOptions", "rivalReleaseRefs")
    pattern = r"MANGAALL_CANONICAL_DIAGNOSTIC\|index=(\d+)" + "".join(
        r"\|" + key + r"=(\d+)" for key in fields)
    rows = {}
    for match in re.finditer(pattern, log):
        index, *counts = map(int, match.groups())
        if index not in expected_indices(shard, focused) or index in rows:
            # Diagnostic rows are not success evidence. Drop ambiguous records.
            return []
        rows[index] = dict(index=index, **dict(zip(fields, counts)))
    return [rows[i] for i in sorted(rows)]


def collect_ranges(log, shard, focused=False):
    pattern = (
        r"MANGAALL_CANONICAL_RANGE\|index=(\d+)\|source=(mangadex|mangadot)"
        r"\|mapped=(\d+)\|regular=(\d+)\|min=(-1|\d+)\|max=(-1|\d+)"
        r"\|chapterOne=(\d+)(?=\s|$)"
    )
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, mapped, regular, lo, hi, one = match.groups()
        index, mapped, regular, lo, hi, one = map(int, (index, mapped, regular, lo, hi, one))
        key = (index, source)
        if (index not in expected_indices(shard, focused) or key in rows
                or not 0 <= one <= regular <= mapped <= 1_000_000
                or not (-1 <= lo <= hi <= 1_000_000)
                or ((lo == -1) != (hi == -1))
                or (regular == 0 and lo != -1)):
            return []
        rows[key] = dict(index=index, source=source, mapped=mapped, regular=regular,
                         min=lo, max=hi, chapterOne=one)
    return [rows[k] for k in sorted(rows)]


def collect_required_credits(log, shard, focused=False):
    pattern = (r"MANGAALL_REQUIRED_CREDITS\|index=(\d+)\|source=(MANGADEX|MANGADOT|UNKNOWN)"
               r"\|required=(\d)\|matched=(\d)\|observed=(\d{1,2})\|count=(\d{1,4})(?=\s|$)")
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, required, matched, observed, count = match.groups()
        index, required, matched, observed, count = map(int, (index, required, matched, observed, count))
        key = (index, source, required, matched, observed)
        if (index not in expected_indices(shard, focused) or key in rows or not 1 <= required <= 8
                or not 0 <= matched <= min(required, observed) or not 0 <= observed <= 64
                or not 0 < count <= 2048 or len(rows) >= 2048):
            return []
        rows[key] = dict(index=index, source=source, required=required, matched=matched,
                         observed=observed, count=count)
    return [rows[k] for k in sorted(rows)]


def collect_evidence_shapes(log, shard, focused=False):
    pattern = (r"MANGAALL_EVIDENCE_SHAPE\|index=(\d+)\|source=(mangadex|mangadot)"
               r"\|shape=(NO_TITLE|REPEATED_NUMBER|NUMERIC_TITLE|LETTER_TITLE|PUNCTUATION_TITLE)"
               r"\|parsed=(UNKNOWN|SEMANTIC|OTHER_NUMBER|PART_OR_SUFFIX|EXACT_ONE)"
               r"\|count=(\d{1,7})(?=\s|$)")
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, shape, parsed, count = match.groups()
        index, count = int(index), int(count)
        key = (index, source, shape, parsed)
        if (index not in expected_indices(shard, focused) or key in rows
                or not 0 < count <= 1_000_000 or len(rows) >= 2048):
            return []
        rows[key] = dict(index=index, source=source, shape=shape, parsed=parsed, count=count)
    return [rows[k] for k in sorted(rows)]


def collect_evidence_ranges(log, shard, focused=False):
    pattern = (
        r"MANGAALL_EVIDENCE_RANGE\|index=(\d+)\|source=(mangadex|mangadot)"
        r"\|observed=(\d+)\|mappedRows=(\d+)\|rawOne=(\d+)\|parsedOne=(\d+)"
        r"\|mappedOne=(\d+)(?=\s|$)"
    )
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, observed, mapped, raw_one, parsed, mapped_one = match.groups()
        index, observed, mapped, raw_one, parsed, mapped_one = map(
            int, (index, observed, mapped, raw_one, parsed, mapped_one))
        key = (index, source)
        if (index not in expected_indices(shard, focused) or key in rows
                or not 0 <= mapped <= observed <= 1_000_000
                or not 0 <= parsed <= raw_one <= observed
                or not 0 <= mapped_one <= min(raw_one, mapped)):
            return []
        rows[key] = dict(index=index, source=source, observed=observed, mappedRows=mapped,
                         rawOne=raw_one, parsedOne=parsed, mappedOne=mapped_one)
    return [rows[k] for k in sorted(rows)]


OPTION_REJECTION_REASONS = {
    "CHAPTER_IDENTITY_REJECTED", "MISSING_CHAPTER_KEY", "SOURCE_NOT_AUTHORIZED",
    "CATALOG_PROOF_MISMATCH", "SOURCE_LANGUAGE_MISSING", "SOURCE_LANGUAGE_MISMATCH",
    "SOURCE_KEY_OR_FACET_REJECTED",
}
OPTION_IDENTITY_REASONS = {
    "UNPARSEABLE_SOURCE_IDENTITY", "UNVERIFIED_EARLY_TITLE", "LOW_CONFIDENCE",
    "CHAPTER_IDENTITY_MISMATCH", "SOURCE_VOLUME_BUNDLE", "CONFLICTING_NUMBER_HINT",
    "CONFLICTING_CHAPTER_MARKERS", "MALFORMED_VOLUME_LABEL", "SOURCE_VOLUME_CONFLICT",
    "CANONICAL_VOLUME_CONFLICT", "MIXED_REJECTIONS",
}


def collect_option_rejections(log, shard, focused=False):
    pattern = (
        r"MANGAALL_CANONICAL_OPTION_REJECTION\|index=(\d+)\|source=(mangadex|mangadot)"
        r"\|reason=([A-Z_]{1,64})\|identity=([A-Z_]{1,64})\|count=(\d{1,7})(?=\s|$)"
    )
    rows = {}
    for match in re.finditer(pattern, log):
        index, source, reason, identity, count = match.groups()
        index, count = int(index), int(count)
        key = (index, source, reason, identity)
        if (index not in expected_indices(shard, focused) or key in rows
                or reason not in OPTION_REJECTION_REASONS or not 0 < count <= 1_000_000
                or (reason == "CHAPTER_IDENTITY_REJECTED" and identity not in OPTION_IDENTITY_REASONS)
                or (reason != "CHAPTER_IDENTITY_REJECTED" and identity != "NONE")):
            return []
        rows[key] = dict(index=index, source=source, reason=reason, identity=identity, count=count)
    return [rows[k] for k in sorted(rows)]


def collect_acquisition(log, shard, focused=False):
    # Closed codes and bounded counts only; never forward raw instrumentation.
    pattern = (
        r"MANGAALL_CANONICAL_ACQUISITION\|index=(\d+)\|source=(MANGADEX|MANGADOT|UNKNOWN)"
        r"\|event=(PROVIDER_READING_(?:LOOKUP_ATTEMPT|CANDIDATE|SOURCE_ASSOCIATION|CHAPTER_ATTEMPT))"
        r"\|outcome=([A-Z_]{1,48})\|reason=([A-Z_]{1,64})"
        r"\|candidates=(\d{1,6})\|count=(\d{1,4})(?=\s|$)"
    )
    rows = []
    seen = set()
    for match in re.finditer(pattern, log):
        index, source, event, outcome, reason, candidates, count = match.groups()
        index, candidates, count = int(index), int(candidates), int(count)
        key = (index, source, event, outcome, reason, candidates)
        if (index not in expected_indices(shard, focused) or
                not 0 < count <= 2048 or key in seen or len(rows) >= 2048):
            return []
        seen.add(key)
        rows.append(dict(index=index, source=source, event=event, outcome=outcome,
                         reason=reason, candidates=candidates, count=count))
    return rows


def source_diagnostics(collect):
    """Source observations, not inventory completeness or an absence certificate."""
    ranges = {(r["index"], r["source"]): r for r in collect.get("ranges", [])}
    summaries = {r["index"]: r for r in collect.get("diagnostics", [])}
    acquisition = collect.get("acquisition", [])
    rows = []
    for work in collect.get("works", []):
        index = work["index"]
        for source in ("mangadex", "mangadot"):
            events = [e for e in acquisition if e["index"] == index and e["source"] == source.upper()]
            coverage = ranges.get((index, source))
            mapped = coverage["mapped"] if coverage else None
            chapter_one = coverage["chapterOne"] if coverage else None
            options = summaries.get(index, {}).get("dexOptions" if source == "mangadex" else "dotOptions")
            failure_count = sum(e["count"] for e in events
                                if e["outcome"] in ("FAILED", "TIMEOUT"))
            incomplete = any(e["event"] == "PROVIDER_READING_LOOKUP_ATTEMPT"
                             and e["outcome"] == "PARTIAL" for e in events)
            empty = any(e["reason"] == "COVERAGE_EMPTY" or
                        (e["event"] == "PROVIDER_READING_CHAPTER_ATTEMPT" and e["outcome"] == "EMPTY")
                        for e in events)
            negative_identity = any(e["reason"] in (
                "AUTHOR_CONFLICT", "CATALOG_REFERENCE_CONFLICT", "MISSING_AUTHOR_EVIDENCE",
                "CANONICAL_METADATA_UNVERIFIED", "SOURCE_DETAIL_UNVERIFIED", "TITLE_UNVERIFIED",
                "EDITION_SOURCE_ATTESTED_VARIANT",
            ) for e in events)
            catalog_blocked = (work["status"].startswith("CATALOG_") or
                               work["status"] in ("EDITION_CREDITS_UNVERIFIED", "DUPLICATE_CATALOG_OWNER"))
            if chapter_one and options:
                status = "CHAPTER_ONE_OPTION_OBSERVED"
            elif chapter_one:
                status = "CHAPTER_ONE_MAPPED_OPTION_UNPROVEN"
            elif mapped:
                status = "CHAPTERS_OBSERVED_WITHOUT_ONE"
            elif not events and coverage is None:
                status = "CATALOG_BLOCKED" if catalog_blocked else "SOURCE_NOT_OBSERVED"
            elif failure_count:
                status = "ACQUISITION_FAILURE_OBSERVED"
            elif negative_identity:
                status = "IDENTITY_OR_EDITION_UNVERIFIED"
            elif empty:
                status = "EMPTY_CANDIDATE_OBSERVED"
            elif incomplete:
                status = "LOOKUP_INCOMPLETE"
            else:
                status = "NO_MAPPED_CHAPTERS_CAUSE_UNPROVEN"
            rows.append(dict(index=index, source=source, status=status,
                             mapped=mapped, chapterOne=chapter_one, readerOptions=options,
                             failureCount=failure_count, lookupPartial=incomplete,
                             emptyCandidateObserved=empty, negativeIdentityObserved=negative_identity,
                             inventoryCompleteness="NOT_PROVEN"))
    return rows


def scope(catalog, cohort=None):
    if catalog!="explicit_plan":
        return None, None
    rows, digest=load_plan(cohort)
    return {r["index"]:r["catalog"] for r in rows}, digest


def invoke(shard, phase, catalog, reader=False, focused=False, cohort=None):
    owners, plan_digest=scope(catalog, cohort)
    args=["shell", "am", "instrument", "-w", "-r",
          "-e", "mangaAllCanonicalOptIn", "I_ACCEPT_FROZEN30_CANONICAL_RESEARCH",
          "-e", "mangaAllCanonicalPhase", phase,
          "-e", "mangaAllCanonicalCatalog", catalog,
          "-e", "mangaAllFrozen30Shard", str(shard)]
    if cohort and cohort.key == "manga":
        args.extend(["-e", "mangaAllCanonicalCohort", "manga"])
    if focused == "japanese2":
        args.extend(["-e", "mangaAllJapaneseReadingDiagnostic", "2"])
    elif focused == "japanese28":
        args.extend(["-e", "mangaAllJapaneseReadingDiagnostic", "1"])
    elif focused:
        args.extend(["-e", "mangaAllCanonicalPending14", "1"])
    if focused == "remaining6":
        args.extend(["-e", "mangaAllCanonicalRemaining6", "1"])
    if plan_digest:
        args.extend(["-e","mangaAllCanonicalPlanSha256",plan_digest])
    args.extend(["-e","mangaAllCanonicalReaderProbe","1" if reader and phase == "RESTORE" else "0"])
    args.extend(["-e","class",TEST,RUNNER])
    try:
        result=adb(*args,check=False,timeout=1850)
        ok=(result.returncode==0 and "INSTRUMENTATION_CODE: -1" in result.stdout
            and "OK (1 test)" in result.stdout)
        log = result.stdout
    except subprocess.SubprocessError:
        log, ok = "", False
    parsed = parse_phase(log,shard,phase,ok,catalog,owners,focused)
    if phase == "COLLECT":
        parsed["catalogSearchFailures"] = collect_catalog_search_failures(log, shard, focused)
        parsed["lookupFailures"] = collect_lookup_failures(log, shard, focused)
        parsed["chapterFailures"] = collect_lookup_failures(log, shard, focused, chapter=True)
        parsed["diagnostics"] = collect_diagnostics(log, shard, focused)
        parsed["ranges"] = collect_ranges(log, shard, focused)
        parsed["evidenceRanges"] = collect_evidence_ranges(log, shard, focused)
        parsed["evidenceShapes"] = collect_evidence_shapes(log, shard, focused)
        parsed["requiredCredits"] = collect_required_credits(log, shard, focused)
        parsed["acquisition"] = collect_acquisition(log, shard, focused)
        parsed["optionRejections"] = collect_option_rejections(log, shard, focused)
    if reader and phase == "RESTORE":
        parsed["reader"] = parse_reader(log, shard, parsed, ok, catalog, owners, focused)
        parsed["readerFailures"] = collect_reader_failures(log, shard, parsed["reader"], focused)
    return parsed


def evaluate(shard, collect, restore, force_stopped, catalog="mangaupdates", reader=False, focused=False, cohort=None):
    restart = (force_stopped and collect["pid"] is not None and restore["pid"] is not None
               and collect["pid"] != restore["pid"])
    return {
        "schemaVersion": 1, "corpusSha256": cohort.digest if cohort else DIGEST, "shard": shard,
        "catalogSource": catalog, "planSha256": scope(catalog, cohort)[1],
        "scope": receipt_scope(focused, cohort),
        "restartObserved": restart,
        "outcome": "PASS" if restart and collect["complete"] and restore["complete"]
            and (not reader or restore.get("reader", {}).get("complete") is True) else "RED",
        "readerProbe": "RESTORED_FIRST_PAGE" if reader else "NOT_TESTED",
        "collect": {k: v for k, v in collect.items() if k != "pid"},
        "restore": {k: v for k, v in restore.items() if k != "pid"},
        "signed": "NO", "readerUI": "NOT_TESTED", "editorialEditionEquivalence": "NOT_PROVEN",
    }


def run(shard, catalog, reader=False, focused=False, cohort=None):
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("EMULATOR_REQUIRED")
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(target) != 1 or len(tests) != 1:
        raise SystemExit("APK_INPUT_COUNT")
    output = report_root(focused, cohort) / f"shard-{shard}"
    output.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        if "Success" not in adb("shell", "pm", "clear", APP).stdout:
            raise SystemExit("DISPOSABLE_DATABASE_CLEAR_FAILED")
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        if adb("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() != "0":
            raise SystemExit("NETWORK_UNAVAILABLE")
        collect = invoke(shard, "COLLECT", catalog, focused=focused, cohort=cohort)
        adb("shell", "am", "force-stop", APP)
        force_stopped = not adb("shell", "pidof", APP, check=False).stdout.strip()
        # Do not clear application data between the two instrumentation runs.
        restore = invoke(shard, "RESTORE", catalog, reader, focused, cohort)
        report = evaluate(shard, collect, restore, force_stopped, catalog, reader, focused, cohort)
        (output / "verdict.json").write_text(json.dumps(report, indent=2) + "\n")
        for phase in ("collect", "restore"):
            for row in report[phase]["works"]:
                print(f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase.upper()}|index={row['index']}|outcome={row['status']}")
        for row in report["collect"].get("lookupFailures", []):
            print("MANGAALL_LOOKUP_FAILURE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("evidenceShapes", []):
            print("MANGAALL_EVIDENCE_SHAPE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("requiredCredits", []):
            print("MANGAALL_REQUIRED_CREDITS|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("chapterFailures", []):
            print("MANGAALL_CHAPTER_FAILURE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["restore"].get("readerFailures", []):
            print("MANGAALL_READER_FAILURE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("catalogSearchFailures", []):
            print("MANGAALL_CATALOG_SEARCH_FAILURE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("diagnostics", []):
            print("MANGAALL_CANONICAL_DIAGNOSTIC|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("evidenceRanges", []):
            print("MANGAALL_EVIDENCE_RANGE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("ranges", []):
            print("MANGAALL_CANONICAL_RANGE|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("acquisition", []):
            print("MANGAALL_CANONICAL_ACQUISITION|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("optionRejections", []):
            print("MANGAALL_CANONICAL_OPTION_REJECTION|" + "|".join(f"{key}={value}" for key, value in row.items()))
        if focused in ("japanese28", "japanese2"):
            for row in source_diagnostics(report["collect"]):
                print("MANGAALL_SOURCE_DIAGNOSTIC|" + "|".join(f"{key}={value}" for key,value in row.items()))
        if reader:
            for row in report["restore"]["reader"]["works"]:
                print(f"MANGAALL_CANONICAL_READER|index={row['index']}|outcome={row['status']}|ui=NOT_TESTED")
        print(f"MANGAALL_CANONICAL_PERSISTENCE|shard={shard}|outcome={report['outcome']}|restart={report['restartObserved']}")
        return report["outcome"] == "PASS"
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)


def aggregate(catalog, reader=False, focused=False, cohort=None):
    owners, plan_digest=scope(catalog, cohort)
    complete = True
    decoded = 0
    source_rows = []
    for shard in range(3):
        if not expected_indices(shard, focused):
            continue
        path = report_root(focused, cohort) / f"shard-{shard}" / "verdict.json"
        if not path.is_file():
            complete = False
            continue
        report = json.loads(path.read_text())
        if focused in ("japanese28", "japanese2"):
            source_rows.extend(source_diagnostics(report.get("collect", {})))
        expected = sorted(expected_indices(shard, focused))
        if (report.get("schemaVersion") != 1 or report.get("corpusSha256") != (cohort.digest if cohort else DIGEST)
                or report.get("catalogSource") != catalog or report.get("planSha256") != plan_digest
                or report.get("shard") != shard or report.get("outcome") != "PASS"
                or report.get("restartObserved") is not True
                or report.get("scope") != receipt_scope(focused, cohort)):
            complete = False
        if reader:
            stage = report.get("restore", {}).get("reader", {})
            rows = stage.get("works", [])
            decoded += sum(r.get("status") == "FIRST_PAGE_DECODED" for r in rows)
            if (report.get("readerProbe") != "RESTORED_FIRST_PAGE" or stage.get("complete") is not True
                    or stage.get("processOk") is not True or stage.get("invalid") != 0
                    or [r.get("index") for r in rows] != expected
                    or any(r.get("status") != "FIRST_PAGE_DECODED" or r.get("catalog") != (owners.get(r["index"]) if owners is not None else catalog) for r in rows)):
                complete = False
        for phase, success in (("collect", "COLLECTED"), ("restore", "RESTORED")):
            data = report.get(phase, {})
            rows = data.get("works", [])
            if (data.get("complete") is not True or data.get("processOk") is not True
                    or data.get("invalid") != 0 or [r.get("index") for r in rows] != expected
                    or any(r.get("status") != success or r.get("catalog") != (owners.get(r["index"]) if owners is not None else catalog) for r in rows)):
                complete = False
    total = len(focused_indices(focused))
    if focused in ("japanese28", "japanese2"):
        print(f"MANGAALL_READING_DIAGNOSTIC|scope={focused.upper()}|finalGate=NOT_TESTED|catalogOwners=FRESH_VERIFICATION")
        for source in ("mangadex", "mangadot"):
            rows = [row for row in source_rows if row["source"] == source]
            counts = {}
            for row in rows:
                counts[row["status"]] = counts.get(row["status"], 0) + 1
            for status,count in sorted(counts.items()):
                print(f"MANGAALL_SOURCE_SUMMARY|source={source}|status={status}|count={count}|inventory=NOT_PROVEN")
    if reader:
        print(f"MANGAALL_CANONICAL_READER|decoded={decoded}|expected={total}|ui=NOT_TESTED")
    print(f"MANGAALL_CANONICAL_PERSISTENCE|outcome={'PASS' if complete else 'RED'}"
          f"|expected={total}|readerUI=NOT_TESTED|editorialEditionEquivalence=NOT_PROVEN|signed=NO")
    return complete


def selftest():
    assert expected_indices(0, "japanese2") == {5}
    assert expected_indices(1, "japanese2") == {13}
    assert expected_indices(2, "japanese2") == set()
    manga = load_cohort("manga")
    assert report_root("japanese2", manga) != report_root(False, manga)
    assert receipt_scope("japanese2", manga) != receipt_scope(False, manga)
    valid = ("MANGAALL_CANONICAL_PERSISTENCE|phase=COLLECT|index=5|outcome=COLLECTED|catalog=mangaupdates\n"
             "MANGAALL_CANONICAL_PROCESS|phase=COLLECT|shard=0|pid=123")
    assert parse_phase(valid, 0, "COLLECT", True, focused="japanese2")["complete"]
    assert not parse_phase(valid.replace("index=5", "index=6"), 0, "COLLECT", True, focused="japanese2")["complete"]
    credit = "MANGAALL_REQUIRED_CREDITS|index=1|source=MANGADEX|required=2|matched=1|observed=1|count=3"
    assert collect_required_credits(credit, 0)[0]["matched"] == 1
    assert collect_required_credits(credit.replace("matched=1", "matched=2"), 0) == []
    assert collect_required_credits(credit + "\n" + credit, 0) == []
    shape = "MANGAALL_EVIDENCE_SHAPE|index=1|source=mangadot|shape=NUMERIC_TITLE|parsed=UNKNOWN|count=1"
    assert collect_evidence_shapes(shape, 0)[0]["parsed"] == "UNKNOWN"
    assert collect_evidence_shapes(shape + "\n" + shape, 0) == []
    lookup = ("MANGAALL_LOOKUP_FAILURE|index=1|source=MANGADOT|code=SOURCE_READ_ERROR"
              "|category=HTTP|http=429|count=2")
    assert collect_lookup_failures(lookup, 0) == [
        dict(index=1, source="MANGADOT", code="SOURCE_READ_ERROR", category="HTTP", http=429, count=2)]
    assert collect_lookup_failures(lookup + "\n" + lookup, 0) == []
    assert collect_lookup_failures(lookup.replace("http=429", "http=999"), 0) == []
    assert collect_lookup_failures(lookup.replace("SOURCE_READ_ERROR", "UNRECOGNIZED"), 0) == []
    chapter = lookup.replace("MANGAALL_LOOKUP_FAILURE", "MANGAALL_CHAPTER_FAILURE")
    assert collect_lookup_failures(chapter, 0, chapter=True) == collect_lookup_failures(lookup, 0)
    assert collect_lookup_failures(chapter, 0) == []
    assert collect_lookup_failures(chapter.replace("http=429", "http=999"), 0, chapter=True) == []
    reader = {"works": [dict(index=1, status="PREPARATION_FAILED")]}
    failure = ("MANGAALL_READER_FAILURE|index=1|code=SOURCE_READ_ERROR|retryable=false"
               "|http=403|category=CHALLENGE_BLOCKED|stage=NONE")
    assert collect_reader_failures(failure, 0, reader) == [
        dict(index=1, code="SOURCE_READ_ERROR", retryable="false", http=403,
             category="CHALLENGE_BLOCKED", stage="NONE")]
    assert collect_reader_failures(failure + "\n" + failure, 0, reader) == []
    assert collect_reader_failures(failure, 0, {"works": [dict(index=1, status="FIRST_PAGE_DECODED")]}) == []
    assert collect_reader_failures(failure.replace("http=403", "http=600"), 0, reader) == []

    failure = "MANGAALL_CATALOG_SEARCH_FAILURE|index=1|attempt=1|reason=TIMEOUT|count=1"
    assert collect_catalog_search_failures(failure, 0) == [
        dict(index=1, attempt=1, reason="TIMEOUT", count=1)]
    assert collect_catalog_search_failures(failure + "\n" + failure, 0) == []
    assert collect_catalog_search_failures(failure, 1) == []
    assert collect_catalog_search_failures(failure.replace("TIMEOUT", "OTHER"), 0) == []
    assert collect_catalog_search_failures(failure.replace("count=1", "count=0"), 0) == []
    assert collect_catalog_search_failures(failure.replace("count=1", "count=33"), 0) == []
    assert collect_catalog_search_failures(failure + "\n" + failure.replace("attempt=1", "attempt=2"), 0) == [
        dict(index=1, attempt=1, reason="TIMEOUT", count=1),
        dict(index=1, attempt=2, reason="TIMEOUT", count=1)]

    rejected = ("MANGAALL_CANONICAL_OPTION_REJECTION|index=1|source=mangadot"
                "|reason=SOURCE_LANGUAGE_MISSING|identity=NONE|count=2")
    assert collect_option_rejections(rejected, 0) == [
        dict(index=1, source="mangadot", reason="SOURCE_LANGUAGE_MISSING", identity="NONE", count=2)]
    assert collect_option_rejections(rejected + "\n" + rejected, 0) == []
    assert collect_option_rejections(rejected.replace("NONE", "LOW_CONFIDENCE"), 0) == []
    assert collect_option_rejections(rejected.replace("SOURCE_LANGUAGE_MISSING", "UNKNOWN_REASON"), 0) == []
    assert collect_option_rejections(rejected, 1) == []
    identity = rejected.replace("SOURCE_LANGUAGE_MISSING", "CHAPTER_IDENTITY_REJECTED").replace("NONE", "LOW_CONFIDENCE")
    assert collect_option_rejections(identity, 0)[0]["identity"] == "LOW_CONFIDENCE"
    assert collect_option_rejections(identity.replace("LOW_CONFIDENCE", "NONE"), 0) == []
    def sample(phase, status, pid):
        return "\n".join(
            [f"MANGAALL_CANONICAL_PROCESS|phase={phase}|shard=0|pid={pid}"]
            + [f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase}|index={i}|outcome={status}|catalog=mangaupdates"
               for i in range(1, 11)]
        )
    first = sample("COLLECT", "COLLECTED", 123)
    second = sample("RESTORE", "RESTORED", 456)
    c = parse_phase(first, 0, "COLLECT", True)
    r = parse_phase(second, 0, "RESTORE", True)
    assert evaluate(0, c, r, True)["outcome"] == "PASS"
    assert evaluate(0, c, r, False)["outcome"] == "RED"
    assert evaluate(0, c, parse_phase(sample("RESTORE", "RESTORED", 123), 0, "RESTORE", True), True)["outcome"] == "RED"
    assert not parse_phase(first + "\n" + first, 0, "COLLECT", True)["complete"]
    assert not parse_phase(first, 1, "COLLECT", True)["complete"]
    assert not parse_phase(first, 0, "COLLECT", False)["complete"]
    assert not parse_phase(second.replace("index=10", "index=11"), 0, "RESTORE", True)["complete"]
    assert not parse_phase(second.replace("RESTORED", "NO_COLLECTED_RECEIPT"), 0, "RESTORE", True)["complete"]
    assert not parse_phase(first.replace("catalog=mangaupdates","catalog=shikimori"), 0, "COLLECT", True)["complete"]
    reader_log = "\n".join(f"MANGAALL_CANONICAL_READER|index={i}|outcome=FIRST_PAGE_DECODED|catalog=mangaupdates|ui=NOT_TESTED" for i in range(1, 11))
    r["reader"] = parse_reader(reader_log, 0, r, True, "mangaupdates", None)
    assert evaluate(0, c, r, True, reader=True)["outcome"] == "PASS"
    assert not parse_reader(reader_log+"\n"+reader_log, 0, r, True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log.replace("index=10", "index=11"), 0, r, True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log, 0, parse_phase(second.replace("RESTORED", "NO_COLLECTED_RECEIPT"), 0, "RESTORE", True), True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log.replace("FIRST_PAGE_DECODED", "LOADER_FAILED"), 0, r, True, "mangaupdates", None)["complete"]
    diagnostic = "MANGAALL_CANONICAL_DIAGNOSTIC|index=1|chapters=10|chapterOne=1|evidence=20|associations=2|verified=2|dexOptions=1|dotOptions=2|rivalReleaseRefs=1"
    assert collect_diagnostics(diagnostic, 0)[0]["rivalReleaseRefs"] == 1
    assert collect_diagnostics(diagnostic+"\n"+diagnostic, 0) == []
    assert collect_diagnostics(diagnostic, 1) == []
    range_row = "MANGAALL_CANONICAL_RANGE|index=1|source=mangadex|mapped=3|regular=3|min=40|max=42|chapterOne=0"
    assert collect_ranges(range_row, 0)[0]["min"] == 40
    assert collect_ranges(range_row + "\n" + range_row, 0) == []
    assert collect_ranges(range_row, 1) == []
    assert collect_ranges(range_row.replace("min=40", "min=43"), 0) == []
    assert collect_ranges(range_row + "|id=foreign", 0) == []
    evidence_range = ("MANGAALL_EVIDENCE_RANGE|index=1|source=mangadot|observed=164"
                      "|mappedRows=7|rawOne=1|parsedOne=0|mappedOne=0")
    assert collect_evidence_ranges(evidence_range, 0)[0]["rawOne"] == 1
    assert collect_evidence_ranges(evidence_range, 1) == []
    assert collect_evidence_ranges(evidence_range + "\n" + evidence_range, 0) == []
    assert collect_evidence_ranges(evidence_range.replace("parsedOne=0", "parsedOne=2"), 0) == []
    assert collect_evidence_ranges(evidence_range + "|label=foreign", 0) == []
    acquisition = ("MANGAALL_CANONICAL_ACQUISITION|index=1|source=MANGADOT"
                   "|event=PROVIDER_READING_SOURCE_ASSOCIATION|outcome=CANDIDATES"
                   "|reason=AUTHOR_CONFLICT|candidates=0|count=2")
    assert collect_acquisition(acquisition, 0)[0]["reason"] == "AUTHOR_CONFLICT"
    assert collect_acquisition(acquisition, 1) == []
    assert collect_acquisition(acquisition + "\n" + acquisition, 0) == []
    assert collect_acquisition(acquisition + "|url=https://example.com", 0) == []
    assert len(PENDING14) == 14
    assert set.union(*(expected_indices(shard, True) for shard in range(3))) == PENDING14
    subset = "\n".join(line for line in first.splitlines()
                       if "PROCESS|" in line or any(f"|index={i}|" in line for i in expected_indices(0, True)))
    fc = parse_phase(subset, 0, "COLLECT", True, focused=True)
    assert fc["complete"] and len(fc["works"]) == 3
    assert not parse_phase(subset, 0, "COLLECT", True)["complete"]
    assert not parse_phase(first, 0, "COLLECT", True, focused=True)["complete"]
    fr = parse_phase(subset.replace("COLLECT", "RESTORE").replace("RESTOREED", "RESTORED").replace("pid=123", "pid=456"),
                     0, "RESTORE", True, focused=True)
    assert fr["complete"]
    focused_reader = "\n".join(line for line in reader_log.splitlines()
                               if any(f"|index={i}|" in line for i in expected_indices(0, True)))
    fr["reader"] = parse_reader(focused_reader, 0, fr, True, "mangaupdates", None, focused=True)
    assert evaluate(0, fc, fr, True, reader=True, focused=True)["outcome"] == "PASS"
    assert evaluate(0, fc, fr, True, reader=True, focused=True)["scope"] != receipt_scope(False)
    assert not parse_reader(reader_log, 0, fr, True, "mangaupdates", None, focused=True)["complete"]
    assert set.union(*(expected_indices(shard, "remaining6") for shard in range(3))) == REMAINING6
    assert [len(expected_indices(shard, "remaining6")) for shard in range(3)] == [1, 1, 4]
    assert report_root("remaining6") != report_root(True) != report_root(False)
    assert receipt_scope("remaining6") not in (receipt_scope(True), receipt_scope(False))
    six_subset = "\n".join(line for line in first.splitlines() if "PROCESS|" in line or "|index=8|" in line)
    assert parse_phase(six_subset, 0, "COLLECT", True, focused="remaining6")["complete"]
    assert not parse_phase(six_subset, 0, "COLLECT", True, focused=True)["complete"]
    assert not parse_phase(subset, 0, "COLLECT", True, focused="remaining6")["complete"]
    old, new = load_cohort(), load_cohort("manga")
    assert old.digest != new.digest
    assert report_root(False, old) != report_root(False, new)
    assert receipt_scope(False, old) != receipt_scope(False, new)
    owners, plan_digest = scope("explicit_plan", new)
    def japanese_sample(phase, status, pid):
        return "\n".join(
            [f"MANGAALL_CANONICAL_PROCESS|phase={phase}|shard=0|pid={pid}"]
            + [f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase}|index={i}|outcome={status}|catalog={owners[i]}"
               for i in range(1, 11)]
        )
    jc = parse_phase(japanese_sample("COLLECT", "COLLECTED", 321), 0, "COLLECT", True, "explicit_plan", owners)
    jr = parse_phase(japanese_sample("RESTORE", "RESTORED", 654), 0, "RESTORE", True, "explicit_plan", owners)
    japanese_reader = "\n".join(
        f"MANGAALL_CANONICAL_READER|index={i}|outcome=FIRST_PAGE_DECODED|catalog={owners[i]}|ui=NOT_TESTED"
        for i in range(1, 11)
    )
    jr["reader"] = parse_reader(japanese_reader, 0, jr, True, "explicit_plan", owners)
    jp = evaluate(0, jc, jr, True, "explicit_plan", True, cohort=new)
    assert jp["outcome"] == "PASS" and jp["corpusSha256"] == new.digest
    assert jp["scope"] == receipt_scope(False, new) and jp["planSha256"] == plan_digest
    assert jp["scope"] != receipt_scope(False, old)
    assert evaluate(0, jc, jr, False, "explicit_plan", True, cohort=new)["outcome"] == "RED"
    assert [len(expected_indices(shard, "japanese28")) for shard in range(3)] == [8, 10, 10]
    assert len(JAPANESE28) == 28
    assert report_root("japanese28", new) != report_root(False, new)
    assert receipt_scope("japanese28", new) != receipt_scope(False, new)
    diagnostic_collect = "\n".join(line for line in japanese_sample("COLLECT", "COLLECTED", 321).splitlines()
                                   if "|index=3|" not in line and "|index=5|" not in line)
    assert parse_phase(diagnostic_collect, 0, "COLLECT", True, "explicit_plan", owners, "japanese28")["complete"]
    assert not parse_phase(diagnostic_collect, 0, "COLLECT", True, "explicit_plan", owners)["complete"]
    sample_sources = dict(works=[dict(index=1,status="COLLECTED"),
                                 dict(index=2,status="CATALOG_UNAVAILABLE"),
                                 dict(index=4,status="REFRESH_FAILED")],
                          ranges=[dict(index=1,source="mangadot",mapped=20,chapterOne=1),
                                  dict(index=1,source="mangadex",mapped=0,chapterOne=0)],
                          diagnostics=[dict(index=1,dexOptions=0,dotOptions=1)],
                          acquisition=[dict(index=1,source="MANGADEX",event="PROVIDER_READING_CANDIDATE",
                                            reason="COVERAGE_EMPTY",outcome="REJECTED",count=1),
                                       dict(index=4,source="MANGADOT",event="PROVIDER_READING_CHAPTER_ATTEMPT",
                                            reason="MALFORMED_RESULT",outcome="FAILED",count=1)])
    classified={(r["index"],r["source"]):r for r in source_diagnostics(sample_sources)}
    assert classified[1,"mangadot"]["status"] == "CHAPTER_ONE_OPTION_OBSERVED"
    assert classified[1,"mangadex"]["status"] == "EMPTY_CANDIDATE_OBSERVED"
    assert classified[2,"mangadex"]["status"] == "CATALOG_BLOCKED"
    assert classified[4,"mangadot"]["status"] == "ACQUISITION_FAILURE_OBSERVED"
    assert classified[4,"mangadex"]["status"] == "SOURCE_NOT_OBSERVED"
    assert all(row["inventoryCompleteness"]=="NOT_PROVEN" for row in classified.values())
    print("MANGAALL_CANONICAL_PERSISTENCE|selftest=PASS|cohorts=SEPARATE")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", choices=("mangaupdates", "shikimori", "explicit_plan"), default="mangaupdates")
    parser.add_argument("--reader", action="store_true")
    focus = parser.add_mutually_exclusive_group()
    focus.add_argument("--pending14", action="store_true", help="Original pending fourteen; never certifies all 30")
    focus.add_argument("--remaining6", action="store_true", help="Exact remaining six; never certifies all 30")
    focus.add_argument("--japanese28", action="store_true", help="Diagnostic reading scope; never final gate")
    focus.add_argument("--japanese2", action="store_true", help="Only indices 5 and 13; never final gate")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--shard", type=int, choices=range(3))
    group.add_argument("--aggregate", action="store_true")
    group.add_argument("--selftest", action="store_true")
    parser.add_argument("--cohort", choices=("manhwa", "manga"), default="manhwa")
    args = parser.parse_args()
    cohort = load_cohort(args.cohort)
    if args.selftest:
        selftest()
        return
    focused = ("japanese2" if args.japanese2 else
               "japanese28" if args.japanese28 else ("remaining6" if args.remaining6 else args.pending14))
    if (args.japanese28 or args.japanese2) and args.cohort != "manga":
        parser.error("Japanese diagnostic requires the Japanese corpus")
    if args.japanese2 and args.shard == 2:
        parser.error("Focused Japanese two-work run has no shard 2")
    if args.cohort == "manga" and (focused not in (False, "japanese28", "japanese2") or args.catalog != "explicit_plan" or not args.reader):
        parser.error("Japanese gate requires explicit plan and Reader probe")
    if focused and (args.catalog != "explicit_plan" or not args.reader):
        parser.error("Focused runs require the original explicit catalog plan and Reader probe")
    raise SystemExit(0 if (aggregate(args.catalog, args.reader, focused, cohort) if args.aggregate
                           else run(args.shard, args.catalog, args.reader, focused, cohort)) else 1)


if __name__ == "__main__":
    main()
