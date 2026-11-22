#!/usr/bin/env bash
# NONSHIPPING: exact synthetic 4,341-row Host Binder acceptance on disposable Android emulator.
# No signed code11 install, external MangaDot/Reader/CDN traffic or publication.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly test_class='eu.kanade.tachiyomi.provider.runtime.MangaAllFutureBinderSnapshotInstrumentedTest#android_host_stub_paginates_4341_rows_and_code10_never_fetches'
readonly out='.github/results/mangaall-future-binder-emulator'
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_FUTURE_BINDER_ANDROID|emulator=REQUIRED|outcome=DENIED' >&2
  exit 2
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "${#target[@]}" -ne 1 || "${#instrument[@]}" -ne 1 ]]; then
  echo 'MANGAALL_FUTURE_BINDER_ANDROID|apk_count=INVALID|outcome=DENIED' >&2
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
  echo 'MANGAALL_FUTURE_BINDER_ANDROID|airplane_mode=REQUIRED|outcome=DENIED' >&2
  exit 4
fi
raw="$out/instrumentation.txt"
status=0
timeout --foreground 540s adb shell am instrument -w -r -e class "$test_class" "$runner" >"$raw" 2>&1 || status=$?
if [[ "$status" -ne 0 ]] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
   ! grep -Fq 'OK (1 test)' "$raw"; then
  echo "MANGAALL_FUTURE_BINDER_ANDROID|result=FAIL|status=$status|test=EXACT_ONE" >&2
  tail -n 55 "$raw" >&2
  exit 5
fi
echo 'MANGAALL_FUTURE_BINDER_ANDROID|offline=TRUE|AIDL_HOST_STUB=EXECUTED|source_rows=4341|code10=DENIED|live_upstream=NONE|signature=UNCHANGED|result=PASS'
