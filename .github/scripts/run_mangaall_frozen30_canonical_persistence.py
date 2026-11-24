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
FOCUSED_OUT = Path(".github/results/mangaall-frozen30-pending14-persistence")
PENDING14 = frozenset((5, 6, 8, 11, 15, 16, 19, 20, 21, 23, 25, 27, 28, 29))


def expected_indices(shard, focused=False):
    indices = set(range(shard * 10 + 1, shard * 10 + 11))
    return indices & PENDING14 if focused else indices


def receipt_scope(focused):
    return ("CANONICAL_FROZEN30_PENDING14_RESTART" if focused
            else "CANONICAL_COLLECTOR_SQLITE_SELECTED_OPTION_RESTART")

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


def parse_phase(log, shard, phase, process_ok, catalog="mangaupdates", owners=None, focused=False):
    indices = expected_indices(shard, focused)
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


READER_STATUS = {
    "CHAPTER_MISSING", "SELECTED_OPTION_MISSING", "PREPARATION_FAILED",
    "UNEXPECTED_DELIVERY", "INVALID_PAGE_AUTHORITY", "LOADER_FAILED",
    "IMAGE_LIMIT", "BITMAP_FAILED", "FIRST_PAGE_DECODED",
    "UNCLASSIFIED_FAILURE", "PERSISTENCE_NOT_VERIFIED",
}


def parse_reader(log, shard, restored, process_ok, catalog, owners, focused=False):
    indices = expected_indices(shard, focused)
    rows = {}
    invalid = 0
    restored_by_index = {row["index"]: row["status"] for row in restored["works"]}
    for match in re.finditer(
        r"MANGAALL_CANONICAL_READER\|index=(\d+)\|outcome=([A-Z_0-9]+)\|catalog=(mangaupdates|shikimori)\|ui=NOT_TESTED",
        log,
    ):
        index, status, owner = match.groups()
        index = int(index)
        if (index not in indices or index in rows or status not in READER_STATUS
                or owner != (owners.get(index) if owners is not None else catalog)
                or (status == "FIRST_PAGE_DECODED" and restored_by_index.get(index) != "RESTORED")):
            invalid += 1
            continue
        rows[index] = status
    return {"processOk": process_ok, "invalid": invalid,
            "complete": process_ok and invalid == 0 and set(rows) == indices
                and all(s == "FIRST_PAGE_DECODED" for s in rows.values()),
            "works": [{"index": i, "catalog": owners.get(i) if owners is not None else catalog,
                       "status": rows.get(i, "NOT_OBSERVED")} for i in sorted(indices)]}


def collect_diagnostics(log, shard, focused=False):
    fields = ("chapters", "chapterOne", "evidence", "associations", "verified",
              "dexOptions", "dotOptions", "rivalReleaseRefs")
    pattern = r"MANGAALL_CANONICAL_DIAGNOSTIC\|index=(\d+)" + "".join(
        r"\|" + key + r"=(\d+)" for key in fields)
    rows = {}
    for match in re.finditer(pattern, log):
        index, *counts = map(int, match.groups())
        if index not in expected_indices(shard, focused) or index in rows:
            # Diagnostic rows are not success evidence. Drop ambiguous records.
            return []
        rows[index] = dict(index=index, **dict(zip(fields, counts)))
    return [rows[i] for i in sorted(rows)]


def collect_acquisition(log, shard, focused=False):
    # Closed codes and bounded counts only; never forward raw instrumentation.
    pattern = (
        r"MANGAALL_CANONICAL_ACQUISITION\|index=(\d+)\|source=(MANGADEX|MANGADOT|UNKNOWN)"
        r"\|event=(PROVIDER_READING_(?:LOOKUP_ATTEMPT|CANDIDATE|SOURCE_ASSOCIATION|CHAPTER_ATTEMPT))"
        r"\|outcome=([A-Z_]{1,48})\|reason=([A-Z_]{1,64})"
        r"\|candidates=(\d{1,6})\|count=(\d{1,4})(?=\s|$)"
    )
    rows = []
    seen = set()
    for match in re.finditer(pattern, log):
        index, source, event, outcome, reason, candidates, count = match.groups()
        index, candidates, count = int(index), int(candidates), int(count)
        key = (index, source, event, outcome, reason, candidates)
        if (index not in expected_indices(shard, focused) or
                not 0 < count <= 2048 or key in seen or len(rows) >= 2048):
            return []
        seen.add(key)
        rows.append(dict(index=index, source=source, event=event, outcome=outcome,
                         reason=reason, candidates=candidates, count=count))
    return rows


