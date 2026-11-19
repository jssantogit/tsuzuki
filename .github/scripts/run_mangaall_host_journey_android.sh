#!/usr/bin/env bash
# Isolated code7 .tsz SCRIPT package and synthetic Host-to-Reader acceptance.
# Not an official signed install, production network or complete code7 Reader E2E.
set -euo pipefail
umask 077
readonly app='app.tsuzuki.dev'
readonly runner='app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner'
readonly output='.github/results/mangaall-host-journey-android'
readonly host_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllSyntheticJourney_reconcilesCanonicalChaptersAndReaderPages'
readonly code7_replay_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllCode7EmittedVectors_reconcileHostAndPreserveSourceIdentity'
readonly diverse_journey_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllDiverseJourney_reconcilesUpdatesReaderAndRejectsPartialSnapshots'
readonly code7_package_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllRealCode7Package_executesOfflineHttpCanonicalAndPages'
readonly signed_code6_case='eu.kanade.tachiyomi.provider.repository.MangaAllSignedUpgradeIsolationTest#publishedCode6IndexVerifiesWithItsActualPinnedRootAndInstallsOriginalArtifact'
readonly signed_upgrade_case='eu.kanade.tachiyomi.provider.repository.MangaAllSignedUpgradeIsolationTest#disposableSameRootCode6ToCode7UpdateRejectsTamperingAndRollsBackAtomically'
readonly ui_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition'
readonly continuous_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#mangaAllScriptSourceTapFailureAndRetryPublishesDecodedReaderPixels'
readonly real_code7_reader_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#mangaAllOriginalCode7TszSourceTap503RetryRendersCdnPixels'
readonly real_code8_reader_case='eu.kanade.tachiyomi.data.tsuzuki.instrumentation.CanonicalReaderSourceSwitchInstrumentedTest#mangaAllPublishedCode8TszSourceTap503RetryRendersCdnPixels'
readonly verdict_probe_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllLiveVerdictProtocol_emitsAndroidStatusStream'
readonly v2_readonly_probe_case='eu.kanade.tachiyomi.provider.reading.MangaAllLookupV2ReadOnlyDiagnosticsTest#mangaAllUnsignedV2ReadOnlyContract_rejectsCanonicalPromotionAndForeignSource'
readonly v2_cursor_probe_case='eu.kanade.tachiyomi.provider.reading.MangaAllLookupV2ReadOnlyDiagnosticsTest#mangaAllUnsignedV2CursorHistory_retainsThreeSourcesAcrossRetryAndRejectsConflicts'
readonly v2_cursor_budget_case='eu.kanade.tachiyomi.provider.reading.MangaAllLookupV2ReadOnlyDiagnosticsTest#mangaAllUnsignedV2CursorHistory_boundsEightPagesAndRejectsReplay'
readonly v2_host_invocation_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllV2Code9OptIn_routesOneReadOnlyDiagnosticAndRejectsWrongPackage'
readonly v2_host_cursor_case='eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllV2Code9OptIn_accumulatesActualRuntimePagesWithoutReaderGrants'
readonly v2_host_provenance_case='eu.kanade.tachiyomi.provider.reading.MangaAllLookupV2ReadOnlyDiagnosticsTest#mangaAllV2HostProvenance_rejectsCrossArtifactPagesAndForgedSignedClaim'
readonly source_row_case='eu.kanade.presentation.reader.MangaAllReaderSourceCardInstrumentedTest#tappingMangaAllSourcePassesExactVerifiedChapterOptionWithoutCrossSourceSelection'

mkdir -p "$output"
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Only disposable emulator supported' >&2
  exit 2
fi
target_count="$(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \) | wc -l)"
test_count="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | wc -l)"
if [ "$target_count" != 1 ] || [ "$test_count" != 1 ]; then
  echo 'ERROR: Expected exactly one APK per build' >&2
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
adb shell pm list instrumentation | tr -d '\r' | grep -Fq "instrumentation:$runner (target=$app)"
adb shell cmd connectivity airplane-mode enable >/dev/null
if [ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != '1' ]; then
  echo 'ERROR: Offline emulator was not established' >&2
  exit 4
