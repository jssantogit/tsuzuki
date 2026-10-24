#!/usr/bin/env python3
"""Compare two matched fresh-emulator observations without inferring causation from missing data."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


VALID_CLASSES = {"MENU_CAUSAL", "MENU_NOT_CAUSAL", "BOOT_PATH_UNSTABLE", "INCONCLUSIVE"}
MAX_IMMEDIATE_MENU_DELTA_NS = 1_000_000_000


def _valid_arm(arm: dict[str, Any] | None, expected_arm: str) -> str | None:
    if arm is None:
        return f"The {expected_arm} arm result is missing or unreadable."
    if arm.get("experiment") != "default_skin_menu_ab" or arm.get("arm") != expected_arm:
        return f"The supplied result is not the expected {expected_arm} arm."
    if arm.get("config_equivalence", {}).get("equivalent") is not True:
        return f"The {expected_arm} emulator configuration did not match the recorded reference."
    if not isinstance(arm.get("canonical_configuration"), dict):
        return f"The {expected_arm} canonical emulator configuration is missing."
    configuration = arm["canonical_configuration"]
    if not configuration.get("runner_image_os") or not configuration.get("runner_image_version"):
        return f"The {expected_arm} hosted runner image identity is missing."
    animation_values = configuration.get("animation_values")
    required_animations = {
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    }
    if not isinstance(animation_values, dict) or not required_animations.issubset(animation_values):
        return f"The {expected_arm} animation-policy values are missing."
    if any(
        not isinstance(animation_values[name], dict)
        or animation_values[name].get("exit_code") != 0
        or animation_values[name].get("stdout_available") is not True
        or animation_values[name].get("value") in (None, "")
        or animation_values[name].get("provenance") != "post_observation"
        for name in required_animations
    ):
        return f"The {expected_arm} animation-policy values were not successfully observed."
    if arm.get("observer_complete") is not True:
        return f"The {expected_arm} log observer did not cover the full interval."
    observation = arm.get("observation", {})
    if (
        observation.get("complete") is not True
        or observation.get("target_seconds") != 15
        or not isinstance(observation.get("elapsed_seconds"), (int, float))
        or observation["elapsed_seconds"] < 15
    ):
        return f"The {expected_arm} arm did not complete the common 15-second observation window."
    if not isinstance(observation.get("snapshot_count"), int) or observation["snapshot_count"] < 2:
        return f"The {expected_arm} arm has too few in-window device snapshots to scope HOME state."
    if not isinstance(arm.get("home_packages_observed"), list) or not arm["home_packages_observed"]:
        return f"The {expected_arm} arm did not resolve a HOME package during the observation window."
    if arm.get("unresolved_home_anr") is not False:
        return f"HOME ANR evidence in the {expected_arm} arm could not be completely scoped."
    if not isinstance(arm.get("home_anr"), bool):
        return f"HOME ANR status for the {expected_arm} arm is unknown."
    if arm.get("boot_path_stable") not in (True, False, None):
        return f"The {expected_arm} boot-path stability state is invalid."
    home_anr_events = arm.get("home_anr_events")
    if not isinstance(home_anr_events, list) or (arm["home_anr"] and not home_anr_events):
        return f"HOME ANR event records for the {expected_arm} arm are missing or inconsistent."
    if not arm["home_anr"] and home_anr_events:
        return f"HOME ANR event records disagree with the {expected_arm} arm's ANR summary."
    midpoint = arm.get("midpoint_screenshot_capture")
    if (
        not isinstance(midpoint, dict)
        or midpoint.get("label") != f"{expected_arm}-midpoint"
        or midpoint.get("valid_png_signature") is not True
        or not isinstance(midpoint.get("bytes"), int)
        or midpoint["bytes"] <= 0
    ):
        return f"The {expected_arm} arm did not capture its required midpoint diagnostic screenshot."
    anr_screenshot = arm.get("anr_screenshot_capture")
    if arm["home_anr"] and (
        not isinstance(anr_screenshot, dict)
        or anr_screenshot.get("label") != f"{expected_arm}-anr"
        or anr_screenshot.get("valid_png_signature") is not True
        or not isinstance(anr_screenshot.get("bytes"), int)
        or anr_screenshot["bytes"] <= 0
    ):
        return f"The {expected_arm} HOME ANR has no valid event-triggered diagnostic screenshot."
    if expected_arm == "treatment":
        prefix_status = arm.get("pre_menu_home_anr_status")
        prefix_events = arm.get("pre_menu_home_anr_events")
        if prefix_status not in {"absent", "present", "unknown"} or not isinstance(prefix_events, list):
            return "Treatment pre-MENU HOME ANR prefix evidence is missing or malformed."
        if (prefix_status == "present") != bool(prefix_events):
            return "Treatment pre-MENU HOME ANR status disagrees with its event records."
    if arm.get("final_home_healthy") not in (True, False, None):
        return f"The {expected_arm} final HOME health state is invalid."
    return None


def _menu_is_immediate(treatment: dict[str, Any]) -> bool:
    if (
        treatment.get("menu_attempted") is not True
        or treatment.get("menu_issued") is not True
        or treatment.get("menu_event_count") != 1
        or treatment.get("menu_command", {}).get("command")
        != ["adb", "-s", treatment.get("serial"), "shell", "input", "keyevent", "82"]
    ):
        return False
    t0 = treatment.get("boot_t0_monotonic_ns")
    menu_start = treatment.get("menu_start_monotonic_ns")
    menu_end = treatment.get("menu_end_monotonic_ns")
    first_device_observation = treatment.get("first_device_observation_after_t0_ns")
    if not all(isinstance(value, int) for value in (t0, menu_start, menu_end, first_device_observation)):
        return False
    if treatment.get("menu_command", {}).get("exit_code") != 0 or treatment.get("menu_command", {}).get("timed_out") is True:
        return False
    return (
        t0 <= menu_start <= t0 + MAX_IMMEDIATE_MENU_DELTA_NS
        and menu_end > menu_start
        and menu_start < first_device_observation
    )


def compare_arms(
    control: dict[str, Any] | None,
    treatment: dict[str, Any] | None,
) -> dict[str, Any]:
    """Return a conservative A/B label; malformed or incomplete evidence is INCONCLUSIVE."""
    for arm, expected in ((control, "control"), (treatment, "treatment")):
        error = _valid_arm(arm, expected)
        if error:
            return {"classification": "INCONCLUSIVE", "reason": error}

    assert control is not None and treatment is not None
    if control.get("menu_attempted") is not False or control.get("menu_event_count") != 0:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "The control arm did not preserve the zero-input contract.",
        }
    if not _menu_is_immediate(treatment):
        return {
            "classification": "INCONCLUSIVE",
            "reason": "The treatment arm did not record exactly one immediate MENU before any device observation.",
        }
    if control["canonical_configuration"] != treatment["canonical_configuration"]:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "The control and treatment emulator configurations are not identical.",
        }
    control_anr = control["home_anr"]
    treatment_anr = treatment["home_anr"]
    if control_anr:
        control_t0 = control.get("boot_t0_monotonic_ns")
        control_events = control.get("home_anr_events", [])
        precise_control_event = any(
            event.get("source") == "logcat"
            and isinstance(event.get("mapped_host_monotonic_ns"), int)
            and isinstance(control_t0, int)
            and control_t0 <= event["mapped_host_monotonic_ns"] <= control_t0 + 15_000_000_000
            for event in control_events
        )
        if not precise_control_event:
            return {
                "classification": "INCONCLUSIVE",
                "reason": "Control HOME ANR evidence lacks a precise logcat event in the observation window.",
            }
        return {
            "classification": "BOOT_PATH_UNSTABLE",
            "reason": "A HOME ANR occurred in the zero-input control, so startup instability confounds the MENU comparison.",
        }

    treatment_prefix_status = treatment["pre_menu_home_anr_status"]
    if treatment_prefix_status == "present":
        return {
            "classification": "INCONCLUSIVE",
            "reason": "Treatment already recorded a HOME ANR before MENU, so the later event cannot be attributed to MENU.",
        }
    if treatment_prefix_status == "unknown":
        return {
            "classification": "INCONCLUSIVE",
            "reason": "Treatment pre-MENU HOME ANR timing or scope is unknown.",
        }

    if control.get("final_home_healthy") is not True:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "The control had no HOME ANR but did not finish on a positively healthy HOME state.",
        }
    if treatment_anr:
        events = treatment.get("home_anr_events", [])
        menu_start = treatment["menu_start_monotonic_ns"]
        t0 = treatment["boot_t0_monotonic_ns"]
        end = t0 + 15_000_000_000
        precise_after_menu = any(
            event.get("source") == "logcat"
            and isinstance(event.get("mapped_host_monotonic_ns"), int)
            and menu_start <= event["mapped_host_monotonic_ns"] <= end
            for event in events
        )
        if not precise_after_menu:
            return {
                "classification": "INCONCLUSIVE",
                "reason": "Treatment HOME ANR evidence lacks a precise logcat timestamp after the MENU event.",
            }
    control_t0 = control.get("boot_t0_monotonic_ns")
    control_events = control.get("home_anr_events", [])
    if not treatment_anr and treatment.get("final_home_healthy") is not True:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "Treatment had no HOME ANR but did not finish on a positively healthy HOME state.",
        }
    if treatment_anr and not control_anr:
        return {
            "classification": "MENU_CAUSAL",
            "reason": "A HOME ANR was observed only in treatment, after the immediate MENU event, with stable control boot and matched configuration.",
        }
    return {
        "classification": "MENU_NOT_CAUSAL",
        "reason": "A matched stable control shows the HOME ANR was absent or also occurred without MENU.",
    }


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
    result["arm_evidence"] = {
        "control": summarize_arm(control),
        "treatment": summarize_arm(treatment),
    }
    result["comparison_evidence"] = {
        "canonical_configuration_matches": bool(
            control is not None
            and treatment is not None
            and control.get("canonical_configuration") == treatment.get("canonical_configuration")
        ),
        "control_to_treatment_home_anr": [
            control.get("home_anr") if control else None,
            treatment.get("home_anr") if treatment else None,
        ],
        "treatment_pre_menu_home_anr_status": (
            treatment.get("pre_menu_home_anr_status") if treatment else None
        ),
        "treatment_pre_menu_home_anr_events": (
            treatment.get("pre_menu_home_anr_events") if treatment else None
        ),
        "control_to_treatment_final_home_health": [
            control.get("final_home_healthy") if control else None,
            treatment.get("final_home_healthy") if treatment else None,
        ],
        "control_to_treatment_final_focus": [
            control.get("final_focus_state") if control else None,
            treatment.get("final_focus_state") if treatment else None,
        ],
        "treatment_menu_delta_ms": treatment.get("menu_delta_ms") if treatment else None,
    }
    result["evidence_paths"] = {
        "control": str(args.control),
        "treatment": str(args.treatment),
    }
    result["classification"] = result.get("classification", "INCONCLUSIVE")
    if result["classification"] not in VALID_CLASSES:
        result = {
            "classification": "INCONCLUSIVE",
            "reason": "Comparator produced an invalid classification.",
            "evidence_paths": result["evidence_paths"],
        }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "ab-result.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (args.output / "run-info.txt").write_text(
        f"CLASSIFICATION={result['classification']}\nREASON={result['reason']}\n",
        encoding="utf-8",
    )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


def summarize_arm(arm: dict[str, Any] | None) -> dict[str, Any] | None:
    if arm is None:
        return None
    return {
        "arm": arm.get("arm"),
        "config_equivalent": arm.get("config_equivalence", {}).get("equivalent"),
        "canonical_configuration_sha256": arm.get("canonical_configuration_sha256"),
        "boot_t0_monotonic_ns": arm.get("boot_t0_monotonic_ns"),
        "menu_start_monotonic_ns": arm.get("menu_start_monotonic_ns"),
        "menu_delta_ms": arm.get("menu_delta_ms"),
        "menu_event_count": arm.get("menu_event_count"),
        "observer_complete": arm.get("observer_complete"),
        "observation": arm.get("observation"),
        "home_packages_observed": arm.get("home_packages_observed"),
        "focus_samples": arm.get("focus_samples"),
        "final_focus_state": arm.get("final_focus_state"),
        "final_home_healthy": arm.get("final_home_healthy"),
        "home_anr": arm.get("home_anr"),
        "unresolved_home_anr": arm.get("unresolved_home_anr"),
        "home_anr_events": arm.get("home_anr_events"),
        "midpoint_screenshot_capture": arm.get("midpoint_screenshot_capture"),
        "anr_screenshot_capture": arm.get("anr_screenshot_capture"),
        "pre_menu_home_anr_status": arm.get("pre_menu_home_anr_status"),
        "pre_menu_home_anr_events": arm.get("pre_menu_home_anr_events"),
        "pre_menu_home_anr_unresolved_events": arm.get("pre_menu_home_anr_unresolved_events"),
    }


if __name__ == "__main__":
    raise SystemExit(main())