def scope(catalog):
    if catalog!="explicit_plan":
        return None, None
    rows, digest=load_plan()
    return {r["index"]:r["catalog"] for r in rows}, digest


def invoke(shard, phase, catalog, reader=False, focused=False):
    owners, plan_digest=scope(catalog)
    args=["shell", "am", "instrument", "-w", "-r",
          "-e", "mangaAllCanonicalOptIn", "I_ACCEPT_FROZEN30_CANONICAL_RESEARCH",
          "-e", "mangaAllCanonicalPhase", phase,
          "-e", "mangaAllCanonicalCatalog", catalog,
          "-e", "mangaAllFrozen30Shard", str(shard)]
    if focused:
        args.extend(["-e", "mangaAllCanonicalPending14", "1"])
    if plan_digest:
        args.extend(["-e","mangaAllCanonicalPlanSha256",plan_digest])
    args.extend(["-e","mangaAllCanonicalReaderProbe","1" if reader and phase == "RESTORE" else "0"])
    args.extend(["-e","class",TEST,RUNNER])
    try:
        result=adb(*args,check=False,timeout=1850)
        ok=(result.returncode==0 and "INSTRUMENTATION_CODE: -1" in result.stdout
            and "OK (1 test)" in result.stdout)
        log = result.stdout
    except subprocess.SubprocessError:
        log, ok = "", False
    parsed = parse_phase(log,shard,phase,ok,catalog,owners,focused)
    if phase == "COLLECT":
        parsed["diagnostics"] = collect_diagnostics(log, shard, focused)
        parsed["acquisition"] = collect_acquisition(log, shard, focused)
    if reader and phase == "RESTORE":
        parsed["reader"] = parse_reader(log, shard, parsed, ok, catalog, owners, focused)
    return parsed


def evaluate(shard, collect, restore, force_stopped, catalog="mangaupdates", reader=False, focused=False):
    restart = (force_stopped and collect["pid"] is not None and restore["pid"] is not None
               and collect["pid"] != restore["pid"])
    return {
        "schemaVersion": 1, "corpusSha256": DIGEST, "shard": shard,
        "catalogSource": catalog, "planSha256": scope(catalog)[1],
        "scope": receipt_scope(focused),
        "restartObserved": restart,
        "outcome": "PASS" if restart and collect["complete"] and restore["complete"]
            and (not reader or restore.get("reader", {}).get("complete") is True) else "RED",
        "readerProbe": "RESTORED_FIRST_PAGE" if reader else "NOT_TESTED",
        "collect": {k: v for k, v in collect.items() if k != "pid"},
        "restore": {k: v for k, v in restore.items() if k != "pid"},
        "signed": "NO", "readerUI": "NOT_TESTED", "editorialEditionEquivalence": "NOT_PROVEN",
    }


