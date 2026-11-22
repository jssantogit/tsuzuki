#!/usr/bin/env bash
# Test-only synthetic unsigned future code11 ZIP. No Provider installation,
# signing, external MangaDot traffic, CDN/Reader, or production data changes.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly class='eu.kanade.tachiyomi.provider.runtime.MangaAllFutureCode11InterprocessInstrumentedTest#unsigned_code11_paginates_4341_rows_over_real_isolated_quickjs_binder'
readonly out='.github/results/mangaall-future-code11-real-ipc'
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_CODE11_IPC|emulator=REQUIRED|outcome=DENIED' >&2
  exit 2
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "${#target[@]}" -ne 1 || "${#instrument[@]}" -ne 1 ]]; then
  echo 'MANGAALL_CODE11_IPC|apk_count=INVALID|outcome=DENIED' >&2
  exit 3
fi
cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "${target[0]}" >/dev/null
adb install -r "${instrument[0]}" >/dev/null
adb shell pm list instrumentation | tr -d '\r' | grep -Fq "instrumentation:$runner (target=$app)"
adb shell cmd connectivity airplane-mode enable >/dev/null
if [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_CODE11_IPC|airplane_mode=REQUIRED|outcome=DENIED' >&2
  exit 4
fi
raw="$out/instrumentation.txt"
status=0
timeout --foreground 660s adb shell am instrument -w -r -e class "$class" "$runner" >"$raw" 2>&1 || status=$?
if [[ "$status" -ne 0 ]] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
   ! grep -Fq 'OK (1 test)' "$raw"; then
  echo "MANGAALL_CODE11_IPC|result=FAIL|exit=$status|one_exact_test=true" >&2
  tail -n 70 "$raw" >&2
  exit 5
fi
echo 'MANGAALL_CODE11_IPC|offline=TRUE|synthetic_unsigned_zip=ONLY|isolated_QuickJs_Binder=PASS|chapters=4341|code10=DENIED|reading_pages=DENIED|source_get=ONE_LOCAL_INTERCEPT|signature=UNCHANGED'
