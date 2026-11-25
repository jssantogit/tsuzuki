#!/usr/bin/env python3
"""Separate public-credit audit of the thirteen remaining frozen works.

No raw instrumentation, URLs, source IDs or private user data are exported.
No chapter inventory/page delivery is invoked or counted as acceptance.
"""
import argparse
import json
import re
from pathlib import Path

from run_mangaall_frozen30_canonical_persistence import (
    APP, RUNNER, TEST, PENDING14, STATUS, adb, expected_indices, scope,
)
from run_mangaall_frozen30_independent_catalog import DIGEST, locked_works

OUT = Path(".github/results/mangaall-pending13-credit-audit")
REMAINING6 = frozenset({8, 15, 21, 27, 28, 29})
SOURCES = {"mangadex", "mangadot"}
DETAILS = {"NONE", "NOT_NEEDED", "SKIPPED_IRRELEVANT", "BUDGET_EXHAUSTED",
           "VERIFIED_WITH_ALIASES", "VERIFIED_NO_ALIASES", "IDENTITY_MISMATCH",
           "INVALID_RESPONSE", "CHALLENGE_BLOCKED", "DETAIL_FAILED"}
COVERAGES = {"NONE", "OBSERVED", "EMPTY", "UNKNOWN", "INVALID_RESPONSE", "CHALLENGE_BLOCKED", "FAILED"}
VERIFICATIONS = {"AUTHOR_CORROBORATED", "CANONICAL_METADATA_UNVERIFIED",
                 "SOURCE_DETAIL_UNVERIFIED", "MISSING_AUTHOR_EVIDENCE", "AUTHOR_CONFLICT"}
EDITIONS = {"UNRESOLVED", "SOURCE_ATTESTED_VARIANT"}
STATES = {"LOOKUP_FAILURE", "COMPLETE", "BOUNDED"}


def valid_names(values):
    return (isinstance(values, list) and len(values) <= 32
            and all(isinstance(v, str) and 0 < len(v) <= 128
                    and all(c.isalnum() or c in " .,'’·_()-" or
                            __import__("unicodedata").category(c).startswith("M") for c in v)
                    for v in values))


def audit_indices(shard, focused=False):
    expected = expected_indices(shard, True) - {16}
    return expected & REMAINING6 if focused else expected


def parse(log, shard, owners, focused=False):
    expected = audit_indices(shard, focused)
    rows = {}
    invalid = 0
    outcomes = {}
    for match in re.finditer(
            r"MANGAALL_CANONICAL_PERSISTENCE\|phase=CREDIT_AUDIT\|index=(\d+)\|outcome=([A-Z_0-9]+)\|catalog=(mangaupdates|shikimori)(?=\s|$)", log):
        index, outcome, owner = int(match[1]), match[2], match[3]
        if (index not in expected or index in outcomes or owner != owners.get(index)
                or outcome not in STATUS | {"IDENTITY_AUDITED"}):
            invalid += 1
            continue
        outcomes[index] = outcome
    for match in re.finditer(r"MANGAALL_PUBLIC_CREDIT_AUDIT\|index=(\d+)\|payload=([0-9a-f]+)(?=\s|$)", log):
        index = int(match[1])
        try:
            if index not in expected or index in rows or len(match[2]) > 96 * 1024:
                raise ValueError("scope")
            row = json.loads(bytes.fromhex(match[2]).decode("utf-8"))
            if (set(row) != {"index", "catalog", "authors", "artists", "aliases", "queryCount",
                                 "availableQueryCount", "sources", "candidates"}
                    or row["index"] != index or row["catalog"] != owners[index]
                    or not all(valid_names(row[k]) for k in ("authors", "artists", "aliases"))
                    or type(row["queryCount"]) is not int or type(row["availableQueryCount"]) is not int
                    or not 1 <= row["queryCount"] <= 6
                    or not row["queryCount"] <= row["availableQueryCount"] <= 4096
                    or (row["queryCount"] < row["availableQueryCount"] and "COMPLETE" in row["sources"].values())
                    or set(row["sources"]) != SOURCES or not set(row["sources"].values()) <= STATES
                    or not isinstance(row["candidates"], list) or len(row["candidates"]) > 24):
                raise ValueError("report")
            for c in row["candidates"]:
                if (set(c) != {"source", "authors", "detail", "coverage", "verification", "edition"}
                        or c["source"] not in SOURCES or not valid_names(c["authors"])
                        or c["detail"] not in DETAILS or c["coverage"] not in COVERAGES
                        or c["verification"] not in VERIFICATIONS or c["edition"] not in EDITIONS):
                    raise ValueError("candidate")
            rows[index] = row
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeError):
            invalid += 1
    return {"scope": ("REMAINING6_PUBLIC_CREDITS_ONLY_NO_READER" if focused
                      else "PUBLIC_CREDITS_ONLY_NO_READER"), "corpusSha256": DIGEST,
            "shard": shard, "expected": sorted(expected), "invalid": invalid,
            "outcomes": [{"index": i, "status": outcomes.get(i, "NOT_OBSERVED")} for i in sorted(expected)],
            "works": [rows[i] for i in sorted(rows)]}


