#!/usr/bin/env python3
"""Conservatively compare matched emulator runs with different output sinks."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


VALID_CLASSES = {
    "DIFFERENCE_CAUSAL",
    "DIFFERENCE_NOT_CAUSAL",
    "BOOT_PATH_UNSTABLE",
    "INCONCLUSIVE",
}
MAX_IMMEDIATE_MENU_DELTA_NS = 1_000_000_000
UPSTREAM_ACTION_URL = "https://github.com/ReactiveCircus/android-emulator-runner/tree/a421e43855164a8197daf9d8d40fe71c6996bb0d"
UPSTREAM_ACTION_PIN = "a421e43855164a8197daf9d8d40fe71c6996bb0d"
HISTORICAL_LOG_PATH = "app/build/default-skin-validation/startup-diff-source/historical-job.log"
HISTORICAL_ANR_REFERENCE = {
    "timestamp_utc": "around 2026-10-13T23:26:43Z",
    "source": "prior forensic report; the cached action log does not contain a literal ANR event line",
}
SELECTED_DIFFERENCE = {
    "id": "D2",
    "name": "emulator output forwarding",
    "control": "stdout redirected to /dev/null while emulator.log is retained",
    "treatment": "stdout forwarded to the GitHub Actions job log while emulator.log is retained",
    "ranking": 1,
    "causal_status": "candidate, not an established root cause",
    "source_refs": [
        f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100",
        f"{UPSTREAM_ACTION_URL}/src/sdk-installer.ts#L41-L78",
        "app/build/default-skin-validation/startup-diff-source/historical-job.log: L423, L572-L577",
        ".github/scripts/probe_default_skin_emulator_pre_menu.sh: emulator launch redirection",
    ],
}

DIFFERENCE_MATRIX = [
    {
        "id": "D1", "layer": "SDK provisioning", "difference": "Installation command sequence differs.",
        "manual": "One sdkmanager --channel=0 --install for emulator, platform-tools, API 35 platform, and Google APIs x86_64 image.",
        "reactive_circus": "build-tools;37.0.0 + platform-tools + platform; then emulator; then system image (channel 0).",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Runtime package versions matched in the prior capture; order still differs.",
        "independently_reproducible": True, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/sdk-installer.ts#L41-L78", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L405-L409", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: sdk_packages"],
    },
    {
        "id": "D2", "layer": "Emulator process output", "difference": "The emulator's stdout sink differs while its log file remains captured.",
        "manual": "stdout/stderr redirected to emulator.log; selected A/B control uses tee to file plus /dev/null.",
        "reactive_circus": "@actions/exec starts `sh -c emulator ... &`; action stdout/stderr listeners forward output to the job log.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Selected isolated variable; prior event ordering places launch before the reported ANR.",
        "independently_reproducible": True, "rank": 1,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L423", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: output-forwarding launch"],
    },
    {
        "id": "D3", "layer": "ADB readiness", "difference": "The host-side readiness gate differs.",
        "manual": "Poll `adb -s emulator-5554 get-state` until device, every two seconds.",
        "reactive_circus": "Poll `adb -s emulator-5554 shell getprop sys.boot_completed`, every two seconds.",
        "phase": "BOOT_BOUNDARY", "occurs_before_historical_anr": True,
        "status": "Observed command difference; not changed by the selected experiment.",
        "independently_reproducible": True, "rank": 3,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L138-L167", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: adb-transport-poll.log"],
    },
    {
        "id": "D4", "layer": "Pre-boot observation", "difference": "Manual run starts a filtered Logcat observer before boot-complete; action does not.",
        "manual": "LogcatObserver starts after ADB transport and before sys.boot_completed polling.",
        "reactive_circus": "No equivalent continuous observer in launchEmulator.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Observed instrumentation difference; observer-start gap is recorded, not hidden.",
        "independently_reproducible": True, "rank": 2,
        "source_refs": [".github/scripts/probe_default_skin_emulator_pre_menu.py: LogcatObserver", f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100"],
    },
    {
        "id": "D5", "layer": "Animation policy",
        "difference": "Both output-forwarding arms retain three read-only pre-boot animation queries; the action does not perform those queries.",
        "manual": "Both control and treatment read `window_animation_scale`, `transition_animation_scale`, and `animator_duration_scale` before boot; neither arm writes these settings.",
        "reactive_circus": "No matching pre-boot reads; `disable-animations` writes scales to 0.0 only after MENU (tracked separately as D8).",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "The same three reads are retained in both compared arms, so they are not the selected A/B variable. The action's post-MENU writes are separately excluded as D8.",
        "independently_reproducible": True, "rank": 4,
        "source_refs": [".github/scripts/probe_default_skin_emulator_pre_menu.py: animation reads", f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L99-L106", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L572-L577"],
    },
    {
        "id": "D6", "layer": "Host diagnostics", "difference": "Manual host tool/runtime checks add pre-launch commands and output.",
        "manual": "sdkmanager/adb versions, ldd, emulator -version and accel-check are recorded.",
        "reactive_circus": "No equivalent host diagnostics in the action's normal emulator launch path.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Observed timing/observability difference; no direct cause evidence.",
        "independently_reproducible": True, "rank": 5,
        "source_refs": [".github/scripts/probe_default_skin_emulator_pre_menu.sh: record_emulator_runtime", f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100"],
    },
    {
        "id": "D7", "layer": "Process supervision", "difference": "Action @actions/exec/sh supervision differs from a manual Bash background process.",
        "manual": "Bash starts emulator in background and retains PID; no app-side supervision.",
        "reactive_circus": "@actions/exec waits for `sh -c` to launch a background process and attaches a stderr listener.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": None,
        "status": "Mechanism not independently isolated by this experiment.",
        "independently_reproducible": False, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L95", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: emulator launch"],
    },
    {
        "id": "D8", "layer": "Animation mutation", "difference": "Action writes three global animation scales after MENU.",
        "manual": "No animation-setting writes in the output-forwarding experiment.",
        "reactive_circus": "Writes window, transition, and animator duration scales to 0.0 after MENU.",
        "phase": "POST_ANR", "occurs_before_historical_anr": False,
        "status": "Excluded as cause by historical ordering: MENU at 23:26:37.191; writes from 23:26:44.453; reported ANR around 23:26:43.",
        "independently_reproducible": True, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L99-L106", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L573-L577"],
    },
    {
        "id": "D9", "layer": "Post-event diagnostics/cleanup", "difference": "Later diagnostic/build/cleanup steps occur after the selected boot observation.",
        "manual": "Bounded diagnostic snapshots finish after the event window; the disposable job exits after evidence collection.",
        "reactive_circus": "Single MENU, then optional setting writes/build; emulator cleanup uses adb emu kill in finally.",
        "phase": "POST_ANR", "occurs_before_historical_anr": False,
        "status": "Not a candidate cause for the reported earlier event.",
        "independently_reproducible": True, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L99-L125", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L1638"],
    },
    {
        "id": "D10", "layer": "SDK executable selection", "difference": "The historical SDK manager executable selected from PATH is not established.",
        "manual": "Find newest sdkmanager under the SDK cmdline-tools tree, then invoke its absolute path.",
        "reactive_circus": "Invoke `sdkmanager` from PATH after prepending latest command-line-tools directories.",
        "phase": "UNKNOWN", "occurs_before_historical_anr": None,
        "status": "Historical resolved executable path/timing unknown; do not infer from source alone.",
        "independently_reproducible": False, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/sdk-installer.ts#L31-L55", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: sdkmanager selection"],
    },
    {
        "id": "D11", "layer": "Runner environment/cache", "difference": "Cross-runner environment and cache effects are not fully measured.",
        "manual": "Uses GitHub-hosted runner environment and records selected HOME/SDK/ADB variables.",
        "reactive_circus": "Action executes in the same workflow runner after prior setup/build steps; exact full environment is not part of the historical capture.",
        "phase": "UNKNOWN", "occurs_before_historical_anr": None,
        "status": "Some environment facts are recorded; unobserved variables and cache mechanisms remain unknown.",
        "independently_reproducible": False, "rank": None,
        "source_refs": ["app/build/default-skin-validation/startup-diff-source/historical-job.log: L348-L423", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: run-metadata.json"],
    },
    {
        "id": "E1", "layer": "AVD profile (equal)", "difference": "No AVD-profile difference observed.",
        "manual": "API 35, google_apis, x86_64, pixel_6, name test, 2 cores; defaults for RAM/heap/disk; hardware keyboard false.",
        "reactive_circus": "API 35, google_apis, x86_64, pixel_6, name test, 2 cores; default RAM/heap/disk; hardware keyboard false.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Observed equal in the recorded invocation/profile.", "independently_reproducible": True, "rank": None,
        "source_refs": ["app/build/default-skin-validation/startup-diff-source/historical-job.log: L348-L370", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: avd-create-command.txt"],
    },
    {
        "id": "E2", "layer": "Emulator launch profile (equal)", "difference": "No emulator binary/version/launch-option difference observed.",
        "manual": "emulator 37.2.12.0 build 16428233; port 5554; swiftshader_indirect; no-window/no-snapshot/noaudio/no-boot-anim; KVM available.",
        "reactive_circus": "Same recorded version/build, port, options and KVM; no `-accel off` override.",
        "phase": "PRE_BOOT", "occurs_before_historical_anr": True,
        "status": "Observed equal in the captured run.", "independently_reproducible": True, "rank": None,
        "source_refs": ["app/build/default-skin-validation/startup-diff-source/historical-job.log: L422-L457", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: emulator-launch-command.txt"],
    },
    {
        "id": "E3", "layer": "Port/boot protocol (equal)", "difference": "No port, timeout, or boot-poll interval difference observed.",
        "manual": "5554; 600-second bound; poll interval 2 seconds.",
        "reactive_circus": "5554; 600-second bound; poll interval 2 seconds.",
        "phase": "BOOT_BOUNDARY", "occurs_before_historical_anr": True,
        "status": "Observed equal.", "independently_reproducible": True, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L140-L165", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L359-L369", ".github/scripts/probe_default_skin_emulator_pre_menu.sh: adb-transport-poll"],
    },
    {
        "id": "E4", "layer": "No ADB kill before the comparison window (equal)", "difference": "Neither path kills ADB/emulator before the shared MENU/observation interval.",
        "manual": "No adb emu kill before the 15-second observation completes.",
        "reactive_circus": "The action's adb emu kill is in cleanup after its body; no kill before MENU/boot observation.",
        "phase": "POST_ANR", "occurs_before_historical_anr": False,
        "status": "Observed equivalent for the compared startup interval; cleanup itself is later.",
        "independently_reproducible": True, "rank": None,
        "source_refs": [f"{UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L120-L125", "app/build/default-skin-validation/startup-diff-source/historical-job.log: L1638"],
    },
]

HISTORICAL_LOG_EVIDENCE = {
    "D1": {
        "status": "OBSERVED",
        "events": [
            {"event": "action installs build-tools/platform-tools/platform", "timestamp_utc": "2026-10-13T23:25:47.8744840Z", "line": 405},
            {"event": "action installs emulator", "timestamp_utc": "2026-10-13T23:25:49.6103623Z", "line": 407},
            {"event": "action installs system image", "timestamp_utc": "2026-10-13T23:25:51.3258837Z", "line": 409},
        ],
    },
    "D2": {"status": "OBSERVED", "events": [{"event": "action emulator launch", "timestamp_utc": "2026-10-13T23:25:54.5092962Z", "line": 423}]},
    "D3": {"status": "OBSERVED", "events": [{"event": "action boot-complete detection", "timestamp_utc": "2026-10-13T23:26:37.1889513Z", "line": 572}]},
    "D4": {"status": "NOT_CAPTURED", "events": [], "note": "The historical action run had no manual Logcat observer; only the current script ordering establishes its pre-boot phase."},
    "D5": {
        "status": "NOT_CAPTURED",
        "events": [],
        "note": "The historical action log has no pre-boot read queries; its later animation writes are recorded separately under D8. Both current output-forwarding arms retain the three read-only queries in their run metadata.",
    },
    "D6": {"status": "NOT_CAPTURED", "events": [], "note": "The historical action log does not record the manual host-diagnostic command sequence."},
    "D7": {"status": "PARTIAL", "events": [{"event": "action emulator launch", "timestamp_utc": "2026-10-13T23:25:54.5092962Z", "line": 423}], "note": "The timestamp identifies launch only; supervision effects were not separately timed."},
    "D8": {"status": "OBSERVED", "events": [{"event": "first animation write", "timestamp_utc": "2026-10-13T23:26:44.4531908Z", "line": 575}]},
    "D9": {"status": "OBSERVED", "events": [{"event": "action emulator cleanup", "timestamp_utc": "2026-10-13T23:35:03.6313790Z", "line": 1638}]},
    "D10": {"status": "UNKNOWN", "events": [], "note": "The historical log shows sdkmanager commands but not the resolved executable path/timing for the manual selection difference."},
    "D11": {"status": "PARTIAL", "events": [{"event": "captured workflow environment excerpt", "timestamp_utc": "2026-10-13T23:25:45.9699563Z", "line": 367}], "note": "The excerpt is not a complete comparison of runner/cache state, so causal timing remains unknown."},
    "E1": {"status": "OBSERVED", "events": [{"event": "action AVD profile inputs", "timestamp_utc": "2026-10-13T23:25:45.9692684Z", "line": 350}]},
    "E2": {"status": "OBSERVED", "events": [{"event": "action emulator launch", "timestamp_utc": "2026-10-13T23:25:54.5092962Z", "line": 423}]},
    "E3": {"status": "OBSERVED", "events": [{"event": "action boot-complete detection", "timestamp_utc": "2026-10-13T23:26:37.1889513Z", "line": 572}]},
    "E4": {"status": "OBSERVED", "events": [{"event": "action cleanup adb emu kill", "timestamp_utc": "2026-10-13T23:35:03.6313790Z", "line": 1638}]},
}

for _row in DIFFERENCE_MATRIX:
    _row["upstream_action_source"] = {
        "repository_url": "https://github.com/ReactiveCircus/android-emulator-runner",
        "pin": UPSTREAM_ACTION_PIN,
        "pinned_source_url": UPSTREAM_ACTION_URL,
    }
    _row["historical_log_path"] = HISTORICAL_LOG_PATH
    _row["historical_log_evidence"] = HISTORICAL_LOG_EVIDENCE[_row["id"]]
    _row["historical_anr_reference"] = HISTORICAL_ANR_REFERENCE


def _valid_arm(arm: dict[str, Any] | None, expected: str) -> str | None:
    if arm is None:
        return f"The {expected} arm result is missing or unreadable."
    if arm.get("experiment") != "default_skin_emulator_output_forwarding" or arm.get("arm") != expected:
        return f"The supplied result is not the expected {expected} output-forwarding arm."
    equivalence = arm.get("config_equivalence")
    if not isinstance(equivalence, dict) or equivalence.get("equivalent") is not True:
        return f"The {expected} emulator configuration did not match the recorded reference."
    checks = equivalence.get("checks")
    required_checks = {
        "emulator_version", "system_image", "avd_only_cpu_override", "launch_command",
        "hardware_acceleration_auto", "emulator_log_read", "gfxstream_observed",
    }
    if (
        not isinstance(checks, dict)
        or not required_checks.issubset(checks)
        or any(checks[name] is not True for name in required_checks)
    ):
        return f"The {expected} emulator equivalence checks are incomplete or failed."
    if not isinstance(arm.get("canonical_configuration"), dict):
        return f"The {expected} canonical emulator configuration is missing."
    configuration = arm["canonical_configuration"]
    required_strings = (
        "runner_image_os", "runner_image_version", "system_image", "libpulse0_package",
        "sdkmanager_version", "adb_version", "emulator_version",
    )
    if any(not isinstance(configuration.get(key), str) or not configuration[key].strip() for key in required_strings):
        return f"The {expected} hosted runner, SDK, or emulator runtime identity is incomplete."
    if (
        configuration["system_image"] != "system-images;android-35;google_apis;x86_64"
        or "37.2.12.0" not in configuration["emulator_version"]
        or "16428233" not in configuration["emulator_version"]
    ):
        return f"The {expected} system image or pinned emulator build differs from the recorded experiment profile."
    required_objects = (
        "source_properties", "platform_tools_source_properties", "platform_source_properties",
        "avd_default_config", "avd_effective_config",
    )
    if any(not isinstance(configuration.get(key), dict) or not configuration[key] for key in required_objects):
        return f"The {expected} SDK/AVD configuration snapshot is incomplete."
    launch_arguments = configuration.get("launch_arguments")
    if not isinstance(launch_arguments, list) or not launch_arguments:
        return f"The {expected} emulator launch arguments are missing."
    acceleration = configuration.get("acceleration")
    if (
        not isinstance(acceleration, dict)
        or acceleration.get("exit_code") != 0
        or acceleration.get("kvm_usable") is not True
    ):
        return f"The {expected} emulator hardware-acceleration evidence is incomplete."
    actual_hash = arm.get("canonical_configuration_sha256")
    expected_hash = hashlib.sha256(
        json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if actual_hash != expected_hash:
        return f"The {expected} canonical configuration hash does not match its evidence."
    environment = configuration.get("host_environment")
    required_environment = {
        "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_AVD_HOME", "ANDROID_USER_HOME",
        "ADB_SERVER_SOCKET", "ADB_SERVER_PORT", "ANDROID_SERIAL", "ANDROID_EMULATOR_PORT",
    }
    if not isinstance(environment, dict) or not required_environment.issubset(environment):
        return f"The {expected} selected host-environment states are incomplete."
    if any(
        not isinstance(environment[name], dict)
        or environment[name].get("state") not in {"set", "unset"}
        or (
            environment[name].get("state") == "set"
            and (
                not isinstance(environment[name].get("value"), str)
                or not environment[name]["value"].strip()
            )
        )
        for name in required_environment
    ):
        return f"The {expected} selected host-environment values are not observed."
    output_capture = configuration.get("output_capture")
    if (
        not isinstance(output_capture, dict)
        or output_capture.get("file_capture") is not True
        or output_capture.get("tee") is not True
        or output_capture.get("capture_file") != "emulator.log"
        or output_capture.get("stderr_route") != "stdout"
    ):
        return f"The {expected} common retained-output capture configuration is missing."
    animation_values = configuration.get("animation_values")
    required_animations = {
        "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
    }
    if not isinstance(animation_values, dict) or not required_animations.issubset(animation_values):
        return f"The {expected} post-observation animation reads are incomplete."
    for name in required_animations:
        observed = animation_values[name]
        if (
            not isinstance(observed, dict)
            or observed.get("exit_code") != 0
            or observed.get("stdout_available") is not True
            or observed.get("value") in (None, "")
            or observed.get("provenance") != "post_observation"
        ):
            return f"The {expected} post-observation animation value {name} was not successfully observed."
    forwarding = arm.get("output_forwarding")
    if not isinstance(forwarding, dict):
        return f"The {expected} selected output-forwarding evidence is missing."
    sink = "discard" if expected == "control" else "github_actions"
    if (
        forwarding.get("stdout_sink") != sink
        or forwarding.get("file_capture") is not True
        or forwarding.get("tee") is not True
        or forwarding.get("stderr_route") != "stdout"
    ):
        return f"The {expected} arm did not use its assigned sink with common retained-log capture."
    launch = arm.get("launch_monotonic_ns")
    applied = forwarding.get("applied_monotonic_ns")
    observer_start = arm.get("observer_started_monotonic_ns")
    if not all(isinstance(value, int) for value in (launch, applied, observer_start)):
        return f"The {expected} output-forwarding or observer timestamps are missing."
    if launch != applied or observer_start < applied:
        return f"The {expected} output-forwarding boundary is inconsistent with emulator launch."
    if arm.get("observer_complete") is not True:
        return f"The {expected} Logcat observer did not cover the complete observation."
    if arm.get("observer_gap_anr_unknown") is not False:
        return f"The {expected} launch-to-observer interval contains unresolved HOME ANR evidence."
    if arm.get("pre_forwarding_home_anr_status") != "absent":
        return f"The {expected} arm has pre-selection or unknown HOME ANR evidence."
    observation = arm.get("observation")
    if not isinstance(observation, dict):
        return f"The {expected} observation timing record is malformed or missing."
    start = observation.get("start_monotonic_ns")
    end = observation.get("end_monotonic_ns")
    if (
        observation.get("complete") is not True
        or observation.get("target_seconds") != 15
        or not isinstance(observation.get("elapsed_seconds"), (int, float))
        or observation["elapsed_seconds"] < 15
        or not isinstance(start, int)
        or not isinstance(end, int)
        or end < start
        or abs(observation["elapsed_seconds"] - ((end - start) / 1_000_000_000)) > 0.005
        or not isinstance(observation.get("snapshot_count"), int)
        or observation["snapshot_count"] < 2
    ):
        return f"The {expected} arm did not complete a measured 15-second observation."
    if not isinstance(arm.get("home_packages_observed"), list) or not arm["home_packages_observed"]:
        return f"The {expected} arm did not resolve a HOME package."
    if arm.get("unresolved_home_anr") is not False:
        return f"HOME ANR evidence in the {expected} arm could not be completely scoped."
    if any(not isinstance(package, str) or not package for package in arm["home_packages_observed"]):
        return f"The {expected} HOME package evidence is malformed."
    if not isinstance(arm.get("home_anr"), bool):
        return f"HOME ANR status for the {expected} arm is unknown."
    events = arm.get("home_anr_events")
    if (
        not isinstance(events, list)
        or any(not isinstance(event, dict) for event in events)
        or (arm["home_anr"] != bool(events))
    ):
        return f"HOME ANR event records disagree with the {expected} arm summary."
    if arm.get("final_home_healthy") not in (True, False):
        return f"The {expected} final HOME state is not positively classified."
    screenshots = arm.get("screenshots")
    required = {"boot", "midpoint", "final"}
    first_home_observed = arm.get("first_home_focus_observed")
    if not isinstance(first_home_observed, bool):
        return f"The {expected} first-HOME-focus observation is missing."
    if first_home_observed:
        required.add("first_home")
    if arm["final_home_healthy"] is True and not arm["home_anr"] and not first_home_observed:
        return f"The {expected} final healthy HOME state conflicts with missing first-HOME-focus evidence."
    if not isinstance(screenshots, dict) or not required.issubset(screenshots):
        return f"The {expected} arm is missing required boot/HOME/midpoint/final captures."
    for name in required | ({"anr"} if arm["home_anr"] else set()):
        shot = screenshots.get(name)
        if (
            not isinstance(shot, dict)
            or shot.get("valid_png_signature") is not True
            or not isinstance(shot.get("bytes"), int)
            or shot["bytes"] <= 0
        ):
            return f"The {expected} arm is missing a valid {name} screenshot."
    if arm.get("menu_attempted") is not True or arm.get("menu_issued") is not True:
        return f"The {expected} arm did not issue its required MENU input."
    if arm.get("menu_event_count") != 1:
        return f"The {expected} arm did not issue exactly one MENU input."
    menu_start = arm.get("menu_start_monotonic_ns")
    menu_end = arm.get("menu_end_monotonic_ns")
    t0 = arm.get("boot_t0_monotonic_ns")
    first_observation = arm.get("first_device_observation_after_t0_ns")
    if not all(isinstance(value, int) for value in (menu_start, menu_end, t0, first_observation)):
        return f"The {expected} immediate MENU timing evidence is incomplete."
    if not (t0 <= menu_start <= t0 + MAX_IMMEDIATE_MENU_DELTA_NS and menu_end > menu_start < first_observation):
        return f"The {expected} MENU was not sent immediately before device observation."
    command = arm.get("menu_command")
    if not isinstance(command, dict):
        return f"The {expected} MENU command evidence is malformed or missing."
    if (
        command.get("command") != ["adb", "-s", arm.get("serial"), "shell", "input", "keyevent", "82"]
        or command.get("exit_code") != 0
        or command.get("timed_out") is True
    ):
        return f"The {expected} MENU command did not complete successfully."
    return None


def _precise_home_anr(arm: dict[str, Any]) -> bool:
    applied = arm["output_forwarding"]["applied_monotonic_ns"]
    end = arm["observation"]["end_monotonic_ns"]
    home_packages = set(arm["home_packages_observed"])
    return any(
        event.get("source") == "logcat"
        and event.get("package") in home_packages
        and isinstance(event.get("mapped_host_monotonic_ns"), int)
        and applied <= event["mapped_host_monotonic_ns"] <= end
        for event in arm["home_anr_events"]
    )


def _comparison_result(
    classification: str,
    reason: str,
    config_equivalent_except_selected_difference: bool | None = None,
) -> dict[str, Any]:
    return {
        "classification": classification,
        "reason": reason,
        "config_equivalent_except_selected_difference": config_equivalent_except_selected_difference,
    }


def compare_arms(control: dict[str, Any] | None, treatment: dict[str, Any] | None) -> dict[str, Any]:
    """Classify only matched runs with fully scoped event timing and state evidence."""
    for arm, expected in ((control, "control"), (treatment, "treatment")):
        error = _valid_arm(arm, expected)
        if error:
            return _comparison_result("INCONCLUSIVE", error)
    assert control is not None and treatment is not None
    configuration_matches = control["canonical_configuration"] == treatment["canonical_configuration"]
    if not configuration_matches:
        return _comparison_result(
            "INCONCLUSIVE",
            "The arms differ outside the selected stdout-sink variable.",
            configuration_matches,
        )
    if (
        not control["output_forwarding"]["applied_monotonic_ns"]
        or not treatment["output_forwarding"]["applied_monotonic_ns"]
    ):
        return _comparison_result(
            "INCONCLUSIVE",
            "The selected difference was not observed at emulator launch.",
            configuration_matches,
        )

    if control["home_anr"]:
        if not _precise_home_anr(control):
            return _comparison_result(
                "INCONCLUSIVE",
                "Control HOME ANR lacks a mapped event in the observation interval.",
                configuration_matches,
            )
        return _comparison_result(
            "BOOT_PATH_UNSTABLE",
            "A HOME ANR occurred in the control without forwarded emulator output.",
            configuration_matches,
        )
    if control["final_home_healthy"] is not True:
        return _comparison_result(
            "INCONCLUSIVE",
            "Control has no ANR but does not finish in a positively healthy HOME state.",
            configuration_matches,
        )

    if treatment["home_anr"]:
        if not _precise_home_anr(treatment):
            return _comparison_result(
                "INCONCLUSIVE",
                "Treatment HOME ANR is unmapped, retained-only, or outside the selected-difference interval.",
                configuration_matches,
            )
        return _comparison_result(
            "DIFFERENCE_CAUSAL",
            "A mapped HOME ANR occurred in the forwarded-output arm after that output path began; the matched control remained healthy. This is a candidate signal, not proof of root cause.",
            configuration_matches,
        )
    if treatment["final_home_healthy"] is not True:
        return _comparison_result(
            "INCONCLUSIVE",
            "Treatment has no ANR but does not finish in a positively healthy HOME state.",
            configuration_matches,
        )
    return _comparison_result(
        "DIFFERENCE_NOT_CAUSAL",
        "Neither matched arm recorded a scoped HOME ANR and both ended on positively healthy HOME states.",
        configuration_matches,
    )


def load_result(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--control", type=Path, required=True)
    parser.add_argument("--treatment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    control = load_result(args.control)
    treatment = load_result(args.treatment)
    result = compare_arms(control, treatment)
    result.update({
        "selected_difference": SELECTED_DIFFERENCE,
        "difference_matrix": DIFFERENCE_MATRIX,
        "historical_reference": {
            "action_source_url": UPSTREAM_ACTION_URL,
            "action_source_pin": UPSTREAM_ACTION_PIN,
            "cached_job_log": "app/build/default-skin-validation/startup-diff-source/historical-job.log",
            "emulator_launch_utc": "2026-10-13T23:25:54.5092962Z (line 423)",
            "boot_complete_utc": "2026-10-13T23:26:37.1889513Z (line 572)",
            "menu_utc": "2026-10-13T23:26:37.1916602Z (line 573)",
            "reported_anr_utc": "around 2026-10-13T23:26:43Z (prior forensic report; not present as a literal ANR line in the cached job log)",
            "first_animation_write_utc": "2026-10-13T23:26:44.4531908Z (line 575)",
        },
        "canonical_configuration_matches": result.get("config_equivalent_except_selected_difference"),
        "arm_evidence": {
            "control": summarize_arm(control),
            "treatment": summarize_arm(treatment),
        },
    })
    args.output.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    (args.output / "ab-result.json").write_text(encoded, encoding="utf-8")
    (args.output / "comparison.json").write_text(encoded, encoding="utf-8")
    (args.output / "selected-difference.json").write_text(json.dumps({
        "selected_difference": SELECTED_DIFFERENCE,
        "historical_reference": result["historical_reference"],
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.output / "difference-matrix.json").write_text(json.dumps({
        "selected_difference": SELECTED_DIFFERENCE,
        "matrix": DIFFERENCE_MATRIX,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    matrix_lines = [
        "ID | Layer | Difference | Timing | Before historical ANR | Independently reproducible | Status | Rank"
    ]
    for row in DIFFERENCE_MATRIX:
        before = row["occurs_before_historical_anr"]
        matrix_lines.append(
            f"{row['id']} | {row['layer']} | {row['difference']} | {row['phase']} | "
            f"{before if before is not None else 'UNKNOWN'} | {row['independently_reproducible']} | "
            f"{row['status']} | {row['rank'] or '-'}"
        )
        matrix_lines.append(f"  Manual: {row['manual']}")
        matrix_lines.append(f"  ReactiveCircus: {row['reactive_circus']}")
        matrix_lines.append(f"  Sources: {'; '.join(row['source_refs'])}")
        matrix_lines.append(
            "  Upstream pin: "
            f"{row['upstream_action_source']['repository_url']}@{row['upstream_action_source']['pin']}"
        )
        matrix_lines.append(
            "  Historical log: "
            f"{row['historical_log_evidence']['status']} "
            f"{json.dumps(row['historical_log_evidence'].get('events', []), sort_keys=True)}"
        )
        matrix_lines.append(
            "  Historical ANR reference: "
            f"{HISTORICAL_ANR_REFERENCE['timestamp_utc']} ({HISTORICAL_ANR_REFERENCE['source']})"
        )
    (args.output / "difference-matrix.txt").write_text("\n".join(matrix_lines) + "\n", encoding="utf-8")
    (args.output / "run-info.txt").write_text(
        f"CLASSIFICATION={result['classification']}\nREASON={result['reason']}\n"
        f"SELECTED_DIFFERENCE={SELECTED_DIFFERENCE['id']}\n", encoding="utf-8"
    )
    return 0 if result["classification"] == "DIFFERENCE_NOT_CAUSAL" else 1


def summarize_arm(arm: dict[str, Any] | None) -> dict[str, Any] | None:
    if arm is None:
        return None
    return {
        "arm": arm.get("arm"),
        "config_sha256": arm.get("canonical_configuration_sha256"),
        "output_forwarding": arm.get("output_forwarding"),
        "home_anr": arm.get("home_anr"),
        "home_anr_events": arm.get("home_anr_events"),
        "final_home_healthy": arm.get("final_home_healthy"),
        "observation": arm.get("observation"),
        "screenshots": arm.get("screenshots"),
    }


if __name__ == "__main__":
    raise SystemExit(main())
