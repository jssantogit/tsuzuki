#!/usr/bin/env python3
"""Verify source-scoped, sanitized MangaDex reports. No raw content retained."""
import argparse
import json
import re
from pathlib import Path

DIGEST = "19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1"
FIELDS = ("candidates", "credited", "lookupIncomplete", "terminal", "empty",
          "inventoryIncomplete", "chapterOne", "external", "pageLists", "pageFailures",
          "readableHids", "pixelAttempts", "pixelDecoded", "pixelFailures", "pixelMode")
PATTERN = re.compile(
    r"MANGAALL_DEX_AUDIT\|index=(\d+)\|outcome="
    r"(CONTRACT_FAILED|INCOMPLETE|NO_EXACT_HID|EMPTY_EN|NO_CHAPTER_ONE_EN|"
    r"SOURCE_PAGE_LIST_READY|EXTERNAL_ONLY|PAGE_LIST_UNAVAILABLE)"
    + "".join(r"\|" + name + r"=(\d+)" for name in FIELDS[:10])
    + r"\|canonical=NOT_TESTED"
    + "".join(r"\|" + name + r"=(\d+)" for name in FIELDS[10:])
    + r"\|stage=(NONE|LOOKUP|CHAPTERS|PAGE_LIST)"
)


def expected_coverage(row):
    if row["candidates"] == 0:
        return "NO_EXACT_HID"
    if row["terminal"] == row["empty"]:
        return "EMPTY_EN"
    if row["chapterOne"] == 0:
        return "NO_CHAPTER_ONE_EN"
    if row["pageLists"] > 0:
        return "SOURCE_PAGE_LIST_READY"
    if row["external"] == row["chapterOne"]:
        return "EXTERNAL_ONLY"
    return "PAGE_LIST_UNAVAILABLE"


def evaluate(raw, status, shard, pixel_mode):
    rows = []
    for match in PATTERN.finditer(raw):
        index, outcome, *counts, stage = match.groups()
        rows.append(dict(index=int(index), outcome=outcome, stage=stage, **dict(zip(FIELDS, map(int, counts)))))
    expected = list(range(shard * 10 + 1, shard * 10 + 11))
    process_ok = status == 0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
    complete = process_ok and [r["index"] for r in rows] == expected
    integrity = complete and all(
        r["outcome"] == expected_coverage(r) and r["stage"] == "NONE"
        and r["lookupIncomplete"] == r["inventoryIncomplete"] == 0
        and r["credited"] <= r["candidates"] == r["terminal"]
        and r["empty"] <= r["terminal"]
        and r["external"] + r["pageLists"] + r["pageFailures"] == r["chapterOne"]
        and (r["chapterOne"] == 0 or r["terminal"] > r["empty"])
        for r in rows
    )
    transport_healthy = integrity and all(r["pageFailures"] == 0 for r in rows)
    pixel_contract = integrity and all(
        r["pixelMode"] == pixel_mode
        and r["readableHids"] <= r["pageLists"]
        and r["readableHids"] <= r["terminal"] - r["empty"]
        and (r["pageLists"] == 0) == (r["readableHids"] == 0)
        and r["pixelDecoded"] + r["pixelFailures"] == r["pixelAttempts"]
        and r["pixelAttempts"] == (r["readableHids"] if pixel_mode else 0)
        for r in rows
    )
    pixel_healthy = pixel_contract and all(r["pixelFailures"] == 0 for r in rows)
    return dict(
        schemaVersion=2, scope="MANGADEX_EN_ONLY", shard=shard, corpusSha256=DIGEST,
        instrumentationPassed=process_ok, completeReport=complete, integrity=integrity,
        transportHealthy=transport_healthy,
        pixelProbe="FIRST_PAGE_PER_READABLE_HID" if pixel_mode else "NOT_TESTED",
        pixelContract=pixel_contract, pixelHealthy=pixel_healthy,
        sourcePageListReady=sum(r["outcome"] == "SOURCE_PAGE_LIST_READY" for r in rows),
        readableHids=sum(r["readableHids"] for r in rows),
        pixelDecoded=sum(r["pixelDecoded"] for r in rows),
        works=rows, canonical="NOT_TESTED", readerUI="NOT_TESTED",
        outcome="PASS" if transport_healthy and pixel_healthy else "RED",
    )


