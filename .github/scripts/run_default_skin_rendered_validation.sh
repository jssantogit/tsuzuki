#!/usr/bin/env bash
set -uo pipefail

results_dir='.github/results/default-skin-rendered'
renders_dir="$results_dir/renders"
junit_dir="$results_dir/junit"
rm -rf "$renders_dir" "$junit_dir"
mkdir -p "$renders_dir" "$junit_dir"
rm -f "$results_dir/run-info.txt" "$results_dir/verification.txt"
rm -rf app/build/outputs/androidTest-results/connected/debug

# Require exactly one emulator, never fall through to an attached personal device.
mapfile -t online_serials < <(adb devices | awk '$2 == "device" { print $1 }')
if (( ${#online_serials[@]} != 1 )) || [[ ! "${online_serials[0]:-}" =~ ^emulator-[0-9]+$ ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=NON_EMULATOR_OR_AMBIGUOUS_ADB_TARGET\n' > "$results_dir/run-info.txt"
  echo '::error::Rendered validation requires exactly one disposable emulator-* ADB target'
  exit 1
fi
export ANDROID_SERIAL="${online_serials[0]}"

# Inspect the generated test package pairing before any test installation/launch.
assemble_status=0
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest || assemble_status=$?
if (( assemble_status != 0 )); then
  printf 'RESULT=BUILD_FAILURE\nREASON=DEBUG_TEST_ASSEMBLY_FAILED\nGRADLE_EXIT=%s\n' "$assemble_status" > "$results_dir/run-info.txt"
  exit 1
fi
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
aapt="$(find "$sdk_root/build-tools" -type f -name aapt -print 2>/dev/null | sort -V | tail -n 1)"
package_metadata="$(python3 - app/build/outputs/apk <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
matches = {"app": [], "test": []}
for metadata_path in sorted(root.rglob("output-metadata.json")):
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        continue
    artifact_type = metadata.get("artifactType", {}).get("type")
    if artifact_type != "APK":
        continue
    relative_parts = metadata_path.relative_to(root).parts
    package_kind = "test" if "androidTest" in relative_parts else "app"
    variant = metadata.get("variantName")
    if package_kind == "app" and variant != "debug":
        continue
    if package_kind == "test" and variant not in {"debug", "debugAndroidTest"}:
        continue
    # The app enables ABI splits; select only the unique unfiltered universal APK.
    elements = [element for element in metadata.get("elements", []) if not element.get("filters", [])]
    if len(elements) != 1 or not elements[0].get("outputFile"):
        continue
    apk_path = (metadata_path.parent / elements[0]["outputFile"]).resolve()
    if not apk_path.is_relative_to(root) or not apk_path.is_file():
        continue
    matches[package_kind].append(apk_path)

if len(matches["app"]) != 1 or len(matches["test"]) != 1:
    print(
        "Expected exactly one debug app APK and one debug instrumentation APK "
        f"from output metadata; found app={len(matches['app'])}, test={len(matches['test'])}",
        file=sys.stderr,
    )
    sys.exit(1)
print(matches["app"][0])
print(matches["test"][0])
PY
)"
metadata_status=$?
if [[ -z "$sdk_root" || -z "$aapt" || "$metadata_status" != 0 ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=DEBUG_INSTRUMENTATION_PACKAGE_NOT_FOUND\n' > "$results_dir/run-info.txt"
  echo '::error::Could not inspect the generated debug and instrumentation packages'
  exit 1
fi
mapfile -t package_apks <<< "$package_metadata"
if (( ${#package_apks[@]} != 2 )); then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=DEBUG_INSTRUMENTATION_PACKAGE_NOT_FOUND\n' > "$results_dir/run-info.txt"
  echo '::error::Generated package metadata did not identify one app and one test APK'
  exit 1
fi
target_apk="${package_apks[0]}"
test_apk="${package_apks[1]}"
target_package="$("$aapt" dump badging "$target_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
test_package="$("$aapt" dump badging "$test_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
runner='androidx.test.runner.AndroidJUnitRunner'
if [[ -z "$target_package" || "$test_package" != "$target_package.test" ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=UNEXPECTED_INSTRUMENTATION_PACKAGE\nTARGET=%s\nTEST=%s\n' \
    "$target_package" "$test_package" > "$results_dir/run-info.txt"
  echo '::error::Unexpected app/test applicationId pairing; refusing to run instrumentation'
  exit 1
fi

# Keep image requests local-only; the suite uses deterministic local fixtures.
adb shell cmd connectivity airplane-mode enable
airplane_mode="$(adb shell settings get global airplane_mode_on | tr -d '\r')"
if [[ "$airplane_mode" != '1' ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=COULD_NOT_DISABLE_NETWORK\n' > "$results_dir/run-info.txt"
  echo '::error::Could not confirm the disposable emulator is offline'
  exit 1
fi

# Run only this deterministic, local-fixture instrumentation suite on the disposable emulator.
# Capture status and continue to retrieve any diagnostics/renders emitted before a test failure.
gradle_status=0
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.presentation.tsuzuki.DefaultSkinRenderedValidationInstrumentedTest \
  || gradle_status=$?

find app/build/outputs/androidTest-results/connected/debug -type f -name '*.xml' -exec cp {} "$junit_dir/" \; 2>/dev/null || true

instrumentation_record="$(adb shell pm list instrumentation | tr -d '\r' | grep 'androidx.test.runner.AndroidJUnitRunner' | grep '(target=' | head -n 1 || true)"
expected_record="instrumentation:$test_package/$runner (target=$target_package)"
if [[ "$instrumentation_record" != "$expected_record" ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=INSTALLED_INSTRUMENTATION_PACKAGE_MISMATCH\nEXPECTED=%s\nACTUAL=%s\n' \
    "$expected_record" "$instrumentation_record" > "$results_dir/run-info.txt"
  echo '::error::Installed instrumentation did not match the package inspected before device execution'
  exit 1
fi
if [[ -z "$target_package" ]]; then
  printf 'RESULT=INFRASTRUCTURE_FAILURE\nREASON=INSTRUMENTATION_TARGET_NOT_FOUND\nGRADLE_EXIT=%s\n' "$gradle_status" > "$results_dir/run-info.txt"
  echo '::error::Could not determine the installed instrumentation target applicationId'
  exit 1
fi
printf 'TARGET_APPLICATION_ID=%s\nTEST_APPLICATION_ID=%s\nADB_SERIAL=%s\nGRADLE_EXIT=%s\n' \
  "$target_package" "$test_package" "$ANDROID_SERIAL" "$gradle_status" > "$results_dir/run-info.txt"

external_path="/sdcard/Android/data/$target_package/files/default-skin-rendered"
pull_status=0
adb pull "$external_path" "$renders_dir" || pull_status=$?

verify_status=0
python3 .github/scripts/verify_default_skin_rendered_evidence.py \
  --junit-dir "$junit_dir" \
  --render-dir "$renders_dir/default-skin-rendered" \
  --summary "$results_dir/verification.txt" || verify_status=$?

if (( gradle_status != 0 || pull_status != 0 || verify_status != 0 )); then
  verification_result="$(sed -n 's/^RESULT=//p' "$results_dir/verification.txt" | head -n 1)"
  if [[ "$verification_result" == "VALIDATION_FAILURE" ]]; then
    result='VALIDATION_FAILURE'
  else
    result='INFRASTRUCTURE_FAILURE'
  fi
  printf 'RESULT=%s\nGRADLE_EXIT=%s\nADB_PULL_EXIT=%s\nVERIFY_EXIT=%s\nVERIFICATION_RESULT=%s\n' \
    "$result" "$gradle_status" "$pull_status" "$verify_status" "$verification_result" >> "$results_dir/run-info.txt"
  exit 1
fi
printf 'RESULT=PASS\n' >> "$results_dir/run-info.txt"
