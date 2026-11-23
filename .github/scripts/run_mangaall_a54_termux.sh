#!/data/data/com.termux/files/usr/bin/bash
# One-phone Galaxy A54 experimental MangaALL WebView validation using Termux.
# Inputs: a ZIP downloaded directly from the reviewed GitHub Actions artifact.
# Never runs as root. Never clears or uninstalls official app.tsuzuki.
set -euo pipefail
umask 077

if [[ "${PREFIX:-}" != '/data/data/com.termux/files/usr' ]]; then
  echo 'MANGAALL_A54_TERMUX|error=RUN_IN_REAL_TERMUX' >&2
  exit 2
fi

for required in adb unzip timeout mktemp; do
  if ! command -v "$required" >/dev/null 2>&1; then
    echo "MANGAALL_A54_TERMUX|error=MISSING_$required" >&2
    echo 'No Termux: apt update && apt full-upgrade && apt install android-tools unzip coreutils'
    exit 2
  fi
done

zipfile="${1:-$HOME/storage/downloads/mangaall-a54-debug-profile-test-apks.zip}"
if [[ ! -f "$zipfile" ]]; then
  echo 'MANGAALL_A54_TERMUX|error=GITHUB_ARTIFACT_ZIP_MISSING'
  echo "Baixe o artefato mangaall-a54-debug-profile-test-apks da CI e use:"
  echo "bash $0 /caminho/real/do/arquivo.zip"
  exit 2
fi

if [[ ! -e "$HOME/storage/downloads" ]]; then
  echo 'MANGAALL_A54_TERMUX|error=GRANT_TERMUX_STORAGE_PERMISSION'
  echo 'Execute termux-setup-storage e autorize o acesso, sem apagar arquivos.'
  exit 2
fi

echo 'Depuração sem fio deve estar ligada no A54. Se ainda não pareou o Termux,'
echo 'abra as Configurações em tela dividida com o Termux, use'
echo 'Opções do desenvolvedor > Depuração sem fio > Parear com código.'
read -r -p 'Já pareou Termux com o telefone? [s/N] ' paired
if [[ "$paired" != 's' && "$paired" != 'S' ]]; then
  read -r -p 'Porta de EMPARELHAMENTO (a exibida junto ao código): ' pair_port
  if [[ ! "$pair_port" =~ ^[0-9]{4,5}$ ]] || (( 10#$pair_port < 1024 || 10#$pair_port > 65535 )); then
    echo 'MANGAALL_A54_TERMUX|error=INVALID_PAIRING_PORT' >&2
    exit 2
  fi
  echo 'Digite o código apenas no prompt do adb (não será registrado em arquivo).'
  adb pair "127.0.0.1:$pair_port"
fi

echo 'A porta de CONEXÃO fica na tela principal de Depuração sem fio.'
echo 'Ela é diferente da porta de pareamento.'
read -r -p 'Porta de CONEXÃO (IP e porta da Depuração sem fio): ' connect_port
if [[ ! "$connect_port" =~ ^[0-9]{4,5}$ ]] || (( 10#$connect_port < 1024 || 10#$connect_port > 65535 )); then
  echo 'MANGAALL_A54_TERMUX|error=INVALID_CONNECTION_PORT' >&2
  exit 2
fi
serial="127.0.0.1:$connect_port"
adb connect "$serial"
if [[ "$(adb -s "$serial" get-state 2>/dev/null)" != 'device' ]]; then
  echo 'MANGAALL_A54_TERMUX|error=LOCALHOST_ADB_NOT_CONNECTED' >&2
  exit 2
fi

workdir="$(mktemp -d "$HOME/mangaall-a54.XXXXXXXX")"
cleanup() {
  rm -rf -- "$workdir"
}
trap cleanup EXIT

echo 'Extraindo somente o artefato da CI para uma pasta temporária...'
unzip -q "$zipfile" -d "$workdir/apks"
mapfile -t app_files < <(find "$workdir/apks" -type f -iname '*universal*.apk')
mapfile -t test_files < <(find "$workdir/apks" -type f -iname '*androidtest*.apk')
if (( ${#app_files[@]} != 1 || ${#test_files[@]} != 1 )); then
  echo 'MANGAALL_A54_TERMUX|error=APK_FILES_NOT_UNIQUE' >&2
  find "$workdir/apks" -type f -name '*.apk' -printf '%f\n'
  exit 2
fi

# Offline after download: embed the reviewed local A54 runner in this file.
# No runtime network dependency, curl/wget, Gradle or git.
cat > "$workdir/runner.sh" <<'MANGAALL_A54_REVIEWED_RUNNER'
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
MANGAALL_A54_REVIEWED_RUNNER
echo 'Executando APKs de debug + teste no mesmo A54, sem alterar o app oficial.'
echo 'Ao aparecer o MangaFire, conclua a verificação normal e toque em "Validar sessão".'
cd "$HOME"
MANGAALL_A54_SESSION_ACK=I_ACCEPT_MANGAFIRE_A54_INTERACTIVE_TEST \
MANGAALL_A54_ADB_SERIAL="$serial" \
MANGAALL_A54_APP_APK="${app_files[0]}" \
MANGAALL_A54_TEST_APK="${test_files[0]}" \
bash "$workdir/runner.sh"
echo "Relatório: $HOME/.github/results/mangaall-a54-profile/verdict.txt"
