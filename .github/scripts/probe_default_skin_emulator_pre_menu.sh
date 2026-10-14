#!/usr/bin/env bash
set -uo pipefail

ab_arm=''
output_forwarding_arm=''
preboot_logcat_arm=''
if [[ "${1:-}" == '--menu-ab-arm' ]]; then
  ab_arm="${2:-}"
  [[ "$ab_arm" == 'control' || "$ab_arm" == 'treatment' ]] || {
    echo 'Usage: probe_default_skin_emulator_pre_menu.sh [--menu-ab-arm control|treatment]' >&2
    exit 2
  }
  shift 2
elif [[ "${1:-}" == '--output-forwarding-ab-arm' ]]; then
  output_forwarding_arm="${2:-}"
  [[ "$output_forwarding_arm" == 'control' || "$output_forwarding_arm" == 'treatment' ]] || {
    echo 'Usage: probe_default_skin_emulator_pre_menu.sh [--output-forwarding-ab-arm control|treatment]' >&2
    exit 2
  }
  shift 2
elif [[ "${1:-}" == '--preboot-logcat-d4-arm' ]]; then
  preboot_logcat_arm="${2:-}"
  [[ "$preboot_logcat_arm" == 'control' || "$preboot_logcat_arm" == 'treatment' ]] || {
    echo 'Usage: probe_default_skin_emulator_pre_menu.sh --preboot-logcat-d4-arm control|treatment' >&2
    exit 2
  }
  shift 2
elif (($# > 0)); then
  echo 'Usage: probe_default_skin_emulator_pre_menu.sh [--menu-ab-arm control|treatment] [--output-forwarding-ab-arm control|treatment] [--preboot-logcat-d4-arm control|treatment]' >&2
  exit 2
fi

results_dir='.github/results/default-skin-pre-menu'
if [[ -n "$ab_arm" ]]; then
  results_dir=".github/results/default-skin-menu-ab/$ab_arm"
elif [[ -n "$output_forwarding_arm" ]]; then
  results_dir=".github/results/default-skin-emulator-output-forwarding-ab/$output_forwarding_arm"
elif [[ -n "$preboot_logcat_arm" ]]; then
  results_dir=".github/results/default-skin-emulator-preboot-logcat-d4/$preboot_logcat_arm"
fi
mkdir -p "$results_dir"
rm -f "$results_dir"/timeline.jsonl "$results_dir"/result.json "$results_dir"/logcat-filtered.jsonl \
  "$results_dir"/run-info.txt "$results_dir"/pre-menu-immediate.png "$results_dir"/pre-menu-final.png \
  "$results_dir"/post-menu-anr.png "$results_dir"/post-menu-stable.png
rm -rf "$results_dir"/snapshots "$results_dir"/config
mkdir -p "$results_dir/snapshots" "$results_dir/config"
record_bootstrap_event() {
  [[ -n "$preboot_logcat_arm" ]] || return 0
  python3 - "$results_dir/timeline.jsonl" "$@" <<'PY'
import json
import sys
from datetime import datetime, timezone
import time

path, name, *pairs = sys.argv[1:]
fields = {}
for pair in pairs:
    key, separator, value = pair.partition("=")
    if separator:
        fields[key] = value
entry = {
    "name": name,
    "utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
    "monotonic_ns": time.monotonic_ns(),
    **fields,
}
with open(path, "a", encoding="utf-8") as output:
    output.write(json.dumps(entry, sort_keys=True) + "\n")
PY
}

record_bootstrap_event 'host_script_entry' "arm=$preboot_logcat_arm" "script=$0"
{
  printf 'CLASSIFICATION=IN_PROGRESS\n'
  printf 'REASON=Probe started; final classification pending.\n'
} > "$results_dir/run-info.txt"

write_run_info() {
  local classification="$1"
  local reason="$2"
  {
    printf 'CLASSIFICATION=%s\n' "$classification"
    printf 'REASON=%s\n' "$reason"
    printf 'RESULT_JSON=%s\n' "$results_dir/result.json"
  } > "$results_dir/run-info.txt"
}

fail_inconclusive() {
  local reason="$1"
  python3 - "$results_dir/result.json" "$reason" <<'PY'
import json
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text(json.dumps({
    "classification": "INCONCLUSIVE",
    "reason": sys.argv[2],
    "menu_attempted": False,
    "menu_issued": False,
}, indent=2) + "\n", encoding="utf-8")
PY
  write_run_info 'INCONCLUSIVE' "$reason"
  echo "::error::$reason"
  exit 1
}

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail_inconclusive 'Android SDK root is unavailable'
sdkmanager="$(find "$sdk_root/cmdline-tools" -type f -name sdkmanager -print 2>/dev/null | sort -V | tail -n 1)"
avdmanager="$(find "$sdk_root/cmdline-tools" -type f -name avdmanager -print 2>/dev/null | sort -V | tail -n 1)"
emulator="$sdk_root/emulator/emulator"

write_sdk_tool_state() {
  local phase="$1"
  local path executable
  {
    printf 'phase=%s\n' "$phase"
    printf 'sdk_root=%s\n' "$sdk_root"
    for tool in sdkmanager avdmanager emulator; do
      case "$tool" in
        sdkmanager) path="$sdkmanager" ;;
        avdmanager) path="$avdmanager" ;;
        emulator) path="$emulator" ;;
      esac
      if [[ -x "$path" ]]; then executable=true; else executable=false; fi
      printf '%s_path=%s\n%s_executable=%s\n' "$tool" "$path" "$tool" "$executable"
    done
  } > "$results_dir/config/sdk-tools-$phase.txt"
}