def run(shard, focused=False):
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("EMULATOR_REQUIRED")
    targets = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
               if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(targets) != 1 or len(tests) != 1:
        raise SystemExit("APK_INPUT_COUNT")
    owners, plan_digest = scope("explicit_plan")
    out = OUT / ("remaining6" if focused else "pending13") / f"shard-{shard}"
    out.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", str(targets[0]))
        adb("install", "-r", str(tests[0]))
        if "Success" not in adb("shell", "pm", "clear", APP).stdout:
            raise SystemExit("DISPOSABLE_DATABASE_CLEAR_FAILED")
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        result = adb("shell", "am", "instrument", "-w", "-r",
                     "-e", "mangaAllCanonicalOptIn", "I_ACCEPT_FROZEN30_CANONICAL_RESEARCH",
                     "-e", "mangaAllCanonicalPhase", "CREDIT_AUDIT",
                     "-e", "mangaAllCanonicalCatalog", "explicit_plan",
                     "-e", "mangaAllCanonicalPending14", "1",
                     "-e", "mangaAllCreditAuditOptIn", "PUBLIC_CREDITS_ONLY",
                     "-e", "mangaAllCreditAuditScope", "REMAINING6" if focused else "PENDING13",
                     "-e", "mangaAllCanonicalPlanSha256", plan_digest,
                     "-e", "mangaAllFrozen30Shard", str(shard),
                     "-e", "class", TEST, RUNNER, check=False, timeout=1850)
        report = parse(result.stdout, shard, owners, focused)
        report["processOk"] = (result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in result.stdout
                               and "OK (1 test)" in result.stdout)
        (out / "public-credit-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        for outcome in report["outcomes"]:
            print(f"MANGAALL_CREDIT_AUDIT_STATUS|index={outcome['index']}|outcome={outcome['status']}")
        for row in report["works"]:
            print(f"MANGAALL_CREDIT_AUDIT|index={row['index']}|candidates={len(row['candidates'])}"
                  f"|authorAliases={len(row['aliases'])}|dex={row['sources']['mangadex']}|dot={row['sources']['mangadot']}")
        return (report["processOk"] and report["invalid"] == 0
                and [row["index"] for row in report["works"]] == report["expected"]
                and all(row["status"] == "IDENTITY_AUDITED" for row in report["outcomes"]))
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)


def selftest():
    assert len(PENDING14 - {16}) == 13
    assert set().union(*(audit_indices(i, True) for i in range(3))) == REMAINING6
    owners = {5: "shikimori"}
    row = {"index": 5, "catalog": "shikimori", "authors": ["Public Writer"],
           "artists": [], "aliases": ["공개 작가"], "queryCount": 3, "availableQueryCount": 3,
           "sources": {"mangadex": "COMPLETE", "mangadot": "BOUNDED"},
           "candidates": [{"source": "mangadex", "authors": ["Writer Public"],
                           "detail": "VERIFIED_WITH_ALIASES", "coverage": "EMPTY",
                           "verification": "AUTHOR_CORROBORATED", "edition": "UNRESOLVED"}]}
    line = "MANGAALL_PUBLIC_CREDIT_AUDIT|index=5|payload=" + json.dumps(row).encode().hex()
    assert parse(line, 0, owners)["works"] == [row]
    failure = "MANGAALL_CANONICAL_PERSISTENCE|phase=CREDIT_AUDIT|index=5|outcome=CATALOG_UNAVAILABLE|catalog=shikimori"
    assert parse(failure, 0, owners)["outcomes"][0]["status"] == "CATALOG_UNAVAILABLE"
    assert parse(failure + "\n" + failure, 0, owners)["invalid"] == 1
    assert parse("", 0, owners)["outcomes"][0]["status"] == "NOT_OBSERVED"
    assert parse(line + "\n" + line, 0, owners)["invalid"] == 1
    assert parse(line, 1, owners)["invalid"] == 1
    assert parse(line, 0, owners, focused=True)["invalid"] == 1
    row["availableQueryCount"] = 7
    assert parse("MANGAALL_PUBLIC_CREDIT_AUDIT|index=5|payload=" + json.dumps(row).encode().hex(), 0, owners)["invalid"] == 1
    row["availableQueryCount"] = 3
    row["authors"] = ["https://example.com"]
    assert not parse("MANGAALL_PUBLIC_CREDIT_AUDIT|index=5|payload=" + json.dumps(row).encode().hex(), 0, owners)["works"]
    print("MANGAALL_CREDIT_AUDIT|selftest=PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--shard", type=int, choices=range(3))
    mode.add_argument("--selftest", action="store_true")
    parser.add_argument("--remaining6", action="store_true")
    args = parser.parse_args()
    locked_works()
    if args.selftest:
        selftest()
    else:
        raise SystemExit(0 if run(args.shard, args.remaining6) else 1)