def selftest():
    def record(index, outcome, **updates):
        row = dict.fromkeys(FIELDS, 0)
        row.update(pixelMode=1)
        row.update(updates)
        return (f"MANGAALL_DEX_AUDIT|index={index}|outcome={outcome}"
                + "".join(f"|{k}={row[k]}" for k in FIELDS[:10])
                + "|canonical=NOT_TESTED"
                + "".join(f"|{k}={row[k]}" for k in FIELDS[10:]) + "|stage=NONE")

    logs = [
        record(1, "SOURCE_PAGE_LIST_READY", candidates=1, credited=1, terminal=1,
               chapterOne=2, external=1, pageLists=1, readableHids=1, pixelAttempts=1, pixelDecoded=1),
        record(2, "EXTERNAL_ONLY", candidates=1, credited=1, terminal=1, chapterOne=1, external=1),
        record(3, "EMPTY_EN", candidates=1, terminal=1, empty=1),
    ] + [record(i, "NO_EXACT_HID") for i in range(4, 11)]
    good = "\n".join(logs) + "\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
    passed = evaluate(good, 0, 0, 1)
    assert passed["outcome"] == "PASS" and passed["pixelDecoded"] == 1
    assert evaluate(good, 1, 0, 1)["outcome"] == "RED"
    assert evaluate(good, 0, 1, 1)["outcome"] == "RED"
    assert evaluate(good, 0, 0, 0)["outcome"] == "RED"
    assert evaluate(good + "\n" + logs[0], 0, 0, 1)["outcome"] == "RED"
    assert evaluate(good.replace("pixelDecoded=1|pixelFailures=0", "pixelDecoded=0|pixelFailures=1"), 0, 0, 1)["outcome"] == "RED"
    assert evaluate(good.replace("pageLists=1|pageFailures=0", "pageLists=0|pageFailures=1"), 0, 0, 1)["outcome"] == "RED"
    assert evaluate(good.replace("outcome=EXTERNAL_ONLY", "outcome=SOURCE_PAGE_LIST_READY"), 0, 0, 1)["outcome"] == "RED"
    assert evaluate(good.replace("lookupIncomplete=0", "lookupIncomplete=1"), 0, 0, 1)["outcome"] == "RED"
    assert evaluate(good.replace("stage=NONE", "stage=LOOKUP"), 0, 0, 1)["outcome"] == "RED"
    print("MANGAALL_DEX_AUDIT|verifierSelftest=PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--log", type=Path)
    parser.add_argument("--status", type=int)
    parser.add_argument("--shard", type=int, choices=range(3))
    parser.add_argument("--pixels", type=int, choices=(0, 1), default=0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return True
    if None in (args.log, args.status, args.shard, args.output):
        parser.error("log, status, shard and output required")
    report = evaluate(args.log.read_text(errors="replace"), args.status, args.shard, args.pixels)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "verdict.json").write_text(json.dumps(report, indent=2) + "\n")
    for row in report["works"]:
        print("MANGAALL_DEX_AUDIT|" + "|".join(f"{k}={v}" for k, v in row.items()))
    print(f"MANGAALL_DEX_AUDIT|shard={args.shard}|reports={len(report['works'])}"
          f"|integrity={report['integrity']}|transportHealthy={report['transportHealthy']}"
          f"|readableHids={report['readableHids']}|pixelDecoded={report['pixelDecoded']}|expected=10"
          "|canonical=NOT_TESTED|readerUI=NOT_TESTED")
    return report["outcome"] == "PASS"


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
