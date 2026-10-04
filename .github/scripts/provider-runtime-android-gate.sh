#!/usr/bin/env bash
set -uo pipefail

# Keep runtime and reading instrumentation on the same physical gate.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=eu.kanade.tachiyomi.provider.runtime
status=$?

if [ "$status" -ne 0 ]; then
  echo "::group::Provider Platform instrumentation failures"
  result_root="app/build/outputs/androidTest-results/connected/debug"
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

exit "$status"
