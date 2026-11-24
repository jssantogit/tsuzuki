#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = '1'
app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
test "${#app[@]}" = 1
test "${#tests[@]}" = 1
adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
code=0
timeout 300s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaBallDevelopmentArchive_executesOfflineLookupAndChapters \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1 || code=$?
cat "$raw"
test "$code" = 0
! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw"
grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"
grep -Eq 'OK \(1 test\)' "$raw"
echo 'MANGABALL_PACKAGE_RUNTIME|lookup=3|chapters=3|denial=REJECTED|network=OFFLINE|reader=NOT_TESTED'
