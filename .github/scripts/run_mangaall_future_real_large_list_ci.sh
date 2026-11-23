#!/usr/bin/env bash
# Real MangaDot >2MiB, future unsigned code11 snapshot, Android CI only.
set -euo pipefail
umask 077
if [[ "${MANGAALL_LARGE_LIVE_ACK:-}" != I_ACCEPT_MANGAALL_LARGE_LIVE ]]; then
  echo 'MANGAALL_GRANULAR_LIVE_GATE|outcome=HARD_STOP|reason=ACK_REQUIRED' >&2
  exit 2
fi
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'MANGAALL_GRANULAR_LIVE_GATE|outcome=HARD_STOP|reason=EMULATOR_REQUIRED' >&2
  exit 2
fi
apps=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
if [[ ${#apps[@]} != 1 || ${#tests[@]} != 1 || ! -f "${apps[0]}" || ! -f "${tests[0]}" ]]; then
  echo 'MANGAALL_GRANULAR_LIVE_GATE|outcome=HARD_STOP|reason=APKS_MISSING' >&2
  exit 2
fi
adb install -r "${apps[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
out=.github/results/mangaall-granular-live
mkdir -p "$out"
raw=$(mktemp)
trap 'rm -f "$raw"' EXIT
code=0
timeout --foreground 520s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.runtime.MangaAllFutureRealThreeSourceCode11InstrumentedTest#real_mangadot_over_2mib_list_pages_through_android_host \
  -e mangaAllLargeLiveOptIn I_ACCEPT_MANGAALL_LARGE_LIVE \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1 || code=$?
# Never upload or print the raw instrument pipe; only the strictly closed verdict.
pattern='MANGAALL_GRANULAR_LIVE\|outcome=PASS\|body=OVER_2MIB\|pages=[0-9]{1,2}\|rows=[0-9]{1,5}\|requests=1\|cursor=TERMINAL\|code11=UNSIGNED\|canonical=NOT_TESTED'
grep -oE "$pattern" "$raw" > "$out/verdict.txt" || true
if [[ "$code" -ne 0 ]] || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" || \
  grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw" || \
  [[ "$(wc -l < "$out/verdict.txt")" -ne 1 ]]; then
  echo 'MANGAALL_GRANULAR_LIVE_GATE|outcome=INCONCLUSIVE|reason=ANDROID_OR_SOURCE_GATE' | tee "$out/verdict.txt"
  exit 3
fi
cat "$out/verdict.txt"
echo 'MANGAALL_GRANULAR_LIVE_GATE|outcome=PASS|source=MANGADOT|code11=UNSIGNED|canonical=NOT_TESTED|reader=NOT_TESTED'
