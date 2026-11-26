#!/usr/bin/env bash
# Retry only the transient Maven Central rate limit, never compiler/test failures.
set -euo pipefail
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
build() {
  local status
  set +e
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
  return "$status"
}
if build --parallel; then
  exit 0
else
  status=$?
fi
if ! grep -Eiq 'repo\.maven\.apache\.org[^[:cntrl:]]*(status code 429|HTTP 429)' "$log"; then
  exit "$status"
fi
echo 'ADVANCED_BUILD|MAVEN_CENTRAL_RATE_LIMIT|RETRY=1'
sleep 30
build --no-parallel --max-workers=2
