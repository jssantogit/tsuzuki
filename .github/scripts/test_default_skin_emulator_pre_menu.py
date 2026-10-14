#!/usr/bin/env python3
"""Tests for conservative pre-menu emulator diagnostic classification."""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / ".github/scripts/probe_default_skin_emulator_pre_menu.py"
SPEC = importlib.util.spec_from_file_location("default_skin_pre_menu_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class ProbeClassificationTest(unittest.TestCase):
    def classify(self, **overrides: object) -> dict[str, object]:
        inputs: dict[str, object] = {
            "config_equivalent": True,
            "observer_available": True,
            "pre_menu_anr": False,
            "stable_pre_menu_health": True,
            "stable_post_menu_health": True,
            "menu_issued": True,
            "post_menu_anr": False,
        }
        inputs.update(overrides)
        return probe.classify_probe(**inputs)

    def test_pre_menu_anr_stops_before_menu_and_is_reported(self) -> None:
        result = self.classify(pre_menu_anr=True, menu_issued=False)

        self.assertEqual("PRE_MENU_ANR", result["classification"])
        self.assertFalse(result["menu_should_run"])

    def test_positive_pre_menu_anr_does_not_require_complete_observer(self) -> None:
        result = self.classify(pre_menu_anr=True, observer_available=False, menu_issued=False)

        self.assertEqual("PRE_MENU_ANR", result["classification"])

    def test_metadata_loader_reads_the_installed_emulator_log_for_equivalence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            log_path = root / "emulator.log"
            log_path.write_text("renderer= gfxstream\n", encoding="utf-8")
            metadata_path = root / "run-metadata.json"
            metadata_path.write_text(
                json.dumps({"emulator_log_path": str(log_path)}),
                encoding="utf-8",
            )

            metadata = probe.load_run_metadata(metadata_path)

        self.assertIn("gfxstream", metadata["emulator_log"])

    def test_equivalence_requires_the_recorded_emulator_image_and_launch_profile(self) -> None:
        metadata = {
            "emulator_version": "Android emulator version 37.2.12.0\nbuild_id 16428233",
            "emulator_version_exit": 0,
            "source_properties": (
                "AndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\nSystemImage.TagId=google_apis\n"
            ),
            "system_image": "system-images;android-35;google_apis;x86_64",
            "avd_default_config": "hw.cpu.ncore=4\n",
            "avd_effective_config": "hw.cpu.ncore=4\nhw.cpu.ncore=2\n",
            "launch_command": (
                "/opt/android/emulator/emulator -port 5554 -avd test -no-window -gpu swiftshader_indirect "
                "-no-snapshot -noaudio -no-boot-anim"
            ),
            "accel_check": "KVM is installed and usable",
            "accel_check_exit": 0,
            "emulator_log_available": True,
            "emulator_log": "renderer gfxstream",
        }

        self.assertTrue(probe.config_equivalence(metadata)["equivalent"])
        metadata["emulator_version"] = "Android emulator version 37.3.0\nbuild_id 999"
        self.assertFalse(probe.config_equivalence(metadata)["equivalent"])

    def test_failed_emulator_version_command_cannot_claim_configuration_equivalence(self) -> None:
        metadata = {
            "emulator_version": "Android emulator version 37.2.12.0\nbuild_id 16428233",
            "emulator_version_exit": 127,
            "source_properties": (
                "AndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\nSystemImage.TagId=google_apis\n"
            ),
            "system_image": "system-images;android-35;google_apis;x86_64",
            "avd_default_config": "hw.cpu.ncore=4\n",
            "avd_effective_config": "hw.cpu.ncore=4\nhw.cpu.ncore=2\n",
            "launch_command": (
                "/opt/android/emulator/emulator -port 5554 -avd test -no-window -gpu swiftshader_indirect "
                "-no-snapshot -noaudio -no-boot-anim"
            ),
            "accel_check": "KVM is installed and usable",
            "accel_check_exit": 0,
            "emulator_log_available": True,
            "emulator_log": "renderer gfxstream",
        }

        self.assertFalse(probe.config_equivalence(metadata)["equivalent"])

    def test_missing_initial_focus_never_claims_healthy_or_menu_causation(self) -> None:
        result = self.classify(stable_pre_menu_health=None, post_menu_anr=True)

        self.assertEqual("INCONCLUSIVE", result["classification"])
        self.assertIn("health", result["reason"].lower())

    def test_anr_for_unresolved_package_is_not_attributed_to_launcher(self) -> None:
        result = self.classify(unresolved_anr=True, post_menu_anr=True)

        self.assertEqual("INCONCLUSIVE", result["classification"])
        self.assertIn("could not be scoped", result["reason"])

    def test_anr_after_menu_is_correlated_only_after_stable_pre_menu_health(self) -> None:
        result = self.classify(post_menu_anr=True)

        self.assertEqual("MENU_TRIGGERED_ANR", result["classification"])

    def test_post_menu_anr_is_not_attributed_when_observer_was_incomplete(self) -> None:
        result = self.classify(post_menu_anr=True, observer_available=False)

        self.assertEqual("INCONCLUSIVE", result["classification"])
        self.assertIn("pre-menu ANR absence", result["reason"])

    def test_no_anr_is_reported_only_for_equivalent_healthy_observed_run(self) -> None:
        result = self.classify()

        self.assertEqual("NO_ANR", result["classification"])

    def test_no_anr_requires_positive_post_menu_home_health(self) -> None:
        result = self.classify(stable_post_menu_health=None)

        self.assertEqual("INCONCLUSIVE", result["classification"])
        self.assertIn("Post-menu HOME health", result["reason"])

    def test_config_mismatch_or_unavailable_observer_is_inconclusive(self) -> None:
        self.assertEqual("INCONCLUSIVE", self.classify(config_equivalent=False)["classification"])
        self.assertEqual("INCONCLUSIVE", self.classify(observer_available=False)["classification"])

    def test_no_menu_without_pre_menu_anr_is_inconclusive(self) -> None:
        result = self.classify(menu_issued=False)

        self.assertEqual("INCONCLUSIVE", result["classification"])

    def test_anr_scope_separates_launcher_other_package_and_unresolved_events(self) -> None:
        events = [
            {"line": "ActivityManager: ANR in com.example.launcher"},
            {"line": "ActivityManager: ANR in com.example.other"},
            {"line": "am_anr: details unavailable"},
            {"line": "am_anr: [0, 1234, com.example.launcher, 0, 0]"},
            {"line": "I am_anr  : [0, 1174, com.example.launcher, 0, 0]"},
        ]

        scoped = probe.scope_anr_events(events, "com.example.launcher")

        self.assertEqual(3, len(scoped["home"]))
        self.assertEqual(1, len(scoped["other"]))
        self.assertEqual(1, len(scoped["unknown"]))

    def test_anr_dialog_title_containing_home_package_is_not_healthy_home_focus(self) -> None:
        sections = {
            "HOME": {"exit_code": 0, "lines": ["com.example.launcher/.Home"]},
            "WINDOW": {
                "exit_code": 0,
                "lines": ["mCurrentFocus=Window{a u0 Application Not Responding: com.example.launcher}"],
            },
            "ACTIVITY": {"exit_code": 0, "lines": ["topResumedActivity=ActivityRecord{ com.example.launcher/.Home}"]},
            "HOME_PID": {"exit_code": 0, "lines": ["1234"]},
            "POWER": {"exit_code": 0, "lines": ["mWakefulness=Awake"]},
            "KEYGUARD": {"exit_code": 0, "lines": ["KeyguardServiceDelegate: showing=false, inputRestricted=false"]},
            "PROVISIONED": {"exit_code": 0, "lines": ["1"]},
            "USER_SETUP": {"exit_code": 0, "lines": ["1"]},
        }

        health = probe.snapshot_health(sections)

        self.assertEqual("OTHER", health["focus_state"])
        self.assertIsNone(health["focus_owner_package"])
        self.assertFalse(health["stable_pre_menu_health"])

    def test_home_health_requires_focused_home_and_live_process(self) -> None:
        sections = {
            "HOME": {"exit_code": 0, "lines": ["com.example.launcher/.Home"]},
            "WINDOW": {"exit_code": 0, "lines": ["mCurrentFocus=Window{a u0 com.example.other/.Other}"]},
            "ACTIVITY": {"exit_code": 0, "lines": ["topResumedActivity=ActivityRecord{ com.example.launcher/.Home}"]},
            "HOME_PID": {"exit_code": 0, "lines": ["1234"]},
            "POWER": {"exit_code": 0, "lines": ["mWakefulness=Awake"]},
            "KEYGUARD": {"exit_code": 0, "lines": ["KeyguardServiceDelegate: showing=false, inputRestricted=false"]},
            "PROVISIONED": {"exit_code": 0, "lines": ["1"]},
            "USER_SETUP": {"exit_code": 0, "lines": ["1"]},
        }

        health = probe.snapshot_health(sections)

        self.assertEqual("OTHER", health["focus_state"])
        self.assertFalse(health["stable_pre_menu_health"])

    def test_failed_outer_snapshot_cannot_claim_health_from_partial_stdout(self) -> None:
        sections = {
            "HOME": {"exit_code": 0, "lines": ["com.example.launcher/.Home"]},
            "WINDOW": {"exit_code": 0, "lines": ["mCurrentFocus=Window{a u0 com.example.launcher/.Home}"]},
            "ACTIVITY": {"exit_code": 0, "lines": ["topResumedActivity=ActivityRecord{ com.example.launcher/.Home}"]},
            "HOME_PID": {"exit_code": 0, "lines": ["1234"]},
            "POWER": {"exit_code": 0, "lines": ["mWakefulness=Awake"]},
            "KEYGUARD": {"exit_code": 0, "lines": ["KeyguardServiceDelegate: showing=false, inputRestricted=false"]},
            "PROVISIONED": {"exit_code": 0, "lines": ["1"]},
            "USER_SETUP": {"exit_code": 0, "lines": ["1"]},
        }

        health = probe.snapshot_health_for_command(
            sections,
            adb_exit_code=1,
            stdout_available=True,
        )

        self.assertIsNone(health["stable_pre_menu_health"])
        self.assertIsNone(health["focus_state"])

    def test_snapshot_anr_output_is_ignored_when_outer_adb_command_failed(self) -> None:
        snapshot = {
            "label": "pre-final",
            "adb_exit_code": 1,
            "stdout_available": True,
            "sections": {"LAST_ANR": {"exit_code": 0, "lines": ["ANR in com.example.launcher"]}},
        }

        self.assertEqual([], probe.snapshot_anr_events(snapshot))

    def test_nonzero_home_pid_lookup_remains_unknown(self) -> None:
        sections = {
            "HOME": {"exit_code": 0, "lines": ["com.example.launcher/.Home"]},
            "HOME_PID": {"exit_code": 1, "lines": []},
        }

        health = probe.snapshot_health(sections)

        self.assertIsNone(health["home_process_alive"])

    def test_anr_scope_tracks_all_home_packages_observed_during_boot_transition(self) -> None:
        events = [
            {"line": "ANR in com.google.android.setup"},
            {"line": "ANR in com.android.launcher3"},
            {"line": "ANR in com.example.unrelated"},
        ]

        scoped = probe.scope_anr_events_to_packages(
            events,
            {"com.google.android.setup", "com.android.launcher3"},
        )

        self.assertEqual(2, len(scoped["home"]))
        self.assertEqual(1, len(scoped["other"]))
        self.assertEqual([], scoped["unknown"])

    def test_treatment_prefix_uses_device_event_time_not_buffered_logcat_receipt(self) -> None:
        snapshots = [
            {
                "adb_exit_code": 0,
                "stdout_available": True,
                "started_monotonic_ns": 10_000_000_000,
                "ended_monotonic_ns": 12_000_000_000,
                "sections": {"DEVICE_EPOCH": {"exit_code": 0, "lines": ["1000.0"]}},
            }
        ]
        received_after_menu = {
            "source": "logcat",
            "host_monotonic_ns": 20_000_000_000,
            "device_epoch": 1000.5,
            "line": "ActivityManager: ANR in com.example.launcher",
        }
        timestamped = probe.timestamp_anr_events([received_after_menu], snapshots)
        prefix = probe.classify_pre_menu_home_anrs(
            timestamped,
            [],
            {"com.example.launcher"},
            menu_start_monotonic_ns=15_000_000_000,
            observer_complete=True,
            observer_start_monotonic_ns=9_000_000_000,
        )

        self.assertEqual(11_500_000_000, timestamped[0]["mapped_host_monotonic_ns"])
        self.assertEqual(20_000_000_000, timestamped[0]["host_monotonic_ns"])
        self.assertEqual("present", prefix["status"])

    def test_treatment_prefix_is_unknown_when_home_anr_time_cannot_be_mapped(self) -> None:
        prefix = probe.classify_pre_menu_home_anrs(
            [
                {
                    "source": "logcat",
                    "host_monotonic_ns": 20_000_000_000,
                    "mapped_host_monotonic_ns": None,
                    "line": "ActivityManager: ANR in com.example.launcher",
                }
            ],
            [],
            {"com.example.launcher"},
            menu_start_monotonic_ns=15_000_000_000,
            observer_complete=True,
            observer_start_monotonic_ns=9_000_000_000,
        )

        self.assertEqual("unknown", prefix["status"])

    def test_retained_snapshot_anr_requires_a_matching_timestamped_home_event(self) -> None:
        snapshot_events = [
            {
                "source": "snapshot:menu-03:LAST_ANR",
                "line": "ANR in com.example.launcher: Input dispatching timed out",
            }
        ]
        matching_live_event = {
            "source": "logcat",
            "line": "ActivityManager: ANR in com.example.launcher: Input dispatching timed out",
            "mapped_host_monotonic_ns": 12_000_000_000,
        }
        unmatched_live_event = {
            "source": "logcat",
            "line": "ActivityManager: ANR in com.example.other",
            "mapped_host_monotonic_ns": 12_000_000_000,
        }
        mismatched_reason_event = {
            "source": "logcat",
            "line": "ActivityManager: ANR in com.example.launcher: Executing service timed out",
            "mapped_host_monotonic_ns": 12_000_000_000,
        }

        self.assertEqual(
            [],
            probe.uncorroborated_snapshot_home_anrs(
                snapshot_events, [matching_live_event], {"com.example.launcher"}
            ),
        )
        self.assertEqual(
            snapshot_events,
            probe.uncorroborated_snapshot_home_anrs(
                snapshot_events, [unmatched_live_event], {"com.example.launcher"}
            ),
        )
        self.assertEqual(
            snapshot_events,
            probe.uncorroborated_snapshot_home_anrs(
                snapshot_events, [mismatched_reason_event], {"com.example.launcher"}
            ),
        )
        matched_prefix = probe.classify_pre_menu_home_anrs(
            [matching_live_event],
            snapshot_events,
            {"com.example.launcher"},
            menu_start_monotonic_ns=11_000_000_000,
            observer_complete=True,
            observer_start_monotonic_ns=9_000_000_000,
        )
        unmatched_prefix = probe.classify_pre_menu_home_anrs(
            [unmatched_live_event],
            snapshot_events,
            {"com.example.launcher"},
            menu_start_monotonic_ns=11_000_000_000,
            observer_complete=True,
            observer_start_monotonic_ns=9_000_000_000,
        )

        self.assertEqual("absent", matched_prefix["status"])
        self.assertEqual("unknown", unmatched_prefix["status"])

    def test_output_forwarding_scope_is_extended_but_legacy_menu_scope_is_unchanged(self) -> None:
        snapshot_event = {
            "source": "snapshot:menu-03:LAST_ANR",
            "line": "ANR in com.example.launcher: Input dispatching timed out",
        }
        legacy_window = probe.selected_anr_observation_window(
            False, 1_000, 10_000, 26_000, 25_000
        )
        output_window = probe.selected_anr_observation_window(
            True, 1_000, 10_000, 26_000, 25_000
        )
        legacy_scope = probe.scope_observation_anrs(
            [], [snapshot_event], [], {"com.example.launcher"}, output_forwarding_ab=False
        )
        output_scope = probe.scope_observation_anrs(
            [], [snapshot_event], [], {"com.example.launcher"}, output_forwarding_ab=True
        )

        self.assertEqual((10_000, 25_000), legacy_window)
        self.assertEqual((1_000, 26_000), output_window)
        self.assertEqual([snapshot_event], legacy_scope["scoped"]["home"])
        self.assertEqual([], output_scope["scoped"]["home"])
        self.assertEqual([snapshot_event], output_scope["unresolved_snapshot_events"])

    def test_ab_elapsed_time_uses_observed_end_not_scheduled_window_length(self) -> None:
        self.assertEqual(15.375, probe.elapsed_seconds(1_000_000_000, 16_375_000_000))

    def test_control_boot_path_is_unstable_only_with_observed_unhealthy_sample(self) -> None:
        snapshots = [
            {"stable_pre_menu_health": True},
            {"stable_pre_menu_health": False},
            {"stable_pre_menu_health": True},
        ]

        self.assertFalse(probe.stable_control_boot_path(snapshots))
        self.assertIsNone(probe.stable_control_boot_path([{"stable_pre_menu_health": None}]))
        self.assertTrue(probe.stable_control_boot_path([{"stable_pre_menu_health": True}]))

    def test_ab_configuration_hash_input_excludes_host_paths_and_keeps_profile_settings(self) -> None:
        metadata = {
            "runner_image_os": "ubuntu24",
            "runner_image_version": "2026.10.01",
            "system_image": "system-images;android-35;google_apis;x86_64",
            "source_properties": "AndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\n",
            "platform_tools_source_properties": "Pkg.Revision=37\n",
            "platform_source_properties": "AndroidVersion.ApiLevel=35\n",
            "sdkmanager_version": "sdkmanager 1",
            "adb_version": "Android Debug Bridge version 1",
            "emulator_version": "Android emulator version 37.2.12.0\nbuild_id 16428233",
            "avd_default_config": "hw.ramSize=1536\nvm.heapSize=228\n",
            "avd_effective_config": "vm.heapSize=228\nhw.ramSize=1536\nhw.cpu.ncore=2\n",
            "launch_command": "/sdk/emulator -port 5554 -avd test -gpu swiftshader_indirect",
            "accel_check_exit": 0,
            "accel_check": "KVM is installed and usable",
        }

        configuration = probe.canonical_configuration(metadata, {"window_animation_scale": {"value": "1"}})

        self.assertEqual("2026.10.01", configuration["runner_image_version"])
        self.assertEqual("1536", configuration["avd_default_config"]["hw.ramSize"])
        self.assertEqual(["-port", "5554", "-avd", "test", "-gpu", "swiftshader_indirect"], configuration["launch_arguments"])
        self.assertNotIn("/sdk", json.dumps(configuration))

    def test_output_forwarding_environment_records_unset_and_normalizes_known_runner_paths(self) -> None:
        metadata = {
            "home": "/home/runner",
            "sdk_root": "/opt/android-sdk",
            "avd_home": "/home/runner/.android/avd",
            "host_environment": {
                "HOME": {"state": "set", "value": "/home/runner"},
                "ANDROID_HOME": {"state": "set", "value": "/opt/android-sdk"},
                "ANDROID_AVD_HOME": {"state": "set", "value": "/home/runner/.android/avd/test.avd"},
                "ANDROID_SERIAL": {"state": "unset"},
            },
        }

        configuration = probe.canonical_configuration(metadata, {})

        self.assertEqual("runner_home", configuration["host_environment"]["HOME"]["value"])
        self.assertEqual("android_sdk", configuration["host_environment"]["ANDROID_HOME"]["value"])
        self.assertEqual(
            "avd_home/test.avd",
            configuration["host_environment"]["ANDROID_AVD_HOME"]["value"],
        )
        self.assertEqual({"state": "unset"}, configuration["host_environment"]["ANDROID_SERIAL"])
        self.assertNotIn("/home/runner", json.dumps(configuration))

    def test_ab_animation_config_uses_successful_end_of_window_reads(self) -> None:
        pre_boot = {
            name: {
                "exit_code": 20,
                "stdout_available": True,
                "value": None,
            }
            for name in (
                "window_animation_scale",
                "transition_animation_scale",
                "animator_duration_scale",
            )
        }
        after_observation = {
            name: {
                "exit_code": 0,
                "stdout_available": True,
                "stdout": "1\n",
            }
            for name in pre_boot
        }

        observed = probe.canonical_animation_values(after_observation)

        self.assertEqual("1", observed["window_animation_scale"]["value"])
        self.assertEqual("post_observation", observed["window_animation_scale"]["provenance"])
        self.assertEqual(20, pre_boot["window_animation_scale"]["exit_code"])

    def test_ab_animation_config_keeps_failed_post_observation_reads_unknown(self) -> None:
        after_observation = {
            name: {
                "exit_code": 20,
                "stdout_available": True,
                "stdout": "",
            }
            for name in (
                "window_animation_scale",
                "transition_animation_scale",
                "animator_duration_scale",
            )
        }

        observed = probe.canonical_animation_values(after_observation)

        self.assertEqual(20, observed["window_animation_scale"]["exit_code"])
        self.assertIsNone(observed["window_animation_scale"]["value"])
        self.assertEqual("post_observation", observed["window_animation_scale"]["provenance"])

    @unittest.skipUnless(shutil.which("bash"), "Bash is required to exercise the SDK bootstrap script")
    def test_sdk_install_runs_before_emulator_binary_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            script_path = root / ".github/scripts/probe_default_skin_emulator_pre_menu.sh"
            script_path.parent.mkdir(parents=True)
            shutil.copy2(ROOT / ".github/scripts/probe_default_skin_emulator_pre_menu.sh", script_path)

            sdk_root = root / "sdk"
            tools = sdk_root / "cmdline-tools/1/bin"
            tools.mkdir(parents=True)
            marker = root / "sdkmanager-invoked.txt"
            sdkmanager = tools / "sdkmanager"
            sdkmanager.write_text(
                "#!/usr/bin/env bash\nprintf invoked > \"$SDK_INSTALL_MARKER\"\nexit 17\n",
                encoding="utf-8",
            )
            sdkmanager.chmod(0o755)
            avdmanager = tools / "avdmanager"
            avdmanager.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            avdmanager.chmod(0o755)

            environment = os.environ.copy()
            environment["ANDROID_HOME"] = str(sdk_root)
            environment["ANDROID_SDK_ROOT"] = str(sdk_root)
            environment["SDK_INSTALL_MARKER"] = str(marker)
            completed = subprocess.run(
                [shutil.which("bash") or "bash", str(script_path)],
                cwd=root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                timeout=30,
            )

            self.assertNotEqual(0, completed.returncode)
            self.assertTrue(marker.is_file(), completed.stdout + completed.stderr)
            self.assertIn("Stable-channel SDK install failed", completed.stdout + completed.stderr)

    @unittest.skipUnless(
        shutil.which("bash") and os.name != "nt",
        "A POSIX path environment is required to exercise the fake SDK bootstrap",
    )
    def test_avdmanager_receives_the_exported_action_avd_home(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            script_path = root / ".github/scripts/probe_default_skin_emulator_pre_menu.sh"
            script_path.parent.mkdir(parents=True)
            shutil.copy2(ROOT / ".github/scripts/probe_default_skin_emulator_pre_menu.sh", script_path)

            sdk_root = root / "sdk"
            tool_dir = sdk_root / "cmdline-tools/1/bin"
            tool_dir.mkdir(parents=True)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            avd_home_marker = root / "avdmanager-home.txt"
            (tool_dir / "sdkmanager").write_text(
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --version ]]; then echo 'sdkmanager 1'; exit 0; fi
mkdir -p "$SDK_ROOT/system-images/android-35/google_apis/x86_64" \\
  "$SDK_ROOT/platform-tools" "$SDK_ROOT/platforms/android-35" \\
  "$SDK_ROOT/emulator"
printf 'api=35\\nabi=x86_64\\ntag=google_apis\\n' \\
  > "$SDK_ROOT/system-images/android-35/google_apis/x86_64/source.properties"
touch "$SDK_ROOT/system-images/android-35/google_apis/x86_64/system.img"
printf 'platform-tools=1\\n' > "$SDK_ROOT/platform-tools/source.properties"
printf 'platform=35\\n' > "$SDK_ROOT/platforms/android-35/source.properties"
cat > "$SDK_ROOT/platform-tools/adb" <<'ADB'
#!/usr/bin/env bash
if [[ "${1:-}" == version ]]; then echo 'Android Debug Bridge version 1'; exit 0; fi
exit 0
ADB
chmod 755 "$SDK_ROOT/platform-tools/adb"
cat > "$SDK_ROOT/emulator/emulator" <<'EMULATOR'
#!/usr/bin/env bash
case "${1:-}" in
  -version) echo 'Android emulator version 37.2.12.0'; echo 'build_id 16428233'; exit 0 ;;
  -accel-check) echo 'KVM is installed and usable'; exit 0 ;;
