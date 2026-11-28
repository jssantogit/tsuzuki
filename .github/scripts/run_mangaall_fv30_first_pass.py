#!/usr/bin/env python3
"""FV30 first-pass Android catalog observation. Never grants reading readiness."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import APP, RUNNER, TEST, adb, case_id, classify
from mangaall_research_cohort import creator_key

LOCK = Path(".github/mangaall-fv30-cohort-lock.json")
LOCK_BLOB = "255cff94778da1fe47e57406ef4bd9df355ba85a"
OUT = Path("fv30-results")
SCOPES = ("shikimori", "mangaupdates")


def locked():
    raw = LOCK.read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    assert blob == LOCK_BLOB, "FV30 lock changed"
    doc = json.loads(raw)
    rows = doc["works"]
    assert [r["index"] for r in rows] == list(range(1, 31))
    assert len({r["title"] for r in rows}) == 30
    profiles = {"manga": ("ja", "shikimori"), "manhwa": ("ko", "mangaupdates"),
                "manhua": ("zh", "mangaupdates")}
    assert all(profiles[r["format"]] == (r["originalLanguage"], r["catalog"]) for r in rows)
    assert [sum(r["format"] == f for r in rows) for f in profiles] == [24, 4, 2]
    return rows, hashlib.sha256(raw).hexdigest()


def classify_exact(record, row):
    return classify(record, row["catalog"], row["format"].upper())


def parse(raw, group):
    by_title = {row["title"]: row for row in group}
    observed = {}
    invalid = 0
    for match in re.finditer(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)", raw):
        try:
            record = json.loads(base64.b64decode(match.group(1), validate=True))
            title = record.get("query")
            row = by_title.get(title)
            if (row is None or row["index"] in observed or record.get("caseId") != case_id(title)
                    or any(record.get(key) is not False for key in
                           ("retrievalHintUsed", "creatorConstraintUsed", "creatorAliasesUsed"))):
                invalid += 1
                continue
            status, identity = classify_exact(record, row)
            if status == "INVALID_ATTESTATION":
                invalid += 1
                continue
            creators = []
            if status == "CATALOG_IDENTITY_ATTESTED":
                creators = sorted({hashlib.sha256(" ".join(creator_key(name)).encode()).hexdigest()
                                   for name in record["authors"] + record["artists"]})
            observed[row["index"]] = dict(index=row["index"], catalog=row["catalog"],
                                           status=status, identityDigest=identity,
                                           creatorDigests=creators)
        except (ValueError, TypeError, AttributeError, KeyError):
            invalid += 1
    return observed, invalid


def run_shard(rows, digest, shard):
    selected = rows[shard * 10:(shard + 1) * 10]
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    assert len(target) == len(tests) == 1
    assert adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"
    observed, invalid, process_ok = {}, 0, True
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        for catalog in SCOPES:
            group = [r for r in selected if r["catalog"] == catalog]
            if not group:
                continue
            payload = base64.b64encode("\n".join(r["format"] + "::" + r["title"]
                                                   for r in group).encode()).decode()
            try:
                result = adb("shell", "am", "instrument", "-w", "-r",
                             "-e", "mangaAllLiveCatalogOptIn", "true",
                             "-e", "mangaAllLiveCatalogSource", catalog,
                             "-e", "worksBase64", payload, "-e", "shardIndex", "0",
                             "-e", "shardCount", "1", "-e", "class", TEST, RUNNER,
                             timeout=950, check=False)
                raw = result.stdout
                process_ok &= result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
                parsed, bad = parse(raw, group)
                observed.update(parsed)
                invalid += bad
            except subprocess.SubprocessError:
                process_ok = False
            finally:
                adb("shell", "am", "force-stop", APP, check=False)
    finally:
        adb("shell", "pm", "clear", APP, check=False)
    reports = [observed.get(r["index"], dict(index=r["index"], catalog=r["catalog"],
               status="NOT_OBSERVED", identityDigest=None, creatorDigests=[])) for r in selected]
    complete = process_ok and invalid == 0 and len(observed) == len(selected)
    accepted = [r for r in reports if r["status"] == "CATALOG_IDENTITY_ATTESTED"]
    ok = complete and len(accepted) == len({r["identityDigest"] for r in accepted}) == len(selected)
    folder = OUT / f"shard-{shard}"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "verdict.json").write_text(json.dumps(dict(schemaVersion=1, corpusSha256=digest,
        shard=shard, scope="FV30_CATALOG_FIRST_PASS_ONLY", processOk=process_ok,
        invalidAttestations=invalid, outcome="PASS" if ok else "RED", works=reports), indent=2) + "\n")
    for r in reports:
        print(f"FV30|index={r['index']}|catalog={r['catalog']}|outcome={r['status']}")
    return ok


def aggregate(rows, digest):
    actual, valid = [], True
    for shard in range(3):
        p = OUT / f"shard-{shard}" / "verdict.json"
        if not p.is_file():
            valid = False
            continue
        doc = json.loads(p.read_text())
        found = doc.get("works", [])
        valid &= (doc.get("schemaVersion") == 1 and doc.get("corpusSha256") == digest
                  and doc.get("scope") == "FV30_CATALOG_FIRST_PASS_ONLY"
                  and doc.get("shard") == shard and doc.get("outcome") == "PASS"
                  and doc.get("processOk") is True and doc.get("invalidAttestations") == 0
                  and [(r.get("index"), r.get("catalog")) for r in found] ==
                      [(r["index"], r["catalog"]) for r in rows[shard * 10:(shard + 1) * 10]])
        actual.extend(found)
    accepted = [r for r in actual if r.get("status") == "CATALOG_IDENTITY_ATTESTED"
                and re.fullmatch(r"[0-9a-f]{64}", r.get("identityDigest") or "")]
    unique = len({r["identityDigest"] for r in accepted})
    ok = valid and len(actual) == len(accepted) == unique == 30
    OUT.mkdir(exist_ok=True)
    (OUT / "first-pass-summary.json").write_text(json.dumps(dict(corpusSha256=digest,
        scope="FV30_CATALOG_FIRST_PASS_ONLY", outcome="PASS" if ok else "RED",
        attested=len(accepted), unique=unique, expected=30,
        independentEditionOracle="PENDING", reading="NOT_RUN", works=actual), indent=2) + "\n")
    print(f"FV30|outcome={'PASS' if ok else 'RED'}|catalog={len(accepted)}/30|unique={unique}|reading=NOT_RUN")
    return ok


def selftest(rows):
    row = rows[28]
    record = dict(schemaVersion=1, mode="DIRECT_LIVE_ANDROID_CATALOG_ONLY",
                  catalogSource=row["catalog"], canonical="NOT_EXECUTED_HOST",
                  status="RESOLVED", provider=row["catalog"], providerId="42",
                  externalIds={}, catalogsQueried=1, catalogsFailed=0,
                  format="MANHUA", creatorsAttested=True, authors=["Fixture"], artists=[],
                  query=row["title"], caseId=case_id(row["title"]), retrievalHintUsed=False,
                  creatorConstraintUsed=False, creatorAliasesUsed=False)
    assert classify_exact(record, row)[0] == "CATALOG_IDENTITY_ATTESTED"
    assert classify_exact(dict(record, format="MANGA"), row)[0] == "FORMAT_UNVERIFIED"
    assert classify_exact(dict(record, provider="shikimori"), row)[0] == "OWNER_PROVENANCE_UNVERIFIED"
    def line(r):
        return "MANGAALL_CATALOG_ATTESTATION|" + base64.b64encode(json.dumps(r).encode()).decode()
    assert parse(line(record), [row])[1] == 0
    assert parse(line(record) + "\n" + line(record), [row])[1] == 1
    assert parse(line(dict(record, creatorConstraintUsed=True)), [row])[1] == 1


def main():
    p = argparse.ArgumentParser()
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-lock", action="store_true")
    mode.add_argument("--shard", type=int, choices=range(3))
    mode.add_argument("--aggregate", action="store_true")
    args = p.parse_args()
    rows, digest = locked()
    if args.check_lock:
        selftest(rows)
        print("FV30|preflight=PASS|works=30|hints=NONE|scope=CATALOG_FIRST_PASS_ONLY")
        return True
    return aggregate(rows, digest) if args.aggregate else run_shard(rows, digest, args.shard)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
