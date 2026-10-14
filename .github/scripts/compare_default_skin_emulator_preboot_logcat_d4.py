#!/usr/bin/env python3
"""Compare matched fresh-emulator runs with or without a pre-boot Logcat reader."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
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
OUTPUT_FORWARDING_COMPARATOR_PATH = Path(__file__).with_name(
    "compare_default_skin_emulator_output_forwarding_ab.py"
)
OUTPUT_FORWARDING_SPEC = importlib.util.spec_from_file_location(
    "default_skin_output_forwarding_reference_matrix",
    OUTPUT_FORWARDING_COMPARATOR_PATH,
)
assert OUTPUT_FORWARDING_SPEC is not None and OUTPUT_FORWARDING_SPEC.loader is not None
OUTPUT_FORWARDING_REFERENCE = importlib.util.module_from_spec(OUTPUT_FORWARDING_SPEC)
OUTPUT_FORWARDING_SPEC.loader.exec_module(OUTPUT_FORWARDING_REFERENCE)

SELECTED_DIFFERENCE = {
    "id": "D4",
    "name": "pre-boot Logcat observer",
    "control": "Continuous filtered adb logcat reader starts before boot-complete and remains through the matched window.",
    "treatment": "No continuous reader before/during the matched window; one final dump is collected afterward.",
    "selected_value": {
        "control": "continuous",
        "treatment": "deferred_final_dump",
    },
    "source_refs": [
        ".github/scripts/probe_default_skin_emulator_pre_menu.py: LogcatObserver startup and deferred treatment path",
        f"{OUTPUT_FORWARDING_REFERENCE.UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100",
    ],
}

DIFFERENCE_MATRIX = copy.deepcopy(OUTPUT_FORWARDING_REFERENCE.DIFFERENCE_MATRIX)
for _row in DIFFERENCE_MATRIX:
    if _row.get("id") == "D4":
        _row.update({
            "layer": "Pre-boot Logcat observation",
            "difference": "Only the continuous Logcat observer policy changes between the paired runs.",
            "manual": SELECTED_DIFFERENCE["control"],
            "reactive_circus": SELECTED_DIFFERENCE["treatment"],
            "phase": "PRE_BOOT",
            "occurs_before_historical_anr": True,
            "status": "Selected D4 variable; the matched run records the observer process and final-dump evidence.",
            "independently_reproducible": True,
            "rank": 1,
            "source_refs": SELECTED_DIFFERENCE["source_refs"],
        })


def _valid_arm(arm: dict[str, Any] | None, expected: str) -> str | None:
    if arm is None:
        return f"The {expected} D4 arm result is missing or unreadable."
    if arm.get("experiment") != "default_skin_emulator_preboot_logcat_d4" or arm.get("arm") != expected:
        return f"The supplied result is not the expected {expected} D4 arm."
    expected_value = SELECTED_DIFFERENCE["selected_value"][expected]
    if (
        arm.get("selected_difference_id") != "D4"
        or arm.get("selected_difference_value") != expected_value
        or arm.get("pre_boot_logcat_observer_enabled") is not (expected == "control")
        or arm.get("observer_pid") in (None, "UNKNOWN")
        or (expected == "control" and not isinstance(arm.get("observer_pid"), int))
        or (expected == "treatment" and arm.get("observer_pid") != "NONE")
    ):
        return f"The {expected} selected D4 observer state is missing or inconsistent."
    equivalence = arm.get("config_equivalence")
    if not isinstance(equivalence, dict) or equivalence.get("equivalent") is not True:
        return f"The {expected} emulator profile did not match the frozen D4 profile."
    configuration = arm.get("canonical_configuration")
    expected_hash = arm.get("canonical_configuration_sha256")
    if not isinstance(configuration, dict) or not isinstance(expected_hash, str):
        return f"The {expected} canonical emulator configuration is missing."
    actual_hash = hashlib.sha256(
        json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if expected_hash != actual_hash:
        return f"The {expected} canonical configuration hash does not match its contents."
    required_checks = {
        "emulator_version", "system_image", "avd_only_cpu_override", "launch_command",
        "hardware_acceleration_auto", "emulator_log_read", "gfxstream_observed",
    }
    checks = equivalence.get("checks")
    if (
        not isinstance(checks, dict)
        or not required_checks.issubset(checks)
        or any(checks[name] is not True for name in required_checks)
    ):
        return f"The {expected} emulator profile checks are missing or failed."

    required_configuration_strings = {
        "runner_image_os", "runner_image_version", "system_image", "libpulse0_package",
        "sdkmanager_version", "adb_version", "emulator_version",
    }
    if any(
        not isinstance(configuration.get(key), str) or not configuration[key].strip()
        for key in required_configuration_strings
    ):
        return f"The {expected} runner, SDK, or emulator identity is incomplete."
    required_configuration_maps = {
        "source_properties", "platform_tools_source_properties", "platform_source_properties",
        "avd_default_config", "avd_effective_config",
    }
    if any(
        not isinstance(configuration.get(key), dict) or not configuration[key]
        for key in required_configuration_maps
    ):
        return f"The {expected} SDK or AVD configuration snapshot is incomplete."
    if not isinstance(configuration.get("launch_arguments"), list) or not configuration["launch_arguments"]:
        return f"The {expected} emulator launch arguments are missing."
    acceleration = configuration.get("acceleration")
    if not isinstance(acceleration, dict) or acceleration.get("exit_code") != 0 or acceleration.get("kvm_usable") is not True:
        return f"The {expected} KVM evidence is incomplete."
    required_environment = {
        "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_AVD_HOME", "ANDROID_USER_HOME",
        "ADB_SERVER_SOCKET", "ADB_SERVER_PORT", "ANDROID_SERIAL", "ANDROID_EMULATOR_PORT",
    }
    environment = configuration.get("host_environment")
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
        or output_capture.get("tee") is not True
        or output_capture.get("file_capture") is not True
        or output_capture.get("capture_file") != "emulator.log"
        or output_capture.get("stderr_route") != "stdout"
    ):
        return f"The {expected} shared output capture configuration is missing."
    animation_values = configuration.get("animation_values")
    required_animations = {
        "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
    }
    if not isinstance(animation_values, dict) or not required_animations.issubset(animation_values):
        return f"The {expected} read-only animation observations are incomplete."
    if any(
        not isinstance(animation_values[name], dict)
        or animation_values[name].get("exit_code") != 0
        or animation_values[name].get("stdout_available") is not True
        or animation_values[name].get("value") in (None, "")
        or animation_values[name].get("provenance") != "post_observation"
        for name in required_animations
    ):
        return f"The {expected} read-only animation observations are not complete."

    if arm.get("menu_attempted") is not True or arm.get("menu_issued") is not True:
        return f"The {expected} arm did not issue its one matched MENU input successfully."
    if arm.get("menu_event_count") != 1:
        return f"The {expected} arm did not record exactly one MENU input."
    launch_ns = arm.get("launch_monotonic_ns")
    launch_utc = arm.get("launch_utc")
    emulator_pid = arm.get("emulator_pid")
    common_output = arm.get("common_emulator_output")
    if (
        not isinstance(launch_ns, int)
        or not isinstance(launch_utc, str)
        or not launch_utc.strip()
        or not isinstance(emulator_pid, int)
        or emulator_pid <= 0
        or not isinstance(common_output, dict)
        or common_output.get("applied_monotonic_ns") != launch_ns
        or common_output.get("stdout_sink") != "discard"
        or common_output.get("tee") is not True
        or common_output.get("file_capture") is not True
        or common_output.get("capture_file") != "emulator.log"
        or common_output.get("stderr_route") != "stdout"
        or common_output.get("same_policy_in_both_arms") is not True
    ):
        return f"The {expected} common emulator launch/output-capture baseline is incomplete."
    bootstrap_events = arm.get("bootstrap_events")
    if not isinstance(bootstrap_events, list):
        return f"The {expected} shell bootstrap timeline is missing."
    event_by_name = {
        event.get("name"): event for event in bootstrap_events if isinstance(event, dict)
    }
    required_bootstrap_events = {"host_script_entry", "avd_creation_started", "avd_creation_completed", "emulator_launch_started"}
    if not required_bootstrap_events.issubset(event_by_name):
        return f"The {expected} shell/AVD/launch timeline markers are incomplete."
    bootstrap_times = [
        event_by_name[name].get("monotonic_ns")
        for name in ("host_script_entry", "avd_creation_started", "avd_creation_completed", "emulator_launch_started")
    ]
    if (
        event_by_name["avd_creation_completed"].get("exit_code") != "0"
        or event_by_name["emulator_launch_started"].get("launch_monotonic_ns") != str(launch_ns)
        or event_by_name["emulator_launch_started"].get("emulator_pid") != str(emulator_pid)
        or event_by_name["emulator_launch_started"].get("launch_utc") != launch_utc
        or any(not isinstance(value, int) for value in bootstrap_times)
        or bootstrap_times != sorted(bootstrap_times)
        or launch_ns < bootstrap_times[2]
        or launch_ns > bootstrap_times[3]
    ):
        return f"The {expected} AVD or emulator launch metadata does not match its timeline."
    menu = arm.get("menu_command")
    if not isinstance(menu, dict) or menu.get("exit_code") != 0 or menu.get("timed_out") is True:
        return f"The {expected} MENU command result is incomplete or failed."
    delta = arm.get("menu_delta_ms")
    if not isinstance(delta, (int, float)) or not 0 <= delta <= MAX_IMMEDIATE_MENU_DELTA_NS / 1_000_000:
        return f"The {expected} MENU input was not issued immediately after boot detection."

    observation = arm.get("observation")
    if not isinstance(observation, dict) or observation.get("complete") is not True:
        return f"The {expected} 15-second observation is incomplete."
    if observation.get("target_seconds") != 15:
        return f"The {expected} observation duration differs from the shared 15-second protocol."
    start_ns, end_ns = observation.get("start_monotonic_ns"), observation.get("end_monotonic_ns")
    if not isinstance(start_ns, int) or not isinstance(end_ns, int) or end_ns - start_ns < 15_000_000_000:
        return f"The {expected} observation does not cover the full monotonic window."

    logcat = arm.get("preboot_logcat")
    if not isinstance(logcat, dict):
        return f"The {expected} D4 Logcat policy/evidence is missing."
    policy = "continuous" if expected == "control" else "deferred_final_dump"
    if logcat.get("policy") != policy:
        return f"The {expected} arm did not follow its assigned D4 reader policy."
    if (
        logcat.get("pre_boot_logcat_observer_enabled") is not (expected == "control")
        or logcat.get("observer_pid") != arm.get("observer_pid")
    ):
        return f"The {expected} Logcat observer summary does not match its arm result."
    process_proof = logcat.get("process_proof")
    if not isinstance(process_proof, dict) or process_proof.get("exit_code") != 0:
        return f"The {expected} host Logcat process proof is unavailable."
    if process_proof.get("stdout_available") is not True:
        return f"The {expected} process-list stdout was unavailable."
    pids = process_proof.get("adb_logcat_pids")
    if not isinstance(pids, list):
        return f"The {expected} process-list result is malformed."
    process_samples = logcat.get("process_samples")
    if not isinstance(process_samples, list) or len(process_samples) < 2:
        return f"The {expected} preboot and post-window process samples are incomplete."
    for sample in process_samples:
        if (
            not isinstance(sample, dict)
            or sample.get("exit_code") != 0
            or sample.get("stdout_available") is not True
            or sample.get("stdout_nonempty") is not True
            or not isinstance(sample.get("process_row_count"), int)
            or sample.get("process_row_count", 0) <= 0
            or not isinstance(sample.get("adb_logcat_pids"), list)
        ):
            return f"The {expected} process sample lacks successful nonempty parsed ps evidence."
    if expected == "control":
        observer_pid = logcat.get("observer_pid")
        if (
            logcat.get("observer_started") is not True
            or logcat.get("observer_running_through_window") is not True
            or not isinstance(observer_pid, int)
            or any(observer_pid not in sample["adb_logcat_pids"] for sample in process_samples)
            or any(sample.get("observer_running") is not True for sample in process_samples)
            or observer_pid not in pids
            or not isinstance(logcat.get("observer_started_monotonic_ns"), int)
            or logcat["observer_started_monotonic_ns"] >= start_ns
            or not isinstance(logcat.get("observer_stop"), dict)
            or logcat["observer_stop"].get("available_at_stop") is not True
            or logcat["observer_stop"].get("truncated") is True
            or logcat["observer_stop"].get("events_dropped") != 0
        ):
            return "The control arm did not prove a live pre-boot Logcat observer through the matched window."
    elif logcat.get("observer_started") is not False or logcat.get("observer_pid") != "NONE" or any(
        sample["adb_logcat_pids"] for sample in process_samples
    ):
        return "The treatment arm did not prove that no continuous adb logcat reader was running."

    dump = logcat.get("final_dump")
    if not isinstance(dump, dict) or dump.get("complete") is not True:
        return f"The {expected} post-window final Logcat dump is incomplete."
    if dump.get("exit_code") != 0 or dump.get("stdout_available") is not True or dump.get("stdout_nonempty") is not True:
        return f"The {expected} final Logcat dump did not provide successful nonempty output."
    coverage = dump.get("coverage")
    if not isinstance(coverage, dict) or coverage.get("covers_start") is not True or coverage.get("covers_end") is not True:
        return f"The {expected} final Logcat dump does not cover the observation interval."
    required_buffers = {"main", "system", "events"}
    per_buffer = coverage.get("covers_start_by_buffer")
    first_epochs = dump.get("first_device_epoch_by_buffer")
    seen_buffers = set(dump.get("buffer_headers_seen", [])) if isinstance(dump.get("buffer_headers_seen"), list) else set()
    coverage_start = logcat.get("coverage_start_device_epoch")
    dump_command = dump.get("command")
    expected_dump_command = ["logcat", "-d", "-b", "main", "-b", "system", "-b", "events", "-v", "epoch"]
    if (
        not isinstance(per_buffer, dict)
        or not required_buffers.issubset(per_buffer)
        or any(per_buffer[name] is not True for name in required_buffers)
        or not required_buffers.issubset(seen_buffers)
        or not isinstance(first_epochs, dict)
        or not required_buffers.issubset(first_epochs)
        or not isinstance(coverage_start, (int, float))
        or any(
            not isinstance(first_epochs[name], (int, float)) or first_epochs[name] > coverage_start
            for name in required_buffers
        )
        or not isinstance(dump_command, list)
        or not all(token in dump_command for token in expected_dump_command)
    ):
        return f"The {expected} final dump does not prove a retained prefix for all requested buffers."
    if logcat.get("preexisting_home_anr_status") not in {"absent", "present", "unknown"}:
        return f"The {expected} pre-observation ANR status is missing or malformed."
    if not isinstance(arm.get("final_home_healthy"), bool):
        return f"The {expected} final HOME health state is unknown."
    if not isinstance(arm.get("home_packages_observed"), list) or not arm["home_packages_observed"]:
        return f"The {expected} arm did not resolve a HOME package."
    home_events = arm.get("home_anr_events")
    if (
        not isinstance(arm.get("home_anr"), bool)
        or not isinstance(home_events, list)
        or any(not isinstance(event, dict) for event in home_events)
        or arm["home_anr"] != bool(home_events)
        or any(
            event.get("package") not in arm["home_packages_observed"]
            or not isinstance(event.get("device_epoch"), (int, float))
            or event.get("timestamp_quality") not in {"device-epoch", "device-epoch-mapped"}
            for event in home_events
        )
    ):
        return f"The {expected} HOME ANR summary and scoped events disagree."
    if arm.get("unresolved_home_anr") is not False:
        return f"The {expected} arm contains unresolved ANR evidence."
    return None


def _configuration_hash(arm: dict[str, Any] | None) -> str | None:
    configuration = arm.get("canonical_configuration") if isinstance(arm, dict) else None
    if not isinstance(configuration, dict):
        return None
    return hashlib.sha256(
        json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _arm_d4_proof(arm: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(arm, dict):
        return {"state": "UNKNOWN"}
    logcat = arm.get("preboot_logcat")
    logcat = logcat if isinstance(logcat, dict) else {}
    samples = logcat.get("process_samples")
    sample_proof = [
        {
            "exit_code": sample.get("exit_code"),
            "stdout_available": sample.get("stdout_available"),
            "stdout_nonempty": sample.get("stdout_nonempty"),
            "process_row_count": sample.get("process_row_count"),
            "adb_logcat_pids": sample.get("adb_logcat_pids"),
        }
        for sample in samples if isinstance(sample, dict)
    ] if isinstance(samples, list) else []
    return {
        "arm": arm.get("arm"),
        "policy": logcat.get("policy"),
        "pre_boot_logcat_observer_enabled": arm.get("pre_boot_logcat_observer_enabled", "UNKNOWN"),
        "observer_pid": arm.get("observer_pid", "UNKNOWN"),
        "observer_started": logcat.get("observer_started"),
        "observer_running_through_window": logcat.get("observer_running_through_window"),
        "process_samples": sample_proof,
        "final_dump_complete": bool(
            isinstance(logcat.get("final_dump"), dict)
            and logcat["final_dump"].get("complete") is True
        ),
    }


def _comparison_result(
    classification: str,
    reason: str,
    control: dict[str, Any] | None,
    treatment: dict[str, Any] | None,
    *,
    config_equivalent: bool,
    **extra: Any,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "classification": classification,
        "reason": reason,
        "config_equivalent": config_equivalent,
        "selected_difference_id": "D4",
        "selected_difference_value": SELECTED_DIFFERENCE["selected_value"],
        "config_hashes": {
            "control": _configuration_hash(control),
            "treatment": _configuration_hash(treatment),
        },
        "config_equivalent_except_selected_difference": config_equivalent,
        "d4_proof": {
            "selected_difference_id": "D4",
            "selected_difference_value": SELECTED_DIFFERENCE["selected_value"],
            "control": _arm_d4_proof(control),
            "treatment": _arm_d4_proof(treatment),
        },
    }
    result.update(extra)
    return result


def compare_arms(control: dict[str, Any] | None, treatment: dict[str, Any] | None) -> dict[str, Any]:
    """Classify only when both run identity and time-bounded evidence are complete."""
    control_error = _valid_arm(control, "control")
    treatment_error = _valid_arm(treatment, "treatment")
    if control_error or treatment_error:
        return _comparison_result(
            "INCONCLUSIVE", control_error or treatment_error or "D4 arm evidence is invalid.",
            control, treatment, config_equivalent=False,
        )
    assert control is not None and treatment is not None
    control_config = control["canonical_configuration"]
    treatment_config = treatment["canonical_configuration"]
    if control_config != treatment_config:
        return _comparison_result(
            "INCONCLUSIVE", "The D4 arms differ in configuration beyond the selected Logcat observer policy.",
            control, treatment, config_equivalent=False,
        )

    control_logcat = control["preboot_logcat"]
    treatment_logcat = treatment["preboot_logcat"]
    if control_logcat.get("preexisting_home_anr_status") == "present":
        return _comparison_result(
            "BOOT_PATH_UNSTABLE", "The continuous-reader control observed a HOME ANR before the selected observation window.",
            control, treatment, config_equivalent=True,
        )
    if (
        control_logcat.get("preexisting_home_anr_status") != "absent"
        or treatment_logcat.get("preexisting_home_anr_status") != "absent"
    ):
        return _comparison_result(
            "INCONCLUSIVE", "A pre-observation HOME ANR could not be ruled out in both arms.",
            control, treatment, config_equivalent=True,
        )

    if control.get("home_anr") is True:
        return _comparison_result(
            "BOOT_PATH_UNSTABLE", "The control arm observed a HOME ANR during the matched window.",
            control, treatment, config_equivalent=True,
        )
    if control.get("final_home_healthy") is not True:
        return _comparison_result(
            "INCONCLUSIVE", "The continuous-reader control did not end with verified healthy HOME state.",
            control, treatment, config_equivalent=True,
        )

    treatment_events = treatment.get("home_anr_events")
    if not isinstance(treatment_events, list):
        return _comparison_result(
            "INCONCLUSIVE", "The treatment HOME ANR event list is malformed.",
            control, treatment, config_equivalent=True,
        )
    start_epoch = treatment_logcat.get("observer_decision_epoch_exclusive")
    end_epoch = treatment.get("observation_device_epoch_end")
    if not isinstance(start_epoch, (int, float)) or not isinstance(end_epoch, (int, float)):
        return _comparison_result(
            "INCONCLUSIVE", "The treatment device-clock bounds for the D4 window are unavailable.",
            control, treatment, config_equivalent=True,
        )
    if treatment_logcat.get("preexisting_home_anr_status") != "absent":
        return _comparison_result(
            "INCONCLUSIVE", "The treatment pre-observation ANR boundary is not affirmatively clear.",
            control, treatment, config_equivalent=True,
        )
    window_events: list[dict[str, Any]] = []
    for event in treatment_events:
        if not isinstance(event, dict) or event.get("package") not in treatment.get("home_packages_observed", []):
            return _comparison_result(
                "INCONCLUSIVE", "Treatment contains a HOME ANR event that is not fully scoped to a resolved HOME package.",
                control, treatment, config_equivalent=True,
            )
        epoch = event.get("device_epoch")
        if (
            not isinstance(epoch, (int, float))
            or event.get("timestamp_quality") not in {"device-epoch", "device-epoch-mapped"}
        ):
            return _comparison_result(
                "INCONCLUSIVE", "Treatment HOME ANR event time is not grounded in the device Logcat epoch.",
                control, treatment, config_equivalent=True,
            )
        if start_epoch <= epoch <= end_epoch:
            window_events.append(event)

    if window_events:
        return _comparison_result(
            "DIFFERENCE_CAUSAL",
            "A treatment HOME ANR was retained in the complete final dump after D4 policy selection; control stayed healthy.",
            control, treatment, config_equivalent=True, treatment_home_anr_events=window_events,
        )
    if treatment.get("home_anr") is True:
        return _comparison_result(
            "INCONCLUSIVE", "Treatment reports a HOME ANR but no event is mapped inside the selected device-clock interval.",
            control, treatment, config_equivalent=True,
        )
    if treatment.get("final_home_healthy") is not True:
        return _comparison_result(
            "INCONCLUSIVE", "The treatment did not end with verified healthy HOME state and has no mapped scoped ANR.",
            control, treatment, config_equivalent=True,
        )
    return _comparison_result(
        "DIFFERENCE_NOT_CAUSAL",
        "Both arms had complete final-dump coverage, no scoped HOME ANR, and verified healthy final HOME.",
        control, treatment, config_equivalent=True,
    )


def write_comparison_artifacts(output: Path, result: dict[str, Any]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    comparison = {
        key: result.get(key)
        for key in (
            "classification", "reason", "selected_difference_id", "selected_difference_value",
            "config_hashes", "config_equivalent_except_selected_difference", "d4_proof",
        )
    }
    selected = {
        "selected_difference": SELECTED_DIFFERENCE,
        "comparison": comparison,
    }
    matrix = {
        "selected_difference_id": "D4",
        "selected_difference": SELECTED_DIFFERENCE,
        "matrix": DIFFERENCE_MATRIX,
        "comparison": comparison,
    }
    (output / "comparison.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (output / "selected-difference.json").write_text(json.dumps(selected, indent=2) + "\n", encoding="utf-8")
    (output / "difference-matrix.json").write_text(json.dumps(matrix, indent=2) + "\n", encoding="utf-8")
    (output / "run-info.txt").write_text(
        f"CLASSIFICATION={result['classification']}\nREASON={result['reason']}\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--control", type=Path, required=True)
    parser.add_argument("--treatment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        control = json.loads(args.control.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        control = None
    try:
        treatment = json.loads(args.treatment.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        treatment = None
    result = compare_arms(control, treatment)
    write_comparison_artifacts(args.output, result)
    print(json.dumps(result, indent=2))
    return 0 if result["classification"] == "DIFFERENCE_NOT_CAUSAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