esac
exit 0
EMULATOR
chmod 755 "$SDK_ROOT/emulator/emulator"
""",
                encoding="utf-8",
            )
            (tool_dir / "sdkmanager").chmod(0o755)
            (tool_dir / "avdmanager").write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s' \"${ANDROID_AVD_HOME:-UNSET}\" > \"$AVD_HOME_MARKER\"\n"
                "exit 17\n",
                encoding="utf-8",
            )
            (tool_dir / "avdmanager").chmod(0o755)
            (bin_dir / "ldd").write_text("#!/usr/bin/env bash\necho 'libc.so => /lib/libc.so'\n", encoding="utf-8")
            (bin_dir / "ldd").chmod(0o755)

            home = root / "home"
            home.mkdir()
            environment = os.environ.copy()
            environment["ANDROID_HOME"] = str(sdk_root)
            environment["ANDROID_SDK_ROOT"] = str(sdk_root)
            environment["SDK_ROOT"] = str(sdk_root)
            environment["AVD_HOME_MARKER"] = str(avd_home_marker)
            environment["HOME"] = str(home)
            environment.pop("ANDROID_AVD_HOME", None)
            environment["PATH"] = f"{bin_dir}:{environment.get('PATH', '')}"
            completed = subprocess.run(
                [shutil.which("bash") or "bash", str(script_path)],
                cwd=root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
                timeout=30,
            )

            self.assertNotEqual(0, completed.returncode)
            runtime_log = root / ".github/results/default-skin-pre-menu/config/emulator-runtime-after-install-ldd.txt"
            runtime_output = runtime_log.read_text(encoding="utf-8") if runtime_log.exists() else ""
            self.assertTrue(avd_home_marker.is_file(), completed.stdout + completed.stderr + runtime_output)
            self.assertEqual(str(home / ".android/avd"), avd_home_marker.read_text(encoding="utf-8"))
            avd_environment = (root / ".github/results/default-skin-pre-menu/config/avd.env").read_text(
                encoding="utf-8",
            )
            self.assertIn(f"ANDROID_AVD_HOME={home}/.android/avd", avd_environment)

class FinalLogcatEvidenceTest(unittest.TestCase):
    @staticmethod
    def dump(main: str, system: str, events: str) -> str:
        return (
            f"--------- beginning of main\n{main}"
            f"--------- beginning of system\n{system}"
            f"--------- beginning of events\n{events}"
        )

    def test_successful_dump_must_cover_the_full_observation_window(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout=self.dump("100.0 I boot\n115.0 I home\n", "101.0 I system\n", "102.0 I events\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=116.0,
        )

        self.assertTrue(summary["complete"])
        self.assertTrue(summary["coverage"]["covers_start"])
        self.assertTrue(summary["coverage"]["covers_end"])

    def test_failed_or_partial_dump_cannot_claim_absence(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=1,
            stdout_available=True,
            stdout=self.dump("100.0 I boot\n115.0 I home\n", "101.0 I system\n", "102.0 I events\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=116.0,
        )
        self.assertFalse(summary["complete"])

    def test_successful_empty_dump_is_not_clean_evidence(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout="",
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=116.0,
        )
        self.assertFalse(summary["stdout_nonempty"])
        self.assertFalse(summary["complete"])

    def test_incomplete_buffer_time_coverage_is_unknown(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout=self.dump("110.0 I too-late\n", "111.0 I system\n", "114.0 I events\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=116.0,
        )
        self.assertFalse(summary["coverage"]["covers_start"])
        self.assertTrue(summary["coverage"]["covers_end"])
        self.assertFalse(summary["complete"])

    def test_dump_must_be_observed_after_window_end(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout=self.dump("100.0 I boot\n", "101.0 I system\n", "102.0 I events\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=False,
            device_epoch_at_dump=116.0,
        )
        self.assertFalse(summary["coverage"]["covers_end"])
        self.assertFalse(summary["complete"])

    def test_dump_device_clock_must_reach_window_end_without_requiring_an_event(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout=self.dump("100.0 I boot\n", "101.0 I system\n", "102.0 I events\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=114.0,
        )
        self.assertTrue(summary["stdout_nonempty"])
        self.assertTrue(summary["coverage"]["covers_start"])
        self.assertFalse(summary["coverage"]["covers_end"])
        self.assertFalse(summary["complete"])

    def test_old_record_in_main_does_not_cover_lost_system_and_events_buffers(self) -> None:
        summary = probe.final_logcat_dump_coverage(
            exit_code=0,
            stdout_available=True,
            stdout=self.dump("90.0 I retained-main\n", "110.0 I lost-system-prefix\n", "112.0 I lost-events-prefix\n"),
            window_start_device_epoch=105.0,
            window_end_device_epoch=115.0,
            dump_completed_after_window=True,
            device_epoch_at_dump=116.0,
        )

        self.assertFalse(summary["coverage"]["covers_start"])
        self.assertFalse(summary["complete"])

    def test_process_probe_matches_only_adb_logcat_for_the_target_serial(self) -> None:
        processes = """\
  100 /opt/android/platform-tools/adb -s emulator-5554 logcat -b main
  101 adb -s emulator-5556 logcat -b main
  102 adb -s emulator-5554 shell getprop sys.boot_completed
  103 grep adb -s emulator-5554 logcat
