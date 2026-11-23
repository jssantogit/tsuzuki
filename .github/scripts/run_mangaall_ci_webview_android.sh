#!/usr/bin/env bash
# Scoped, test-only Android emulator in GitHub Actions.
# No live MangaFire traffic unless the workflow authorizes it.
set -euo pipefail
umask 077

readonly ack='I_ACCEPT_MANGAFIRE_CI_WEBVIEW_LIVE'
if [[ "${MANGAALL_CI_WEBVIEW_ACK:-}" != "$ack" ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=EXPLICIT_CONSENT_REQUIRED' >&2
  exit 2
fi
if ! command -v adb >/dev/null 2>&1; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=ADB_REQUIRED' >&2
  exit 2
fi
qemu="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$qemu" != '1' ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=EMULATOR_REQUIRED' >&2
  exit 2
fi
echo 'MANGAALL_CI_BROWSER_GATE|environment=ANDROID_EMULATOR|signedCode10=EXACT|officialApp=UNTOUCHED'

app=(app/build/outputs/apk/debug/*universal*.apk)
tests=(app/build/outputs/apk/androidTest/debug/*.apk)
if [[ ${#app[@]} -ne 1 || ${#tests[@]} -ne 1 || ! -f "${app[0]}" || ! -f "${tests[0]}" ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=APK_INPUTS_MISSING' >&2
  exit 2
fi
adb install -r "${app[0]}" >/dev/null
adb install -r "${tests[0]}" >/dev/null
readonly out=".github/results/mangaall-ci-webview"
mkdir -p "$out"
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
code=0
timeout --foreground 355s adb shell am instrument -w -r \
  -e class 'eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllCode10CiWebviewSameProfile_checksRealSignedApi' \
  -e mangaAllCiWebviewOptIn "$ack" \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1 || code=$?

# Only explicit redacted records may leave the temporary instrument pipe.
grep -oE \
  'MANGAALL_CI_WEBVIEW\|phase=STARTED|MANGAALL_CI_WEBVIEW\|phase=WARMUP\|outcome=(PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)|MANGAALL_CI_WEBVIEW\|work=(punpun|koe)\|api=(VALID_JSON|BLOCKED|NOT_OBSERVED)\|candidates=[0-9]{1,2}|MANGAALL_CI_WEBVIEW\|outcome=(CI_SIGNED_API_ACCEPTED|CI_INCONCLUSIVE)\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED' \
  "$raw" | sort -u > "$out/verdict.txt" || true

if [[ "$code" -ne 0 ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_EXIT' >> "$out/verdict.txt"
elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_TEST_FAILED' >> "$out/verdict.txt"
elif ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_INCOMPLETE' >> "$out/verdict.txt"
fi
cat "$out/verdict.txt"

if [[ "$code" -eq 0 ]] &&
  grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|outcome=CI_SIGNED_API_ACCEPTED|signedJson=2|works=2|providerCandidateWorks=2' "$out/verdict.txt" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|work=punpun|api=VALID_JSON|candidates=' "$out/verdict.txt" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|work=koe|api=VALID_JSON|candidates=' "$out/verdict.txt"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=PASS|signedCode10=UNCHANGED|canonical=NOT_TESTED|chapters=NOT_TESTED|readerPixels=NOT_TESTED'
  exit 0
fi

echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=SIGNED_API_GATE_NOT_MET' >&2
exit 3
