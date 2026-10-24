#!/usr/bin/env python3
"""Tests for the conservative emulator output-forwarding comparison."""

from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / ".github/scripts/compare_default_skin_emulator_output_forwarding_ab.py"
SPEC = importlib.util.spec_from_file_location("default_skin_output_forwarding_ab", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
comparator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(comparator)


def valid_arm(arm: str) -> dict[str, object]:
    treatment = arm == "treatment"
    t0 = 10_000_000_000
    launch = 1_000_000_000
    value = {
        "experiment": "default_skin_emulator_output_forwarding",
        "arm": arm,
        "config_equivalence": {"equivalent": True},
        "canonical_configuration": {
            "runner_image_os": "Linux",
            "runner_image_version": "2026.10",
            "emulator_version": "Android emulator version 37.2.12.0 (build_id 16428233)",
            "system_image": "system-images;android-35;google_apis;x86_64",
            "source_properties": {"AndroidVersion.ApiLevel": "35", "SystemImage.Abi": "x86_64"},
            "platform_tools_source_properties": {"Pkg.Revision": "36.0.0"},
            "platform_source_properties": {"Pkg.Revision": "3"},
            "libpulse0_package": "libpulse0:amd64 1.0",
            "sdkmanager_version": "sdkmanager 20.0",
            "adb_version": "Android Debug Bridge version 1.0.41",
            "avd_default_config": {"hw.device.name": "pixel_6"},
            "avd_effective_config": {"hw.device.name": "pixel_6", "hw.cpu.ncore": "2"},
            "launch_arguments": ["-port", "5554", "-avd", "test", "-no-window"],
            "acceleration": {"exit_code": 0, "kvm_usable": True},
            "host_environment": {
                "HOME": {"state": "set", "value": "runner_home"},
                "ANDROID_HOME": {"state": "set", "value": "android_sdk"},
                "ANDROID_SDK_ROOT": {"state": "unset"},
                "ANDROID_AVD_HOME": {"state": "set", "value": "avd_home"},
                "ANDROID_USER_HOME": {"state": "unset"},
                "ADB_SERVER_SOCKET": {"state": "unset"},
                "ADB_SERVER_PORT": {"state": "unset"},
                "ANDROID_SERIAL": {"state": "unset"},
                "ANDROID_EMULATOR_PORT": {"state": "unset"},
            },
            "capture": {"file": "emulator.log", "tee": True, "stderr": "stdout"},
            "output_capture": {
                "file_capture": True,
                "tee": True,
                "capture_file": "emulator.log",
                "stderr_route": "stdout",
            },
            "animation_values": {
                name: {
                    "exit_code": 0,
                    "stdout_available": True,
                    "value": "1.0",
                    "provenance": "post_observation",
                }
                for name in (
                    "window_animation_scale",
                    "transition_animation_scale",
                    "animator_duration_scale",
                )
            },
        },
        "output_forwarding": {
            "applied_monotonic_ns": launch,
            "stdout_sink": "github_actions" if treatment else "discard",
            "stderr_route": "stdout",
            "file_capture": True,
            "tee": True,
        },
        "launch_monotonic_ns": launch,
        "observer_started_monotonic_ns": 5_000_000_000,
        "observer_complete": True,
        "observer_gap_anr_unknown": False,
        "pre_forwarding_home_anr_status": "absent",
        "observation": {
            "complete": True,
            "target_seconds": 15,
            "elapsed_seconds": 15.2,
            "start_monotonic_ns": t0,
            "end_monotonic_ns": t0 + 15_200_000_000,
            "snapshot_count": 6,
        },
        "boot_t0_monotonic_ns": t0,
        "menu_attempted": True,
        "menu_issued": True,
        "menu_event_count": 1,
        "menu_start_monotonic_ns": t0 + 100_000_000,
        "menu_end_monotonic_ns": t0 + 200_000_000,
        "first_device_observation_after_t0_ns": t0 + 300_000_000,
        "serial": "emulator-5554",
        "menu_command": {
            "command": ["adb", "-s", "emulator-5554", "shell", "input", "keyevent", "82"],
            "exit_code": 0,
            "timed_out": False,
        },
        "home_packages_observed": ["com.android.launcher3"],
        "unresolved_home_anr": False,
        "home_anr": False,
        "home_anr_events": [],
        "first_home_focus_observed": True,
        "final_home_healthy": True,
        "screenshots": {
            name: {"valid_png_signature": True, "bytes": 1024}
            for name in ("boot", "first_home", "midpoint", "final")
        },
    }
    value["config_equivalence"] = {
        "equivalent": True,
        "checks": {key: True for key in (
            "emulator_version", "system_image", "avd_only_cpu_override",
            "launch_command", "hardware_acceleration_auto", "emulator_log_read", "gfxstream_observed",
        )},
    }
    seal_configuration(value)
    return value


def seal_configuration(arm: dict[str, object]) -> None:
    configuration = arm["canonical_configuration"]
    arm["canonical_configuration_sha256"] = hashlib.sha256(
        json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


class OutputForwardingComparisonTest(unittest.TestCase):
    def compare(self, control: dict[str, object], treatment: dict[str, object]) -> str:
        return str(comparator.compare_arms(control, treatment)["classification"])

    def test_treatment_home_anr_after_forwarding_before_menu_is_causal_candidate(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [{
            "source": "logcat",
            "mapped_host_monotonic_ns": 5_500_000_000,
            "package": "com.android.launcher3",
        }]
        treatment["first_home_focus_observed"] = False
        treatment["screenshots"]["anr"] = {"valid_png_signature": True, "bytes": 1024}

        self.assertEqual("DIFFERENCE_CAUSAL", self.compare(control, treatment))

    def test_no_anr_and_healthy_final_home_is_not_causal(self) -> None:
        self.assertEqual("DIFFERENCE_NOT_CAUSAL", self.compare(valid_arm("control"), valid_arm("treatment")))

    def test_anr_capture_does_not_require_a_home_focus_screenshot(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["final_home_healthy"] = False
        treatment["first_home_focus_observed"] = False
        treatment["home_anr_events"] = [{
            "source": "logcat",
            "mapped_host_monotonic_ns": 5_500_000_000,
            "package": "com.android.launcher3",
        }]
        treatment["screenshots"]["anr"] = {"valid_png_signature": True, "bytes": 1024}
        del treatment["screenshots"]["first_home"]

        self.assertEqual("DIFFERENCE_CAUSAL", self.compare(control, treatment))

    def test_first_home_capture_is_required_only_after_home_focus_was_observed(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["first_home_focus_observed"] = False
        treatment["final_home_healthy"] = False
        del treatment["screenshots"]["first_home"]

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_control_home_anr_is_boot_path_unstable(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        control["home_anr"] = True
        control["home_anr_events"] = [{
            "source": "logcat",
            "mapped_host_monotonic_ns": 5_500_000_000,
            "package": "com.android.launcher3",
        }]
        control["screenshots"]["anr"] = {"valid_png_signature": True, "bytes": 1024}

        self.assertEqual("BOOT_PATH_UNSTABLE", self.compare(control, treatment))

    def test_pre_forwarding_or_unmapped_anr_is_inconclusive(self) -> None:
        for event in (
            {"source": "logcat", "mapped_host_monotonic_ns": 900_000_000, "package": "com.android.launcher3"},
            {"source": "snapshot", "host_monotonic_ns": 5_500_000_000, "package": "com.android.launcher3"},
        ):
            control = valid_arm("control")
            treatment = valid_arm("treatment")
            treatment["home_anr"] = True
            treatment["home_anr_events"] = [event]
            treatment["screenshots"]["anr"] = {"valid_png_signature": True, "bytes": 1024}
            with self.subTest(event=event):
                self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_unknown_observer_gap_or_mismatched_config_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["observer_gap_anr_unknown"] = True
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["canonical_configuration"]["api"] = 34
        seal_configuration(treatment)
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_wrong_sink_or_missing_screenshot_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["output_forwarding"]["stdout_sink"] = "discard"
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        del treatment["screenshots"]["first_home"]
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_missing_runtime_or_environment_proof_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        del treatment["canonical_configuration"]["host_environment"]["ANDROID_SERIAL"]
        seal_configuration(treatment)
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["canonical_configuration"]["animation_values"]["animator_duration_scale"]["exit_code"] = 20
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_arm_rejects_incomplete_configuration_and_bad_configuration_hash(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["canonical_configuration"]["emulator_version"] = ""
        seal_configuration(treatment)
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["canonical_configuration_sha256"] = "0" * 64
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_arm_requires_complete_equivalence_environment_and_measured_duration(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        del treatment["config_equivalence"]["checks"]["gfxstream_observed"]
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["canonical_configuration"]["host_environment"]["HOME"]["value"] = ""
        seal_configuration(treatment)
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["observation"]["elapsed_seconds"] = 15
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_malformed_nested_arm_evidence_fails_closed(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["observation"] = None
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = ["not-an-event-object"]
        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_each_matrix_row_has_pinned_source_and_historical_timing_provenance(self) -> None:
        allowed_phases = {
            "PRE_BOOT",
            "BOOT_BOUNDARY",
            "POST_BOOT_PRE_ANR",
            "POST_ANR",
            "UNKNOWN",
        }
        required = {
            "layer", "manual", "reactive_circus", "phase",
            "occurs_before_historical_anr", "independently_reproducible",
            "source_refs", "upstream_action_source", "historical_log_path",
            "historical_log_evidence", "historical_anr_reference",
        }
        for row in comparator.DIFFERENCE_MATRIX:
            with self.subTest(row=row["id"]):
                self.assertTrue(required.issubset(row))
                self.assertIn(row["phase"], allowed_phases)
                self.assertEqual(comparator.UPSTREAM_ACTION_PIN, row["upstream_action_source"]["pin"])
                self.assertTrue(row["source_refs"])
                self.assertIn("status", row["historical_log_evidence"])
                self.assertIn("timestamp_utc", row["historical_anr_reference"])

        rows = {row["id"]: row for row in comparator.DIFFERENCE_MATRIX}
        animation_reads = rows["D5"]
        self.assertEqual("PRE_BOOT", animation_reads["phase"])
        self.assertIn("both control and treatment", animation_reads["manual"].lower())
        for setting in (
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        ):
            self.assertIn(setting, animation_reads["manual"])
        self.assertIn("after MENU", animation_reads["reactive_circus"])
        self.assertEqual("POST_ANR", rows["D8"]["phase"])
        self.assertEqual("POST_ANR", rows["D9"]["phase"])
        self.assertEqual("UNKNOWN", rows["D10"]["phase"])
        self.assertEqual("UNKNOWN", rows["D11"]["phase"])
        self.assertIn(
            f"{comparator.UPSTREAM_ACTION_URL}/src/emulator-manager.ts#L86-L100",
            rows["D6"]["source_refs"],
        )
        unresolved = {row["id"] for row in comparator.DIFFERENCE_MATRIX
                      if row["occurs_before_historical_anr"] is None}
        self.assertEqual({"D7", "D10", "D11"}, unresolved)

    def test_comparison_reports_only_validated_canonical_config_match(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")

        matched = comparator.compare_arms(control, treatment)
        self.assertIs(True, matched["config_equivalent_except_selected_difference"])

        treatment["canonical_configuration"]["runner_image_version"] = "different"
        seal_configuration(treatment)
        mismatched = comparator.compare_arms(control, treatment)
        self.assertEqual("INCONCLUSIVE", mismatched["classification"])
        self.assertIs(False, mismatched["config_equivalent_except_selected_difference"])

        unvalidated = comparator.compare_arms(None, valid_arm("treatment"))
        self.assertIsNone(unvalidated["config_equivalent_except_selected_difference"])


if __name__ == "__main__":
    unittest.main()
