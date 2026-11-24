#!/usr/bin/env bash
# Only after the source-only Android creator gate has passed.
set -euo pipefail
umask 077
catalog="${1:?Select one independently verified catalog scope}"
[[ "$catalog" == "mangaupdates" || "$catalog" == "shikimori" || "$catalog" == "explicit_controls" ]]
app=app.tsuzuki.dev
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb shell pm clear "$app" | grep -q Success
adb shell cmd connectivity airplane-mode disable
log="$(mktemp)"
trap 'rm -f "$log"; adb shell am force-stop "$app" >/dev/null || true; adb shell pm clear "$app" >/dev/null || true' EXIT
status=0
timeout --foreground 390s adb shell am instrument -w -r \
  -e mangaDotCorroborationOptIn I_ACCEPT_MANGADOT_CORROBORATION_RESEARCH \
  -e mangaAllLiveCatalogSource "$catalog" \
  -e class eu.kanade.tachiyomi.data.tsuzuki.instrumentation.MangaAllMangaDotCorroborationInstrumentedTest#corroborateFrozenControls \
  "$app.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 || status=$?
python3 - "$log" "$status" "$catalog" <<'PY'
import re,sys
from pathlib import Path
raw=Path(sys.argv[1]).read_text(errors="replace")
allowed={
    "CATALOG_AMBIGUOUS","CATALOG_NOT_FOUND","CATALOG_UNAVAILABLE","CATALOG_INCOMPLETE",
    "CATALOG_UNVERIFIED","CATALOG_FORMAT_UNSUPPORTED","CATALOG_PROVENANCE_MISSING",
    "SOURCE_LOOKUP_FAILED","SOURCE_IDENTITY_MISSING_OR_AMBIGUOUS","SOURCE_DETAIL_UNVERIFIED",
    "CANONICAL_METADATA_UNVERIFIED","MISSING_AUTHOR_EVIDENCE","AUTHOR_CONFLICT","TITLE_UNVERIFIED",
    "SOURCE_CHAPTER_PAGE_FAILED","SOURCE_COVERAGE_UNVERIFIED","PORTFOLIO_PREVIEW_REJECTED",
    "UNCLASSIFIED_FAILURE","WORK_CORROBORATED_PREVIEW",
}
rows=re.findall(r"MANGAALL_DOT_CORROBORATION\|index=(\d+)\|catalog=(mangaupdates|shikimori)\|outcome=([A-Z_]+)",raw)
for index,catalog,outcome in rows:
    if outcome in allowed:
        print(f"MANGAALL_DOT_CORROBORATION|index={index}|catalog={catalog}|outcome={outcome}|sqlite=NOT_TESTED|reader=NOT_TESTED")
assert sys.argv[2]=="0" and "INSTRUMENTATION_CODE: -1" in raw and "OK (1 test)" in raw
expected=[(str(i),("shikimori" if i==12 else "mangaupdates") if sys.argv[3]=="explicit_controls" else sys.argv[3],"WORK_CORROBORATED_PREVIEW") for i in (1,3,10,12)]
assert rows==expected
PY
