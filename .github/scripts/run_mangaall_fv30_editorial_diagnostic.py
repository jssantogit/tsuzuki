#!/usr/bin/env python3
"""Context-assisted diagnostic. Never mutates original titles or first-pass receipts."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_fv30_first_pass import locked as lock_a
from run_mangaall_fv30_parallel import locked as lock_bc
from run_mangaall_fv30_focused import DIAGNOSTIC
from run_mangaall_frozen30_independent_catalog import APP, RUNNER, TEST, adb, case_id, classify
from mangaall_research_cohort import creator_key

PLAN = Path(".github/mangaall-fv30-editorial-diagnostic-plan.json")
PLAN_BLOB = "5c6800f2a5f6a8c8816994dc9e8578bb3d3488ba"
SCOPE = "FV30_EDITORIAL_CONTEXT_DIAGNOSTIC_NOT_FIRST_PASS"
OUT = Path("fv30-editorial-results")
PENDING = {"fv30a": {2, 14, 22, 29}, "fv30b": {4, 12, 13, 16, 21, 30}, "fv30c": {10}}
JOBS = {"a-shiki": ("fv30a", "shikimori"), "b-shiki": ("fv30b", "shikimori"),
        "c-shiki": ("fv30c", "shikimori"), "a-mu-manhua": ("fv30a", "mangaupdates"),
        "b-mu-manhua": ("fv30b", "mangaupdates")}


def load():
    raw = PLAN.read_bytes()
    assert hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == PLAN_BLOB
    doc = json.loads(raw)
    assert doc["schemaVersion"] == 1 and doc["scope"] == SCOPE
    locked = dict(fv30a=lock_a(), **lock_bc())
    rows = {}
    for case in doc["cases"]:
        key, i = case["cohort"], case["index"]
        assert i in PENDING[key] and (key, i) not in rows
        original = locked[key][0][i-1]
        assert 0 < len(case["query"]) <= 256
        assert len(case["creators"]) <= 8 and all(0 < len(x) <= 128 for x in case["creators"])
        assert case["minimumVolumes"] in (None, 2)
        assert case["evidence"] and all(url.startswith("https://") for url in case["evidence"])
        rows[key, i] = dict(case, format=original["format"], originalTitle=original["title"])
    assert set(rows) == {(c, i) for c, indices in PENDING.items() for i in indices}
    return rows, hashlib.sha256(raw).hexdigest()


def selected(rows, job):
    key, catalog = JOBS[job]
    return [dict(row, catalog=catalog) for (c, _), row in sorted(rows.items())
            if c == key and (catalog != "mangaupdates" or row["format"] == "manhua")]


def encoded(value):
    return base64.b64encode(json.dumps(value, ensure_ascii=False).encode()).decode()


def parse(raw, group):
    by_query = {r["query"]: r for r in group}
    seen, invalid = {}, 0
    for match in re.finditer(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)", raw):
        try:
            record = json.loads(base64.b64decode(match.group(1), validate=True))
            row = by_query[record["query"]]
            index = row["index"]
            assert index not in seen and record["caseId"] == case_id(row["query"])
            assert record["creatorConstraintUsed"] is bool(row["creators"])
            assert record["editionExtentUsed"] is (row["minimumVolumes"] is not None)
            assert record["retrievalHintUsed"] is False and record["creatorAliasesUsed"] is False
            status, identity = classify(record, row["catalog"], row["format"].upper())
            assert status != "INVALID_ATTESTATION"
            credits = []
            if status == "CATALOG_IDENTITY_ATTESTED":
                actual = {creator_key(n) for n in record["authors"] + record["artists"]}
                assert all(creator_key(n) in actual for n in row["creators"])
                credits = sorted(hashlib.sha256(" ".join(n).encode()).hexdigest() for n in actual)
            seen[index] = dict(index=index, cohort=row["cohort"], catalog=row["catalog"],
                               status=status, identityDigest=identity, creatorDigests=credits,
                               alternativeQueryUsed=row["query"] != row["originalTitle"],
                               creatorConstraintUsed=bool(row["creators"]),
                               editionExtentUsed=row["minimumVolumes"] is not None)
        except (AssertionError, KeyError, ValueError, TypeError, AttributeError):
            invalid += 1
    return seen, invalid


def run(rows, digest, job):
    group = selected(rows, job)
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    assert len(target) == len(tests) == 1
    assert adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"
    seen, invalid, process_ok, raw = {}, 0, False, ""
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        payload = base64.b64encode("\n".join(r["format"] + "::" + r["query"] for r in group).encode()).decode()
        credits = {r["query"]: r["creators"] for r in group if r["creators"]}
        extent = {r["query"]: r["minimumVolumes"] for r in group if r["minimumVolumes"] is not None}
        result = adb("shell", "am", "instrument", "-w", "-r",
                     "-e", "mangaAllLiveCatalogOptIn", "true",
                     "-e", "mangaAllLiveCatalogSource", group[0]["catalog"],
                     "-e", "worksBase64", payload, "-e", "shardIndex", "0", "-e", "shardCount", "1",
                     "-e", "catalogRequiredCreditsBase64", encoded(credits),
                     "-e", "catalogMinimumVolumesBase64", encoded(extent),
                     "-e", "class", TEST, RUNNER, timeout=950, check=False)
        raw = result.stdout
        process_ok = result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
        seen, invalid = parse(raw, group)
    except subprocess.SubprocessError:
        process_ok = False
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)
    complete = process_ok and invalid == 0 and len(seen) == len(group)
    folder = OUT / job
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "verdict.json").write_text(json.dumps(dict(schemaVersion=1, scope=SCOPE, planSha256=digest,
        job=job, complete=complete, works=list(seen.values())), indent=2) + "\n")
    diagnostics = []
    for match in DIAGNOSTIC.finditer(raw):
        local = int(match.group(1))
        if 0 <= local < len(group) and len(diagnostics) < 256:
            line = match.group(0).replace(f"index={local}|", f"index={group[local]['index']}|", 1)
            diagnostics.append(line)
            print(line)
    (folder / "pagination.txt").write_text("\n".join(diagnostics) + "\n")
    for r in seen.values():
        print(f"FV30_EDITORIAL|cohort={r['cohort']}|index={r['index']}|catalog={r['catalog']}|outcome={r['status']}|alternate={r['alternativeQueryUsed']}")
    return complete


def aggregate(rows, digest):
    valid, result = True, []
    for job in JOBS:
        p = OUT / job / "verdict.json"
        if not p.is_file():
            valid = False
            continue
        doc = json.loads(p.read_text())
        group = selected(rows, job)
        keys = {(r["cohort"], r["index"], r["catalog"]) for r in group}
        works = doc.get("works", [])
        valid &= (doc.get("schemaVersion") == 1 and doc.get("scope") == SCOPE
                  and doc.get("planSha256") == digest and doc.get("job") == job and doc.get("complete") is True
                  and len(works) == len(keys) and {(r["cohort"], r["index"], r["catalog"]) for r in works} == keys)
        result.extend(works)
    candidates = {(r["cohort"], r["index"]) for r in result if r["status"] == "CATALOG_IDENTITY_ATTESTED"}
    expected = sum(len(selected(rows, job)) for job in JOBS)
    valid &= len(result) == expected
    OUT.mkdir(exist_ok=True)
    (OUT / "summary.json").write_text(json.dumps(dict(schemaVersion=1, scope=SCOPE, planSha256=digest,
        complete=valid, observations=len(result), expected=expected, assistedCandidateWorks=len(candidates),
        unassistedFirstPass="UNCHANGED", reading="NOT_RUN", works=result), indent=2) + "\n")
    print(f"FV30_EDITORIAL|complete={valid}|observations={len(result)}/{expected}|assistedCandidates={len(candidates)}/11|firstPass=UNCHANGED")
    return valid


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--job", choices=tuple(JOBS))
    mode.add_argument("--aggregate", action="store_true")
    args = parser.parse_args()
    rows, digest = load()
    if args.check:
        assert sum(len(selected(rows, job)) for job in JOBS) == 13
        assert all(len({r["query"] for r in selected(rows, job)}) == len(selected(rows, job)) for job in JOBS)
        print("FV30_EDITORIAL|preflight=PASS|pending=11|observations=13|firstPass=UNCHANGED")
        return True
    return aggregate(rows, digest) if args.aggregate else run(rows, digest, args.job)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
