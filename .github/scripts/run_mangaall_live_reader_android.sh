#!/usr/bin/env bash
# Signed code8 + real independent catalog + real Android Reader image pixels.
# Never execute on physical devices, never upload raw stdout/HTTP or images.
set -euo pipefail
umask 077

readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly test_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaAllLiveAndroidReaderInstrumentedTest#signedMangaAllRealReaderPagesRenderInDisposableAndroidEmulator'
readonly out=".github/results/mangaall-live-reader-${MANGAALL_SHARD_INDEX:?}"
mkdir -p "$out"
readonly raw="$out/instrumentation-internal.txt"
readonly work_file="$out/input-internal.txt"
cleanup() {
  rm -f "$raw" "$work_file"
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
  # Instrumentation state is sandbox-only; remove the test package too.
  adb uninstall "$app.test" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'MANGAALL_LIVE_READER_HARD_STOP|NOT_DISPOSABLE_EMULATOR' >&2
  exit 2
fi
mapfile -t targets < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t tests < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [ "${#targets[@]}" -ne 1 ] || [ "${#tests[@]}" -ne 1 ]; then
  echo 'MANGAALL_LIVE_READER_HARD_STOP|APK_NOT_UNIQUE' >&2
  exit 3
fi
if ! [[ "${MANGAALL_SIGNED_SHA256:?}" =~ ^[a-f0-9]{64}$ ]]; then
  echo 'MANGAALL_LIVE_READER_HARD_STOP|PACKAGE_SHA_INVALID' >&2
  exit 4
fi
printf '%s' "${MANGAALL_WORKS:?}" > "$work_file"
adb install -r "${targets[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
adb shell pm clear "$app" | tr -d '\r' | grep -Fq 'Success'
encoded="$(base64 -w0 < "$work_file")"
code=0
timeout --foreground 2100s adb shell am instrument -w -r \
  -e class "$test_case" \
  -e mangaAllLiveReaderOptIn true \
  -e signedPackageSha256 "$MANGAALL_SIGNED_SHA256" \
  -e worksBase64 "$encoded" \
  -e shardIndex "$MANGAALL_SHARD_INDEX" \
  -e shardCount "$MANGAALL_SHARD_COUNT" \
  "$runner" > "$raw" 2>&1 || code=$?

# Validated closed codes ONLY. Original logs may contain HTTP exceptions,
# source URLs, cookies, image bytes or freeform titles: never upload them.
python3 .github/scripts/validate_mangaall_live_reader.py \
  "$raw" "$work_file" "$MANGAALL_SHARD_INDEX" "$MANGAALL_SHARD_COUNT" \
  "$out/reader-verdict.json" || {
    echo 'MANGAALL_LIVE_READER_GATE|INVALID_OR_MISSING_REPORT' >&2
    exit 5
  }
if [ "$code" -ne 0 ] || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
  ! grep -Fq 'OK (1 test)' "$raw"; then
  printf 'MANGAALL_LIVE_READER_GATE|INCONCLUSIVE_OR_FAILED|shard=%s|instrumentationExit=%s\n' \
    "$MANGAALL_SHARD_INDEX" "$code" >&2
  exit 1
fi
printf 'MANGAALL_LIVE_READER_GATE|PASS|shard=%s|scope=ACTUAL_READER_VIEW_PIXELS\n' \
  "$MANGAALL_SHARD_INDEX"