fi
run_case() {
  local label="$1" test_method="$2" opt_in="$3" raw="$output/$1.txt" exit_code=0
  adb shell pm clear "$app" | tr -d '\r' | grep -Fq 'Success'
  timeout --foreground 360s adb shell am instrument -w -r \
    -e class "$test_method" \
    -e "$opt_in" true \
    "$runner" > "$raw" 2>&1 || exit_code=$?
  if [ "$exit_code" != 0 ] ||
     ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
     ! grep -Fq 'OK (1 test)' "$raw"; then
    printf 'MANGAALL_JOURNEY|phase=%s|outcome=FAIL|runnerExit=%s\n' "$label" "$exit_code" >&2
    # Real screenshot + UI accessibility tree from the disposable emulator
    # make the next "source not visible" failure diagnosable rather than
    # retrying APKs without evidence. Files are CI artifacts, not log stdout.
    timeout 12 adb exec-out screencap -p > "$output/$label.failure.png" 2>/dev/null || true
    timeout 20 adb shell uiautomator dump /sdcard/mangaall-window.xml >/dev/null 2>&1 || true
    timeout 12 adb exec-out cat /sdcard/mangaall-window.xml > "$output/$label.failure.xml" 2>/dev/null || true
    timeout 10 adb logcat -d -s AndroidRuntime:E ActivityTaskManager:W '*:S' > "$output/$label.failure-logcat.txt" 2>/dev/null || true
    grep -E 'INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE|FAILURES!!!|Tests run:' "$raw" || true
    return 1
  fi
  printf 'MANGAALL_JOURNEY|phase=%s|outcome=PASS\n' "$label"
}
# Dedicated feature-branch gate avoids rerunning the historic 10-phase suite
# every time the code8 Reader acceptance test is reviewed. The full original
# suite remains accessible through workflow_dispatch with default scope.
if [ "${MANGAALL_CODE8_ONLY:-0}" = "1" ]; then
  run_case 'LIVE_VERDICT_INSTRUMENTATION_STATUS_PROTOCOL' "$verdict_probe_case" 'mangaAllStatusProbeOptIn'
  if ! grep -Fxq 'INSTRUMENTATION_STATUS: stream=MANGAALL_STATUS_PROBE|outcome=VISIBLE' \
      "$output/LIVE_VERDICT_INSTRUMENTATION_STATUS_PROTOCOL.txt"; then
    echo 'MANGAALL_JOURNEY|phase=LIVE_VERDICT_INSTRUMENTATION_STATUS_PROTOCOL|outcome=FAIL|reason=STATUS_STREAM_MISSING' >&2
    exit 1
  fi
  for marker in \
    'INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_SOURCE|source=mangadex|outcome=READY|candidates=1|workId=mangadex:11111111-2222-4333-8444-555555555555|chapterId=mangadex:aaaa1111-2222-4333-8444-555555555555|language=en' \
    'INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_SOURCE|source=mangadot|outcome=AMBIGUOUS|candidates=2' \
    'INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_SOURCE|source=mangafire|outcome=MISSING|candidates=0' \
    'INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_AUDIT|outcome=COMPLETE|sources=3|titleParity=VARIANCE|authorParity=UNVERIFIED' \
    'INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_AUDIT|outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE|code=SOURCE_READ_ERROR|sourceFailure=CHALLENGE_BLOCKED|httpStatus=403|retryable=true'
  do
    if ! grep -Fxq "$marker" "$output/LIVE_VERDICT_INSTRUMENTATION_STATUS_PROTOCOL.txt"; then
      echo 'MANGAALL_JOURNEY|phase=LIVE_VERDICT_INSTRUMENTATION_STATUS_PROTOCOL|outcome=FAIL|reason=TRI_SOURCE_STATUS_MISSING' >&2
      exit 1
    fi
  done
  run_case 'UNSIGNED_V2_READONLY_TRI_SOURCE_CONTRACT' "$v2_readonly_probe_case" 'mangaAllStatusProbeOptIn'
  run_case 'UNSIGNED_V2_READONLY_CURSOR_HISTORY' "$v2_cursor_probe_case" 'mangaAllStatusProbeOptIn'
  run_case 'UNSIGNED_V2_CURSOR_BUDGET_AND_REPLAY' "$v2_cursor_budget_case" 'mangaAllStatusProbeOptIn'
  run_case 'UNSIGNED_CODE9_V2_HOST_INVOCATION_VERSION_GATED' "$v2_host_invocation_case" 'mangaAllStatusProbeOptIn'
  run_case 'UNSIGNED_CODE9_V2_HOST_RUNTIME_CURSOR_AUDIT' "$v2_host_cursor_case" 'mangaAllStatusProbeOptIn'
  run_case 'UNSIGNED_CODE9_V2_HOST_PROVENANCE_PINNING' "$v2_host_provenance_case" 'mangaAllStatusProbeOptIn'
  run_case 'CODE8_PUBLISHED_TSZ_CONTINUOUS_READER_TAP_503_RETRY_PIXELS' "$real_code8_reader_case" 'androidSourceSwitchOptIn'
  echo 'MANGAALL_JOURNEY|pipeline=PUBLISHED_CODE8_OFFLINE_READER|outcome=PASS|readerPixels=DECODED_SYNTHETIC_HTTP|liveCdn=NOT_TESTED'
  exit 0
