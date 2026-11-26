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
adb logcat -c
status=0
timeout --foreground 330s adb shell am instrument -w -r -e class "$class" -e advancedLive true "$runner" >"$raw" 2>&1 || status=$?
adb logcat -d -s AdvancedLiveGate:I | grep -Eo 'ADVANCED_LIVE\|[a-z-]+\|[a-z-]+\|[A-Z0-9_]+\|ms=[0-9]+|ADVANCED_CONTRACT\|(PASS|FAIL)|ADVANCED_CONTRACT_DETAIL\|[A-Z_]+\|(PASS|FAIL)|ADVANCED_CONTRACT_FAILURE\|[A-Z_]+\|(EXCEPTION|ASSERTION)|ADVANCED_RECOVERY\|[a-z-]+\|RUNTIME_DIED|ADVANCED_IDENTITY\|[a-z-]+\|[a-z-]+\|matches=[0-9]+\|excluded=[0-9]+\|eligible=[0-9]+' | tee "$out/verdicts.txt" || true
if [[ "$status" != 0 ]] || ! grep -Fq 'OK (1 test)' "$raw" || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"; then
  echo "ADVANCED_LIVE|instrumentation=FAIL|exit=$status" >&2
  exit 5
fi
python3 - "$out/verdicts.txt" <<'PY'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text().splitlines()
assert [line for line in lines if line.startswith("ADVANCED_CONTRACT|")] == ["ADVANCED_CONTRACT|PASS"]
rows = [line.split("|") for line in lines if line.startswith("ADVANCED_LIVE|")]
expected = {(source, work) for source in ("mangaall-ball", "mangafire") for work in ("death-note", "punpun", "akame")}
assert len(rows) == 6 and {(r[1], r[2]) for r in rows} == expected, "Missing or duplicate verdict"
assert all(len(r) == 5 and r[3] == "PASS" for r in rows), "Live gate not green"
PY
