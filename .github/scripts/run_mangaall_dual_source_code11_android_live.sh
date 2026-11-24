#!/usr/bin/env bash
# NONSHIPPING: exact single-work Android Host/QuickJS Binder live source probe.
# No signing, publishing, editorial binding or Reader pixels.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly case='eu.kanade.tachiyomi.provider.runtime.MangaAllFutureRealDualSourceCode11LiveInstrumentedTest#real_dual_source_unsigned_code11_bleach_real_network_chapters'
readonly consent='I_ACCEPT_MANGAALL_TWO_SOURCE_CODE11_ANDROID_LIVE'
readonly out='.github/results/mangaall-dual-source-code11-android-live'
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'MANGAALL_DUAL_ANDROID_LIVE|outcome=DENIED|reason=ANDROID_EMULATOR_REQUIRED' | tee "$out/verdict.txt"
  exit 2
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "${#target[@]}" -ne 1 || "${#instrument[@]}" -ne 1 ]]; then
  echo 'MANGAALL_DUAL_ANDROID_LIVE|outcome=DENIED|reason=APK_INPUT_COUNT' | tee "$out/verdict.txt"
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
adb shell cmd connectivity airplane-mode disable >/dev/null
if [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '0' ]]; then
  echo 'MANGAALL_DUAL_ANDROID_LIVE|outcome=RED|reason=NETWORK_NOT_AVAILABLE' | tee "$out/verdict.txt"
  exit 4
fi
log="$(mktemp)"
trap 'rm -f "$log"; cleanup' EXIT
status=0
timeout --foreground 860s adb shell am instrument -w -r \
  -e mangaAllDualCode11LiveOptIn "$consent" -e class "$case" "$runner" >"$log" 2>&1 || status=$?
if [[ "$status" -ne 0 ]] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$log" ||
   ! grep -Fq 'OK (1 test)' "$log"; then
  echo "MANGAALL_DUAL_ANDROID_LIVE|outcome=RED|reason=INSTRUMENTATION_FAILURE|processStatus=$status|canonical=NOT_TESTED|reader=NOT_TESTED|signed=NO" | tee "$out/verdict.txt"
  # Keep raw source/response/URL or Android diagnostics out of uploaded artifacts.
  exit 5
fi
# All chapter-count/cursor/ID assertions are inside the single exact Android
# instrumentation test. Its full successful completion is the pass criterion.
echo 'MANGAALL_DUAL_ANDROID_LIVE|outcome=PASS|scope=SOURCE_HID_ONLY|nativeSnapshot=TESTED|multipage=TESTED|terminal=TESTED|chapterOne=TESTED|canonical=NOT_TESTED|reader=NOT_TESTED|signed=NO|mangafire=NOT_CALLED' | tee "$out/verdict.txt"
