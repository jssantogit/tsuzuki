#!/usr/bin/env bash
# LOCAL/PHYSICAL Galaxy A54 only. Never executes in Actions, emulators or app release.
# Install debug/test APKS alongside the signed app; do not clear/uninstall any user data.
set -euo pipefail
umask 077
readonly ack='I_ACCEPT_MANGAFIRE_A54_INTERACTIVE_TEST'
if [[ "${MANGAALL_A54_SESSION_ACK:-}" != "$ack" ]]; then
  echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=EXPLICIT_CONSENT_REQUIRED' >&2
  exit 2
fi
if ! command -v adb >/dev/null 2>&1; then
  echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=ADB_REQUIRED' >&2
  exit 2
fi
serials="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -n "${MANGAALL_A54_ADB_SERIAL:-}" ]]; then
  serial="$MANGAALL_A54_ADB_SERIAL"
  if [[ ! "$serial" =~ ^127[.]0[.]0[.]1:([0-9]{4,5})$ ]]; then
    echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=LOCALHOST_ADB_SERIAL_REQUIRED' >&2
    exit 2
  fi
  port="${BASH_REMATCH[1]}"
  if (( port < 1024 || port > 65535 )) || ! grep -Fxq "$serial" <<< "$serials"; then
    echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=ADB_CONNECTION_NOT_READY' >&2
    exit 2
  fi
else
  if [[ "$(printf '%s\n' "$serials" | sed '/^$/d' | wc -l)" -ne 1 ]]; then
    echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=EXACTLY_ONE_AUTHORIZED_DEVICE_REQUIRED' >&2
    exit 2
  fi
  serial="$serials"
fi
device() { adb -s "$serial" "$@"; }
qemu="$(device shell getprop ro.kernel.qemu | tr -d '\r')"
model="$(device shell getprop ro.product.model | tr -d '\r')"
if [[ "$qemu" == '1' || ! "$model" =~ ^SM-A546[A-Z0-9/-]*$ ]]; then
  echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=PHYSICAL_A54_REQUIRED' >&2
  exit 2
fi

readonly apk="${MANGAALL_A54_APP_APK:-}"
readonly test_apk="${MANGAALL_A54_TEST_APK:-}"
if [[ ! -f "$apk" || ! -f "$test_apk" ]]; then
  echo 'MANGAALL_A54_PROFILE|outcome=HARD_STOP|reason=DEBUG_AND_TEST_APKS_REQUIRED' >&2
  exit 2
fi
# Debug applicationId app.tsuzuki.dev is separate from official app.tsuzuki.
echo 'MANGAALL_A54_PROFILE|device=PHYSICAL_A54|installation=DEBUG_APP_SEPARATE|officialApp=UNTOUCHED'
device install -r "$apk" >/dev/null
device install -r "$test_apk" >/dev/null
readonly out=".github/results/mangaall-a54-profile"
mkdir -p "$out"
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT
echo 'Abra a tela MangaFire no A54, conclua somente a verificação normal do site e toque em "Validar sessão".'
exitcode=0
timeout --foreground 290s adb -s "$serial" shell am instrument -w -r \
  -e class 'eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaAllCode10HumanVerifiedBrowserProfile_checksRealSignedApi' \
  -e mangaAllA54SessionOptIn "$ack" \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1 || exitcode=$?
# Only closed, source-redacted diagnostic fields may leave the temporary output.
grep -oE 'MANGAALL_A54_PROFILE\|work=(punpun|koe)\|outcome=(SIGNED_API_JSON_VALID|INCONCLUSIVE)\|mangafireCandidates=[0-9]{1,2}\|browserApi=(VALID_JSON|BLOCKED_OR_UNVERIFIED)|MANGAALL_A54_PROFILE\|work=(punpun|koe)\|outcome=INCONCLUSIVE\|reason=SIGNED_API_REQUEST_NOT_OBSERVED\|mangafireCandidates=[0-9]{1,2}|MANGAALL_A54_PROFILE\|outcome=(BROWSER_SIGNED_API_ACCEPTED|INCONCLUSIVE)\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED|MANGAALL_A54_PROFILE\|outcome=INCONCLUSIVE\|reason=USER_VERIFICATION_NOT_COMPLETED' \
  "$raw" | sort -u > "$out/verdict.txt" || true
if [[ "$exitcode" -ne 0 ]] || ! grep -Fq 'INSTRUMENTATION_CODE: -1' "$raw" ||
  ! grep -Fq 'OK (1 test)' "$raw" || [[ ! -s "$out/verdict.txt" ]]; then
  echo 'MANGAALL_A54_PROFILE|outcome=FAIL|reason=INSTRUMENTATION_OR_REPORT_MISSING' >&2
  exit 1
fi
cat "$out/verdict.txt"
if ! grep -Fq 'outcome=BROWSER_SIGNED_API_ACCEPTED|signedJson=2' "$out/verdict.txt"; then
  echo 'MANGAALL_A54_PROFILE|gate=INCONCLUSIVE|canonical=NOT_TESTED|readerPixels=NOT_TESTED'
  exit 3
fi
echo 'MANGAALL_A54_PROFILE|gate=API_SESSION_VALIDATED|canonical=NOT_TESTED|readerPixels=NOT_TESTED'
