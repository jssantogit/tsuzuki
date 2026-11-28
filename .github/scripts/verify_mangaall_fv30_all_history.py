#!/usr/bin/env python3
"""Consolidate all three immutable first passes and focal artifacts; no live calls."""
import json
import re
from pathlib import Path

from run_mangaall_fv30_first_pass import locked as original_lock
from run_mangaall_fv30_parallel import locked as parallel_lock

ROOT = Path("fv30-all-history")
HASH = re.compile(r"[0-9a-f]{64}")
SCOPES = {"shikimori", "mangaupdates"}
EXPECTED = {"fv30a": {2, 14, 22, 29}, "fv30b": {4, 12, 13, 16, 21, 30}, "fv30c": {10}}


def read(path):
    return json.loads((ROOT / path).read_text())


def validate(row, index):
    assert row["index"] == index and row["catalog"] in SCOPES
    assert row["status"] != "NOT_OBSERVED"
    if row["status"] == "CATALOG_IDENTITY_ATTESTED":
        assert HASH.fullmatch(row["identityDigest"])
        assert row["creatorDigests"] and all(HASH.fullmatch(x) for x in row["creatorDigests"])
    else:
        assert row["identityDigest"] is None and not row["creatorDigests"]


def consolidate():
    rows_a, digest_a = original_lock()
    prior_a = read("a/candidate-receipts.json")
    assert prior_a["scope"] == "FV30_ACCUMULATED_CATALOG_CANDIDATES_ONLY"
    assert prior_a["corpusSha256"] == digest_a and prior_a["uniqueCandidates"] == 26
    assert prior_a["pending"] == sorted(EXPECTED["fv30a"])
    assert prior_a["duplicateGroups"] == [] and not prior_a["conflicts"]
    assert [r["index"] for r in prior_a["works"]] == list(range(1, 31))
    data = {"fv30a": prior_a["works"]}
    focused = read("focused/summary.json")
    assert focused["scope"] == "FV30_BC_FOCUSED_DIAGNOSTIC_ONLY"
    assert focused["sourceRun"] == 46136665066 and focused["complete"] is True
    assert focused["observations"] == focused["expected"] == len(focused["works"]) == 32
    assert focused["candidateWorks"] == 17
    observed_keys = set()
    for key, (rows, digest) in parallel_lock().items():
        prior = read(f"{key}/first-pass-summary.json")
        assert prior["cohort"] == key and prior["corpusSha256"] == digest
        assert prior["scope"] == "FV30_CATALOG_FIRST_PASS_ONLY"
        assert prior["attested"] == prior["unique"] == (20 if key == "fv30b" else 16)
        assert [(r["index"], r["catalog"]) for r in prior["works"]] == [(r["index"], r["catalog"]) for r in rows]
        observations = {r["index"]: [dict(r, runId=46136665066)] for r in prior["works"]}
        pending = {r["index"] for r in prior["works"] if r["status"] != "CATALOG_IDENTITY_ATTESTED"}
        for row in focused["works"]:
            if row["cohort"] != key:
                continue
            index = row["index"]
            assert index in pending
            pair = (key, index, row["catalog"])
            assert pair not in observed_keys
            observed_keys.add(pair)
            validate(row, index)
            observations[index].append(dict(row, runId=46137565150))
        assert {i for c, i, _ in observed_keys if c == key} == pending
        entries = []
        for index, obs in sorted(observations.items()):
            for row in obs:
                validate(row, index)
            accepted = [r for r in obs if r["status"] == "CATALOG_IDENTITY_ATTESTED"]
            assert len(accepted) <= 1, "Cross-catalog edition equivalence not established"
            entries.append(dict(index=index, candidate=accepted[0] if accepted else None, observations=obs))
        data[key] = entries
    assert len(observed_keys) == 32
    by_identity = {}
    for key, entries in data.items():
        assert {r["index"] for r in entries if r["candidate"] is None} == EXPECTED[key]
        for entry in entries:
            candidate = entry["candidate"]
            if candidate is not None:
                validate(candidate, entry["index"])
                assert candidate in entry["observations"]
                by_identity.setdefault(candidate["identityDigest"], []).append([key, entry["index"]])
    duplicates = [items for items in by_identity.values() if len(items) > 1]
    eligible = sum(len(v) == 1 for v in by_identity.values())
    summary = dict(schemaVersion=1, scope="FV30_ABC_HISTORICAL_CANDIDATES_ONLY",
                   cohorts=data, uniqueCandidates=eligible, duplicateGroups=duplicates,
                   pending={key: sorted(indices) for key, indices in EXPECTED.items()},
                   independentEditionOracle="PENDING", reading="NOT_RUN")
    (ROOT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"FV30_ALL_HISTORY|uniqueCandidates={eligible}/90|duplicateGroups={len(duplicates)}|oracle=PENDING|reading=NOT_RUN")
    return not duplicates and eligible == 79


if __name__ == "__main__":
    raise SystemExit(0 if consolidate() else 1)
