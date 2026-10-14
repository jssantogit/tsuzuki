#!/usr/bin/env python3
"""Tests for conservative Default Skin emulator MENU A/B comparisons."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / ".github/scripts/compare_default_skin_emulator_menu_ab.py"
SPEC = importlib.util.spec_from_file_location("default_skin_menu_ab", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
comparator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(comparator)


def valid_arm(arm: str) -> dict[str, object]:
    is_treatment = arm == "treatment"
    return {
        "experiment": "default_skin_menu_ab",
        "arm": arm,
        "config_equivalence": {"equivalent": True},
        "canonical_configuration": {
            "api": 35,
            "runner_image_os": "Linux",
            "runner_image_version": "2026.10",
            "animation_values": {
                name: {"exit_code": 0, "value": "1"}
                for name in (
                    "window_animation_scale",
                    "transition_animation_scale",
                    "animator_duration_scale",
                )
            },
        },
        "observer_complete": True,
        "observation": {"target_seconds": 15, "elapsed_seconds": 15.0, "complete": True, "snapshot_count": 6},
        "home_packages_observed": ["com.android.launcher3"],
        "boot_path_stable": True,
        "final_home_healthy": True,
        "final_focus_state": "HOME",
        "home_anr": False,
        "unresolved_home_anr": False,
        "pre_menu_home_anr_status": "absent",
        "pre_menu_home_anr_events": [],
        "midpoint_screenshot_capture": {
            "label": f"{arm}-midpoint",
            "valid_png_signature": True,
            "bytes": 4,
        },
        "anr_screenshot_capture": None,
        "menu_attempted": is_treatment,
        "menu_issued": is_treatment,
        "menu_event_count": 1 if is_treatment else 0,
        "boot_t0_monotonic_ns": 1_000_000_000,
        "menu_start_monotonic_ns": 1_100_000_000 if is_treatment else None,
        "menu_end_monotonic_ns": 1_150_000_000 if is_treatment else None,
        "home_anr_events": [],
        "serial": "emulator-5554",
        "first_device_observation_after_t0_ns": 1_200_000_000,
        "menu_command": {
            "command": ["adb", "-s", "emulator-5554", "shell", "input", "keyevent", "82"],
            "exit_code": 0,
            "timed_out": False,
        } if is_treatment else {},
    }


class MenuABComparisonTest(unittest.TestCase):
    def compare(self, control: dict[str, object] | None, treatment: dict[str, object] | None) -> str:
        return str(comparator.compare_arms(control, treatment)["classification"])

    def test_single_treatment_anr_after_immediate_menu_is_menu_causal(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [
            {
                "source": "logcat",
                "host_monotonic_ns": 1_500_000_000,
                "mapped_host_monotonic_ns": 1_500_000_000,
            }
        ]
        treatment["anr_screenshot_capture"] = {
            "label": "treatment-anr",
            "valid_png_signature": True,
            "bytes": 4,
        }

        self.assertEqual("MENU_CAUSAL", self.compare(control, treatment))

    def test_treatment_pre_menu_home_anr_cannot_be_attributed_to_menu(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [{"source": "logcat", "host_monotonic_ns": 1_500_000_000}]
        treatment["pre_menu_home_anr_status"] = "present"
        treatment["pre_menu_home_anr_events"] = [
            {
                "source": "logcat",
                "host_monotonic_ns": 1_500_000_000,
                "mapped_host_monotonic_ns": 1_050_000_000,
                "device_epoch": 1_800_000_000.0,
            }
        ]
        control["anr_screenshot_capture"] = {
            "label": "control-anr",
            "valid_png_signature": True,
            "bytes": 4,
        }
        treatment["anr_screenshot_capture"] = {
            "label": "treatment-anr",
            "valid_png_signature": True,
            "bytes": 4,
        }

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_unknown_treatment_pre_menu_prefix_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["pre_menu_home_anr_status"] = "unknown"

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_matching_stable_no_anr_pair_is_menu_not_causal(self) -> None:
        self.assertEqual("MENU_NOT_CAUSAL", self.compare(valid_arm("control"), valid_arm("treatment")))

    def test_control_home_anr_is_boot_path_unstable(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        control["home_anr"] = True
        control["home_anr_events"] = [
            {
                "source": "logcat",
                "host_monotonic_ns": 1_400_000_000,
                "mapped_host_monotonic_ns": 1_400_000_000,
            }
        ]
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [
            {
                "source": "logcat",
                "host_monotonic_ns": 1_500_000_000,
                "mapped_host_monotonic_ns": 1_500_000_000,
            }
        ]
        control["anr_screenshot_capture"] = {
            "label": "control-anr",
            "valid_png_signature": True,
            "bytes": 4,
        }
        treatment["anr_screenshot_capture"] = {
            "label": "treatment-anr",
            "valid_png_signature": True,
            "bytes": 4,
        }

        self.assertEqual("BOOT_PATH_UNSTABLE", self.compare(control, treatment))

    def test_initial_unfocused_control_is_not_a_failure_if_home_recovers(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        control["boot_path_stable"] = False
        control["final_focus_state"] = "HOME"
        control["final_home_healthy"] = True

        self.assertEqual("MENU_NOT_CAUSAL", self.compare(control, treatment))

    def test_no_treatment_anr_without_final_healthy_home_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["final_home_healthy"] = False
        treatment["final_focus_state"] = "NONE"

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_missing_matched_midpoint_render_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["midpoint_screenshot_capture"] = {"label": "treatment-midpoint", "valid_png_signature": False}

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_home_anr_without_triggered_screenshot_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        treatment["home_anr"] = True
        treatment["home_anr_events"] = [
            {"source": "logcat", "host_monotonic_ns": 1_500_000_000, "mapped_host_monotonic_ns": 1_500_000_000}
        ]

        self.assertEqual("INCONCLUSIVE", self.compare(control, treatment))

    def test_missing_or_non_equivalent_evidence_is_inconclusive(self) -> None:
        control = valid_arm("control")
        treatment = valid_arm("treatment")
        cases: list[tuple[dict[str, object] | None, dict[str, object] | None]] = [
            (None, treatment),
            (control, None),
        ]
        mismatch = valid_arm("treatment")
        mismatch["canonical_configuration"] = {
            **control["canonical_configuration"],
            "api": 34,
        }
        cases.append((control, mismatch))
        non_immediate = valid_arm("treatment")
        non_immediate["menu_start_monotonic_ns"] = 3_000_000_000
        cases.append((control, non_immediate))
        observer_gap = valid_arm("treatment")
        observer_gap["observer_complete"] = False
        cases.append((control, observer_gap))

        for case in cases:
            with self.subTest(case=case):
                self.assertEqual("INCONCLUSIVE", self.compare(*case))


if __name__ == "__main__":
    unittest.main()
