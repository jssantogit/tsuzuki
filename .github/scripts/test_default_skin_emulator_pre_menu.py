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
        ]

        scoped = probe.scope_anr_events(events, "com.example.launcher")

        self.assertEqual(1, len(scoped["home"]))
        self.assertEqual(1, len(scoped["other"]))
        self.assertEqual(1, len(scoped["unknown"]))

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


if __name__ == "__main__":
    unittest.main()
