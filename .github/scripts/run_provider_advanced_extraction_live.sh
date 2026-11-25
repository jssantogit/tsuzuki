#!/usr/bin/env bash
# NONSHIPPING: unsigned research source ZIP embedded in androidTest only.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly class='eu.kanade.tachiyomi.provider.runtime.ProviderAdvancedExtractionLiveInstrumentedTest#threeKnownWorksBothSources'
readonly out='.github/results/advanced-live'
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'PROVIDER_ADVANCED_EXTRACTION|emulator=REQUIRED|outcome=DENIED' >&2
  exit 2
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "${#target[@]}" -ne 1 || "${#instrument[@]}" -ne 1 ]]; then
  echo 'PROVIDER_ADVANCED_EXTRACTION|apk_count=INVALID|outcome=DENIED' >&2
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
raw="$(mktemp)"
trap 'rm -f "$raw"; cleanup' EXIT
approved=false
if [[ "${MANGABALL_API_APPROVED:-false}" == true ]]; then approved=true; fi
adb logcat -c
status=0
timeout --foreground 330s adb shell am instrument -w -r -e class "$class" -e advancedLive true -e mangaBallApiApproved "$approved" "$runner" >"$raw" 2>&1 || status=$?
adb logcat -d -s AdvancedLiveGate:I | grep -Eo 'ADVANCED_LIVE\|[a-z-]+\|[a-z-]+\|[A-Z0-9_]+\|ms=[0-9]+' | tee "$out/verdicts.txt" || true
[[ "$status" == 0 ]] && grep -Fq 'OK (1 test)' "$raw" && grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"
[[ "$(grep -c '|PASS|' "$out/verdicts.txt")" == 6 ]]
