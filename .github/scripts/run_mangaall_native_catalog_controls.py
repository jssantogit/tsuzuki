#!/usr/bin/env python3
"""Research-only, exact locked cases; native names narrow retrieval, not identity."""
import base64
import json
import re
import subprocess
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import (
    APP, DIGEST, RUNNER, TEST, adb, case_id, classify, locked_works,
)

NATIVE = {2: "전지적 독자 시점", 28: "그녀가 공작저로 가야 했던 사정", 29: "치즈인더트랩"}
OUT = Path(".github/results/mangaall-native-catalog-controls")


def main():
    works = locked_works()
    selected = {works[i-1]: native for i, native in NATIVE.items()}
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
        payload = base64.b64encode("\n".join("manhwa::" + title for title in selected).encode()).decode()
        hints = base64.b64encode(json.dumps(selected, ensure_ascii=False).encode()).decode()
        result = adb("shell", "am", "instrument", "-w", "-r",
                     "-e", "mangaAllLiveCatalogOptIn", "true",
                     "-e", "mangaAllLiveCatalogSource", "mangaupdates",
                     "-e", "worksBase64", payload, "-e", "catalogSearchHintsBase64", hints,
                     "-e", "shardIndex", "0", "-e", "shardCount", "1", "-e", "class", TEST, RUNNER,
                     timeout=240, check=False)
        log = result.stdout
        instrument_ok = (result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in log
                         and "OK (1 test)" in log)
    except (subprocess.SubprocessError, RuntimeError):
        print("NATIVE_CATALOG|instrumentation=FAILED_OR_TIMED_OUT")
    finally:
        adb("shell", "am", "force-stop", APP, check=False)
        adb("shell", "pm", "clear", APP, check=False)
    records = {}
    invalid = 0
    for match in re.finditer(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)", log):
        try:
            record = json.loads(base64.b64decode(match.group(1), validate=True))
            title = record.get("query")
            if (title not in selected or record.get("caseId") != case_id(title)
                    or title in records or record.get("retrievalHintUsed") is not True):
                invalid += 1
                continue
            records[title] = record
        except (ValueError, TypeError, AttributeError):
            invalid += 1
    rows = []
    for index in NATIVE:
        title = works[index-1]
        status, identity = classify(records[title]) if title in records else ("NOT_OBSERVED", None)
        rows.append(dict(index=index, status=status, identityDigest=identity))
        print(f"NATIVE_CATALOG|index={index}|outcome={status}")
    ok = (instrument_ok and invalid == 0 and len(records) == 3
          and all(r["status"] == "CATALOG_IDENTITY_ATTESTED" for r in rows)
          and len({r["identityDigest"] for r in rows}) == 3)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "verdict.json").write_text(json.dumps(dict(schemaVersion=1, corpusSha256=DIGEST,
        scope="INDEPENDENT_NATIVE_QUERY_CONTROLS_ONLY", catalogSource="mangaupdates",
        instrumentationPassed=instrument_ok, invalidAttestations=invalid,
        outcome="PASS" if ok else "RED", works=rows), indent=2)+"\n")
    print(f"NATIVE_CATALOG|outcome={'PASS' if ok else 'RED'}|expected=3|canonical=NOT_TESTED")
    return ok


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
