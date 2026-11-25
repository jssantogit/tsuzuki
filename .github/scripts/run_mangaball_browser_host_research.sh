#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = '1'
app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
test "${#app[@]}" = 1
test "${#tests[@]}" = 1
adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
mkdir -p .github/results/mangaball-browser-transport
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
code=0
timeout 240s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.runtime.MangaBallBrowserHostResearchTest#threeTitleNormalBrowserTransport \
  -e mangaBallBrowserOptIn I_ACCEPT_MANGABALL_BROWSER_TRANSPORT \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner >"$raw" 2>&1 || code=$?
verdict=.github/results/mangaball-browser-transport/verdict.txt
grep -oE 'MANGABALL_BROWSER\|index=[1-3]\|outcome=(VALID_JSON|UPSTREAM_CHALLENGE|HTTP_REJECTED|TIMEOUT|BROWSER_FAILED|INVALID_JSON)\|rows=(-1|[0-9]|1[0-9]|2[0-4])|MANGABALL_BROWSER_SUMMARY\|accepted=[0-3]\|expected=3\|chapters=NOT_TESTED\|reader=NOT_TESTED' "$raw" | sort -u > "$verdict" || true
cat "$verdict"
test "$code" = 0
! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw"
grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"
grep -Fxq 'MANGABALL_BROWSER_SUMMARY|accepted=3|expected=3|chapters=NOT_TESTED|reader=NOT_TESTED' "$verdict"
for index in 1 2 3; do
  grep -Eq "^MANGABALL_BROWSER\\|index=$index\\|outcome=VALID_JSON\\|rows=([0-9]|1[0-9]|2[0-4])$" "$verdict"
done
