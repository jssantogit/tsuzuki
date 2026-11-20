#!/usr/bin/env python3
"""Correlate signed code9 source, independent catalog and Kotlin SQLite verdicts.

This reads actual artifacts *only* after the manually requested execution.
Never infers canonical identity from matching text, invents chapter IDs, or
marks a challenged source as an exhaustive empty source.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from mangaall_code9_canonical_smoke_plan import parse_plan, ACK

SHA = "d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622"
SQL = "MANGAALL_REAL_SQLITE_REFRESH|"
COL = "MANGAALL_CANONICAL_COLLECTOR|"
CASE = re.compile(r"user-[a-f0-9]{12}\Z")
SAFE = re.compile(r"[A-Z0-9_.,:-]{1,500}\Z")


def json_object(path, limit=4_000_000):
    raw = Path(path).read_bytes()
    if len(raw) > limit:
        raise ValueError("Oversized canonical smoke artifact")
    obj = json.loads(raw)
    if not isinstance(obj, dict):
        raise ValueError("Unexpected artifact object")
    return obj


def closed_lines(xml_file, marker):
    raw = Path(xml_file).read_bytes()
    if len(raw) > 4_000_000:
        raise ValueError("Oversized JUnit XML")
    root = ET.fromstring(raw)
    if root.attrib.get("failures", "0") != "0" or root.attrib.get("errors", "0") != "0":
        raise ValueError("JUnit reports failing canonical/SQLite execution")
    found = []
    for node in root.iter():
        if node.tag.split("}")[-1] != "system-out" or not node.text:
            continue
        for text in node.text.splitlines():
            if marker not in text:
                continue
            line = text.split(marker, 1)[1].strip()
            fields = {}
            for part in line.split("|"):
                if "=" not in part:
                    raise ValueError("Malformed closed Host verdict")
                key, value = part.split("=", 1)
                if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key) or not value or not SAFE.fullmatch(value):
                    raise ValueError("Unsafe Host verdict attribute")
                fields[key] = value
            if not CASE.fullmatch(fields.get("case", "")):
                if fields.get("EMPTY_SHARD") is None:
                    raise ValueError("Host verdict missing valid case ID")
            else:
                found.append(fields)
    return found


def _by_case(rows, column):
    values = {}
    for row in rows:
        key = row.get(column)
        if not isinstance(key, str) or not CASE.fullmatch(key) or key in values:
            raise ValueError("Duplicated or forged canonical smoke case")
        values[key] = row
    return values


def merge(works, tier, source, matrix, catalog, collector_rows, sqlite_rows):
    vector = parse_plan(works, tier, ACK)
    if (source.get("schemaVersion") != 1 or
            source.get("captureKind") != "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH" or
            source.get("origin") != "LIVE_SIGNED_PROVIDER_JS" or
            source.get("providerId") != "app.tsuzuki.mangaall" or
            source.get("providerVersionCode") != 9 or
            source.get("repositoryId") != "app.tsuzuki.providers" or
            source.get("signedIndexSequence") != 35 or
            source.get("packageSha256") != SHA or
            source.get("selectionSource") != "USER_INPUT" or
            source.get("shardIndex") != 0 or source.get("shardCount") != 1):
        raise ValueError("Not an authentic sequence35/code9 capture identity")
    if catalog.get("schemaVersion") != 1 or
            catalog.get("origin") != "DIRECT_LIVE_ANDROID_CATALOG_ONLY" or
            catalog.get("canonicalStage") != "NOT_EXECUTED_HOST" or
            not isinstance(catalog.get("sha256"), str)):
        raise ValueError("Catalog proof cannot be promoted or spoofed")
    if matrix.get("provider") != "app.tsuzuki.mangaall" or
            matrix.get("version") != "0.5.4" or
            matrix.get("shardCount") != 1 or matrix.get("shardIndex") != 0 or
            matrix.get("selectionSource") != "USER_INPUT"):
        raise ValueError("Source matrix not from the same code9 vector")
    chosen = [v["caseId"] for v in vector]
    ids = set(chosen)
    sources = _by_case(source.get("cases", []), "caseId")
    catalogs = _by_case(catalog.get("cases", []), "caseId")
    matrices = _by_case(matrix.get("cases", []), "case")
    collectors = _by_case(collector_rows, "case")
    sqlites = _by_case(sqlite_rows, "case")
    if any(set(group) != ids for group in (sources, catalogs, matrices, collectors, sqlites)):
        raise ValueError("Source/catalog/Host/SQLite case set differs; no cross-work substitution")

    rows = []
    for title in vector:
        cid = title["caseId"]
        probe = matrices[cid]
        health = probe.get("httpBySource")
        if not isinstance(health, dict) or set(health) != {"MANGADEX", "MANGADOT", "MANGAFIRE"}:
            raise ValueError("Smoke must attribute HTTP health to exactly three sources")
        source_incomplete = any(
            isinstance(record, dict) and (
                (record.get("blocked403") or 0) > 0 or
                (record.get("transportFailed") or 0) > 0
            ) for record in health.values()
        ) or probe.get("lookupComplete") is not True
        cat = catalogs[cid].get("status")
        col = collectors[cid]
        sql = sqlites[cid]
        if not isinstance(cat, str) or not re.fullmatch(r"[A-Z_]+", cat):
            raise ValueError("Unclosed catalog status")
        if sql.get("sqliteConfirmed", "0").isdigit() is False:
            raise ValueError("Invalid SQLite confirmed chapter count")
        if col.get("gaps", "0") != "0" or sql.get("replayGaps", "0") != "0":
            outcome = "REPLAY_GAP"
        elif source_incomplete:
            outcome = "INCONCLUSIVE_SOURCE"
        elif cat != "RESOLVED":
            outcome = "INCONCLUSIVE_CATALOG"
        elif int(sql.get("sqliteConfirmed", "0")) > 0:
            outcome = "CHAPTER_EVIDENCE_OBSERVED_NEEDS_READER"
        else:
            outcome = "INCONCLUSIVE_CANONICAL_CHAPTERS"
        rows.append({
            "position": title["position"],
            "caseId": cid,
            "outcome": outcome,
            "sourceIncomplete": source_incomplete,
            "catalog": cat,
            "sqliteConfirmed": int(sql.get("sqliteConfirmed", "0")),
            "readerPixels": "NOT_TESTED_REAL_CDN",
        })
    return {
        "schemaVersion": 1, "runScope": "CODE9_SIGNED_V1_CAPTURE_TO_CANONICAL_SQLITE",
        "signedIndexSequence": 35, "providerCode": 9, "works": len(rows),
        "ordered": True, "catalogIndependent": True, "allThreeSourcesInOneMangaAllLookup": True,
        "readerPixels": "NOT_TESTED_REAL_CDN",
        "canonicalGrantFromV2": False,
        "results": rows,
    }


def main():
    if len(sys.argv) != 7:
        raise SystemExit("usage: report.py SOURCE.json MATRIX.json CATALOG.json COLLECTOR.xml SQLITE.xml OUT.json")
    source, matrix, catalog = map(json_object, sys.argv[1:4])
    result = merge(os.environ["MANGAALL_WORKS"], os.environ["MANGAALL_SMOKE_TIER"],
                   source, matrix, catalog,
                   closed_lines(sys.argv[4], COL),
                   closed_lines(sys.argv[5], SQL))
    Path(sys.argv[6]).parent.mkdir(parents=True, exist_ok=True)
    Path(sys.argv[6]).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    counts = {}
    for row in result["results"]:
        counts[row["outcome"]] = counts.get(row["outcome"], 0) + 1
    print("MANGAALL_CODE9_CANONICAL_SMOKE_REPORT|works=" + str(result["works"]) +
          "|outcomes=" + ",".join(f"{key}:{value}" for key, value in sorted(counts.items())) +
          "|readerCdnPixels=NOT_TESTED|bindingFromV2=DENIED")


if __name__ == "__main__":
    main()
