#!/usr/bin/env bash
# Disposable emulator-only Reader regression smoke gate.
set -euo pipefail
umask 077

app='app.tsuzuki.dev'
runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest'
nav_class='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalTitleSourceNavigationInstrumentedTest'

[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || {
  echo 'ERROR: Refusing to run destructive fixture outside an emulator' >&2
  exit 2
}
mapfile -t app_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | sort)
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort)
(( ${#app_apks[@]} == 1 && ${#test_apks[@]} == 1 )) || {
  echo 'ERROR: Expected exactly one debug APK and one test APK' >&2
  exit 3
}
cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb install -r "${app_apks[0]}"
adb install -r "${test_apks[0]}"
if ! adb shell pm list packages "$app" | tr -d '\r' | grep -Fxq "package:${app}"; then
  echo "ERROR: The installed debug package does not match ${app}" >&2
  exit 4
fi
adb shell pm clear "$app" | grep -Fq 'Success'

run_test() {
  local test_name="$1" arg_name="$2" output_file="$3"
  local exit_code=0
  timeout 330 adb shell am instrument -w -r \
    -e class "$test_name" \
    -e "$arg_name" true \
    "$runner" > "$output_file" 2>&1 || exit_code=$?
  # Redact the full Android instrumentation stream from CI logs. Keep only
  # the runner's conclusion; the raw log remains a short-lived CI artifact.
  if (( exit_code != 0 )) || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$output_file" ||
    ! grep -Eq 'OK \(1 test\)' "$output_file"; then
    echo "FAIL: Android instrumentation ${test_name} exit=${exit_code}" >&2
    grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE|Tests run:' "$output_file" || true
    return 1
  fi
  echo "PASS: ${test_name}"
}

mkdir -p .github/results/canonical-v2-android
run_test "${class}#sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition" \
  androidSourceSwitchOptIn .github/results/canonical-v2-android/source-switch.txt

adb shell pm clear "$app" | grep -Fq 'Success'
run_test "${class}#emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession" \
  androidSourceSwitchOptIn .github/results/canonical-v2-android/source-error.txt

adb shell pm clear "$app" | grep -Fq 'Success'
run_test "${class}#pageCountDifferenceClampsPositionToValidPage" \
  androidSourceSwitchOptIn .github/results/canonical-v2-android/page-clamp.txt

adb shell pm clear "$app" | grep -Fq 'Success'
run_test "${class}#activityRecreationRestoresObservedCanonicalPosition" \
  androidSourceSwitchOptIn .github/results/canonical-v2-android/reader-recreate.txt

adb shell pm clear "$app" | grep -Fq 'Success'
run_test "${class}#boundaryNextPreviousKeepsObservedCanonicalPageAndProgress" \
  androidSourceSwitchOptIn .github/results/canonical-v2-android/chapter-boundaries.txt

adb shell pm clear "$app" | grep -Fq 'Success'
run_test "${nav_class}#invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences" \
  androidNavigationOptIn .github/results/canonical-v2-android/navigation.txt
