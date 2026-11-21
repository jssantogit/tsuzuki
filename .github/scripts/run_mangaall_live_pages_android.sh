#!/usr/bin/env bash
# Manual-only live Android probe: original signed MangaAll code8 -> actual
# reading.pages -> Host loader -> optionally the actual ReaderActivity pixels.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly mode="${MANGAALL_GATE_MODE:-transport}"
# The legacy code8 gate stays the default. Signed code9 is a separate,
# manually authorized Android workflow and must be selected explicitly.
readonly signed_code="${MANGAALL_SIGNED_CODE:-8}"
case "$signed_code" in
  8|9) ;;
  *)
    echo 'LIVE_PAGE_HARD_STOP|reason=UNRECOGNIZED_SIGNED_CODE' >&2
    exit 2
    ;;
esac
case "$mode" in
  transport)
    test_case="eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllSignedCode${signed_code}LivePages_downloadAndDecodeAtMostTwo"
    ;;
  reader)
    test_case="eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#mangaAllPublishedCode${signed_code}LiveReaderDisplaysCdnPixels"
    ;;
  *)
    echo 'LIVE_PAGE_HARD_STOP|reason=INVALID_GATE_MODE' >&2
    exit 2
    ;;
esac
# Keep private per-title evidence only until the test invocation exits.
readonly output='.github/results/mangaall-live-pages-android'
mkdir -p "$output"

# Reject shell/meta injection before passing user-selected, observed IDs to adb.
[[ "${MANGAALL_WORK_ID:-}" =~ ^(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}$ ]] || {
  echo 'LIVE_PAGE_HARD_STOP|reason=INVALID_WORK_ID' >&2; exit 2;
}
[[ "${MANGAALL_CHAPTER_ID:-}" =~ ^[A-Za-z0-9._:-]{1,160}$ ]] || {
  echo 'LIVE_PAGE_HARD_STOP|reason=INVALID_CHAPTER_ID' >&2; exit 2;
}
[[ "${MANGAALL_LANGUAGE:-}" =~ ^[a-z]{2}(-[a-z]{2})?$ ]] || {
  echo 'LIVE_PAGE_HARD_STOP|reason=INVALID_LANGUAGE' >&2; exit 2;
}
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'LIVE_PAGE_HARD_STOP|reason=NOT_DISPOSABLE_EMULATOR' >&2
  exit 3
fi
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [ "${#target_apks[@]}" -ne 1 ] || [ "${#test_apks[@]}" -ne 1 ]; then
  echo 'LIVE_PAGE_HARD_STOP|reason=APK_NOT_UNIQUE' >&2
  exit 4
fi

raw="$output/instrumentation-private.txt"
cleanup() {
  # No URLs, remote headers, raw traces or page image bytes survive this run.
  rm -f "$raw"
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
adb shell pm clear "$app" | tr -d '\r' | grep -Fq 'Success'
status=0
timeout --foreground 340s adb shell am instrument -w -r \
  -e class "$test_case" \
  -e mangaAllLivePageOptIn true \
  -e mangaAllLiveReaderOptIn true \
  -e androidSourceSwitchOptIn true \
  -e providerWorkId "$MANGAALL_WORK_ID" \
  -e providerChapterId "$MANGAALL_CHAPTER_ID" \
  -e facetLanguage "$MANGAALL_LANGUAGE" \
  "$runner" > "$raw" 2>&1 || status=$?

# Export only an exact, closed vocabulary verdict (never arbitrary upstream text).
grep -oE 'INSTRUMENTATION_STATUS: stream=MANGAALL_LIVE_PAGE_GATE\|outcome=(INCONCLUSIVE\|reason=(CHAPTER_ATTESTATION_UNAVAILABLE|CHAPTER_NOT_ATTESTED_IN_FIRST_PAGE|CHAPTER_NOT_ATTESTED_IN_BOUND|CHAPTER_CURSOR_LIMIT|CHAPTER_AMBIGUOUS|CHAPTER_IDENTITY_NOT_COMPATIBLE_WITH_TEST_FIXTURE|CHAPTER_MAPPING_UNRESOLVED|CDN_OR_READER_UNAVAILABLE|SCREEN_PIXELS_NOT_ATTESTED|PROVIDER_PAGES_UNAVAILABLE|NO_HTTP_PAGES|CDN_FETCH_UNAVAILABLE|INVALID_IMAGE_DIMENSIONS|IMAGE_DECODE_FAILED)|PASS\|provenance=DIRECT_LIVE_ANDROID\|pagesDecoded=[12]\|readerActivityPixels=(NOT_TESTED|REAL_CDN_VISIBLE))' \
  "$raw" | sed 's/^INSTRUMENTATION_STATUS: stream=//' | tail -n 1 > "$output/verdict.txt" || true
if [ "$status" -ne 0 ] || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
   ! grep -Fq 'OK (1 test)' "$raw" || [ ! -s "$output/verdict.txt" ]; then
  echo "MANGAALL_LIVE_PAGE_GATE|outcome=FAIL|reason=INSTRUMENTATION_OR_CONTRACT|runnerExit=$status" >&2
  exit 1
fi
cat "$output/verdict.txt"
if ! grep -Fq 'outcome=PASS|' "$output/verdict.txt"; then
  echo 'MANGAALL_LIVE_PAGE_GATE|gate=NOT_ACCEPTED|reason=EXTERNAL_ENVIRONMENT_INCONCLUSIVE'
  exit 2
fi
if [ "$mode" = 'reader' ]; then
  grep -Fq 'readerActivityPixels=REAL_CDN_VISIBLE' "$output/verdict.txt" || exit 1
  echo 'MANGAALL_LIVE_PAGE_GATE|gate=ANDROID_REAL_CDN_READER_PIXELS_ACCEPTED'
else
  grep -Fq 'readerActivityPixels=NOT_TESTED' "$output/verdict.txt" || exit 1
  echo 'MANGAALL_LIVE_PAGE_GATE|gate=ANDROID_IMAGE_BYTES_ACCEPTED|readerActivityPixels=NOT_TESTED'
fi
