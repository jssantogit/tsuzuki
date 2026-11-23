#!/usr/bin/env bash
# Actual Android emulator for CLOSED instrumentation verdicts only.
# Does not start provider requests, Browser Host or external HTTP.
set -euo pipefail
umask 077
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=EMULATOR_REQUIRED' >&2
  exit 2
fi
app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
if [[ ${#app[@]} -ne 1 || ${#tests[@]} -ne 1 ||
      ! -f "${app[0]}" || ! -f "${tests[0]}" ]]; then
  echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=APK_INPUTS_MISSING' >&2
  exit 2
fi
adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
run_offline_test() {
  local method="$1"
  local result
  result="$(timeout --foreground 120s adb shell am instrument -w -r \
    -e class "eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#${method}" \
    app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner 2>&1)" || {
    echo "MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=INSTRUMENTATION_EXIT|test=$method" >&2
    exit 1
  }
  if ! grep -Fq 'INSTRUMENTATION_CODE: -1' <<<"$result"; then
    echo "MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=TEST_DID_NOT_PASS|test=$method" >&2
    exit 1
  fi
}
# Cold-start differential: the FIRST real Chromium instrumentation probe
# uses live WebSettings, but intercepts ALL requests before any remote access.
# Test the loaded document's final native URL class and JavaScript origin.
run_offline_test 'mangaAllCiShapeSolverExactAsset_interceptOfflineWithoutNetwork'
# Negative control: a data: URL has opaque origin and must NEVER receive
# the HTTPS-only bridge; the verdict is typed, not a 100s blind timeout.
run_offline_test 'mangaAllCiShapeSolverExactAsset_opaqueOriginFailsClosedOffline'
run_offline_test 'mangaAllCiBrowserVerdictContract_rejectsUnboundedOrUntrustedData'
run_offline_test 'mangaAllCiMangaFire403Classifier_offlineResponseOnly'
run_offline_test 'mangaAllCiShapeNativeNavigationClassifier_offline'
run_offline_test 'mangaAllCiShapeSolverBridge_offlineWithoutNetwork'
# Second differential: the exact 921-line HTML asset with blockNetworkLoads.
run_offline_test 'mangaAllCiShapeSolverExactAsset_offlineWithoutNetwork'
echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=PASS|source_records=STRICT|bridge=REAL_CHROMIUM|exact_asset=FIRST_JS_STAGE|intercepted_live_setting=PASS|network=NOT_STARTED'