def run(shard, catalog, reader=False, focused=False):
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("EMULATOR_REQUIRED")
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(target) != 1 or len(tests) != 1:
        raise SystemExit("APK_INPUT_COUNT")
    output = (FOCUSED_OUT if focused else OUT) / f"shard-{shard}"
    output.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        if "Success" not in adb("shell", "pm", "clear", APP).stdout:
            raise SystemExit("DISPOSABLE_DATABASE_CLEAR_FAILED")
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        if adb("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() != "0":
            raise SystemExit("NETWORK_UNAVAILABLE")
        collect = invoke(shard, "COLLECT", catalog, focused=focused)
        adb("shell", "am", "force-stop", APP)
        force_stopped = not adb("shell", "pidof", APP, check=False).stdout.strip()
        # Do not clear application data between the two instrumentation runs.
        restore = invoke(shard, "RESTORE", catalog, reader, focused)
        report = evaluate(shard, collect, restore, force_stopped, catalog, reader, focused)
        (output / "verdict.json").write_text(json.dumps(report, indent=2) + "\n")
        for phase in ("collect", "restore"):
            for row in report[phase]["works"]:
                print(f"MANGAALL_CANONICAL_PERSISTENCE|phase={phase.upper()}|index={row['index']}|outcome={row['status']}")
        for row in report["collect"].get("diagnostics", []):
            print("MANGAALL_CANONICAL_DIAGNOSTIC|" + "|".join(f"{key}={value}" for key, value in row.items()))
        for row in report["collect"].get("acquisition", []):
            print("MANGAALL_CANONICAL_ACQUISITION|" + "|".join(f"{key}={value}" for key, value in row.items()))
        if reader:
            for row in report["restore"]["reader"]["works"]:
                print(f"MANGAALL_CANONICAL_READER|index={row['index']}|outcome={row['status']}|ui=NOT_TESTED")
        print(f"MANGAALL_CANONICAL_PERSISTENCE|shard={shard}|outcome={report['outcome']}|restart={report['restartObserved']}")
        return report["outcome"] == "PASS"
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)


