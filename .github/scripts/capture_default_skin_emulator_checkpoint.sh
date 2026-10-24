#!/usr/bin/env bash
set -uo pipefail

label="${1:-}"
serial="${2:-}"
output="${3:-}"

if [[ ! "$label" =~ ^[A-H]$ || ! "$serial" =~ ^emulator-[0-9]+$ || -z "$output" ]]; then
  echo 'Usage: capture_default_skin_emulator_checkpoint.sh A-H emulator-<id> output-file' >&2
  exit 2
fi

mkdir -p "$(dirname "$output")" || exit 2
host_utc="$(date -u +'%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || printf unknown)"
host_monotonic_ns="$(python3 -c 'import time; print(time.monotonic_ns())' 2>/dev/null || printf unknown)"
{
  printf 'CHECKPOINT=%s HOST_UTC=%s HOST_MONOTONIC_NS=%s ADB_SERIAL=%s\n' \
    "$label" "$host_utc" "$host_monotonic_ns" "$serial"
} >> "$output" || exit 2

# Keep this diagnostic best-effort: a broken snapshot must not mask the runner's
# actual build/test result. The remote shell bounds each potentially large log.
snapshot_script=$(cat <<'ANDROID_SNAPSHOT'
set +e
echo "ANDROID_UPTIME=$(cat /proc/uptime 2>/dev/null)"
home_result=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1)
home_status=$?
home_component=$(printf '%s\n' "$home_result" | tail -n 1 | tr -d '\r')
case "$home_component" in
  *.*/*) home_package=${home_component%%/*} ;;
  *) home_package= ;;
esac
echo "HOME_RESOLVE_EXIT=$home_status"
echo "HOME_COMPONENT=$home_component"
echo "HOME_PACKAGE=$home_package"
device_provisioned=$(settings get global device_provisioned 2>&1); provisioned_status=$?
user_setup=$(settings get secure user_setup_complete 2>&1); user_setup_status=$?
echo "DEVICE_PROVISIONED_EXIT=$provisioned_status VALUE=$device_provisioned"
echo "USER_SETUP_COMPLETE_EXIT=$user_setup_status VALUE=$user_setup"
echo 'FOCUSED_WINDOWS_BEGIN'
focused_windows=$(dumpsys window windows 2>&1); focused_status=$?
echo "FOCUSED_WINDOWS_COMMAND_EXIT=$focused_status"
printf '%s\n' "$focused_windows" | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow|mObscuringWindow' | head -n 12
echo 'FOCUSED_WINDOWS_END'
echo 'ACTIVITY_TOP_BEGIN'
top_activities=$(dumpsys activity activities 2>&1); activity_status=$?
echo "ACTIVITY_TOP_COMMAND_EXIT=$activity_status"
printf '%s\n' "$top_activities" | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity|mFocusedActivity' | head -n 16
echo 'ACTIVITY_TOP_END'
echo 'POWER_KEYGUARD_BEGIN'
power_state=$(dumpsys power 2>&1); power_status=$?
policy_state=$(dumpsys window policy 2>&1); policy_status=$?
echo "POWER_COMMAND_EXIT=$power_status"
printf '%s\n' "$power_state" | grep -E 'mWakefulness|Wakefulness|mInteractive|Display Power' | head -n 12
echo "KEYGUARD_COMMAND_EXIT=$policy_status"
printf '%s\n' "$policy_state" | grep -E 'mShowingLockscreen|mKeyguard|mDreamingLockscreen' | head -n 12
echo 'POWER_KEYGUARD_END'
if [ -n "$home_package" ]; then
  home_pid=$(pidof "$home_package" 2>&1); pid_status=$?
  echo "HOME_PID_EXIT=$pid_status VALUE=$home_pid"
  echo 'HOME_PROCESSES_BEGIN'
  process_list=$(ps -A 2>&1); ps_status=$?
  echo "HOME_PROCESSES_COMMAND_EXIT=$ps_status"
  printf '%s\n' "$process_list" | grep -F "$home_package" | head -n 8
  echo 'HOME_PROCESSES_END'
  echo 'HOME_EXIT_INFO_BEGIN'
  exit_info=$(dumpsys activity exit-info "$home_package" 2>&1); exit_status=$?
  echo "HOME_EXIT_INFO_COMMAND_EXIT=$exit_status"
  printf '%s\n' "$exit_info" | head -n 32
  echo 'HOME_EXIT_INFO_END'
  echo 'HOME_LAST_ANR_BEGIN'
  last_anr=$(dumpsys activity lastanr 2>&1); anr_status=$?
  echo "HOME_LAST_ANR_COMMAND_EXIT=$anr_status"
  printf '%s\n' "$last_anr" | grep -i -C 2 -E "$home_package|ANR|not responding" | tail -n 28
  echo 'HOME_LAST_ANR_END'
else
  echo 'HOME_PROCESS_DIAGNOSTICS=UNAVAILABLE_HOME_COMPONENT'
fi
echo 'FILTERED_LOGCAT_BEGIN'
if [ -n "$home_package" ]; then
  recent_log=$(logcat -b main -b system -b events -d -t 500 2>&1); log_status=$?
  echo "FILTERED_LOGCAT_COMMAND_EXIT=$log_status"
  printf '%s\n' "$recent_log" | grep -i -E "$home_package|ActivityTaskManager|WindowManager|am_anr|InputDispatcher|not responding" | tail -n 60
else
  recent_log=$(logcat -b main -b system -b events -d -t 500 2>&1); log_status=$?
  echo "FILTERED_LOGCAT_COMMAND_EXIT=$log_status"
  printf '%s\n' "$recent_log" | grep -i -E 'ActivityTaskManager|WindowManager|am_anr|InputDispatcher|not responding|launcher' | tail -n 60
fi
echo 'FILTERED_LOGCAT_END'
ANDROID_SNAPSHOT
)

set +e
snapshot="$(printf '%s\n' "$snapshot_script" | adb -s "$serial" shell sh 2>&1)"
adb_status=$?
set -e
{
  printf '%s\n' "$snapshot"
  printf 'ADB_SNAPSHOT_EXIT=%s\n' "$adb_status"
  if (( adb_status != 0 )); then
    printf 'SNAPSHOT_STATUS=UNAVAILABLE_DIAGNOSTIC_ONLY\n'
  else
    printf 'SNAPSHOT_STATUS=CAPTURED_NOT_CAUSAL_PROOF\n'
  fi
  printf '\n'
} >> "$output" || true

exit 0
