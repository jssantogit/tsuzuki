#!/usr/bin/env bash
set -uo pipefail

status=0

run_package() {
  local package="$1"
  local label="$2"

  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package="$package"
  local package_status=$?

  if [ "$package_status" -ne 0 ]; then
    status="$package_status"
    echo "::group::$label instrumentation failures"
    local result_root="app/build/outputs/androidTest-results/connected/debug"
    if [ -d "$result_root" ]; then
      while IFS= read -r -d '' file; do
        echo "===== $file ====="
        cat "$file"
      done < <(find "$result_root" -type f -name '*.xml' -print0)
    else
      echo "No connected-test XML directory found at $result_root"
    fi
    echo "::endgroup::"
  fi
}

# Keep runtime/torrent acquisition and Reader archive acceptance on the same
# physical emulator gate and branch head without broadening to unrelated tests.
run_package "eu.kanade.tachiyomi.provider.runtime" "Provider Platform runtime"
run_package "eu.kanade.tachiyomi.ui.reader.loader" "Provider Torrent Reader"

exit "$status"
