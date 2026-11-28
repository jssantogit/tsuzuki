#!/usr/bin/env python3
"""Combine immutable FV30 catalog observations without promoting an editorial edition."""
import json
import re
from pathlib import Path

from run_mangaall_fv30_first_pass import locked

BASELINE = 46107669239
FOCUSED = 46132114609
PENDING = {2, 4, 14, 22, 27, 28, 29, 30}
SCOPES = ("shikimori", "mangaupdates")
HASH = re.compile(r"[0-9a-f]{64}")


def verify_observation(row, index, catalog):
    assert row["index"] == index and row["catalog"] == catalog
    status = row["status"]
    assert isinstance(status, str) and status != "NOT_OBSERVED"
    if status == "CATALOG_IDENTITY_ATTESTED":
        assert HASH.fullmatch(row["identityDigest"])
        assert row["creatorDigests"] and all(HASH.fullmatch(x) for x in row["creatorDigests"])
    else:
        assert row["identityDigest"] is None and not row["creatorDigests"]


def consolidate(rows, digest, initial, focused):
    observations = {}
    for shard, doc in enumerate(initial):
        assert doc["schemaVersion"] == 1 and doc["corpusSha256"] == digest
        assert doc["scope"] == "FV30_CATALOG_FIRST_PASS_ONLY" and doc["shard"] == shard
        assert doc["processOk"] is True and doc["invalidAttestations"] == 0
        works = rows[shard * 10:(shard + 1) * 10]
        assert len(doc["works"]) == len(works)
        for work, entry in zip(works, doc["works"]):
            index = work["index"]
            verify_observation(entry, index, work["catalog"])
            observations[index] = [dict(entry, runId=BASELINE)]
    assert set(observations) == set(range(1, 31))
    baseline_resolved = {i for i, entries in observations.items()
                         if entries[0]["status"] == "CATALOG_IDENTITY_ATTESTED"}
    assert set(observations) - baseline_resolved == PENDING
    assert focused["scope"] == "FV30_FOCUSED_CATALOG_DIAGNOSTIC"
    assert focused["corpusSha256"] == digest and focused["complete"] is True
    assert focused["expected"] == focused["observed"] == 16
    expected = {(i, c) for i in PENDING for c in SCOPES}
    seen = set()
    for row in focused["works"]:
        key = (row["index"], row["catalog"])
        assert key in expected and key not in seen
        verify_observation(row, *key)
        observations[key[0]].append(dict(row, runId=FOCUSED))
        seen.add(key)
    assert seen == expected
    candidates = {}
    conflicts = {}
    for index, entries in sorted(observations.items()):
        attested = [e for e in entries if e["status"] == "CATALOG_IDENTITY_ATTESTED"]
        if len(attested) == 1:
            candidates[index] = attested[0]
        elif len(attested) > 1:
            # Distinct owners cannot establish edition equivalence by title alone.
            conflicts[index] = attested
    by_digest = {}
    for index, row in candidates.items():
        by_digest.setdefault(row["identityDigest"], []).append(index)
    duplicate_groups = [indices for indices in by_digest.values() if len(indices) > 1]
    duplicate_indices = {i for group in duplicate_groups for i in group}
    eligible = {i: row for i, row in candidates.items() if i not in duplicate_indices}
    return dict(schemaVersion=1, scope="FV30_ACCUMULATED_CATALOG_CANDIDATES_ONLY",
                corpusSha256=digest, baselineRun=BASELINE, focusedRun=FOCUSED,
                baselineAttested=len(baseline_resolved), uniqueCandidates=len(eligible),
                pending=sorted(set(observations)-set(eligible)), duplicateGroups=duplicate_groups,
                conflicts=conflicts, independentEditionOracle="PENDING", reading="NOT_RUN",
                works=[dict(index=i, candidate=eligible.get(i), observations=entries)
                       for i, entries in sorted(observations.items())])


def main():
    rows, digest = locked()
    root = Path("fv30-historical-artifacts")
    initial = [json.loads((root / f"initial-{s}" / "verdict.json").read_text()) for s in range(3)]
    focused = json.loads((root / "focused" / "summary.json").read_text())
    summary = consolidate(rows, digest, initial, focused)
    (root / "candidate-receipts.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"FV30_HISTORY|baseline={summary['baselineAttested']}/30|uniqueCandidates={summary['uniqueCandidates']}/30"
          f"|pending={','.join(map(str, summary['pending']))}|oracle=PENDING|reading=NOT_RUN")
    # This gate certifies historical artifact integrity, not 30/30 readiness.
    return True


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
