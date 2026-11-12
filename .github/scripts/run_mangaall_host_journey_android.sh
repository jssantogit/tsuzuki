#!/usr/bin/env bash
# Segmented synthetic MangaAll Host-to-Reader acceptance. NOT the real
# published package or the MangaAll source-row UI tap.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly output='.github/results/mangaall-host-journey-android'
readonly host_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllSyntheticJourney_reconcilesCanonicalChaptersAndReaderPages'
readonly ui_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition'
readonly source_row_case='eu.kanade.presentation.reader.MangaAllReaderSourceCardInstrumentedTest#tappingMangaAllSourcePassesExactVerifiedChapterOptionWithoutCrossSourceSelection'

mkdir -p "$output"
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Only disposable emulator supported' >&2
  exit 2
fi
target_count="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | wc -l)"
test_count="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | wc -l)"
if [ "$target_count" != 1 ] || [ "$test_count" != 1 ]; then
  echo 'ERROR: Expected exactly one APK per build' >&2
  exit 3
fi
target_apk="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | head -n 1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | head -n 1)"
cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "$target_apk" >/dev/null
adb install -r "$test_apk" >/dev/null
adb shell pm list packages "$app" | tr -d '\r' | grep -Fxq "package:$app"
adb shell pm list instrumentation | tr -d '\r' | grep -Fq "instrumentation:$runner (target=$app)"
adb shell cmd connectivity airplane-mode enable >/dev/null
if [ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Offline emulator was not established' >&2
  exit 4
fi
run_case() {
  local label="$1" test_method="$2" opt_in="$3" raw="$output/$1.txt" exit_code=0
  adb shell pm clear "$app" | tr -d '\r' | grep -Fq 'Success'
  timeout --foreground 360s adb shell am instrument -w -r \
    -e class "$test_method" \
    -e "$opt_in" true \
    "$runner" > "$raw" 2>&1 || exit_code=$?
  if [ "$exit_code" != 0 ] ||
     ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
     ! grep -Fq 'OK (1 test)' "$raw"; then
    printf 'MANGAALL_JOURNEY|phase=%s|outcome=FAIL|runnerExit=%s\n' "$label" "$exit_code" >&2
    grep -E 'INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE|FAILURES!!!|Tests run:' "$raw" || true
    return 1
  fi
  printf 'MANGAALL_JOURNEY|phase=%s|outcome=PASS\n' "$label"
}
run_case 'SCRIPT_CANONICAL_HTTP_BYTES' "$host_case" 'mangaAllHostJourneyOptIn'
run_case 'MANGAALL_SOURCE_ROW_TAP' "$source_row_case" 'mangaAllSourceRowOptIn'
run_case 'READER_UI_SOURCE_SWITCH_PIXELS' "$ui_case" 'androidSourceSwitchOptIn'
printf 'MANGAALL_JOURNEY|pipeline=SEGMENTED_SYNTHETIC|outcome=PASS|mangaAllSourceRowTap=TESTED|mangaAllViewModelIntegration=NOT_TESTED|signedPackage=NOT_TESTED|livePageImage=NOT_TESTED\n'
