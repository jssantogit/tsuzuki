#!/usr/bin/env python3
"""Strict, nonshipping single-catalog gate on the fixed corpus."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
import unicodedata
from pathlib import Path

LOCK = Path(".github/mangaall-dual-source-30-cohort-lock.json")
REQUEST = Path(".github/mangaall-dual-source-30-request.json")
DIGEST = "19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1"
OUT = Path(".github/results/mangaall-frozen30-independent-catalog")
APP = "app.tsuzuki.dev"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = ("eu.kanade.tachiyomi.data.tsuzuki.instrumentation."
        "MangaAllIndependentCatalogLiveInstrumentedTest#"
        "arbitraryWorksAreQueriedThroughProductionIndependentCatalog")
STATUSES = {
    "RESOLVED", "CATALOG_AMBIGUOUS", "CATALOG_NOT_FOUND",
    "CATALOG_UNAVAILABLE", "CATALOG_INCOMPLETE",
    "CATALOG_UNVERIFIED", "CATALOG_FORMAT_UNSUPPORTED",
}


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def locked_works():
    lock = json.loads(LOCK.read_text())
    works = lock["works"]
    if (lock["cohort"] != "MANHWA_30_FIXED_20261123"
            or lock["format"] != "manhwa"
            or lock["freezeUntil"] != "THIRTY_OF_THIRTY_VERIFIED_AND_EXPLICITLY_APPROVED"
            or len(works) != 30 or len(set(works)) != 30
            or json.loads(REQUEST.read_text())["works"] != works
            or digest(json.dumps(works, ensure_ascii=False, separators=(",", ":"))) != DIGEST):
        raise SystemExit("FROZEN_CORPUS_MISMATCH")
    return works


def case_id(title):
    name = re.sub(r"\s+", " ", unicodedata.normalize("NFKC", title).strip()).lower()
    return "user-" + digest(name)[:12]


def classify(record, catalog="mangaupdates", expected_format="MANHWA"):
    if expected_format not in ("MANHWA", "MANGA"):
        raise ValueError("Unknown frozen catalog format")
    if catalog not in ("mangaupdates", "shikimori"):
        raise ValueError("Unknown independent catalog scope")
    status = record.get("status")
    if status not in STATUSES:
        return "INVALID_ATTESTATION", None
    if (record.get("schemaVersion") != 1
            or record.get("mode") != "DIRECT_LIVE_ANDROID_CATALOG_ONLY"
            or record.get("catalogSource") != catalog
            or record.get("canonical") != "NOT_EXECUTED_HOST"):
        return "INVALID_ATTESTATION", None
    if status != "RESOLVED":
        return status, None
    owner = record.get("provider")
    owner_id = record.get("providerId")
    external = record.get("externalIds")
    if (owner != catalog or not isinstance(owner_id, str)
            or not re.fullmatch(r"[1-9][0-9]*", owner_id)
            or not isinstance(external, dict) or (owner in external and external[owner] != owner_id)
            or record.get("catalogsQueried") != 1 or record.get("catalogsFailed") != 0):
        return "OWNER_PROVENANCE_UNVERIFIED", None
    # CatalogItem.provider/providerId are the detail owner identity. externalIds
    # contains optional crosswalks; the Kotlin resolver verified the search/detail
    # owner match and toCanonicalReadingMetadata validates and adds the self ID.
    if record.get("format") != expected_format:
        return "FORMAT_UNVERIFIED", None
    authors = record.get("authors")
    artists = record.get("artists")
    if (record.get("creatorsAttested") is not True
            or not isinstance(authors, list) or not isinstance(artists, list)
            or not (authors or artists)
            or not all(isinstance(name, str) and name.strip() for name in authors + artists)):
        return "CREATOR_PROVENANCE_UNVERIFIED", None
    return "CATALOG_IDENTITY_ATTESTED", digest(owner + ":" + owner_id)


def evaluate(works, shard, log, instrument_ok, catalog="mangaupdates"):
    selected = works[shard * 10:(shard + 1) * 10]
    records = {}
    invalid = 0
    for match in re.finditer(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)", log):
        try:
            record = json.loads(base64.b64decode(match.group(1), validate=True))
            title = record.get("query")
            if title not in selected or record.get("caseId") != case_id(title) or title in records:
                invalid += 1
                continue
            records[title] = record
        except (ValueError, TypeError, AttributeError):
            invalid += 1
    results = []
    for offset, title in enumerate(selected):
        status, identity = classify(records[title], catalog) if title in records else ("NOT_OBSERVED", None)
        results.append({"index": shard * 10 + offset + 1, "status": status, "identityDigest": identity})
    ok = (instrument_ok and invalid == 0 and len(records) == 10
          and all(r["status"] == "CATALOG_IDENTITY_ATTESTED" for r in results)
          and len({r["identityDigest"] for r in results}) == 10)
    return {"schemaVersion": 1, "corpusSha256": DIGEST, "shard": shard,
            "catalogSource": catalog, "instrumentationPassed": instrument_ok, "invalidAttestations": invalid,
            "outcome": "PASS" if ok else "RED", "works": results,
            "scope": "INDEPENDENT_CATALOG_ONLY", "sourceBinding": "NOT_TESTED",
            "sqlite": "NOT_TESTED", "readerUI": "NOT_TESTED", "signed": "NO"}


def adb(*args, timeout=120, check=True):
    return subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=check)


def result_root(catalog):
    return OUT if catalog == "mangaupdates" else OUT.with_name(OUT.name + "-" + catalog)


def shard_run(works, shard, catalog="mangaupdates"):
    output = result_root(catalog) / f"shard-{shard}"
    output.mkdir(parents=True, exist_ok=True)
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise SystemExit("EMULATOR_REQUIRED")
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(target) != 1 or len(tests) != 1:
        raise SystemExit("APK_INPUT_COUNT")
    log = ""
    instrument_ok = False
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        if adb("shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() != "0":
            raise RuntimeError("NETWORK_UNAVAILABLE")
        selected = works[shard * 10:(shard + 1) * 10]
        payload = base64.b64encode("\n".join("manhwa::" + title for title in selected).encode()).decode()
        result = adb("shell", "am", "instrument", "-w", "-r",
                     "-e", "mangaAllLiveCatalogOptIn", "true",
                     "-e", "mangaAllLiveCatalogSource", catalog,
                     "-e", "worksBase64", payload, "-e", "shardIndex", "0",
                     "-e", "shardCount", "1", "-e", "class", TEST, RUNNER,
                     timeout=950, check=False)
        log = result.stdout
        instrument_ok = (result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in log
                         and "OK (1 test)" in log)
    except (subprocess.SubprocessError, RuntimeError):
        # Never upload raw source metadata, exception strings, or logcat.
        print("MANGAALL_CATALOG|instrumentation=FAILED_OR_TIMED_OUT")
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)
    diagnostic_pattern = (
        r"MANGAALL_CATALOG_DIAGNOSTIC\|index=(\d+)\|offset=(\d+)\|"
        + ("MU_SEARCH_CONTRACT" if catalog == "mangaupdates" else "SHIKI_SEARCH_CONTRACT") +
        r"\|reason=(FIRST_GEOMETRY|PAGE_GEOMETRY|ROW_COUNT|INVALID_OR_REPEATED_ID|OTHER_FAILURE|OK)"
        r"(?:\|page=-?\d+\|size=-?\d+\|total=-?\d+\|rows=\d+|\|total=-?\d+\|rows=\d+)?"
    )
    diagnostics = []
    for match in re.finditer(diagnostic_pattern, log):
        index = int(match.group(1))
        if not 0 <= index < 10:
            continue
        line = match.group(0).replace(f"index={index}|", f"index={shard * 10 + index + 1}|", 1)
        diagnostics.append(line)
        print(line)
    (output / "pagination-diagnostics.txt").write_text("\n".join(diagnostics[:80]) + "\n")
    report = evaluate(works, shard, log, instrument_ok, catalog)
    (output / "verdict.json").write_text(json.dumps(report, indent=2) + "\n")
    for row in report["works"]:
        print(f"MANGAALL_CATALOG|index={row['index']}|outcome={row['status']}")
    print(f"MANGAALL_CATALOG|shard={shard}|outcome={report['outcome']}|scope=INDEPENDENT_CATALOG_ONLY")
    return report["outcome"] == "PASS"


def aggregate(catalog="mangaupdates"):
    rows = []
    all_shards = True
    for shard in range(3):
        path = result_root(catalog) / f"shard-{shard}" / "verdict.json"
        if not path.is_file():
            all_shards = False
            continue
        report = json.loads(path.read_text())
        expected_indices = list(range(shard * 10 + 1, shard * 10 + 11))
        actual = report.get("works", [])
        if (report.get("schemaVersion") != 1 or report.get("corpusSha256") != DIGEST
                or report.get("catalogSource") != catalog
                or report.get("shard") != shard
                or report.get("scope") != "INDEPENDENT_CATALOG_ONLY"
                or [r.get("index") for r in actual] != expected_indices
                or report.get("outcome") != "PASS"
                or report.get("instrumentationPassed") is not True
                or report.get("invalidAttestations") != 0):
            all_shards = False
        rows.extend(actual)
    attested = [r for r in rows if r.get("status") == "CATALOG_IDENTITY_ATTESTED"
                and isinstance(r.get("identityDigest"), str)
                and re.fullmatch(r"[0-9a-f]{64}", r["identityDigest"])]
    unique = len({r["identityDigest"] for r in attested})
    ok = all_shards and len(rows) == len(attested) == unique == 30
    print(f"MANGAALL_CATALOG|outcome={'PASS' if ok else 'RED'}|attested={len(attested)}"
          f"|distinctCatalogIdentities={unique}|expected=30"
          "|scope=INDEPENDENT_CATALOG_ONLY|sourceBinding=NOT_TESTED|sqlite=NOT_TESTED|readerUI=NOT_TESTED")
    return ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", choices=("mangaupdates", "shikimori"), default="mangaupdates")
    parser.add_argument("--shard", type=int, choices=range(3))
    parser.add_argument("--aggregate", action="store_true")
    parser.add_argument("--check-lock", action="store_true")
    args = parser.parse_args()
    works = locked_works()
    if sum((args.shard is not None, args.aggregate, args.check_lock)) != 1:
        parser.error("Select exactly one mode")
    if args.check_lock:
        print("MANGAALL_CATALOG|cohort=LOCKED|works=30")
        return
    raise SystemExit(0 if (aggregate(args.catalog) if args.aggregate else shard_run(works, args.shard, args.catalog)) else 1)


if __name__ == "__main__":
    main()
