#!/usr/bin/env bash
set -euo pipefail
umask 077
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = '1'
app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
test "${#app[@]}" = 1
test "${#tests[@]}" = 1
adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
mkdir -p .github/results/mangaball-native-http-parity
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
code=0
timeout 240s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaBallDevelopmentArchive_nativeHttpOfflineParity \
  -e mangaBallTransportParityOptIn I_ACCEPT_OFFLINE_TRANSPORT_PARITY \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner >"$raw" 2>&1 || code=$?
verdict=.github/results/mangaball-native-http-parity/verdict.txt
expected='MANGABALL_OFFLINE_PARITY|native=PASS|host=PASS|quickjs=PASS|limit=RESPONSE_LIMIT|denial=REJECTED|live=NOT_TESTED'
grep -Fo "$expected" "$raw" >"$verdict" || true
cat "$verdict"
test "$code" = 0
! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw"
grep -Fxq 'INSTRUMENTATION_CODE: -1' "$raw"
test "$(wc -l <"$verdict")" = 1
grep -Fxq "$expected" "$verdict"
