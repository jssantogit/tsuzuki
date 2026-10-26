#!/usr/bin/env bash
# Offline, opt-in, disposable-emulator-only ICE/EPUB adoption integration.
set -euo pipefail
umask 077

app='app.tsuzuki.dev'
runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.SelectedEpubAdoptionInstrumentedTest'
results='.github/results/ice-epub-android'

if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'ICE_EPUB_E2E|outcome=BLOCKED|reason=EMULATOR_REQUIRED' >&2
  exit 2
fi
mapfile -t app_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
if (( ${#app_apks[@]} != 1 || ${#test_apks[@]} != 1 )); then
  echo 'ICE_EPUB_E2E|outcome=BLOCKED|reason=APK_SET_UNEXPECTED' >&2
  exit 3
fi

cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
mkdir -p "$results"
adb install -r "${app_apks[0]}"
adb install -r "${test_apks[0]}"
adb shell pm list packages "$app" | tr -d '\r' | grep -Fxq "package:$app" || {
  echo 'ICE_EPUB_E2E|outcome=BLOCKED|reason=PACKAGE_UNEXPECTED' >&2
  exit 4
}

run_case() {
  local test_name="$1" output="$2" result=0
  adb shell pm clear "$app" | tr -d '\r' | grep -Fxq 'Success'
  timeout 330 adb shell am instrument -w -r \
    -e class "${class}#${test_name}" \
    -e iceEpubE2eOptIn true \
    "$runner" > "$results/$output" 2>&1 || result=$?
  if (( result != 0 )) ||
    ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$results/$output" ||
    ! grep -Eq '^OK \(1 test\)' "$results/$output"; then
    echo "ICE_EPUB_E2E|outcome=FAIL|case=$test_name|runner=$result" >&2
    # Emit only diagnostic headings and first exception lines (not full device logs).
    grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE|Tests run:|^INSTRUMENTATION_STATUS: stack=|^Error in ' \
      "$results/$output" | tail -n 12 || true
    return 1
  fi
  echo "ICE_EPUB_E2E|outcome=PASS|case=$test_name"
}

run_case contentUriImportsShadowThenExplicitAdoptionRoundTripsPortableReceiptWithoutReaderPages import-adopt-restore.txt
run_case malformedContentUriAndInterruptedStreamNeverMaterializeChapters failures.txt
echo 'ICE_EPUB_E2E|outcome=PASS|cases=2|network=NONE|production=UNTOUCHED'