"""

        self.assertEqual([100], probe.adb_logcat_process_pids(processes, "emulator-5554"))

    def test_orchestration_clock_summary_uses_the_run_command_result_shape(self) -> None:
        command = {
            "exit_code": 0,
            "stdout_available": True,
            "stdout": "1791981000\n",
            "started_monotonic_ns": 10,
            "ended_monotonic_ns": 20,
        }

        summary = probe.summarize_device_clock(command)

        self.assertEqual(1791981000.0, summary["device_epoch_seconds"])
        self.assertEqual((1791981001.0, 1791980999.0), probe.preboot_logcat_clock_bounds(summary))

    def test_unavailable_or_disabled_clock_query_has_unknown_bounds(self) -> None:
        failed = probe.summarize_device_clock({
            "exit_code": 1,
            "stdout_available": True,
            "stdout": "partial 1791981000",
            "started_monotonic_ns": 10,
            "ended_monotonic_ns": 20,
        })

        self.assertEqual((None, None), probe.preboot_logcat_clock_bounds(failed))
        self.assertEqual((None, None), probe.preboot_logcat_clock_bounds(None))

    def test_process_absence_requires_successful_nonempty_parsed_process_rows(self) -> None:
        evidence = probe.process_list_evidence(
            {
                "exit_code": 0,
                "stdout_available": True,
                "stdout": "PID COMMAND\n1 init\n2 adb -s emulator-5554 logcat\n",
            },
            "emulator-5554",
        )
        self.assertTrue(evidence["stdout_nonempty"])
        self.assertEqual(2, evidence["process_row_count"])
        self.assertEqual([2], evidence["adb_logcat_pids"])

        empty = probe.process_list_evidence(
            {"exit_code": 0, "stdout_available": True, "stdout": ""},
            "emulator-5554",
        )
        self.assertFalse(empty["stdout_nonempty"])
        self.assertEqual(0, empty["process_row_count"])
        self.assertEqual([], empty["adb_logcat_pids"])

    def test_failed_process_list_cannot_claim_reader_absence_from_partial_output(self) -> None:
        evidence = probe.process_list_evidence(
            {
                "exit_code": 1,
                "stdout_available": True,
                "stdout": "PID COMMAND\n1 init\n",
            },
            "emulator-5554",
        )

        self.assertFalse(evidence["stdout_nonempty"])
        self.assertEqual(0, evidence["process_row_count"])
        self.assertEqual([], evidence["adb_logcat_pids"])

    def test_first_home_screenshot_is_captured_once_for_both_d4_arms(self) -> None:
        for mode in ("preboot-logcat-control", "preboot-logcat-treatment"):
            self.assertTrue(probe.should_capture_first_home_screenshot(mode, "HOME", False))
            self.assertFalse(probe.should_capture_first_home_screenshot(mode, "HOME", True))
            self.assertFalse(probe.should_capture_first_home_screenshot(mode, "UNKNOWN", False))

    def test_treatment_boot_timeout_can_stop_without_an_observer(self) -> None:
        self.assertIsNone(probe.stop_optional_observer(None))


if __name__ == "__main__":
    unittest.main()
