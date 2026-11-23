#!/usr/bin/env bash
# Scoped, test-only Android emulator in GitHub Actions.
# No live MangaFire traffic unless the workflow authorizes it.
set -euo pipefail
umask 077

readonly ack='I_ACCEPT_MANGAFIRE_CI_WEBVIEW_LIVE'
readonly mode="${MANGAALL_CI_GATE_MODE:-api}"
if [[ "$mode" != 'api' && "$mode" != 'chapters' && "$mode" != 'three' && "$mode" != 'fire' && "$mode" != 'shape' ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=INVALID_GATE_MODE' >&2
  exit 2
fi
if [[ "$mode" == 'shape' && "${MANGAALL_CI_SHAPE_ACK:-}" != 'I_ACCEPT_MANGAFIRE_SHAPE_SOLVER_CI_LIVE' ]]; then
  echo 'MANGAALL_CI_BROWSER_GATE|outcome=HARD_STOP|reason=SHAPE_SOLVER_EXPLICIT_CONSENT_REQUIRED' >&2
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
if [[ "$mode" == 'chapters' || "$mode" == 'three' || "$mode" == 'fire' || "$mode" == 'shape' ]]; then
  if [[ "$mode" == 'shape' ]]; then
    test_method='mangaAllCode10CiWebviewShapeSolverThreeWorks_liveSourceAndChapterAudit'
  elif [[ "$mode" == 'three' || "$mode" == 'fire' ]]; then
    test_method='mangaAllCode10CiWebviewThreeWorks_liveSourceAndChapterAudit'
  else
    test_method='mangaAllCode10CiWebviewMangaFireChapters_checksRealPagination'
  fi
  pattern='MANGAALL_CI_SOURCE\|work=(punpun|koe|akame)\|source=(mangadex|mangadot|mangafire)\|candidates=[0-9]{1,2}\|lookup=(COMPLETE|PARTIAL)|MANGAALL_CI_CHAPTER\|phase=WARMUP\|outcome=(SKIPPED|PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)|MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP_FAILURE\|cause=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|UNCLASSIFIED_ERROR)\|http=(NONE|200|403|OTHER)|MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=API\|signedUrl=(OBSERVED|NOT_OBSERVED)\|http=(NONE|200|403|OTHER)\|transport=(HTTP|BROWSER|NONE)\|json=(VALIDATED|UNVERIFIED)|MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP\|outcome=(COMPLETE|CURSOR_LIMIT|READ_ERROR|TOO_LARGE|TIMEOUT|OTHER|INVALID_IDENTITY|IDENTITY_CONFLICT)\|pages=[0-8]\|candidates=[0-9]{1,2}|MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|outcome=(READY|INCONCLUSIVE)\|pages=[0-4]\|count=([0-9]|[1-9][0-9]|[1-9][0-9]{2})\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)\|reason=(NONE|LOOKUP_AMBIGUOUS|LOOKUP_UNAVAILABLE|LOOKUP_INCOMPLETE|TIMEOUT|SOURCE_TOO_LARGE|SOURCE_READ_ERROR|OTHER|CURSOR_LIMIT|INVALID_IDENTITY|EMPTY|CHAPTER_ONE_NOT_ATTESTED)|MANGAALL_CI_CHAPTER\|outcome=(CHAPTERS_ACCEPTED|INCONCLUSIVE)\|ready=[0-3]\|works=[23]\|canonical=NOT_TESTED\|readerPixels=NOT_TESTED|MANGAALL_CI_FIRE_SOURCE\|work=(punpun|koe|akame)\|lookup=(COMPLETE|INCOMPLETE)\|api=(VALIDATED|UNVERIFIED)\|chapters=(COMPLETE|INCOMPLETE)\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)\|outcome=(SOURCE_READY|INCONCLUSIVE)|MANGAALL_CI_FIRE_SOURCE\|outcome=(SOURCE_CHAPTERS_ACCEPTED|INCONCLUSIVE)\|ready=[0-3]\|works=[23]\|canonical=NOT_TESTED\|unified=NOT_PROVEN|MANGAALL_CI_LOOKUP_BLOCKER\|work=(punpun|koe|akame)\|source=MANGADOT\|reason=PAGE_FOUR_PENDING|MANGAALL_CI_BROWSER_FALLBACK\|work=(punpun|koe|akame)\|attempts=[0-9]\|outcome=(NOT_USED|SUCCEEDED|UPSTREAM_CHALLENGE|HTTP_REJECTED|TIMEOUT|NAVIGATION|TLS|RENDERER_GONE|WEBVIEW_UNSUPPORTED|SELECTOR_MISSING|CANCELLED|UNKNOWN|NO_RESULT)|MANGAALL_CI_SHAPE_SOLVER\|outcome=(SOLVED|UNSOLVED|UNSUPPORTED|HTTP_REJECTED|NAVIGATION_FAILED|TLS_REJECTED|RENDERER_EXITED|ASSET_INVALID|TIMEOUT)\|mode=DEBUG_ONLY|MANGAALL_CI_SHAPE_STAGE\|last=(NO_EVENT|DOCUMENT_READY|OPENCV_IMPORTED|OPENCV_IMPORT_FAILED|SOLVER_RUNNING|GENERATE_STARTED|GENERATE_OK|GENERATE_HTTP_ERROR|VERIFY_STARTED|VERIFY_OK|VERIFY_HTTP_ERROR)\|mode=DEBUG_ONLY|MANGAALL_CI_SHAPE_NATIVE\|last=(NO_EVENT|ACTIVITY_CREATED|ASSET_READY|PROFILE_READY|LISTENER_REGISTERED|LOAD_REQUESTED|PAGE_COMMITTED|PAGE_FINISHED)\|mode=DEBUG_ONLY|MANGAALL_CI_SHAPE_JS_PROBE\|result=(PENDING|BRIDGE_READY|BRIDGE_MISSING|JS_UNAVAILABLE)\|mode=DEBUG_ONLY|MANGAALL_CI_SHAPE_DOCUMENT\|origin=(PENDING|EXPECTED|SAME_SITE|OPAQUE|FOREIGN|JS_UNAVAILABLE)\|mode=DEBUG_ONLY|MANGAALL_CI_SHAPE_NAV\|phase=(START|COMMIT|FINISH)\|kind=(PENDING|EXPECTED|SAME_SITE|ABOUT|DATA|NO_URL|OTHER)\|mode=DEBUG_ONLY'
else
  test_method='mangaAllCode10CiWebviewSameProfile_checksRealSignedApi'
  pattern='MANGAALL_CI_WEBVIEW\|phase=STARTED|MANGAALL_CI_WEBVIEW\|phase=WARMUP\|outcome=(PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)|MANGAALL_CI_WEBVIEW\|work=(punpun|koe)\|api=(VALID_JSON|BLOCKED|NOT_OBSERVED)\|candidates=[0-9]{1,2}|MANGAALL_CI_WEBVIEW\|outcome=(CI_SIGNED_API_ACCEPTED|CI_INCONCLUSIVE)\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED'
fi
timeout_seconds=545
if [[ "$mode" == 'three' || "$mode" == 'fire' ]]; then timeout_seconds=760; fi
if [[ "$mode" == 'shape' ]]; then timeout_seconds=900; fi
timeout --foreground "${timeout_seconds}s" adb shell am instrument -w -r \
  -e class "eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#$test_method" \
  -e mangaAllCiWebviewOptIn "$ack" \
  -e mangaAllCiShapeOptIn "${MANGAALL_CI_SHAPE_ACK:-NOT_AUTHORIZED}" \
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

if [[ "$mode" == 'chapters' || "$mode" == 'three' || "$mode" == 'fire' || "$mode" == 'shape' ]]; then
  expected=2
  if [[ "$mode" == 'three' || "$mode" == 'fire' || "$mode" == 'shape' ]]; then expected=3; fi
  if [[ "$mode" == 'fire' || "$mode" == 'shape' ]]; then
    if [[ "$mode" == 'shape' ]]; then
      if ! grep -Fq 'MANGAALL_CI_SHAPE_SOLVER|outcome=SOLVED|mode=DEBUG_ONLY' "$out/verdict.txt"; then
        echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=SHAPE_SESSION|reason=SHAPE_NOT_CLEARED' >&2
        exit 3
      fi
    fi
    # All three sources were queried in the original signed router.
    # A healthy MangaFire never upgrades incomplete global coverage.
    if [[ "$code" -ne 0 ]] || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw"; then
      echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=MANGAFIRE_SOURCE|reason=INSTRUMENTATION_INCOMPLETE' >&2
      exit 3
    fi
    if python3 .github/scripts/mangaall_ci_fire_source_gate.py "$out/verdict.txt"; then
      echo 'MANGAALL_CI_BROWSER_GATE|outcome=PASS|stage=MANGAFIRE_SOURCE|works=3|signedCode10=UNCHANGED|unified=NOT_PROVEN|canonical=NOT_TESTED|readerPixels=NOT_TESTED'
      exit 0
    fi
    echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=MANGAFIRE_SOURCE|reason=SOURCE_EVIDENCE_NOT_COMPLETE' >&2
    exit 3
  fi
  valid_work_records=true
  for work in punpun koe; do
    if ! grep -Fq "MANGAALL_CI_CHAPTER|work=$work|outcome=READY|" "$out/verdict.txt"; then
      valid_work_records=false
    fi
  done
  if [[ "$mode" == 'three' ]]; then
    if ! grep -Fq 'MANGAALL_CI_CHAPTER|work=akame|outcome=READY|' "$out/verdict.txt"; then
      valid_work_records=false
    fi
    # Strictly require actual unified three-source lookup observations for
    # all 3 works; no provider HID or raw API URL leaves the test.
    for work in punpun koe akame; do
      for source in mangadex mangadot mangafire; do
        if ! grep -Fq "MANGAALL_CI_SOURCE|work=$work|source=$source|" "$out/verdict.txt"; then
          valid_work_records=false
        fi
      done
    done
  fi
  # The three-work mode additionally requires a 3x3 source provenance
  # matrix, a complete unique lookup, valid signed browser API and every
  # chapter cursor ending with chapter one attested.
  if [[ "$mode" == 'three' ]]; then
    if ! python3 .github/scripts/mangaall_ci_three_gate.py "$out/verdict.txt"; then
      echo 'MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=THREE_SOURCES|reason=EVIDENCE_NOT_COMPLETE' >&2
      exit 3
    fi
  fi
  if [[ "$code" -eq 0 ]] &&
    grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" &&
    grep -Fq "MANGAALL_CI_CHAPTER|outcome=CHAPTERS_ACCEPTED|ready=$expected|works=$expected" "$out/verdict.txt" &&
    [[ "$valid_work_records" == 'true' ]]; then
    echo "MANGAALL_CI_BROWSER_GATE|outcome=PASS|stage=CHAPTERS|works=$expected|signedCode10=UNCHANGED|canonical=NOT_TESTED|readerPixels=NOT_TESTED"
    exit 0
  fi
  echo "MANGAALL_CI_BROWSER_GATE|outcome=INCONCLUSIVE|stage=CHAPTERS|works=$expected|reason=CHAPTER_GATE_NOT_MET" >&2
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