fi
run_case 'SCRIPT_CANONICAL_HTTP_BYTES' "$host_case" 'mangaAllHostJourneyOptIn'
run_case 'MANGAALL_13_FORMAT_CANONICAL_REFRESH_AND_READER' "$diverse_journey_case" 'mangaAllHostJourneyOptIn'
run_case 'CODE7_JS_VECTORS_HOST_CANONICAL_AND_PAGES' "$code7_replay_case" 'mangaAllHostJourneyOptIn'
run_case 'CODE7_REAL_TSZ_SCRIPT_CANONICAL_PAGES' "$code7_package_case" 'mangaAllHostJourneyOptIn'
run_case 'OFFICIAL_SIGNED_CODE6_REPOSITORY_INSTALL' "$signed_code6_case" 'mangaAllSignedUpgradeOptIn'
run_case 'EPHEMERAL_SAME_ROOT_CODE6_TO_CODE7_UPGRADE_ROLLBACK' "$signed_upgrade_case" 'mangaAllSignedUpgradeOptIn'
run_case 'MANGAALL_SOURCE_ROW_TAP' "$source_row_case" 'mangaAllSourceRowOptIn'
run_case 'MANGAALL_CONTINUOUS_READER_TAP_RETRY_PIXELS' "$continuous_case" 'androidSourceSwitchOptIn'
run_case 'CODE7_REAL_TSZ_CONTINUOUS_READER_TAP_503_RETRY_PIXELS' "$real_code7_reader_case" 'androidSourceSwitchOptIn'
run_case 'CODE8_PUBLISHED_TSZ_CONTINUOUS_READER_TAP_503_RETRY_PIXELS' "$real_code8_reader_case" 'androidSourceSwitchOptIn'
run_case 'READER_UI_SOURCE_SWITCH_PIXELS' "$ui_case" 'androidSourceSwitchOptIn'
printf 'MANGAALL_JOURNEY|pipeline=CODE7_PACKAGE_AND_SYNTHETIC_HOST_READER|outcome=PASS|realReaderViewModel=TESTED|sourceTapPixels=CODE7_ORIGINAL_OFFLINE|code7JsOutputHostReplay=TESTED|code7OriginalTszAndroid=TESTED|code7ReaderActivityOffline=TESTED|officialCode6SignedInstall=TESTED|code7EphemeralSignatureUpgrade=TESTED|officialCode7Signature=NOT_TESTED|kotlinFormat=CI_REQUIRED|liveProvider=NOT_TESTED\\n'