record_emulator_runtime() {
  local phase="$1"
  local prefix="$results_dir/config/emulator-runtime-$phase"
  local command_text
  if [[ ! -x "$emulator" ]]; then
    {
      printf 'emulator_path=%s\n' "$emulator"
      printf 'emulator_executable=false\n'
      printf 'ldd_exit_code=NOT_RUN\nemulator_version_exit_code=NOT_RUN\n'
    } > "$prefix.txt"
    return 0
  fi

  printf -v command_text '%q ' ldd "$emulator"
  printf '%s\n' "${command_text% }" > "$prefix-ldd-command.txt"
  ldd "$emulator" > "$prefix-ldd.txt" 2>&1
  emulator_ldd_status=$?
  printf 'LDD_EXIT_CODE=%s\n' "$emulator_ldd_status" >> "$prefix-ldd.txt"

  printf -v command_text '%q ' "$emulator" -version
  printf '%s\n' "${command_text% }" > "$results_dir/config/emulator-version-command.txt"
  "$emulator" -version > "$results_dir/config/emulator-version.txt" 2>&1
  emulator_version_status=$?
  printf 'EMULATOR_VERSION_EXIT=%s\n' "$emulator_version_status" \
    >> "$results_dir/config/emulator-version.txt"
  {
    printf 'emulator_path=%s\n' "$emulator"
    printf 'emulator_executable=true\n'
    printf 'ldd_exit_code=%s\n' "$emulator_ldd_status"
    printf 'emulator_version_exit_code=%s\n' "$emulator_version_status"
    if grep -Eq '=>[[:space:]]+not found' "$prefix-ldd.txt"; then
      printf 'unresolved_shared_library=true\n'
    else
      printf 'unresolved_shared_library=false\n'
    fi
  } > "$prefix.txt"
}

write_sdk_tool_state before-install
[[ -x "$sdkmanager" ]] || fail_inconclusive 'Android SDK sdkmanager is unavailable; see config/sdk-tools-before-install.txt'
record_emulator_runtime before-install
export PATH="$sdk_root/platform-tools:$sdk_root/emulator:$PATH"

serial='emulator-5554'
image_package='system-images;android-35;google_apis;x86_64'
avd_name='test'
export ANDROID_AVD_HOME="$HOME/.android/avd"
avd_home="$ANDROID_AVD_HOME"
avd_dir="$avd_home/$avd_name.avd"
avd_ini="$avd_home/$avd_name.ini"
mkdir -p "$avd_home"
{
  printf 'HOME=%s\n' "$HOME"
  printf 'ANDROID_USER_HOME=%s\n' "${ANDROID_USER_HOME:-UNSET}"
  printf 'ANDROID_AVD_HOME=%s\n' "$ANDROID_AVD_HOME"
} > "$results_dir/config/avd.env"
[[ ! -e "$avd_dir" && ! -e "$avd_ini" ]] || fail_inconclusive 'Fresh AVD name already exists; refusing to delete or reuse it'

mkdir -p "$results_dir/config"
sdk_packages=(emulator platform-tools 'platforms;android-35' "$image_package")
{
  printf '%q ' "$sdkmanager" --channel=0 --install "${sdk_packages[@]}"
  printf '\n'
} > "$results_dir/config/sdk-install-command.txt"
"$sdkmanager" --channel=0 --install "${sdk_packages[@]}" > "$results_dir/config/sdkmanager-install.log" 2>&1
sdk_status=$?
printf 'SDKMANAGER_INSTALL_EXIT=%s\n' "$sdk_status" >> "$results_dir/config/sdkmanager-install.log"
(( sdk_status == 0 )) || fail_inconclusive 'Stable-channel SDK install failed; see sdkmanager-install.log'

