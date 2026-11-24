#!/usr/bin/env bash
# Independent MangaDex diagnostic. No raw logs, URLs or chapter IDs retained.
set -euo pipefail
umask 077
shard="${1:?Provide shard 0, 1 or 2}"
[[ "$shard" =~ ^[012]$ ]]
pixels="${2:-0}"
[[ "$pixels" =~ ^[01]$ ]]
app=app.tsuzuki.dev
out=".github/results/mangaall-frozen30-mangadex-audit/shard-$shard"
mkdir -p "$out"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mapfile -t target < <(find app/build/outputs/apk/debug -type f \( -name '*universal*.apk' -o -name 'app-debug.apk' \))
mapfile -t tests < <(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk')
[[ "${#target[@]}" -eq 1 && "${#tests[@]}" -eq 1 ]]
log="$(mktemp)"
trap 'rm -f "$log"; adb shell am force-stop "$app" >/dev/null || true; adb shell pm clear "$app" >/dev/null || true' EXIT
adb install -r "${target[0]}"
adb install -r "${tests[0]}"
adb shell cmd connectivity airplane-mode disable
status=0
timeout --foreground 1900s adb shell am instrument -w -r \
  -e mangaDexAuditOptIn I_ACCEPT_FROZEN30_MANGADEX_AUDIT \
  -e mangaAllFrozen30Shard "$shard" \
  -e mangaDexAuditPixels "$pixels" \
  -e class 'eu.kanade.tachiyomi.provider.runtime.MangaAllFrozen30MangaDexAuditInstrumentedTest#auditMangaDexIndependently' \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 || status=$?
python3 .github/scripts/verify_mangaall_frozen30_mangadex_audit.py \
  --log "$log" --status "$status" --shard "$shard" --pixels "$pixels" --output "$out"
