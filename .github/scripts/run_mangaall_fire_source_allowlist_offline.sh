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
    echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=INSTRUMENTATION_EXIT' >&2
    exit 1
  }
  if ! grep -Fq 'INSTRUMENTATION_CODE: -1' <<<"$result"; then
    echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=TEST_DID_NOT_PASS' >&2
    exit 1
  fi
}
run_offline_test 'mangaAllCiBrowserVerdictContract_rejectsUnboundedOrUntrustedData'
run_offline_test 'mangaAllCiShapeSolverBridge_offlineWithoutNetwork'
# The real 921-line solver asset must load and emit its first JS stage in
# the same Chromium profile while *all* HTTP requests are blocked.
run_offline_test 'mangaAllCiShapeSolverExactAsset_offlineWithoutNetwork'
echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=PASS|source_records=STRICT|bridge=REAL_CHROMIUM|exact_asset=FIRST_JS_STAGE|network=NOT_STARTED'