write_sdk_tool_state after-install
[[ -x "$avdmanager" ]] || fail_inconclusive 'Android SDK avdmanager is unavailable after package installation; see config/sdk-tools-after-install.txt'
[[ -x "$emulator" ]] || fail_inconclusive 'Android emulator binary is unavailable after package installation; see config/sdk-tools-after-install.txt'
record_emulator_runtime after-install
if (( emulator_ldd_status != 0 )) || grep -Eq '=>[[:space:]]+not found' "$results_dir/config/emulator-runtime-after-install-ldd.txt"; then
  fail_inconclusive 'Android emulator shared-library check failed; see config/emulator-runtime-after-install-ldd.txt'
fi
(( emulator_version_status == 0 )) || fail_inconclusive 'Android emulator version command failed; see config/emulator-version.txt and emulator-runtime-after-install-ldd.txt'

"$sdkmanager" --version > "$results_dir/config/sdkmanager-version.txt" 2>&1 || true
printf -v command_text '%q ' "$sdkmanager" --version
printf '%s\n' "${command_text% }" > "$results_dir/config/sdkmanager-version-command.txt"
"$sdk_root/platform-tools/adb" version > "$results_dir/config/adb-version.txt" 2>&1 || true
printf -v command_text '%q ' "$sdk_root/platform-tools/adb" version
printf '%s\n' "${command_text% }" > "$results_dir/config/adb-version-command.txt"
"$emulator" -accel-check > "$results_dir/config/accel-check.txt" 2>&1
accel_status=$?
printf 'ACCEL_CHECK_EXIT=%s\n' "$accel_status" >> "$results_dir/config/accel-check.txt"
printf -v command_text '%q ' "$emulator" -accel-check
printf '%s\n' "${command_text% }" > "$results_dir/config/accel-check-command.txt"

image_dir="$sdk_root/system-images/android-35/google_apis/x86_64"
[[ -s "$image_dir/source.properties" ]] || fail_inconclusive 'Installed system image source.properties is missing'
cp "$image_dir/source.properties" "$results_dir/config/source.properties"
[[ -s "$sdk_root/platform-tools/source.properties" ]] \
  || fail_inconclusive 'Installed platform-tools source.properties is missing'
[[ -s "$sdk_root/platforms/android-35/source.properties" ]] \
  || fail_inconclusive 'Installed Android 35 platform source.properties is missing'
cp "$sdk_root/platform-tools/source.properties" "$results_dir/config/platform-tools-source.properties"
cp "$sdk_root/platforms/android-35/source.properties" "$results_dir/config/platform-source.properties"

printf -v avdmanager_command '%q ' "$avdmanager" create avd --force -n "$avd_name" --package "$image_package" --device pixel_6
printf "printf 'no\\n' | %s\n" "${avdmanager_command% }" > "$results_dir/config/avd-create-command.txt"
record_bootstrap_event 'avd_creation_started' "command=${avdmanager_command% }"
printf 'no\n' | "$avdmanager" create avd --force -n "$avd_name" --package "$image_package" --device pixel_6 \
  > "$results_dir/config/avdmanager-create.log" 2>&1
avd_status=$?
printf 'AVDMANAGER_CREATE_EXIT=%s\n' "$avd_status" >> "$results_dir/config/avdmanager-create.log"
(( avd_status == 0 )) || fail_inconclusive 'Pixel 6 AVD creation failed; see avdmanager-create.log'
record_bootstrap_event 'avd_creation_completed' "exit_code=$avd_status" "avd_name=$avd_name"
if [[ ! -s "$avd_dir/config.ini" ]]; then
  {
    printf 'avdmanager=%s\n' "$avdmanager"
    printf 'HOME=%s\nANDROID_USER_HOME=%s\nANDROID_AVD_HOME=%s\n' \
      "$HOME" "${ANDROID_USER_HOME:-UNSET}" "$ANDROID_AVD_HOME"
    printf 'expected_config=%s\n' "$avd_dir/config.ini"
    printf 'command='
    printf '%q ' "$avdmanager" list avd
    printf '\n'
    "$avdmanager" list avd
    printf 'AVDMANAGER_LIST_EXIT=%s\n' "$?"
  } > "$results_dir/config/avd-path-diagnostic.txt" 2>&1
  fail_inconclusive 'Created AVD config.ini is missing; see config/avd.env and config/avd-path-diagnostic.txt'
fi
cp "$avd_dir/config.ini" "$results_dir/config/avd-config-default.ini"
printf 'hw.cpu.ncore=2\n' >> "$avd_dir/config.ini"
cp "$avd_dir/config.ini" "$results_dir/config/avd-config-effective.ini"

