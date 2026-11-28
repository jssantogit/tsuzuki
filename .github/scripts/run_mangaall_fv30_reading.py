#!/usr/bin/env python3
"""Nonshipping mixed-corpus Android canonical collector. Never invoked by offline CI."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from mangaall_fv30_reading_profile_plan import slices, verified_plan
from run_mangaall_fv30_first_pass import locked
from run_mangaall_frozen30_canonical_persistence import (
    APP, RUNNER, TEST, adb, parse_phase, parse_reader, collect_diagnostics,
    collect_ranges, collect_lookup_failures, collect_reader_failures,
    collect_catalog_search_failures, collect_evidence_ranges, collect_evidence_shapes,
    collect_acquisition, collect_option_rejections, source_diagnostics,
)

ASSETS = Path("app/src/androidTest/assets")
OUT = Path("fv30-reading-results")
SCOPE = "FV30_CANONICAL_SQLITE_RESTART_FIRST_PAGE"


def read_plan():
    rows, digest = locked()
    lock_bytes = Path(".github/mangaall-fv30-cohort-lock.json").read_bytes()
    assert (ASSETS / "mangaall-fv30-cohort-lock.json").read_bytes() == lock_bytes
    plan_raw = (ASSETS / "mangaall-fv30-verified-catalog-plan.json").read_bytes()
    receipts_raw = (ASSETS / "attested-identities.json").read_bytes()
    owners = verified_plan(rows, digest, plan_raw, receipts_raw)
    return rows, digest, hashlib.sha256(plan_raw).hexdigest(), owners


def invoke(shard, lang, phase, digest, plan_digest, owners, indices):
    args = ["shell", "am", "instrument", "-w", "-r",
            "-e", "mangaAllCanonicalOptIn", "I_ACCEPT_FROZEN30_CANONICAL_RESEARCH",
            "-e", "mangaAllCanonicalPhase", phase,
            "-e", "mangaAllCanonicalCatalog", "explicit_plan",
            "-e", "mangaAllCanonicalCohort", "fv30",
            "-e", "mangaAllCanonicalOriginalLanguage", lang,
            "-e", "mangaAllCanonicalCorpusSha256", digest,
            "-e", "mangaAllCanonicalPlanSha256", plan_digest,
            "-e", "mangaAllFrozen30Shard", str(shard),
            "-e", "mangaAllCanonicalReaderProbe", "1" if phase == "RESTORE" else "0",
            "-e", "class", TEST, RUNNER]
    try:
        result = adb(*args, check=False, timeout=1850)
        raw = result.stdout
        ok = result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
    except subprocess.SubprocessError:
        raw, ok = "", False
    return parse_evidence(raw, shard, phase, ok, owners, indices)


def parse_evidence(raw, shard, phase, ok, owners, indices):
    parsed = parse_phase(raw, shard, phase, ok, "explicit_plan", owners, indices)
    if phase == "COLLECT":
        parsed["diagnostics"] = collect_diagnostics(raw, shard, indices)
        parsed["ranges"] = collect_ranges(raw, shard, indices)
        parsed["lookupFailures"] = collect_lookup_failures(raw, shard, indices)
        parsed["chapterFailures"] = collect_lookup_failures(raw, shard, indices, chapter=True)
        parsed["catalogSearchFailures"] = collect_catalog_search_failures(raw, shard, indices)
        parsed["evidenceRanges"] = collect_evidence_ranges(raw, shard, indices)
        parsed["evidenceShapes"] = collect_evidence_shapes(raw, shard, indices)
        parsed["acquisition"] = collect_acquisition(raw, shard, indices)
        parsed["optionRejections"] = collect_option_rejections(raw, shard, indices)
    else:
        parsed["reader"] = parse_reader(raw, shard, parsed, ok, "explicit_plan", owners, indices)
        parsed["readerFailures"] = collect_reader_failures(raw, shard, parsed["reader"], indices)
    return parsed


def report(shard, lang, digest, plan_digest, collect, restore, stopped):
    restart = stopped and collect["pid"] is not None and restore["pid"] is not None and collect["pid"] != restore["pid"]
    passed = restart and collect["complete"] and restore["complete"] and restore["reader"]["complete"]
    return dict(schemaVersion=1, scope=SCOPE, corpusSha256=digest, planSha256=plan_digest,
                shard=shard, originalLanguage=lang, restartObserved=restart,
                outcome="PASS" if passed else "RED",
                collect={k: v for k, v in collect.items() if k != "pid"},
                restore={k: v for k, v in restore.items() if k != "pid"},
                readerProbe="RESTORED_FIRST_PAGE", inventory="NOT_PROVEN", readerUI="NOT_TESTED", signed="NO")


def run(rows, digest, plan_digest, owners, shard, lang):
    indices = frozenset(slices(rows)[shard, lang])
    assert (ASSETS / f"mangaall-fv30-{lang}-unsigned-research.zip").is_file()
    assert adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"
    targets = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
               if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    assert len(targets) == len(tests) == 1
    folder = OUT / f"shard-{shard}-{lang}"
    folder.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", str(targets[0]))
        adb("install", "-r", str(tests[0]))
        assert "Success" in adb("shell", "pm", "clear", APP).stdout
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        assert adb("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() == "0"
        collect = invoke(shard, lang, "COLLECT", digest, plan_digest, owners, indices)
        adb("shell", "am", "force-stop", APP)
        stopped = not adb("shell", "pidof", APP, check=False).stdout.strip()
        restore = invoke(shard, lang, "RESTORE", digest, plan_digest, owners, indices)
        result = report(shard, lang, digest, plan_digest, collect, restore, stopped)
        (folder / "verdict.json").write_text(json.dumps(result, indent=2) + "\n")
        for stage in (collect, restore, restore["reader"]):
            for row in stage["works"]:
                print(f"FV30_READING|index={row['index']}|language={lang}|outcome={row['status']}")
        for row in source_diagnostics(collect):
            print("FV30_SOURCE|" + "|".join(f"{k}={v}" for k, v in row.items()))
        print(f"FV30_READING|shard={shard}|language={lang}|outcome={result['outcome']}|restart={result['restartObserved']}")
        return result["outcome"] == "PASS"
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)


def verify_slice(doc, shard, lang, digest, plan_digest, owners, indices):
    if not (doc.get("schemaVersion") == 1 and doc.get("scope") == SCOPE
            and doc.get("corpusSha256") == digest and doc.get("planSha256") == plan_digest
            and doc.get("shard") == shard and doc.get("originalLanguage") == lang
            and doc.get("restartObserved") is True and doc.get("outcome") == "PASS"
            and doc.get("readerProbe") == "RESTORED_FIRST_PAGE"):
        return False
    for stage, status in ((doc.get("collect", {}), "COLLECTED"),
                          (doc.get("restore", {}), "RESTORED"),
                          (doc.get("restore", {}).get("reader", {}), "FIRST_PAGE_DECODED")):
        rows = stage.get("works", [])
        if not (stage.get("complete") is True and stage.get("processOk") is True and stage.get("invalid") == 0
                and [r.get("index") for r in rows] == sorted(indices)
                and all(r.get("status") == status and r.get("catalog") == owners[r["index"]] for r in rows)):
            return False
    return True


def aggregate(rows, digest, plan_digest, owners):
    passed = set()
    for (shard, lang), indices in slices(rows).items():
        path = OUT / f"shard-{shard}-{lang}" / "verdict.json"
        if path.is_file() and verify_slice(json.loads(path.read_text()), shard, lang, digest, plan_digest, owners, indices):
            passed.update(indices)
    ok = passed == set(range(1, 31))
    print(f"FV30_READING|outcome={'PASS' if ok else 'RED'}|verified={len(passed)}/30|inventory=NOT_PROVEN|ui=NOT_TESTED")
    return ok


def selftest():
    rows, digest = locked()
    owners = {r["index"]: r["catalog"] for r in rows}
    indices = frozenset((4,))
    owner = owners[4]
    def logs(phase, outcome, pid):
        return (f"MANGAALL_CANONICAL_PROCESS|phase={phase}|shard=0|pid={pid}\n"
                f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase}|index=4|outcome={outcome}|catalog={owner}\n")
    c = parse_evidence(logs("COLLECT", "COLLECTED", 1), 0, "COLLECT", True, owners, indices)
    r = parse_evidence(logs("RESTORE", "RESTORED", 2) +
        f"MANGAALL_CANONICAL_READER|index=4|outcome=FIRST_PAGE_DECODED|catalog={owner}|ui=NOT_TESTED\n",
        0, "RESTORE", True, owners, indices)
    assert c["complete"] and r["complete"] and r["reader"]["complete"]
    good = report(0, "ko", digest, "a"*64, c, r, True)
    assert verify_slice(good, 0, "ko", digest, "a"*64, owners, indices)
    assert not verify_slice(good, 0, "ja", digest, "a"*64, owners, indices)
    assert not verify_slice(good, 0, "ko", digest, "a"*64, owners, {1, 4})
    assert not verify_slice(report(0, "ko", digest, "a"*64, c, r, False), 0, "ko", digest, "a"*64, owners, indices)
    assert not parse_evidence(logs("COLLECT", "COLLECTED", 1).replace("index=4", "index=1"),
                              0, "COLLECT", True, owners, indices)["complete"]
    print("FV30_READING|receiptParser=PASS|live=NOT_RUN")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--selftest", action="store_true")
    p.add_argument("--aggregate", action="store_true")
    p.add_argument("--shard", type=int, choices=range(3))
    p.add_argument("--profile", choices=("ja", "ko", "zh"))
    args = p.parse_args()
    if args.selftest:
        selftest()
        return True
    if args.aggregate == (args.shard is not None and args.profile is not None):
        p.error("Select aggregate or one explicit shard and profile")
    rows, digest, plan_digest, owners = read_plan()
    if args.aggregate:
        return aggregate(rows, digest, plan_digest, owners)
    return run(rows, digest, plan_digest, owners, args.shard, args.profile)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
