#!/usr/bin/env python3
"""FV30 eight-case comparative diagnostic; not an independent holdout rerun."""
import argparse
import base64
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_fv30_first_pass import locked, parse, selftest, SCOPES
from run_mangaall_frozen30_independent_catalog import APP, RUNNER, TEST, adb

PENDING = (2, 4, 14, 22, 27, 28, 29, 30)
OUT = Path("fv30-focused-results")
DIAGNOSTIC = re.compile(
    r"MANGAALL_CATALOG_DIAGNOSTIC\|index=(\d+)\|offset=(\d+)\|"
    r"(?:MU|SHIKI)_SEARCH_CONTRACT\|reason="
    r"(FIRST_GEOMETRY|PAGE_GEOMETRY|ROW_COUNT|INVALID_OR_REPEATED_ID|OTHER_FAILURE|"
    r"HTTP_[1-5][0-9]{2}|NETWORK_FAILURE|SERIALIZATION_FAILURE|OK)"
    r"(?:\|page=-?\d+\|size=-?\d+\|total=-?\d+\|rows=\d+|\|total=-?\d+\|rows=\d+)?"
    r"(?=\s|$)"
)


def selected(rows, catalog):
    return [dict(row, catalog=catalog) for row in rows if row["index"] in PENDING]


def run(rows, digest, catalog):
    group = selected(rows, catalog)
    target = [p for p in Path("app/build/outputs/apk/debug").rglob("*.apk")
              if p.name == "app-debug.apk" or "universal" in p.name]
    tests = list(Path("app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    assert len(target) == len(tests) == 1
    assert adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"
    observed, invalid, process_ok, diagnostics = {}, 0, False, []
    try:
        adb("install", "-r", str(target[0]))
        adb("install", "-r", str(tests[0]))
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        payload = base64.b64encode("\n".join(r["format"] + "::" + r["title"]
                                               for r in group).encode()).decode()
        result = adb("shell", "am", "instrument", "-w", "-r",
                     "-e", "mangaAllLiveCatalogOptIn", "true",
                     "-e", "mangaAllLiveCatalogSource", catalog,
                     "-e", "worksBase64", payload, "-e", "shardIndex", "0",
                     "-e", "shardCount", "1", "-e", "class", TEST, RUNNER,
                     timeout=950, check=False)
        raw = result.stdout
        process_ok = result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
        observed, invalid = parse(raw, group)
        for match in DIAGNOSTIC.finditer(raw):
            local = int(match.group(1))
            if 0 <= local < len(group) and len(diagnostics) < 256:
                diagnostics.append(match.group(0).replace(f"index={local}|", f"index={group[local]['index']}|", 1))
    except subprocess.SubprocessError:
        process_ok = False
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)
    reports = [observed.get(r["index"], dict(index=r["index"], catalog=catalog,
               status="NOT_OBSERVED", identityDigest=None, creatorDigests=[])) for r in group]
    complete = process_ok and invalid == 0 and len(observed) == len(group)
    folder = OUT / catalog
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "verdict.json").write_text(json.dumps(dict(schemaVersion=1, corpusSha256=digest,
        scope="FV30_FOCUSED_CATALOG_DIAGNOSTIC", catalog=catalog, processOk=process_ok,
        invalidAttestations=invalid, complete=complete, works=reports), indent=2) + "\n")
    (folder / "pagination.txt").write_text("\n".join(diagnostics) + "\n")
    for line in diagnostics:
        print(line)
    for row in reports:
        print(f"FV30_FOCUSED|index={row['index']}|catalog={catalog}|outcome={row['status']}")
    return complete


def aggregate(rows, digest):
    valid, reports = True, []
    for catalog in SCOPES:
        p = OUT / catalog / "verdict.json"
        if not p.is_file():
            valid = False
            continue
        doc = json.loads(p.read_text())
        works = doc.get("works", [])
        valid &= (doc.get("schemaVersion") == 1 and doc.get("corpusSha256") == digest
                  and doc.get("scope") == "FV30_FOCUSED_CATALOG_DIAGNOSTIC"
                  and doc.get("catalog") == catalog and doc.get("processOk") is True
                  and doc.get("invalidAttestations") == 0 and doc.get("complete") is True
                  and [(w.get("index"), w.get("catalog")) for w in works] ==
                      [(i, catalog) for i in PENDING])
        reports.extend(works)
    resolved = {w["index"] for w in reports if w.get("status") == "CATALOG_IDENTITY_ATTESTED"}
    OUT.mkdir(exist_ok=True)
    (OUT / "summary.json").write_text(json.dumps(dict(scope="FV30_FOCUSED_CATALOG_DIAGNOSTIC",
        corpusSha256=digest, complete=valid, observed=len(reports), expected=16,
        candidateWorks=len(resolved), independentEditionOracle="PENDING", reading="NOT_RUN",
        works=reports), indent=2) + "\n")
    print(f"FV30_FOCUSED|diagnosticComplete={valid}|observations={len(reports)}/16|candidateWorks={len(resolved)}/8|reading=NOT_RUN")
    return valid and len(reports) == 16


def main():
    p = argparse.ArgumentParser()
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--catalog", choices=SCOPES)
    mode.add_argument("--aggregate", action="store_true")
    args = p.parse_args()
    rows, digest = locked()
    if args.check:
        selftest(rows)
        assert all(tuple(r["index"] for r in selected(rows, c)) == PENDING for c in SCOPES)
        assert DIAGNOSTIC.search("MANGAALL_CATALOG_DIAGNOSTIC|index=0|offset=0|MU_SEARCH_CONTRACT|reason=OK|total=10000|rows=25")
        assert not DIAGNOSTIC.search("MANGAALL_CATALOG_DIAGNOSTIC|index=0|offset=0|MU_SEARCH_CONTRACT|reason=SECRET")
        return True
    return aggregate(rows, digest) if args.aggregate else run(rows, digest, args.catalog)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
