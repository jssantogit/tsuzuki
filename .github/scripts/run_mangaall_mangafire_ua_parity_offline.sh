#!/usr/bin/env bash
# Real Android Chromium identity check with no MangaFire/source traffic.
set -euo pipefail
umask 077

app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
if [[ ${#app[@]} -ne 1 || ${#tests[@]} -ne 1 ||
      ! -f "${app[0]}" || ! -f "${tests[0]}" ]]; then
  echo 'MANGAALL_UA_PARITY|outcome=FAIL|stage=APK_INPUTS'
  exit 2
fi
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo 'MANGAALL_UA_PARITY|outcome=FAIL|stage=EMULATOR_REQUIRED'
  exit 2
fi

adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
if ! timeout --foreground 90s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.runtime.MangaAllMangaFireUserAgentInstrumentationTest#mangaAllMangaFireDefaultHttpUserAgent_matchesRealChromiumWithoutCookies \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1; then
  echo 'MANGAALL_UA_PARITY|outcome=FAIL|stage=ANDROID_INSTRUMENTATION'
  exit 1
fi
if ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"; then
  echo 'MANGAALL_UA_PARITY|outcome=FAIL|stage=ANDROID_ASSERTIONS'
  exit 1
fi

echo 'MANGAALL_UA_PARITY|outcome=PASS|scope=MANGAFIRE_ONLY|realChromium=YES|cookies=NOT_EXPORTED|network=NOT_STARTED'
