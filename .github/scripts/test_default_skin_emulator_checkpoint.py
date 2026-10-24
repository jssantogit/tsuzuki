#!/usr/bin/env python3
"""Deterministic shell-helper tests for rendered-validation emulator diagnostics."""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CAPTURE_SCRIPT = ROOT / ".github/scripts/capture_default_skin_emulator_checkpoint.sh"


class EmulatorCheckpointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.call_log = self.root / "adb-args.txt"
        self.remote_script = self.root / "remote-script.sh"
        fake_adb = self.bin / "adb"
        fake_adb.write_text(
            "#!/bin/sh\n"
            'printf "%s\\n" "$@" > "$ADB_ARG_LOG"\n'
            'cat > "$ADB_SCRIPT_LOG"\n'
            'PATH="$MOCK_ANDROID_BIN:$PATH" sh "$ADB_SCRIPT_LOG"\n'
            'script_status=$?\n'
            'exit "${FAKE_ADB_EXIT:-$script_status}"\n',
            encoding="utf-8",
        )
        fake_adb.chmod(0o755)
        mock_commands = {
            "cmd": "printf '%s\\n' 'org.example.launcher/.Home'\n",
            "settings": "printf '%s\\n' '1'\n",
            "dumpsys": (
                "case \"$*\" in\n"
                "  'window windows') printf '%s\\n' 'mCurrentFocus=Window{fixture}' ;;\n"
                "  'activity activities') printf '%s\\n' 'topResumedActivity=org.example.launcher/.Home' ;;\n"
                "  'power') printf '%s\\n' 'mWakefulness=Awake mInteractive=true' ;;\n"
                "  'window policy') printf '%s\\n' 'mShowingLockscreen=false' ;;\n"
                "  'activity exit-info org.example.launcher') printf '%s\\n' 'exitInfo=none' ;;\n"
                "  'activity lastanr') printf '%s\\n' 'No ANR records' ;;\n"
                "esac\n"
            ),
            "pidof": "printf '%s\\n' '1234'\n",
            "ps": "printf '%s\\n' 'u0_a123 1234 org.example.launcher'\n",
            "logcat": "printf '%s\\n' 'InputDispatcher boot event for org.example.launcher'\n",
        }
        self.android_bin = self.root / "android-bin"
        self.android_bin.mkdir()
        for name, body in mock_commands.items():
            command = self.android_bin / name
            command.write_text(f"#!/bin/sh\n{body}", encoding="utf-8")
            command.chmod(0o755)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_capture(self, output: Path, fake_exit: int = 0) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["PATH"] = os.pathsep.join((str(self.bin), env.get("PATH", "")))
        env["ADB_ARG_LOG"] = str(self.call_log)
        env["ADB_SCRIPT_LOG"] = str(self.remote_script)
        env["MOCK_ANDROID_BIN"] = str(self.android_bin)
        env["FAKE_ADB_EXIT"] = str(fake_exit)
        return subprocess.run(
            ["bash", str(CAPTURE_SCRIPT), "A", "emulator-5554", str(output)],
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )

    def test_records_timestamped_checkpoint_and_targets_the_declared_emulator(self) -> None:
        output = self.root / "checkpoint.log"

        result = self.run_capture(output)

        self.assertEqual(0, result.returncode, result.stderr)
        report = output.read_text(encoding="utf-8")
        self.assertIn("CHECKPOINT=A", report)
        self.assertIn("HOST_UTC=", report)
        self.assertIn("HOST_MONOTONIC_NS=", report)
        self.assertIn("ADB_SERIAL=emulator-5554", report)
        self.assertIn("HOME_COMPONENT=org.example.launcher/.Home", report)
        self.assertIn("mCurrentFocus=Window{fixture}", report)
        self.assertIn("InputDispatcher boot event", report)
        args = self.call_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(["-s", "emulator-5554", "shell", "sh"], args)
        remote_script = self.remote_script.read_text(encoding="utf-8")
        self.assertIn("resolve-activity", remote_script)
        self.assertIn("dumpsys activity exit-info", remote_script)
        self.assertIn("logcat -b main -b system -b events -d", remote_script)

    def test_diagnostic_adb_failure_is_recorded_without_masking_primary_runner(self) -> None:
        output = self.root / "checkpoint.log"

        result = self.run_capture(output, fake_exit=23)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("ADB_SNAPSHOT_EXIT=23", output.read_text(encoding="utf-8"))

    def test_runner_records_all_checkpoints_without_reordering_preflight_inputs(self) -> None:
        runner = (ROOT / ".github/scripts/run_default_skin_rendered_validation.sh").read_text(
            encoding="utf-8",
        )

        self.assertEqual(list("ABCDEFGH"), re.findall(r"(?m)^checkpoint ([A-H])$", runner))
        ordered_steps = (
            "checkpoint A",
            "svc power stayon true",
            "checkpoint B",
            "input keyevent KEYCODE_WAKEUP",
            "checkpoint C",
            "wm dismiss-keyguard",
            "checkpoint D",
            "checkpoint E",
            "input keyevent KEYCODE_MENU",
            "checkpoint F",
            "sleep 1",
            "checkpoint G",
            "./gradlew :app:assembleDebug",
            "checkpoint H",
            "./gradlew :app:connectedDebugAndroidTest",
        )
        positions = [runner.index(step) for step in ordered_steps]
        self.assertEqual(sorted(positions), positions)


if __name__ == "__main__":
    unittest.main()
