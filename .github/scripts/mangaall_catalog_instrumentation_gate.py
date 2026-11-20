#!/usr/bin/env python3
"""Fail-closed Android instrumentation and catalog case-set gate.

A real upstream CATALOG_UNAVAILABLE is an inconclusive *observation*, not a
failure of the Android test runner. A failed JUnit assertion, absent or
swapped work, fabricated owner, or incomplete test output DOES fail the gate.
Never expose raw upstream error text, credentials or response bodies.
"""
import json
import os
import re
import sys
from pathlib import Path
from validate_mangaall_catalog_attestation import case_id

CLOSED = {"RESOLVED", "CATALOG_AMBIGUOUS", "CATALOG_NOT_FOUND"}
BATCH = re.compile(
    r"INSTRUMENTATION_STATUS: stream=MANGAALL_REAL_CATALOG_BATCH\|"
    r"selected=(\d+)\|conclusive=(\d+)\|catalog=MANGAUPDATES_PRODUCTION"
    r"\|canonical=NOT_EXECUTED_HOST"
)
RESULT = re.compile(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)\s*$")
STATUS = re.compile(r"(?m)^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$")


def validate(raw, artifact, works, shard_index=0, shard_count=1, exit_code=0):
    if exit_code != 0 or len(raw.encode("utf-8")) > 1_000_000:
        raise ValueError("ANDROID_RUNNER_FAILED")
    status_codes = [int(value) for value in STATUS.findall(raw)]
    end_codes = [int(value) for value in RESULT.findall(raw)]
    if (end_codes != [-1] or not status_codes or 0 not in status_codes or
            any(value < 0 for value in status_codes) or
            re.search(r"(?m)^INSTRUMENTATION_(?:RESULT|STATUS): (?:shortMsg|stack)=", raw) or
            "FAILURES!!!" in raw or "INSTRUMENTATION_ABORTED" in raw):
        raise ValueError("ANDROID_TEST_DID_NOT_PASS")
    if not 1 <= shard_count <= 3 or shard_index not in range(shard_count):
        raise ValueError("INVALID_SHARD")
    lines = [line.strip() for line in works.splitlines() if line.strip()]
    if not 1 <= len(lines) <= 30:
        raise ValueError("INVALID_VECTOR")
    selected = []
    for index, line in enumerate(lines):
        if index % shard_count != shard_index:
            continue
        title = line.split("::", 1)[-1].strip()
        if not title or len(title) > 256:
            raise ValueError("INVALID_TITLE")
        selected.append((case_id(title), title))
    found = BATCH.findall(raw)
    if len(found) != 1 or int(found[0][0]) != len(selected):
        raise ValueError("CATALOG_BATCH_MISMATCH")
    if not isinstance(artifact, dict) or artifact.get("schemaVersion") != 1 or \
            artifact.get("origin") != "DIRECT_LIVE_ANDROID_CATALOG_ONLY" or \
            artifact.get("canonicalStage") != "NOT_EXECUTED_HOST":
        raise ValueError("UNTRUSTED_CATALOG_PROVENANCE")
    records = artifact.get("cases")
    if not isinstance(records, list) or len(records) != len(selected):
        raise ValueError("CATALOG_RESULT_COUNT_MISMATCH")
    for row, (identity, title) in zip(records, selected):
        if row.get("caseId") != identity or row.get("query") != title:
            raise ValueError("CATALOG_ORDER_OR_ID_MISMATCH")
    conclusive = sum(row.get("status") in CLOSED for row in records)
    if int(found[0][1]) != conclusive:
        raise ValueError("CATALOG_CONCLUSIVE_COUNT_MISMATCH")
    return {"works": len(selected), "conclusive": conclusive,
            "inconclusive": len(selected) - conclusive}


def main(argv):
    if len(argv) != 4:
        raise SystemExit("usage: gate.py RAW_INSTRUMENTATION ARTIFACT.json RUNNER_EXIT_CODE")
    try:
        summary = validate(
            Path(argv[1]).read_text(encoding="utf-8"),
            json.loads(Path(argv[2]).read_text(encoding="utf-8")),
            os.environ["MANGAALL_WORKS"],
            int(os.environ["MANGAALL_SHARD_INDEX"]),
            int(os.environ["MANGAALL_SHARD_COUNT"]),
            int(argv[3]),
        )
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        print("MANGAALL_LIVE_CATALOG_GATE|outcome=FAIL|reason=TEST_OR_CASESET_INVALID",
              file=sys.stderr)
        return 1
    outcome = "RECORDED" if summary["conclusive"] == summary["works"] else "PARTIAL_INCONCLUSIVE"
    print("MANGAALL_LIVE_CATALOG_GATE|outcome=" + outcome +
          "|works=" + str(summary["works"]) +
          "|conclusive=" + str(summary["conclusive"]) +
          "|inconclusive=" + str(summary["inconclusive"]) +
          "|canonical=NOT_EXECUTED_HOST|reader=NOT_TESTED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
