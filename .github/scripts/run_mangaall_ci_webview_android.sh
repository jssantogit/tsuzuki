#!/usr/bin/env bash
# Scoped, test-only Android emulator in GitHub Actions.
# No live MangaFire traffic unless the workflow authorizes it.
set -euo pipefail
umask 077

readonly ack='I_ACCEPT_MANGAFIRE_CI_WEBVIEW_LIVE'
readonly mode="${MANGAALL_CI_GATE_MODE:-api}"
if [[ "$mode" != 'api' && "$mode" != 'chapters' ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=INVALID_GATE_MODE' >&2
  exit 2
fi
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
if [[ "$mode" == 'chapters' ]]; then
  test_method='mangaAllCode10CiWebviewMangaFireChapters_checksRealPagination'
  pattern='MANGAALL_CI_CHAPTER\|phase=WARMUP\|outcome=(PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)|MANGAALL_CI_CHAPTER\|work=(punpun|koe)\|outcome=(READY|INCONCLUSIVE)\|pages=[0-4]\|count=([0-9]|[1-9][0-9]|[1-9][0-9]{2})\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)\|reason=(NONE|LOOKUP_AMBIGUOUS|LOOKUP_UNAVAILABLE|TIMEOUT|SOURCE_TOO_LARGE|SOURCE_READ_ERROR|OTHER|CURSOR_LIMIT|INVALID_IDENTITY|EMPTY|CHAPTER_ONE_NOT_ATTESTED)|MANGAALL_CI_CHAPTER\|outcome=(CHAPTERS_ACCEPTED|INCONCLUSIVE)\|ready=[0-2]\|works=2\|canonical=NOT_TESTED\|readerPixels=NOT_TESTED'
else
  test_method='mangaAllCode10CiWebviewSameProfile_checksRealSignedApi'
  pattern='MANGAALL_CI_WEBVIEW\|phase=STARTED|MANGAALL_CI_WEBVIEW\|phase=WARMUP\|outcome=(PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)|MANGAALL_CI_WEBVIEW\|work=(punpun|koe)\|api=(VALID_JSON|BLOCKED|NOT_OBSERVED)\|candidates=[0-9]{1,2}|MANGAALL_CI_WEBVIEW\|outcome=(CI_SIGNED_API_ACCEPTED|CI_INCONCLUSIVE)\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED'
fi
timeout --foreground 430s adb shell am instrument -w -r \
  -e class "eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#$test_method" \
  -e mangaAllCiWebviewOptIn "$ack" \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1 || code=$?

# Only explicit redacted records may leave the temporary instrument pipe.
grep -oE "$pattern" "$raw" | sort -u > "$out/verdict.txt" || true

if [[ "$code" -ne 0 ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_EXIT' >> "$out/verdict.txt"
elif grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$raw"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_TEST_FAILED' >> "$out/verdict.txt"
elif ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|reason=INSTRUMENTATION_INCOMPLETE' >> "$out/verdict.txt"
fi
cat "$out/verdict.txt"

if [[ "$mode" == 'chapters' ]]; then
  if [[ "$code" -eq 0 ]] &&
    grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" &&
    grep -Fq 'MANGAALL_CI_CHAPTER|outcome=CHAPTERS_ACCEPTED|ready=2|works=2' "$out/verdict.txt" &&
    grep -Fq 'MANGAALL_CI_CHAPTER|work=punpun|outcome=READY|' "$out/verdict.txt" &&
    grep -Fq 'MANGAALL_CI_CHAPTER|work=koe|outcome=READY|' "$out/verdict.txt"; then
    echo 'MANGAALL_CI_BROWSER_GATE|outcome=PASS|stage=CHAPTERS|signedCode10=UNCHANGED|canonical=NOT_TESTED|readerPixels=NOT_TESTED'
    exit 0
  fi
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=CHAPTERS|reason=CHAPTER_GATE_NOT_MET' >&2
  exit 3
fi

if [[ "$code" -eq 0 ]] &&
  grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|outcome=CI_SIGNED_API_ACCEPTED|signedJson=2|works=2|providerCandidateWorks=2' "$out/verdict.txt" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|work=punpun|api=VALID_JSON|candidates=' "$out/verdict.txt" &&
  grep -Fq 'MANGAALL_CI_WEBVIEW|work=koe|api=VALID_JSON|candidates=' "$out/verdict.txt"; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=PASS|stage=API|signedCode10=UNCHANGED|canonical=NOT_TESTED|chapters=NOT_TESTED|readerPixels=NOT_TESTED'
  exit 0
fi

echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=API|reason=SIGNED_API_GATE_NOT_MET' >&2
exit 3
