#!/usr/bin/env bash
# NONSHIPPING: fixed cohort of EXACTLY 30 Korean manhwa titles, split 10/10/10.
# GitHub-hosted Android emulator only. Real Host/QuickJS/Binder, source HIDs only.
# Never claims canonical binding, Reader pixels, production signing or release.
set -euo pipefail
umask 077
readonly shard="${1:?Provide frozen 30-work shard (0, 1 or 2)}"
if [[ ! "$shard" =~ ^[012]$ ]]; then
  echo 'MANGAALL_FROZEN30_ANDROID|outcome=DENIED|reason=INVALID_SHARD'
  exit 2
fi
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly test_class='eu.kanade.tachiyomi.provider.runtime.MangaAllFrozen30Code11AndroidLiveInstrumentedTest#frozen30_android_real_source_hid_chapter_continuations'
readonly consent='I_ACCEPT_MANGAALL_TWO_SOURCE_CODE11_ANDROID_LIVE'
readonly out=".github/results/mangaall-frozen30-code11-android-live/shard-$shard"
mkdir -p "$out"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo "MANGAALL_FROZEN30_ANDROID|outcome=DENIED|shard=$shard|reason=EMULATOR_REQUIRED" | tee "$out/verdict.txt"
  exit 3
fi
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t instrument < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [[ "${#target[@]}" -ne 1 || "${#instrument[@]}" -ne 1 ]]; then
  echo "MANGAALL_FROZEN30_ANDROID|outcome=DENIED|shard=$shard|reason=APK_INPUT_COUNT" | tee "$out/verdict.txt"
  exit 4
fi
cleanup() {
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "${target[0]}" >/dev/null
adb install -r "${instrument[0]}" >/dev/null
adb shell pm list instrumentation | tr -d '\r' | grep -Fq "instrumentation:$runner (target=$app)"
adb shell cmd connectivity airplane-mode disable >/dev/null
if [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '0' ]]; then
  echo "MANGAALL_FROZEN30_ANDROID|outcome=RED|shard=$shard|reason=NETWORK_UNAVAILABLE" | tee "$out/verdict.txt"
  exit 5
fi
log="$(mktemp)"
trap 'rm -f "$log"; cleanup' EXIT
status=0
timeout --foreground 1900s adb shell am instrument -w -r \
  -e mangaAllDualCode11LiveOptIn "$consent" \
  -e mangaAllFrozen30Shard "$shard" \
  -e class "$test_class" "$runner" >"$log" 2>&1 || status=$?
if [[ "$status" -ne 0 ]] ||
   ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$log" ||
   ! grep -Fq 'OK (1 test)' "$log" ||
   ! grep -Fq "MANGAALL_FROZEN30_ANDROID|outcome=PASS|shard=$shard|works=10" "$log"; then
  echo "MANGAALL_FROZEN30_ANDROID|outcome=RED|shard=$shard|reason=INSTRUMENTATION_FAILURE|processStatus=$status|canonical=NOT_TESTED|reader=NOT_TESTED|signed=NO" | tee "$out/verdict.txt"
  # Sanitized, bounded diagnosis: no source response, chapter/HID, exception,
  # URLs, cookies, headers or raw instrumentation log enters the artifact.
  grep -E 'MANGAALL_FROZEN30_ANDROID\|index=[0-9]+\|shard=[012]\|' "$log" |
    sed -E 's/\|rivalCandidates=[0-9]+/|rivalCandidates=REDACTED/g' |
    head -n 10 >"$out/progress.txt" || true
  exit 6
fi
echo "MANGAALL_FROZEN30_ANDROID|outcome=PASS|shard=$shard|works=10|scope=SOURCE_HID_ONLY|chapterOne=TESTED|terminal=TESTED|canonical=NOT_TESTED|reader=NOT_TESTED|signed=NO|mangafire=NOT_CALLED" | tee "$out/verdict.txt"