printf -v launch_command '%q ' "$emulator" -port 5554 -avd "$avd_name" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim
launch_command="${launch_command% }"
printf '%s\n' "$launch_command" > "$results_dir/config/emulator-launch-command.txt"
launch_monotonic_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
launch_utc="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf 'HOST_UTC=%s HOST_MONOTONIC_NS=%s\n' \
  "$launch_utc" "$launch_monotonic_ns" \
  > "$results_dir/emulator-launch-start.txt"
if [[ -n "$output_forwarding_arm" ]]; then
  if [[ "$output_forwarding_arm" == 'treatment' ]]; then
    "$emulator" -port 5554 -avd "$avd_name" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
      > >(tee "$results_dir/emulator.log") 2>&1 &
  else
    "$emulator" -port 5554 -avd "$avd_name" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
      > >(tee "$results_dir/emulator.log" >/dev/null) 2>&1 &
  fi
elif [[ -n "$preboot_logcat_arm" ]]; then
  "$emulator" -port 5554 -avd "$avd_name" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    > >(tee "$results_dir/emulator.log" >/dev/null) 2>&1 &
else
  "$emulator" -port 5554 -avd "$avd_name" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    > "$results_dir/emulator.log" 2>&1 &
fi
emulator_pid=$!
printf 'LAUNCH_COMMAND=%s\nEMULATOR_PID=%s\n' "$launch_command" "$emulator_pid" \
  >> "$results_dir/emulator-launch-start.txt"
record_bootstrap_event 'emulator_launch_started' "launch_utc=$launch_utc" \
  "launch_monotonic_ns=$launch_monotonic_ns" "emulator_pid=$emulator_pid" \
  "output_sink=discard" "tee_file_capture=true" "stderr_route=stdout" "command=$launch_command"

python3 - "$results_dir/run-metadata.json" "$results_dir" "$sdk_root" "$image_package" "$avd_name" "$accel_status" "$emulator_version_status" "$output_forwarding_arm" "$launch_monotonic_ns" "$preboot_logcat_arm" <<'PY'
import os
import json
import pathlib
import sys

metadata_path, results, sdk_root, image_package, avd_name, accel_status, emulator_version_status, output_arm, launch_ns, logcat_arm = sys.argv[1:]
root = pathlib.Path(results)
launch_state = {}
for line in (root / "emulator-launch-start.txt").read_text(encoding="utf-8").splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        launch_state[key] = value
bootstrap_events = []
for line in (root / "timeline.jsonl").read_text(encoding="utf-8").splitlines():
    try:
        bootstrap_events.append(json.loads(line))
    except json.JSONDecodeError:
        continue
metadata = {
    "system_image": image_package,
    "emulator_version": (root / "config/emulator-version.txt").read_text(encoding="utf-8", errors="replace"),
    "emulator_version_exit": int(emulator_version_status),
    "adb_version": (root / "config/adb-version.txt").read_text(encoding="utf-8", errors="replace"),
    "sdkmanager_version": (root / "config/sdkmanager-version.txt").read_text(encoding="utf-8", errors="replace"),
    "source_properties": (root / "config/source.properties").read_text(encoding="utf-8", errors="replace"),
    "platform_tools_source_properties": (root / "config/platform-tools-source.properties").read_text(encoding="utf-8", errors="replace"),
    "platform_source_properties": (root / "config/platform-source.properties").read_text(encoding="utf-8", errors="replace"),
    "avd_default_config": (root / "config/avd-config-default.ini").read_text(encoding="utf-8", errors="replace"),
    "avd_effective_config": (root / "config/avd-config-effective.ini").read_text(encoding="utf-8", errors="replace"),
    "launch_command": (root / "config/emulator-launch-command.txt").read_text(encoding="utf-8", errors="replace"),
    "accel_check": (root / "config/accel-check.txt").read_text(encoding="utf-8", errors="replace"),
    "emulator_log_path": str(root / "emulator.log"),
    "sdk_root": sdk_root,
    "home": os.environ.get("HOME"),
    "avd_home": os.environ.get("ANDROID_AVD_HOME"),
    "avd_name": avd_name,
    "accel_check_exit": int(accel_status),
    "runner_image_os": os.environ.get("ImageOS"),
    "runner_image_version": os.environ.get("ImageVersion"),
    "libpulse0_package": (root / "runtime-dependencies/config/libpulse-after.txt").read_text(
        encoding="utf-8", errors="replace"
    ) if (root / "runtime-dependencies/config/libpulse-after.txt").is_file() else "",
}
if logcat_arm:
    metadata["launch_utc"] = launch_state.get("HOST_UTC")
    metadata["launch_monotonic_ns"] = int(launch_state["HOST_MONOTONIC_NS"])
    metadata["emulator_pid"] = int(launch_state["EMULATOR_PID"])
    metadata["bootstrap_events"] = bootstrap_events
    metadata["common_emulator_output"] = {
        "applied_monotonic_ns": int(launch_ns),
        "stdout_sink": "discard",
        "tee": True,
        "file_capture": True,
        "capture_file": "emulator.log",
        "stderr_route": "stdout",
        "same_policy_in_both_arms": True,
    }
