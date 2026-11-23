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
result="$(timeout --foreground 120s adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllCiBrowserVerdictContract_rejectsUnboundedOrUntrustedData \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner 2>&1)" || {
  echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=INSTRUMENTATION_EXIT' >&2
  exit 1
}
if ! grep -Fq 'INSTRUMENTATION_CODE: -1' <<<"$result"; then
  echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=FAIL|reason=TEST_DID_NOT_PASS' >&2
  exit 1
fi
echo 'MANGAALL_CI_SOURCE_ALLOWLIST|outcome=PASS|source_records=STRICT|network=NOT_STARTED'
