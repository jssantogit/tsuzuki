#!/usr/bin/env python3
"""Fail-closed 30-work aggregation of ORIGINAL independent signed source/Host shards.

A green CI result with INCONCLUSIVE_SOURCE never becomes a chapter binding,
Reader pixel proof or a claim that all thirty works are functional.
"""
import json
import os
import pathlib
import sys
from mangaall_code10_canonical_smoke_plan import ACK, parse_plan
from mangaall_code10_canonical_smoke_report import SHA

ALLOWED = {
    "INCONCLUSIVE_SOURCE",
    "INCONCLUSIVE_CATALOG",
    "CHAPTER_EVIDENCE_OBSERVED_NEEDS_READER",
    "INCONCLUSIVE_CANONICAL_CHAPTERS",
    "REPLAY_GAP",
}


def summarize(works, acknowledgement, summaries):
    expected = parse_plan(works, "scale", acknowledgement)
    if len(expected) != 30 or len(summaries) != 3:
        raise ValueError("Thirty-work aggregation requires exactly three source shards")
    positions = {row["position"]: row["caseId"] for row in expected}
    seen_shards, seen_cases, results = set(), set(), []
    for shard in summaries:
        if not isinstance(shard, dict) or shard.get("schemaVersion") != 1 or (
            shard.get("runScope") != "CODE10_SIGNED_V1_CAPTURE_TO_CANONICAL_SQLITE"
        ) or shard.get("providerCode") != 10 or shard.get("signedIndexSequence") != 36 or (
            shard.get("shardCount") != 3 or shard.get("fullVectorWorks") != 30 or
            shard.get("shardSource") != "ORIGINAL_SIGNED_CODE10_PROVIDER_CAPTURE"
        ) or shard.get("catalogIndependent") is not True or (
            shard.get("allThreeSourcesInOneMangaAllLookup") is not True
        ) or shard.get("canonicalGrantFromV2") is not False or (
            shard.get("readerPixels") != "NOT_TESTED_REAL_CDN"
        ):
            raise ValueError("Scale summary does not attest signed-code10 three-source Host evidence")
        index = shard.get("shardIndex")
        if type(index) is not int or index not in (0, 1, 2) or index in seen_shards:
            raise ValueError("Missing, duplicated or unexpected source shard")
        seen_shards.add(index)
        rows = shard.get("results")
        if shard.get("works") != 10 or not isinstance(rows, list) or len(rows) != 10:
            raise ValueError("Incomplete or oversized source shard")
        expected_positions = list(range(index + 1, 31, 3))
        if [x.get("position") for x in rows if isinstance(x, dict)] != expected_positions:
            raise ValueError("Source shard original global work order is inconsistent")
        for row in rows:
            position, case = row.get("position"), row.get("caseId")
            if expected.get(position - 1, {}) if False else False:
                raise AssertionError("unreachable")
            if type(position) is not int or positions.get(position) != case or case in seen_cases:
                raise ValueError("Foreign, duplicated or substituted work in scaled Host report")
            if row.get("outcome") not in ALLOWED or row.get("readerPixels") != "NOT_TESTED_REAL_CDN":
                raise ValueError("Non-closed source/Reader outcome")
            if row.get("editorialObservation") not in {
                "CONFIRMED_ROWS_OBSERVED_NOT_READER_PROOF",
                "BOUND_WITHOUT_CONFIRMED_ROWS",
                "PROVISIONAL_ASSOCIATION_ONLY",
                "AMBIGUOUS_SOURCE_EDITION",
                "NO_CHAPTER_BINDING_OBSERVED",
            }:
                raise ValueError("Unrecognized editorial observation")
            seen_cases.add(case)
            results.append({
                "position": position, "caseId": case, "outcome": row["outcome"],
                "readerPixels": "NOT_TESTED_REAL_CDN",
                "sourceIncomplete": row.get("sourceIncomplete") is True,
                "sqliteConfirmed": row.get("sqliteConfirmed"),
                "sqliteProvisional": row.get("sqliteProvisional"),
                "sqliteBindings": row.get("sqliteBindings"),
                "editorialObservation": row["editorialObservation"],
            })
    if seen_shards != {0, 1, 2} or seen_cases != set(positions.values()) or len(results) != 30:
        raise ValueError("Thirty-work Host correlation missed or duplicated evidence")
    results.sort(key=lambda x: x["position"])
    counts = {}
    for row in results:
        counts[row["outcome"]] = counts.get(row["outcome"], 0) + 1
    return {
        "schemaVersion": 1,
        "scope": "SIGNED_CODE10_30_WORK_THREE_SOURCE_KOTLIN_SQLITE_EVIDENCE_ONLY",
        "signedIndexSequence": 36, "signedCode10Sha256": SHA,
        "totalWorks": 30, "shardCount": 3, "sourceShards": [0, 1, 2],
        "outcomes": counts, "results": results,
        "allThirtyFunctional": False,
        "readerPixels": "NOT_TESTED_REAL_CDN",
        "automaticEditorialBindingFromPartialV1": False,
        "releaseSigningAuthorized": False,
    }


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: aggregate.py SHARD0.json SHARD1.json SHARD2.json OUTPUT.json")
    values = []
    for p in sys.argv[1:4]:
        data = pathlib.Path(p).read_bytes()
        if len(data) > 4_000_000:
            raise ValueError("Oversized shard report")
        values.append(json.loads(data))
    result = summarize(os.environ["MANGAALL_WORKS"], os.environ["MANGAALL_SMOKE_ACK"], values)
    target = pathlib.Path(sys.argv[4])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("MANGAALL_SCALE30_AGGREGATE|works=30|shards=3|caseSet=EXACT|canonical=NO_AUTO_GRANT|realReaderPixels=NOT_TESTED|outcomes=" +
          ",".join(f"{key}:{value}" for key, value in sorted(result["outcomes"].items())))
    if result["outcomes"].get("REPLAY_GAP", 0) > 0:
        raise SystemExit("MANGAALL_SCALE30_GATE|FAIL_REPLAY_GAP")


if __name__ == "__main__":
    main()
