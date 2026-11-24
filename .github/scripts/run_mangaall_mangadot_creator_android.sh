#!/usr/bin/env bash
set -euo pipefail
app=app.tsuzuki.dev
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mapfile -t targets < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t tests < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
test "${#targets[@]}" -eq 1
test "${#tests[@]}" -eq 1
adb install -r "${targets[0]}"
adb install -r "${tests[0]}"
adb shell pm clear "$app" | grep -q Success
adb shell cmd connectivity airplane-mode disable
log="$(mktemp)"
trap 'rm -f "$log"; adb shell am force-stop "$app" >/dev/null || true; adb shell pm clear "$app" >/dev/null || true' EXIT
set +e
adb shell am instrument -w -r \
  -e mangaDotCreatorOptIn I_ACCEPT_MANGADOT_CREATOR_RESEARCH \
  -e class eu.kanade.tachiyomi.provider.runtime.MangaAllMangaDotCreatorLiveInstrumentedTest#frozenCreatorSamplesThroughRealHost \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1
status=$?
set -e
python3 - "$log" "$status" <<'PY'
import re,sys
from pathlib import Path
raw=Path(sys.argv[1]).read_text(errors="replace")
rows=re.findall(r"MANGAALL_DOT_CREATOR\|index=(\d+)\|outcome=(PASS|RED)\|exact=(\d+)\|verified=(\d+)\|canonical=NOT_TESTED",raw)
for index,outcome,exact,verified in rows:
    print(f"MANGAALL_DOT_CREATOR|index={index}|outcome={outcome}|exact={exact}|verified={verified}|canonical=NOT_TESTED")
assert sys.argv[2]=="0" and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
assert rows==[(str(i),"PASS","1","1") for i in (1,3,10,12)], "Source creator evidence incomplete"
PY
