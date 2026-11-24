#!/usr/bin/env bash
# Independent MangaDex diagnostic. No raw logs, URLs or chapter IDs retained.
set -euo pipefail
umask 077
shard="${1:?Provide shard 0, 1 or 2}"
[[ "$shard" =~ ^[012]$ ]]
app=app.tsuzuki.dev
out=".github/results/mangaall-frozen30-mangadex-audit/shard-$shard"
mkdir -p "$out"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t tests < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
[[ "${#target[@]}" -eq 1 && "${#tests[@]}" -eq 1 ]]
log="$(mktemp)"
trap 'rm -f "$log"; adb shell am force-stop "$app" >/dev/null || true; adb shell pm clear "$app" >/dev/null || true' EXIT
adb install -r "${target[0]}"
adb install -r "${tests[0]}"
adb shell cmd connectivity airplane-mode disable
status=0
timeout --foreground 1900s adb shell am instrument -w -r \
  -e mangaDexAuditOptIn I_ACCEPT_FROZEN30_MANGADEX_AUDIT \
  -e mangaAllFrozen30Shard "$shard" \
  -e class 'eu.kanade.tachiyomi.provider.runtime.MangaAllFrozen30MangaDexAuditInstrumentedTest#auditMangaDexIndependently' \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 || status=$?
python3 - "$log" "$status" "$shard" "$out" <<'PY'
import json,re,sys
from pathlib import Path
raw=Path(sys.argv[1]).read_text(errors="replace")
shard=int(sys.argv[3])
fields=("candidates","credited","lookupIncomplete","terminal","empty","inventoryIncomplete","chapterOne","external","pageLists","pageFailures")
pattern=(r"MANGAALL_DEX_AUDIT\|index=(\d+)\|outcome=(CONTRACT_FAILED|INCOMPLETE|NO_EXACT_HID|EMPTY_EN|NO_CHAPTER_ONE_EN|SOURCE_PAGE_LIST_READY|EXTERNAL_ONLY|PAGE_LIST_UNAVAILABLE)"
         +"".join(r"\|"+f+r"=(\d+)" for f in fields)+r"\|canonical=NOT_TESTED")
rows=[]
for match in re.finditer(pattern,raw):
    index,outcome,*counts=match.groups()
    row=dict(index=int(index),outcome=outcome,**dict(zip(fields,map(int,counts))))
    rows.append(row)
    print(match.group(0))
expected=list(range(shard*10+1,shard*10+11))
process_ok=sys.argv[2]=="0" and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
complete=process_ok and [r["index"] for r in rows]==expected
integrity=complete and all(r["outcome"] not in ("CONTRACT_FAILED","INCOMPLETE") and
    r["credited"]<=r["candidates"] and r["terminal"]==r["candidates"] and
    r["empty"]<=r["terminal"] and r["external"]+r["pageLists"]+r["pageFailures"]==r["chapterOne"]
    for r in rows)
ready=sum(r["outcome"]=="SOURCE_PAGE_LIST_READY" for r in rows)
# Known externally hosted chapters are coverage observations, not failures.
# Unexpected errors from reading.pages must not be certified as healthy.
transport_healthy=integrity and all(r["pageFailures"]==0 for r in rows)
# A complete observation of missing/externally hosted chapters is NOT a
# successful chapter-one Reader result, and remains explicitly counted.
report=dict(schemaVersion=1,scope="MANGADEX_EN_ONLY",shard=shard,
    corpusSha256="19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1",
    instrumentationPassed=process_ok,completeReport=complete,integrity=integrity,
    transportHealthy=transport_healthy,
    sourcePageListReady=ready,works=rows,canonical="NOT_TESTED",readerPixels="NOT_TESTED")
Path(sys.argv[4],"verdict.json").write_text(json.dumps(report,indent=2)+"\n")
print(f"MANGAALL_DEX_AUDIT|shard={shard}|reports={len(rows)}|integrity={integrity}|transportHealthy={transport_healthy}|ready={ready}|expected=10|canonical=NOT_TESTED")
assert integrity, "Incomplete or invalid independent source audit"
assert transport_healthy, "Unexpected MangaDex page preparation failure"
PY
