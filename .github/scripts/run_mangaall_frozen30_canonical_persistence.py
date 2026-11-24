#!/usr/bin/env python3
"""Nonshipping Android collector/SQLite restart gate; sanitized evidence only."""
import argparse
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import DIGEST, locked_works
from run_mangaall_frozen30_catalog_plan import plan as load_plan

APP = "app.tsuzuki.dev"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = ("eu.kanade.tachiyomi.data.tsuzuki.instrumentation."
        "MangaAllFrozen30CanonicalPersistenceInstrumentedTest#frozen30CanonicalPersistence")
OUT = Path(".github/results/mangaall-frozen30-canonical-persistence")
STATUS = {
    "DIRTY_DATABASE", "CATALOG_AMBIGUOUS", "CATALOG_NOT_FOUND",
    "CATALOG_UNAVAILABLE", "CATALOG_INCOMPLETE", "CATALOG_UNVERIFIED",
    "CATALOG_FORMAT_UNSUPPORTED", "CATALOG_PROVENANCE_MISSING",
    "CATALOG_FORMAT_OR_CREATOR_UNVERIFIED", "DUPLICATE_CATALOG_OWNER",
    "REFRESH_FAILED", "CANONICAL_CHAPTER_ONE_MISSING_OR_AMBIGUOUS",
    "READER_OPTION_MISSING_OR_AMBIGUOUS", "SOURCE_ASSOCIATION_MISSING",
    "SOURCE_AUTHOR_OR_EDITION_UNVERIFIED", "SOURCE_SELECTION_FAILED",
    "SQLITE_INVARIANT_FAILED", "COLLECTED", "NO_COLLECTED_RECEIPT",
    "SQLITE_RESTORE_MISMATCH", "RESTORED", "UNCLASSIFIED_FAILURE",
}


def adb(*args, check=True, timeout=120):
    return subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=check)


def parse_phase(log, shard, phase, process_ok, catalog="mangaupdates", owners=None):
    indices = set(range(shard * 10 + 1, shard * 10 + 11))
    rows = {}
    invalid = 0
    for match in re.finditer(
        r"MANGAALL_CANONICAL_PERSISTENCE\|phase=(COLLECT|RESTORE)\|index=(\d+)\|outcome=([A-Z_0-9]+)\|catalog=(mangaupdates|shikimori)",
        log,
    ):
        observed_phase, index, status, observed_owner = match.groups()
        index = int(index)
        if (observed_phase != phase or index not in indices
                or index in rows or status not in STATUS
                or observed_owner != (owners.get(index) if owners is not None else catalog)
                or (phase == "COLLECT" and status == "RESTORED")
                or (phase == "RESTORE" and status == "COLLECTED")):
            invalid += 1
            continue
        rows[index] = status
    pids = re.findall(r"MANGAALL_CANONICAL_PROCESS\|phase=(COLLECT|RESTORE)\|shard=([012])\|pid=(\d+)", log)
    pid = None
    if len(pids) == 1 and pids[0][:2] == (phase, str(shard)) and int(pids[0][2]) > 0:
        pid = int(pids[0][2])
    else:
        invalid += 1
    expected = "COLLECTED" if phase == "COLLECT" else "RESTORED"
    complete = (process_ok and invalid == 0 and set(rows) == indices
                and all(status == expected for status in rows.values()))
    return {"phase": phase, "processOk": process_ok, "invalid": invalid, "pid": pid,
            "complete": complete,
            "works": [{"index": i, "catalog": owners.get(i) if owners is not None else catalog,
                       "status": rows.get(i, "NOT_OBSERVED")} for i in sorted(indices)]}


def scope(catalog):
    if catalog!="explicit_plan":
        return None, None
    rows, digest=load_plan()
    return {r["index"]:r["catalog"] for r in rows}, digest


def invoke(shard, phase, catalog):
    owners, plan_digest=scope(catalog)
    args=["shell", "am", "instrument", "-w", "-r",
          "-e", "mangaAllCanonicalOptIn", "I_ACCEPT_FROZEN30_CANONICAL_RESEARCH",
          "-e", "mangaAllCanonicalPhase", phase,
          "-e", "mangaAllCanonicalCatalog", catalog,
          "-e", "mangaAllFrozen30Shard", str(shard)]
    if plan_digest:
        args.extend(["-e","mangaAllCanonicalPlanSha256",plan_digest])
    args.extend(["-e","class",TEST,RUNNER])
    try:
        result=adb(*args,check=False,timeout=1850)
        ok=(result.returncode==0 and "INSTRUMENTATION_CODE: -1" in result.stdout
            and "OK (1 test)" in result.stdout)
        return parse_phase(result.stdout,shard,phase,ok,catalog,owners)
    except subprocess.SubprocessError:
        return parse_phase("",shard,phase,False,catalog,owners)