def aggregate(catalog, reader=False, focused=False):
    owners, plan_digest=scope(catalog)
    complete = True
    decoded = 0
    for shard in range(3):
        path = (FOCUSED_OUT if focused else OUT) / f"shard-{shard}" / "verdict.json"
        if not path.is_file():
            complete = False
            continue
        report = json.loads(path.read_text())
        expected = sorted(expected_indices(shard, focused))
        if (report.get("schemaVersion") != 1 or report.get("corpusSha256") != DIGEST
                or report.get("catalogSource") != catalog or report.get("planSha256") != plan_digest
                or report.get("shard") != shard or report.get("outcome") != "PASS"
                or report.get("restartObserved") is not True
                or report.get("scope") != receipt_scope(focused)):
            complete = False
        if reader:
            stage = report.get("restore", {}).get("reader", {})
            rows = stage.get("works", [])
            decoded += sum(r.get("status") == "FIRST_PAGE_DECODED" for r in rows)
            if (report.get("readerProbe") != "RESTORED_FIRST_PAGE" or stage.get("complete") is not True
                    or stage.get("processOk") is not True or stage.get("invalid") != 0
                    or [r.get("index") for r in rows] != expected
                    or any(r.get("status") != "FIRST_PAGE_DECODED" or r.get("catalog") != (owners.get(r["index"]) if owners is not None else catalog) for r in rows)):
                complete = False
        for phase, success in (("collect", "COLLECTED"), ("restore", "RESTORED")):
            data = report.get(phase, {})
            rows = data.get("works", [])
            if (data.get("complete") is not True or data.get("processOk") is not True
                    or data.get("invalid") != 0 or [r.get("index") for r in rows] != expected
                    or any(r.get("status") != success or r.get("catalog") != (owners.get(r["index"]) if owners is not None else catalog) for r in rows)):
                complete = False
    total = len(PENDING14) if focused else 30
    if reader:
        print(f"MANGAALL_CANONICAL_READER|decoded={decoded}|expected={total}|ui=NOT_TESTED")
    print(f"MANGAALL_CANONICAL_PERSISTENCE|outcome={'PASS' if complete else 'RED'}"
          f"|expected={total}|readerUI=NOT_TESTED|editorialEditionEquivalence=NOT_PROVEN|signed=NO")
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
    reader_log = "\n".join(f"MANGAALL_CANONICAL_READER|index={i}|outcome=FIRST_PAGE_DECODED|catalog=mangaupdates|ui=NOT_TESTED" for i in range(1, 11))
    r["reader"] = parse_reader(reader_log, 0, r, True, "mangaupdates", None)
    assert evaluate(0, c, r, True, reader=True)["outcome"] == "PASS"
    assert not parse_reader(reader_log+"\n"+reader_log, 0, r, True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log.replace("index=10", "index=11"), 0, r, True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log, 0, parse_phase(second.replace("RESTORED", "NO_COLLECTED_RECEIPT"), 0, "RESTORE", True), True, "mangaupdates", None)["complete"]
    assert not parse_reader(reader_log.replace("FIRST_PAGE_DECODED", "LOADER_FAILED"), 0, r, True, "mangaupdates", None)["complete"]
    diagnostic = "MANGAALL_CANONICAL_DIAGNOSTIC|index=1|chapters=10|chapterOne=1|evidence=20|associations=2|verified=2|dexOptions=1|dotOptions=2|rivalReleaseRefs=1"
    assert collect_diagnostics(diagnostic, 0)[0]["rivalReleaseRefs"] == 1
    assert collect_diagnostics(diagnostic+"\n"+diagnostic, 0) == []
    assert collect_diagnostics(diagnostic, 1) == []
    acquisition = ("MANGAALL_CANONICAL_ACQUISITION|index=1|source=MANGADOT"
                   "|event=PROVIDER_READING_SOURCE_ASSOCIATION|outcome=CANDIDATES"
                   "|reason=AUTHOR_CONFLICT|candidates=0|count=2")
    assert collect_acquisition(acquisition, 0)[0]["reason"] == "AUTHOR_CONFLICT"
    assert collect_acquisition(acquisition, 1) == []
    assert collect_acquisition(acquisition + "\n" + acquisition, 0) == []
    assert collect_acquisition(acquisition + "|url=https://example.com", 0) == []
    assert len(PENDING14) == 14
    assert set.union(*(expected_indices(shard, True) for shard in range(3))) == PENDING14
    subset = "\n".join(line for line in first.splitlines()
                       if "PROCESS|" in line or any(f"|index={i}|" in line for i in expected_indices(0, True)))
    fc = parse_phase(subset, 0, "COLLECT", True, focused=True)
    assert fc["complete"] and len(fc["works"]) == 3
    assert not parse_phase(subset, 0, "COLLECT", True)["complete"]
    assert not parse_phase(first, 0, "COLLECT", True, focused=True)["complete"]
    fr = parse_phase(subset.replace("COLLECT", "RESTORE").replace("RESTOREED", "RESTORED").replace("pid=123", "pid=456"),
                     0, "RESTORE", True, focused=True)
    assert fr["complete"]
    focused_reader = "\n".join(line for line in reader_log.splitlines()
                               if any(f"|index={i}|" in line for i in expected_indices(0, True)))
    fr["reader"] = parse_reader(focused_reader, 0, fr, True, "mangaupdates", None, focused=True)
    assert evaluate(0, fc, fr, True, reader=True, focused=True)["outcome"] == "PASS"
    assert evaluate(0, fc, fr, True, reader=True, focused=True)["scope"] != receipt_scope(False)
    assert not parse_reader(reader_log, 0, fr, True, "mangaupdates", None, focused=True)["complete"]
    print("MANGAALL_CANONICAL_PERSISTENCE|selftest=PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", choices=("mangaupdates", "shikimori", "explicit_plan"), default="mangaupdates")
    parser.add_argument("--reader", action="store_true")
    parser.add_argument("--pending14", action="store_true", help="Exact previously failed indices only; never certifies all 30")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--shard", type=int, choices=range(3))
    group.add_argument("--aggregate", action="store_true")
    group.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    locked_works()
    if args.selftest:
        selftest()
        return
    if args.pending14 and (args.catalog != "explicit_plan" or not args.reader):
        parser.error("--pending14 requires the original explicit catalog plan and Reader probe")
    raise SystemExit(0 if (aggregate(args.catalog, args.reader, args.pending14) if args.aggregate
                           else run(args.shard, args.catalog, args.reader, args.pending14)) else 1)


if __name__ == "__main__":
    main()
