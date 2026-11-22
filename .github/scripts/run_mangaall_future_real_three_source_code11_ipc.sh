#!/usr/bin/env bash
# NONSHIPPING: unsigned research source ZIP embedded in androidTest only.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly class='eu.kanade.tachiyomi.provider.runtime.MangaAllFutureRealThreeSourceCode11InstrumentedTest#real_three_source_unsigned_code11_keeps_lookup_and_native_chapters_separate'
readonly out='.github/results/mangaall-future-real-three-source-ipc'
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_REAL_JS_CODE11|emulator=REQUIRED|outcome=DENIED' >&2
  exit 2
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "\${#target[@]}" -ne 1 || "\${#instrument[@]}" -ne 1 ]]; then
  echo 'MANGAALL_REAL_JS_CODE11|apk_count=INVALID|outcome=DENIED' >&2
  exit 3
fi
cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "\${target[0]}" >/dev/null
adb install -r "\${instrument[0]}" >/dev/null
adb shell pm list instrumentation | tr -d '\r' | grep -Fq "instrumentation:$runner (target=$app)"
adb shell cmd connectivity airplane-mode enable >/dev/null
if [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_REAL_JS_CODE11|airplane_mode=REQUIRED|outcome=DENIED' >&2
  exit 4
fi
raw="$out/instrumentation.txt"
status=0
timeout --foreground 660s adb shell am instrument -w -r -e class "$class" "$runner" >"$raw" 2>&1 || status=$?
if [[ "$status" -ne 0 ]] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
   ! grep -Fq 'OK (1 test)' "$raw"; then
  echo "MANGAALL_REAL_JS_CODE11|result=FAIL|exit=$status|one_exact_test=true" >&2
  tail -n 65 "$raw" >&2
  exit 5
fi
echo 'MANGAALL_REAL_JS_CODE11|offline=TRUE|real_three_source_modules=PASS|isolated_QuickJs_Binder=PASS|chapters=4341|mangafire_429=INCONCLUSIVE|source_get=ONE_LOCAL_INTERCEPT|unsigned_test_zip=ONLY|signature=UNCHANGED|canonical=NOT_GRANTED'
