#!/usr/bin/env python3
"""Fail-closed, no-URL export of actual Android MangaAll Reader instrumentation.

Never copies raw Android stdout or image bytes into CI artifacts. Only closed
codes/counts and query hashes are retained, and every assigned work must be
accounted for (including inconclusive upstream and reader failures).
"""
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

RESULT = re.compile(
    r"(?m)^INSTRUMENTATION_STATUS: stream="
    r"(MANGAALL_LIVE_ANDROID_READER\|[^\r\n]{1,700})\s*$"
)
BATCH = re.compile(
    r"(?m)^INSTRUMENTATION_STATUS: stream="
    r"MANGAALL_LIVE_ANDROID_READER_BATCH\|selected=(\d+)\|"
    r"rendered=(\d+)\|mode=DIRECT_LIVE_ANDROID_READER_VIEW\s*$"
)
FIELDS = (
    "case", "index", "mode", "stage", "catalog", "sqliteChapters",
    "readerOptions", "pageMetadata", "bitmapDecoded", "readerViewLoaded",
    "windowPixelCopy", "activityNavigation", "pageErrorCode",
    "pageErrorStage", "pageHttpStatus",
)
CLOSED = re.compile(r"[A-Z][A-Z0-9_]{0,80}\Z")
NONNEG = re.compile(r"\d{1,6}\Z")


def normalized_title(line):
    fields = line.split("::", maxsplit=1)
    title = fields[1].strip() if len(fields) == 2 else line.strip()
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", title).strip()).lower()


def case_id(work):
    return "user-" + hashlib.sha256(normalized_title(work).encode("utf-8")).hexdigest()[:12]


def validate(instrumentation, works, shard_index, shard_count):
    if len(instrumentation.encode("utf-8")) > 800_000:
        raise ValueError("Instrumentation output too large")
    work_lines = [x.strip() for x in works.splitlines() if x.strip()]
    if not (1 <= len(work_lines) <= 30 and 1 <= shard_count <= 3 and 0 <= shard_index < shard_count):
        raise ValueError("Invalid generic work/shard input")
    assigned = {i: case_id(w) for i, w in enumerate(work_lines) if i % shard_count == shard_index}
    if len(set(assigned.values())) != len(assigned):
        raise ValueError("Duplicate assigned case ID")
    rows, seen = [], set()
    for match in RESULT.finditer(instrumentation):
        tokens = match.group(1).split("|")
        if tokens[0] != "MANGAALL_LIVE_ANDROID_READER":
            raise ValueError("Unexpected Android result marker")
        pairs = [token.split("=", maxsplit=1) for token in tokens[1:]]
        if len(pairs) != len(FIELDS) or [k for k, _ in pairs] != list(FIELDS):
            raise ValueError("Malformed or unexpected Reader status fields")
        record = dict(pairs)
        if not NONNEG.fullmatch(record["index"]):
            raise ValueError("Invalid Reader case index")
        index = int(record["index"])
        if index not in assigned or record["case"] != assigned[index] or index in seen:
            raise ValueError("Missing/mismatched/duplicate Reader work result")
        seen.add(index)
        if record["mode"] != "DIRECT_LIVE_ANDROID_READER_VIEW":
            raise ValueError("Incorrect Reader evidence level")
        if record["activityNavigation"] != "NOT_EXECUTED":
            raise ValueError("View-only probe cannot claim Activity navigation")
        if any(not CLOSED.fullmatch(record[field]) for field in (
            "stage", "catalog", "pageErrorCode", "pageErrorStage"
        )):
            raise ValueError("Non-closed stage or catalog status")
        if not NONNEG.fullmatch(record["pageHttpStatus"]):
            raise ValueError("Invalid source HTTP status")
        record["pageHttpStatus"] = int(record["pageHttpStatus"])
        if record["pageHttpStatus"] not in (0, *range(100, 600)):
            raise ValueError("Invalid source HTTP range")
        if record["pageErrorCode"] == "NONE" and (
            record["pageErrorStage"] != "NONE" or record["pageHttpStatus"]
        ):
            raise ValueError("Pages failure claims without typed Provider error")
        for field in ("sqliteChapters", "readerOptions", "pageMetadata"):
            if not NONNEG.fullmatch(record[field]):
                raise ValueError("Invalid observed count")
            record[field] = int(record[field])
        for field in ("bitmapDecoded", "readerViewLoaded", "windowPixelCopy"):
            if record[field] not in ("true", "false"):
                raise ValueError("Invalid pixel-proof boolean")
            record[field] = record[field] == "true"
        if record["windowPixelCopy"] and (
            not record["bitmapDecoded"] or not record["readerViewLoaded"]
        ):
            raise ValueError("Pixel copy without bitmap decode and Viewer load")
        if (record["stage"] == "RENDERED_PIXELS") != record["windowPixelCopy"]:
            raise ValueError("False Android pixel success claim")
        rows.append(record)
    batches = BATCH.findall(instrumentation)
    if len(batches) != 1:
        raise ValueError("Missing or duplicate Reader batch attestation")
    expected, reported_pixels = map(int, batches[0])
    if expected != len(assigned) or len(seen) != len(assigned):
        raise ValueError("Silent omission of assigned live Reader works")
    pixels = sum(r["windowPixelCopy"] for r in rows)
    if reported_pixels != pixels:
        raise ValueError("Rendered count does not equal observed window pixel copies")
    return {
        "schemaVersion": 1,
        "mode": "DIRECT_LIVE_ANDROID_READER_VIEW",
        "scope": "PROVIDER_REAL_HTTP_SQLDELIGHT_READER_VIEW_NOT_ACTIVITY_NAVIGATION",
        "assigned": len(assigned),
        "rendered": pixels,
        "cases": sorted(rows, key=lambda row: int(row["index"])),
    }


def export(raw_path, works_path, shard_index, shard_count, destination):
    report = validate(
        Path(raw_path).read_text(encoding="utf-8"),
        Path(works_path).read_text(encoding="utf-8"),
        shard_index,
        shard_count,
    )
    Path(destination).write_text(
        json.dumps(report, ensure_ascii=True, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit("usage: validate_mangaall_live_reader.py RAW_LOG WORKS_FILE SHARD_INDEX SHARD_COUNT OUT_JSON")
    report = export(
        sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
    )
    print(f"MANGAALL_ANDROID_READER_VALIDATED|assigned={report['assigned']}|rendered={report['rendered']}")