if output_arm or logcat_arm:
    environment_names = (
        "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_AVD_HOME", "ANDROID_USER_HOME",
        "ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_SOCKET", "ADB_SERVER_PORT", "ANDROID_ADB_SERVER_PORT",
        "ANDROID_SERIAL", "ANDROID_EMULATOR_HOME", "ANDROID_EMULATOR_PORT",
    )
    metadata["host_environment"] = {
        name: {"state": "set", "value": os.environ[name]} if name in os.environ
        else {"state": "unset"}
        for name in environment_names
    }
if output_arm:
    metadata["output_forwarding"] = {
        "applied_monotonic_ns": int(launch_ns),
        "stdout_sink": "github_actions" if output_arm == "treatment" else "discard",
        "stderr_route": "stdout",
        "tee": True,
        "file_capture": True,
        "capture_file": "emulator.log",
        "mode": output_arm,
    }
(root / "output-capture-metadata.json").write_text(json.dumps({
    "experiment": "default_skin_emulator_output_forwarding",
    "arm": output_arm or None,
    "host_environment": metadata.get("host_environment"),
    "output_forwarding": metadata.get("output_forwarding"),
    "launch_monotonic_ns": int(launch_ns),
}, indent=2) + "\n", encoding="utf-8") if output_arm else None
(root / "preboot-logcat-policy.json").write_text(json.dumps({
    "experiment": "default_skin_emulator_preboot_logcat_d4",
    "arm": logcat_arm or None,
    "policy": "continuous" if logcat_arm == "control" else "deferred_final_dump" if logcat_arm == "treatment" else None,
    "continuous_reader_before_observation": logcat_arm == "control",
    "final_dump_same_both_arms": bool(logcat_arm),
    "common_emulator_output": metadata.get("common_emulator_output"),
}, indent=2) + "\n", encoding="utf-8") if logcat_arm else None
(root / "run-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
PY

# Wait only for ADB transport; the observer begins before sys.boot_completed.
transport_ready=0
transport_deadline=$((SECONDS + 600))
transport_log="$results_dir/adb-transport-poll.log"
: > "$transport_log"
while (( SECONDS < transport_deadline )); do
  transport_output="$(adb -s "$serial" get-state 2>&1)"
  transport_status=$?
  printf 'HOST_UTC=%s HOST_MONOTONIC_SECONDS=%s EXIT=%s OUTPUT_AVAILABLE=%s OUTPUT=%q\n' \
    "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$SECONDS" "$transport_status" \
    "$([[ -n "$transport_output" ]] && echo true || echo false)" "$transport_output" >> "$transport_log"
  if (( transport_status == 0 )) && [[ "$(tr -d '\r' <<< "$transport_output")" == 'device' ]]; then
    transport_ready=1
    break
  fi
  sleep 2
done
(( transport_ready == 1 )) || fail_inconclusive 'Emulator ADB transport did not appear within 600 seconds'

if [[ -n "$ab_arm" ]]; then
  python3 .github/scripts/probe_default_skin_emulator_pre_menu.py \
    --serial "$serial" \
    --results "$results_dir" \
    --metadata "$results_dir/run-metadata.json" \
    --mode "$ab_arm"
elif [[ -n "$output_forwarding_arm" ]]; then
  python3 .github/scripts/probe_default_skin_emulator_pre_menu.py \
    --serial "$serial" \
    --results "$results_dir" \
    --metadata "$results_dir/run-metadata.json" \
    --mode "output-$output_forwarding_arm"
elif [[ -n "$preboot_logcat_arm" ]]; then
  python3 .github/scripts/probe_default_skin_emulator_pre_menu.py \
    --serial "$serial" \
    --results "$results_dir" \
    --metadata "$results_dir/run-metadata.json" \
    --mode "preboot-logcat-$preboot_logcat_arm"
else
  python3 .github/scripts/probe_default_skin_emulator_pre_menu.py \
    --serial "$serial" \
    --results "$results_dir" \
    --metadata "$results_dir/run-metadata.json"
fi
