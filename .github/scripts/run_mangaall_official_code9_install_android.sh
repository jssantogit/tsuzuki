#!/usr/bin/env bash
# MANUAL gate only, after the actual Provider repository has a signed code9
# sequence-35 index. Never sign, install on physical devices or use live HTTP.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly test='eu.kanade.tachiyomi.provider.repository.MangaAllSignedUpgradeIsolationTest#officiallySignedCode9FromIndex35_installsWithPinnedRootAndAuditsThreeSources'
readonly output='.github/results/mangaall-code9-official-signed-install'
mkdir -p "$output"
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Only a disposable emulator is allowed' >&2
  exit 2
fi
target_count="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | wc -l)"
test_count="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | wc -l)"
if [ "$target_count" != 1 ] || [ "$test_count" != 1 ]; then
  echo 'ERROR: Exactly one app and test APK required' >&2
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
adb shell cmd connectivity airplane-mode enable >/dev/null
if [ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Offline emulator was not established' >&2
  exit 4
fi
adb shell pm clear "$app" | tr -d '\r' | grep -Fxq 'Success'
exit_code=0
timeout --foreground 450s adb shell am instrument -w -r \
  -e class "$test" \
  -e mangaAllSignedCode9OptIn true \
  "$runner" > "$output/acceptance.txt" 2>&1 || exit_code=$?
if [ "$exit_code" != 0 ] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$output/acceptance.txt" ||
   ! grep -Fq 'OK (1 test)' "$output/acceptance.txt"; then
  echo "MANGAALL_CODE9_SIGNED_HOST|outcome=FAIL|runnerExit=$exit_code" >&2
  grep -E 'INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE|FAILURES!!!|Tests run:' \
    "$output/acceptance.txt" || true
  exit 1
fi
printf 'MANGAALL_CODE9_SIGNED_HOST|index=OFFICIAL_P256_SEQUENCE35|installation=VERIFIED_LEDGER|quickjs=V2_READONLY|threeSources=ONE_LOOKUP_SYNTHETIC_HTTP|rollback=CODE8|cdn=NOT_TESTED|outcome=PASS\n'
