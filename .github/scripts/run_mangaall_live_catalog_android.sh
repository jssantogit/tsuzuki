#!/usr/bin/env bash
# Opt-in live independent catalog gate. Never runs against a user's installed app.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly test_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaAllIndependentCatalogLiveInstrumentedTest#arbitraryWorksAreQueriedThroughProductionIndependentCatalog'
readonly out=".github/results/mangaall-live-catalog-${MANGAALL_SHARD_INDEX:?}"
mkdir -p "$out"
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'CATALOG_HARD_STOP|reason=NOT_DISPOSABLE_EMULATOR' >&2
  exit 2
fi
mapfile -t target_apks < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
if [ "${#target_apks[@]}" -ne 1 ] || [ "${#test_apks[@]}" -ne 1 ]; then
  echo 'CATALOG_HARD_STOP|reason=APK_NOT_UNIQUE' >&2
  exit 3
fi
cleanup() {
  # Raw upstream exception text may contain response URLs; never retain it as a CI artifact.
  rm -f "$out/instrumentation-internal.txt"
  adb shell am force-stop "$app" >/dev/null 2>&1 || true
  adb shell pm clear "$app" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb install -r "${target_apks[0]}" >/dev/null
adb install -r "${test_apks[0]}" >/dev/null
adb shell pm clear "$app" | tr -d '\r' | grep -Fq 'Success'
encoded="$(printf '%s' "${MANGAALL_WORKS:?}" | base64 -w0)"
raw="$out/instrumentation-internal.txt"
status=0
timeout --foreground 900s adb shell am instrument -w -r \
  -e class "$test_case" \
  -e mangaAllLiveCatalogOptIn true \
  -e worksBase64 "$encoded" \
  -e shardIndex "$MANGAALL_SHARD_INDEX" \
  -e shardCount "$MANGAALL_SHARD_COUNT" \
  "$runner" > "$raw" 2>&1 || status=$?
# Only publicly sourced IDs, closed status codes, bounded counts and title
# digests leave the disposable runner. Do not upload raw instrumentation logs.
grep -E 'MANGAALL_REAL_CATALOG(\||_BATCH\|)' "$raw" > "$out/catalog-report.txt" || true
grep -E 'INSTRUMENTATION_CODE:|INSTRUMENTATION_STATUS_CODE:|OK \([0-9]+ test' "$raw" > "$out/instrumentation-status.txt" || true
# Strictly validate the encoded real catalog identity/author claims before
# exporting them. No stdout from the HTTP client is retained by this script.
python3 .github/scripts/validate_mangaall_catalog_attestation.py \
  "$raw" "$out/catalog-attestations.json"
# The Android test runner does not always emit the optional 'OK (1 test)'
# summary. Its final code alone may also follow a failed assertion. Verify
# the actual per-test status, exact case IDs/order and real catalog attestation
# for every requested title before treating this stage as a valid capture.
# Inconclusive upstream CATALOG_UNAVAILABLE does NOT grant canonical identity.
python3 .github/scripts/mangaall_catalog_instrumentation_gate.py \
  "$raw" "$out/catalog-attestations.json" "$status"
rm -f "$raw"
