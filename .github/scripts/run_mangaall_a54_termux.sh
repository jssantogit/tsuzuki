#!/data/data/com.termux/files/usr/bin/bash
# One-phone Galaxy A54 experimental MangoALL WebView validation using Termux.
# Inputs: a ZIP downloaded directly from the reviewed GitHub Actions artifact.
# Never runs as root. Never clears or uninstalls official app.tsuzuki.
set -euo pipefail
umask 077

if [[ "${PREFIX:-}" != '/data/data/com.termux/files/usr' ]]; then
  echo 'MANGAALL_A54_TERMUX|error=RUN_IN_REAL_TERMUX' >&2
  exit 2
fi

for required in adb unzip curl timeout mktemp; do
  if ! command -v "$required" >/dev/null 2>&1; then
    echo "MANGAALL_A54_TERMUX|error=MISSING_$required" >&2
    echo 'No Termux: pkg update && pkg install -y android-tools unzip curl coreutils'
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

# The reviewed runner is downloaded as a text script; no Gradle or git needed.
runner='https://raw.githubusercontent.com/jssantogit/tsuzuki/probe/mangaall-a54-browser-profile-validation-20261122/.github/scripts/run_mangaall_a54_browser_profile.sh'
curl -fsSL --retry 2 --connect-timeout 15 --max-time 35 "$runner" -o "$workdir/runner.sh"
echo 'Executando APKs de debug + teste no mesmo A54, sem alterar o app oficial.'
echo 'Ao aparecer o MangaFire, conclua a verificação normal e toque em "Validar sessão".'
cd "$HOME"
MANGAALL_A54_SESSION_ACK=I_ACCEPT_MANGAFIRE_A54_INTERACTIVE_TEST \
MANGAALL_A54_ADB_SERIAL="$serial" \
MANGAALL_A54_APP_APK="${app_files[0]}" \
MANGAALL_A54_TEST_APK="${test_files[0]}" \
bash "$workdir/runner.sh"
echo "Relatório: $HOME/.github/results/mangaall-a54-profile/verdict.txt"
