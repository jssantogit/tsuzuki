#!/usr/bin/env python3
"""Tests for the D4 pre-boot Logcat observer comparison."""

from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / ".github/scripts/compare_default_skin_emulator_preboot_logcat_d4.py"
SPEC = importlib.util.spec_from_file_location("default_skin_preboot_logcat_d4", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
comparator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(comparator)


def valid_arm(arm: str) -> dict[str, object]:
    control = arm == "control"
    environment = {
        name: {"state": "unset"}
        for name in (
            "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_AVD_HOME", "ANDROID_USER_HOME",
            "ADB_SERVER_SOCKET", "ADB_SERVER_PORT", "ANDROID_SERIAL", "ANDROID_EMULATOR_PORT",
        )
    }
    config: dict[str, object] = {
        "runner_image_os": "ubuntu24",
        "runner_image_version": "2026.10.01",
        "emulator": "37.2.12.0/build 16428233",
        "system_image": "system-images;android-35;google_apis;x86_64",
        "libpulse0_package": "install ok installed 1.3.1-1ubuntu1",
        "sdkmanager_version": "12.0",
        "adb_version": "Android Debug Bridge version 1.0.41",
        "emulator_version": "37.2.12.0 (build_id 16428233)",
        "source_properties": {"AndroidVersion.ApiLevel": "35"},
        "platform_tools_source_properties": {"Pkg.Revision": "36"},
        "platform_source_properties": {"Pkg.Revision": "2"},
        "avd_default_config": {"hw.device.name": "pixel_6"},
        "avd_effective_config": {"hw.cpu.ncore": "2"},
        "avd": {"name": "test", "device": "pixel_6", "cores": 2},
        "launch": ["-port", "5554", "-avd", "test", "-no-window", "-gpu", "swiftshader_indirect"],
        "launch_arguments": ["-port", "5554", "-avd", "test", "-no-window", "-gpu", "swiftshader_indirect"],
        "acceleration": {"exit_code": 0, "kvm_usable": True},
        "host_environment": environment,
        "output_capture": {
            "tee": True,
            "file_capture": True,
            "capture_file": "emulator.log",
            "stderr_route": "stdout",
        },
        "animation_values": {
            name: {
                "exit_code": 0,
                "stdout_available": True,
                "value": value,
                "provenance": "post_observation",
            }
            for name, value in (
                ("window_animation_scale", "1.0"),
                ("transition_animation_scale", "1.0"),
                ("animator_duration_scale", "null"),
            )
        },
    }
    value: dict[str, object] = {
        "experiment": "default_skin_emulator_preboot_logcat_d4",
        "arm": arm,
        "launch_utc": "2026-10-14T12:00:00Z",
        "launch_monotonic_ns": 5,
        "emulator_pid": 456,
        "common_emulator_output": {
            "applied_monotonic_ns": 5,
            "stdout_sink": "discard",
            "tee": True,
            "file_capture": True,
            "capture_file": "emulator.log",
            "stderr_route": "stdout",
            "same_policy_in_both_arms": True,
        },
        "selected_difference_id": "D4",
        "selected_difference_value": "continuous" if control else "deferred_final_dump",
        "pre_boot_logcat_observer_enabled": control,
        "observer_pid": 123 if control else "NONE",
        "bootstrap_events": [
            {"name": "host_script_entry", "monotonic_ns": 1},
            {"name": "avd_creation_started", "monotonic_ns": 2},
            {"name": "avd_creation_completed", "monotonic_ns": 3, "exit_code": "0"},
            {
                "name": "emulator_launch_started",
                "monotonic_ns": 6,
                "launch_monotonic_ns": "5",
                "launch_utc": "2026-10-14T12:00:00Z",
                "emulator_pid": "456",
            },
        ],
        "canonical_configuration": config,
        "config_equivalence": {
            "equivalent": True,
            "checks": {
                "emulator_version": True,
                "system_image": True,
                "avd_only_cpu_override": True,
                "launch_command": True,
                "hardware_acceleration_auto": True,
                "emulator_log_read": True,
                "gfxstream_observed": True,
            },
        },
        "menu_attempted": True,
        "menu_issued": True,
        "menu_event_count": 1,
        "menu_delta_ms": 0.2,
        "menu_command": {"exit_code": 0, "timed_out": False},
        "observation": {
            "complete": True,
            "target_seconds": 15,
            "start_monotonic_ns": 20_000_000_000,
            "end_monotonic_ns": 35_100_000_000,
        },
        "preboot_logcat": {
            "policy": "continuous" if control else "deferred_final_dump",
            "pre_boot_logcat_observer_enabled": control,
            "observer_pid": 123 if control else "NONE",
            "observer_started": control,
            "observer_running_through_window": control,
            "observer_started_monotonic_ns": 1_000_000_000 if control else None,
            "observer_stop": {
                "available_at_stop": True,
                "exit_code": -15,
                "truncated": False,
                "events_dropped": 0,
            } if control else None,
            "observer_decision_epoch_exclusive": 1_000.0,
            "coverage_start_device_epoch": 990.0,
            "process_samples": [
                {
                    "exit_code": 0,
                    "stdout_available": True,
                    "stdout_nonempty": True,
                    "process_row_count": 8,
                    "adb_logcat_pids": [123] if control else [],
                    "observer_pid": 123 if control else None,
                    "observer_running": control,
                },
                {
                    "exit_code": 0,
                    "stdout_available": True,
                    "stdout_nonempty": True,
                    "process_row_count": 8,
                    "adb_logcat_pids": [123] if control else [],
                    "observer_pid": 123 if control else None,
                    "observer_running": control,
                },
            ],
            "process_proof": {
                "exit_code": 0,
                "stdout_available": True,
                "stdout_nonempty": True,
                "process_row_count": 8,
                "adb_logcat_pids": [123] if control else [],
                "observer_pid": 123 if control else None,
                "observer_running": control,
            },
            "final_dump": {
                "command": [
                    "adb", "-s", "emulator-5554", "logcat", "-d", "-b", "main", "-b",
                    "system", "-b", "events", "-v", "epoch",
                ],
                "exit_code": 0,
                "stdout_available": True,
                "stdout_nonempty": True,
                "complete": True,
                "coverage": {
                    "covers_start": True,
                    "covers_start_by_buffer": {"main": True, "system": True, "events": True},
                    "covers_end": True,
                },
                "buffer_headers_seen": ["main", "system", "events"],
                "first_device_epoch_by_buffer": {"main": 980.0, "system": 980.0, "events": 980.0},
            },
            "preexisting_home_anr_status": "absent",
        },
        "home_packages_observed": ["com.android.launcher3"],
        "home_anr": False,
        "home_anr_events": [],
        "unresolved_home_anr": False,
        "observation_device_epoch_end": 1_020.0,
        "final_home_healthy": True,
    }
    value["canonical_configuration_sha256"] = hashlib.sha256(
        json.dumps(config, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return value


class PrebootLogcatD4ComparisonTest(unittest.TestCase):
    def compare(self, control: dict[str, object], treatment: dict[str, object]) -> str:
        return str(comparator.compare_arms(control, treatment)["classification"])

    def test_equal_healthy_arms_are_not_causal(self) -> None:
        self.assertEqual("DIFFERENCE_NOT_CAUSAL", self.compare(valid_arm("control"), valid_arm("treatment")))

    def test_comparison_reports_selected_difference_and_configuration_hash_proof(self) -> None:
        result = comparator.compare_arms(valid_arm("control"), valid_arm("treatment"))

        self.assertEqual("D4", result["selected_difference_id"])
        self.assertEqual("continuous", result["selected_difference_value"]["control"])
        self.assertEqual("deferred_final_dump", result["selected_difference_value"]["treatment"])
        self.assertTrue(result["config_equivalent_except_selected_difference"])
        self.assertEqual(result["config_hashes"]["control"], result["config_hashes"]["treatment"])
        self.assertTrue(result["d4_proof"]["control"]["pre_boot_logcat_observer_enabled"])
        self.assertFalse(result["d4_proof"]["treatment"]["pre_boot_logcat_observer_enabled"])

    def test_comparison_writes_consolidated_difference_artifacts(self) -> None:
        result = comparator.compare_arms(valid_arm("control"), valid_arm("treatment"))
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory)
            comparator.write_comparison_artifacts(output, result)
            matrix = json.loads((output / "difference-matrix.json").read_text(encoding="utf-8"))
            selected = json.loads((output / "selected-difference.json").read_text(encoding="utf-8"))

        self.assertEqual("D4", selected["selected_difference"]["id"])
        self.assertIn("D4", [row["id"] for row in matrix["matrix"]])

    def test_treatment_anr_after_d4_selection_is_causal_candidate(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [{
            "package": "com.android.launcher3",
            "device_epoch": 1_010.0,
            "timestamp_quality": "device-epoch",
        }]
        self.assertEqual("DIFFERENCE_CAUSAL", self.compare(control, treatment))

    def test_control_anr_marks_boot_path_unstable(self) -> None:
        control = valid_arm("control")
        control["home_anr"] = True
        control["home_anr_events"] = [{
            "package": "com.android.launcher3",
            "device_epoch": 1_010.0,
            "timestamp_quality": "device-epoch-mapped",
        }]
        self.assertEqual("BOOT_PATH_UNSTABLE", self.compare(control, valid_arm("treatment")))

    def test_treatment_preexisting_anr_is_not_attributed_to_d4(self) -> None:
        treatment = valid_arm("treatment")
        treatment["preboot_logcat"]["preexisting_home_anr_status"] = "present"  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_missing_continuous_control_process_proof_is_inconclusive(self) -> None:
        control = valid_arm("control")
        control["preboot_logcat"]["process_proof"]["adb_logcat_pids"] = []  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(control, valid_arm("treatment")))

    def test_treatment_must_prove_no_reader_at_both_preboot_and_postwindow(self) -> None:
        treatment = valid_arm("treatment")
        treatment["preboot_logcat"]["process_samples"][0]["adb_logcat_pids"] = [321]  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_control_observer_must_run_through_window_and_stop_cleanly(self) -> None:
        control = valid_arm("control")
        control["preboot_logcat"]["observer_running_through_window"] = False  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(control, valid_arm("treatment")))

        control = valid_arm("control")
        control["preboot_logcat"]["observer_stop"]["available_at_stop"] = False  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(control, valid_arm("treatment")))

        control = valid_arm("control")
        control["preboot_logcat"]["observer_stop"]["events_dropped"] = 1  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(control, valid_arm("treatment")))

    def test_incomplete_runtime_configuration_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        del treatment["canonical_configuration"]["host_environment"]  # type: ignore[index]
        treatment["canonical_configuration_sha256"] = hashlib.sha256(
            json.dumps(
                treatment["canonical_configuration"], sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        ).hexdigest()
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_d4_arms_must_preserve_the_same_discarded_emulator_output_sink(self) -> None:
        treatment = valid_arm("treatment")
        treatment["common_emulator_output"]["stdout_sink"] = "github_actions"  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_d4_requires_actual_shell_avd_and_emulator_launch_markers(self) -> None:
        treatment = valid_arm("treatment")
        treatment["bootstrap_events"] = treatment["bootstrap_events"][:-1]  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_treatment_with_continuous_reader_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        treatment["preboot_logcat"]["observer_started"] = True  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_unavailable_or_incomplete_final_dump_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        treatment["preboot_logcat"]["final_dump"]["complete"] = False  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_missing_one_buffer_prefix_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        treatment["preboot_logcat"]["final_dump"]["coverage"]["covers_start_by_buffer"]["events"] = False  # type: ignore[index]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_home_anr_boolean_must_match_scoped_event_list(self) -> None:
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_configuration_mismatch_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        treatment["canonical_configuration"]["avd"]["cores"] = 4  # type: ignore[index]
        treatment["canonical_configuration_sha256"] = hashlib.sha256(
            json.dumps(
                treatment["canonical_configuration"], sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        ).hexdigest()
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_unmapped_or_unscoped_anr_is_inconclusive(self) -> None:
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [{
            "package": "com.android.launcher3",
            "device_epoch": None,
            "timestamp_quality": "unmapped",
        }]
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))

    def test_treatment_must_finish_with_healthy_home_for_no_anr_result(self) -> None:
        treatment = valid_arm("treatment")
        treatment["final_home_healthy"] = False
        self.assertEqual("INCONCLUSIVE", self.compare(valid_arm("control"), treatment))


if __name__ == "__main__":
    unittest.main()
