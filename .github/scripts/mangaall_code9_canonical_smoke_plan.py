#!/usr/bin/env python3
"""Offline-only preflight for the first code9 canonical/SQLite smoke vector.

Run this BEFORE checking out Providers, opening a network connection, using
an emulator, or accepting a prior source/canonical report as a PASS.
"""
import hashlib
import os
import re
import sys
import unicodedata

ACK = "I_ACCEPT_CODE9_CANONICAL_SMOKE"
MODES = {"isolated": (3, 3), "scale": (10, 30)}
FORMATS = {"unknown", "manga", "manhwa", "manhua", "webtoon", "light_novel", "web_novel"}
MAX_BYTES = 8192


def parse_plan(works, tier, acknowledgement):
    if acknowledgement != ACK:
        raise ValueError("Explicit code9 canonical smoke authorization missing")
    if tier not in MODES or not isinstance(works, str) or len(works.encode("utf-8")) > MAX_BYTES:
        raise ValueError("Invalid smoke tier or oversized vector")
    rows = [x.strip() for x in works.splitlines() if x.strip()]
    low, high = MODES[tier]
    if not low <= len(rows) <= high:
        raise ValueError(f"Tier {tier} requires between {low} and {high} works")
    result, seen = [], set()
    for row in rows:
        if "::" in row:
            if row.count("::") != 1:
                raise ValueError("Ambiguous work format separator")
            format_name, value = row.split("::", 1)
            format_name, value = format_name.strip().lower(), value.strip()
            if format_name not in FORMATS:
                raise ValueError("Unsupported work format")
        else:
            format_name, value = "unknown", row
        if not value or len(value) > 180 or re.search(r"[\x00-\x1f\x7f]", value) or \
                re.search(r"https?://", value, flags=re.I) or not any(c.isalnum() for c in value):
            raise ValueError("Invalid title in smoke vector")
        value = re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip()
        key = value.lower()
        if key in seen:
            raise ValueError("Duplicate canonical smoke title")
        seen.add(key)
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()[:12]
        result.append({
            "caseId": "user-" + digest,
            "query": value,
            "format": format_name,
            "position": len(result) + 1,
        })
    return result


def main():
    try:
        records = parse_plan(
            os.environ.get("MANGAALL_WORKS", ""),
            os.environ.get("MANGAALL_SMOKE_TIER", ""),
            os.environ.get("MANGAALL_SMOKE_ACK", ""),
        )
    except ValueError as error:
        print("MANGAALL_CODE9_CANONICAL_PLAN|outcome=DENIED|reason=" +
              re.sub(r"[^A-Z0-9_]", "_", str(error).upper())[:110], file=sys.stderr)
        return 2
    print(
        "MANGAALL_CODE9_CANONICAL_PLAN|outcome=READY|tier=" +
        os.environ["MANGAALL_SMOKE_TIER"].upper() +
        "|works=" + str(len(records)) +
        "|ordered=TRUE|sources=3|shards=1|signedCode=9|indexSequence=35" +
        "|upstream=NOT_RUN|canonical=NOT_RUN|readerPixels=NOT_RUN"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
