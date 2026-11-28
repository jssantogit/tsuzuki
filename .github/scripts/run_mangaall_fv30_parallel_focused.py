#!/usr/bin/env python3
"""Focused follow-up of the immutable B/C first pass, using its exact APK."""
import argparse
import json
from pathlib import Path

import run_mangaall_fv30_focused as core
from run_mangaall_fv30_parallel import locked

JOBS = {
    "b-shiki-alternate": ("fv30b", "shikimori", (26, 27, 29, 30)),
    "b-mu-alternate": ("fv30b", "mangaupdates", (4, 12, 13, 15, 16, 21)),
    "b-mu-diagnose": ("fv30b", "mangaupdates", (26, 27, 29, 30)),
    "c-shiki-retry": ("fv30c", "shikimori", tuple(range(1, 11))),
    "c-shiki-alternate": ("fv30c", "shikimori", (25, 28, 29, 30)),
    "c-mu-diagnose": ("fv30c", "mangaupdates", (25, 28, 29, 30)),
}
OUT = Path("fv30-parallel-focused-results")
PRIOR = Path("fv30-parallel-prior")


def check():
    cohorts = locked()
    for key, (rows, digest) in cohorts.items():
        report = json.loads((PRIOR / key / "first-pass-summary.json").read_text())
        assert report["cohort"] == key and report["corpusSha256"] == digest
        assert report["functionalBaselineSha"] == "c17347072ff8d4445cdbc905173289c0cd0c760b"
        works = report["works"]
        assert [(r["index"], r["catalog"]) for r in works] == [(r["index"], r["catalog"]) for r in rows]
        remaining = {r["index"] for r in works if r["status"] != "CATALOG_IDENTITY_ATTESTED"}
        selected = {i for c, _, indices in JOBS.values() if c == key for i in indices}
        assert selected == remaining
        assert len(remaining) == (10 if key == "fv30b" else 14)
    return cohorts


def run(job):
    cohorts = check()
    key, catalog, indices = JOBS[job]
    rows, digest = cohorts[key]
    core.PENDING = indices
    core.OUT = OUT / job
    print(f"FV30_PARALLEL_FOCUSED|job={job}|cohort={key}|source={catalog}|expected={len(indices)}")
    return core.run(rows, digest, catalog)


def aggregate():
    cohorts = check()
    complete = True
    results = []
    for job, (key, catalog, indices) in JOBS.items():
        rows, digest = cohorts[key]
        path = OUT / job / catalog / "verdict.json"
        if not path.is_file():
            complete = False
            continue
        doc = json.loads(path.read_text())
        valid = (doc.get("schemaVersion") == 1 and doc.get("scope") == "FV30_FOCUSED_CATALOG_DIAGNOSTIC"
                 and doc.get("corpusSha256") == digest and doc.get("catalog") == catalog
                 and doc.get("complete") is True and doc.get("processOk") is True
                 and doc.get("invalidAttestations") == 0
                 and [(r["index"], r["catalog"]) for r in doc.get("works", [])]
                 == [(i, catalog) for i in indices])
        complete &= valid
        if valid:
            results.extend(dict(row, cohort=key, diagnosticJob=job) for row in doc["works"])
    resolved = {(r["cohort"], r["index"]) for r in results if r["status"] == "CATALOG_IDENTITY_ATTESTED"}
    OUT.mkdir(exist_ok=True)
    expected = sum(len(indices) for _, _, indices in JOBS.values())
    complete &= len(results) == expected
    (OUT / "summary.json").write_text(json.dumps(dict(
        schemaVersion=1, scope="FV30_BC_FOCUSED_DIAGNOSTIC_ONLY", sourceRun=46136665066,
        observations=len(results), expected=expected, complete=complete,
        candidateWorks=len(resolved), independentEditionOracle="PENDING", reading="NOT_RUN",
        works=results), indent=2) + "\n")
    print(f"FV30_PARALLEL_FOCUSED|complete={complete}|observations={len(results)}/{expected}|candidateWorks={len(resolved)}/24|reading=NOT_RUN")
    return complete


def main():
    p = argparse.ArgumentParser()
    m = p.add_mutually_exclusive_group(required=True)
    m.add_argument("--check", action="store_true")
    m.add_argument("--job", choices=tuple(JOBS))
    m.add_argument("--aggregate", action="store_true")
    a = p.parse_args()
    if a.check:
        check()
        print("FV30_PARALLEL_FOCUSED|preflight=PASS|pending=24|approvedReexecuted=0")
        return True
    return aggregate() if a.aggregate else run(a.job)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