def evaluate(shard, collect, restore, force_stopped, catalog="mangaupdates"):
    restart = (force_stopped and collect["pid"] is not None and restore["pid"] is not None
               and collect["pid"] != restore["pid"])
    return {
        "schemaVersion": 1, "corpusSha256": DIGEST, "shard": shard,
        "catalogSource": catalog, "planSha256": scope(catalog)[1],
        "scope": "CANONICAL_COLLECTOR_SQLITE_SELECTED_OPTION_RESTART",
        "restartObserved": restart,
        "outcome": "PASS" if restart and collect["complete"] and restore["complete"] else "RED",
        "collect": {k: v for k, v in collect.items() if k != "pid"},
        "restore": {k: v for k, v in restore.items() if k != "pid"},
        "signed": "NO", "readerUI": "NOT_TESTED", "editorialEditionEquivalence": "NOT_PROVEN",
    }


def run(shard, catalog):
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("EMULATOR_REQUIRED")
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(target) != 1 or len(tests) != 1:
        raise SystemExit("APK_INPUT_COUNT")
    output = OUT / f"shard-{shard}"
    output.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        if "Success" not in adb("shell", "pm", "clear", APP).stdout:
            raise SystemExit("DISPOSABLE_DATABASE_CLEAR_FAILED")
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        if adb("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() != "0":
            raise SystemExit("NETWORK_UNAVAILABLE")
        collect = invoke(shard, "COLLECT", catalog)
        adb("shell", "am", "force-stop", APP)
        force_stopped = not adb("shell", "pidof", APP, check=False).stdout.strip()
        # Do not clear application data between the two instrumentation runs.
        restore = invoke(shard, "RESTORE", catalog)
        report = evaluate(shard, collect, restore, force_stopped, catalog)
        (output / "verdict.json").write_text(json.dumps(report, indent=2) + "\n")
        for phase in ("collect", "restore"):
            for row in report[phase]["works"]:
                print(f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase.upper()}|index={row['index']}|outcome={row['status']}")
        print(f"MANGAALL_CANONICAL_PERSISTENCE|shard={shard}|outcome={report['outcome']}|restart={report['restartObserved']}")
        return report["outcome"] == "PASS"
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)


def aggregate(catalog):
    owners, plan_digest=scope(catalog)
    complete = True
    for shard in range(3):
        path = OUT / f"shard-{shard}" / "verdict.json"
        if not path.is_file():
            complete = False
            continue
        report = json.loads(path.read_text())
        expected = list(range(shard * 10 + 1, shard * 10 + 11))
        if (report.get("schemaVersion") != 1 or report.get("corpusSha256") != DIGEST
                or report.get("catalogSource") != catalog or report.get("planSha256") != plan_digest
                or report.get("shard") != shard or report.get("outcome") != "PASS"
                or report.get("restartObserved") is not True
                or report.get("scope") != "CANONICAL_COLLECTOR_SQLITE_SELECTED_OPTION_RESTART"):
            complete = False
        for phase, success in (("collect", "COLLECTED"), ("restore", "RESTORED")):
            data = report.get(phase, {})
            rows = data.get("works", [])
            if (data.get("complete") is not True or data.get("processOk") is not True
                    or data.get("invalid") != 0 or [r.get("index") for r in rows] != expected
                    or any(r.get("status") != success or r.get("catalog") != (owners.get(r["index"]) if owners is not None else catalog) for r in rows)):
                complete = False
    print(f"MANGAALL_CANONICAL_PERSISTENCE|outcome={'PASS' if complete else 'RED'}"
          "|expected=30|readerUI=NOT_TESTED|editorialEditionEquivalence=NOT_PROVEN|signed=NO")
    return complete


def selftest():
    def sample(phase, status, pid):
        return "\n".join(
            [f"MANGAALL_CANONICAL_PROCESS|phase={phase}|shard=0|pid={pid}"]
            + [f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase}|index={i}|outcome={status}|catalog=mangaupdates"
               for i in range(1, 11)]
        )
    first = sample("COLLECT", "COLLECTED", 123)
    second = sample("RESTORE", "RESTORED", 456)
    c = parse_phase(first, 0, "COLLECT", True)
    r = parse_phase(second, 0, "RESTORE", True)
    assert evaluate(0, c, r, True)["outcome"] == "PASS"
    assert evaluate(0, c, r, False)["outcome"] == "RED"
    assert evaluate(0, c, parse_phase(sample("RESTORE", "RESTORED", 123), 0, "RESTORE", True), True)["outcome"] == "RED"
    assert not parse_phase(first + "\n" + first, 0, "COLLECT", True)["complete"]
    assert not parse_phase(first, 1, "COLLECT", True)["complete"]
    assert not parse_phase(first, 0, "COLLECT", False)["complete"]
    assert not parse_phase(second.replace("index=10", "index=11"), 0, "RESTORE", True)["complete"]
    assert not parse_phase(second.replace("RESTORED", "NO_COLLECTED_RECEIPT"), 0, "RESTORE", True)["complete"]
    assert not parse_phase(first.replace("catalog=mangaupdates","catalog=shikimori"), 0, "COLLECT", True)["complete"]
    print("MANGAALL_CANONICAL_PERSISTENCE|selftest=PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", choices=("mangaupdates", "shikimori", "explicit_plan"), default="mangaupdates")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--shard", type=int, choices=range(3))
    group.add_argument("--aggregate", action="store_true")
    group.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    locked_works()
    if args.selftest:
        selftest()
        return
    raise SystemExit(0 if (aggregate(args.catalog) if args.aggregate else run(args.shard, args.catalog)) else 1)


if __name__ == "__main__":
    main()
