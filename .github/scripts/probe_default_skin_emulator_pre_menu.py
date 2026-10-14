#!/usr/bin/env python3
"""Observe a fresh disposable emulator before and after exactly one MENU input."""

from __future__ import annotations

import argparse
import atexit
from collections import deque
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
from typing import Any


ANR_PATTERN = re.compile(r"\bam_anr\b|ApplicationNotResponding|ANR in\b|not responding", re.I)
LOGCAT_PATTERN = re.compile(
    r"\bam_anr\b|ApplicationNotResponding|ANR in\b|not responding|"
    r"ActivityTaskManager|WindowManager|InputDispatcher|launcher|Keyguard|BootAnimation",
    re.I,
)
EXPECTED_EMULATOR_VERSION = "Android emulator version 37.2.12.0"
EXPECTED_EMULATOR_BUILD = "build_id 16428233"
EXPECTED_IMAGE = "system-images;android-35;google_apis;x86_64"
EXPECTED_LAUNCH = (
    "emulator -port 5554 -avd test -no-window -gpu swiftshader_indirect "
    "-no-snapshot -noaudio -no-boot-anim"
)


def classify_probe(
    *,
    config_equivalent: bool,
    observer_available: bool,
    pre_menu_anr: bool,
    stable_pre_menu_health: bool | None,
    stable_post_menu_health: bool | None,
    menu_issued: bool,
    post_menu_anr: bool,
    unresolved_anr: bool = False,
) -> dict[str, Any]:
    """Classify observations conservatively; absent/failed evidence is not healthy."""
    if not config_equivalent:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "Emulator configuration did not match the recorded reference configuration.",
            "menu_should_run": False,
        }
    if pre_menu_anr:
        return {
            "classification": "PRE_MENU_ANR",
            "reason": "ANR evidence was observed before the first MENU input; MENU must not run.",
            "menu_should_run": False,
        }
    if unresolved_anr:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "ANR evidence was observed but could not be scoped to the resolved HOME package.",
            "menu_should_run": False,
        }
    if not observer_available:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "The live logcat observer was unavailable or incomplete; pre-menu ANR absence cannot be established.",
            "menu_should_run": False,
        }
    if stable_pre_menu_health is not True:
        health_reason = "unknown" if stable_pre_menu_health is None else "not ready"
        return {
            "classification": "INCONCLUSIVE",
            "reason": f"Pre-menu emulator health/focus was {health_reason}; MENU timing is not attributable.",
            "menu_should_run": menu_issued,
        }
    if not menu_issued:
        return {
            "classification": "INCONCLUSIVE",
            "reason": "No pre-menu ANR was observed, but the single MENU command did not complete successfully.",
            "menu_should_run": False,
        }
    if post_menu_anr:
        return {
            "classification": "MENU_TRIGGERED_ANR",
            "reason": "ANR evidence followed MENU after stable pre-menu health; this is temporal correlation, not causal proof.",
            "menu_should_run": False,
        }
    if stable_post_menu_health is not True:
        health_reason = "unknown" if stable_post_menu_health is None else "not ready"
        return {
            "classification": "INCONCLUSIVE",
            "reason": f"Post-menu HOME health was {health_reason}; absence of an ANR is not sufficient evidence.",
            "menu_should_run": False,
        }
    return {
        "classification": "NO_ANR",
        "reason": "No ANR evidence was observed in the monitored interval after stable pre-menu health.",
        "menu_should_run": False,
    }


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def mark(name: str, timeline_path: Path, fields: dict[str, Any] | None = None) -> dict[str, Any]:
    entry = {
        "name": name,
        "utc": utc_now(),
        "monotonic_ns": time.monotonic_ns(),
        **(fields or {}),
    }
    with timeline_path.open("a", encoding="utf-8") as output:
        output.write(json.dumps(entry, sort_keys=True) + "\n")
    return entry


def run_command(
    command: list[str],
    *,
    timeout_seconds: int = 30,
    input_text: str | None = None,
    capture_bytes: bool = False,
) -> dict[str, Any]:
    started_ns = time.monotonic_ns()
    try:
        result = subprocess.run(
            command,
            input=input_text.encode("utf-8") if capture_bytes and input_text is not None else input_text,
            capture_output=True,
            text=not capture_bytes,
            timeout=timeout_seconds,
            check=False,
        )
        stdout: str | bytes = result.stdout
        stderr: str | bytes = result.stderr
        return {
            "command": command,
            "started_monotonic_ns": started_ns,
            "ended_monotonic_ns": time.monotonic_ns(),
            "exit_code": result.returncode,
            "stdout_available": True,
            "stderr_available": True,
            "stdout_nonempty": bool(stdout),
            "stderr_nonempty": bool(stderr),
            "stdout": stdout,
            "stderr": stderr,
            "timed_out": False,
        }
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout or (b"" if capture_bytes else "")
        stderr = error.stderr or (b"" if capture_bytes else "")
        return {
            "command": command,
            "started_monotonic_ns": started_ns,
            "ended_monotonic_ns": time.monotonic_ns(),
            "exit_code": None,
            "stdout_available": True,
            "stderr_available": True,
            "stdout_nonempty": bool(stdout),
            "stderr_nonempty": bool(stderr),
            "stdout": stdout,
            "stderr": stderr,
            "timed_out": True,
            "error": "command timeout",
        }
    except OSError as error:
        return {
            "command": command,
            "started_monotonic_ns": started_ns,
            "ended_monotonic_ns": time.monotonic_ns(),
            "exit_code": None,
            "stdout_available": False,
            "stderr_available": False,
            "stdout": b"" if capture_bytes else "",
            "stderr": str(error),
            "timed_out": False,
            "error": f"{error.__class__.__name__}: {error}",
        }


class LogcatObserver:
    def __init__(self, serial: str, output_path: Path) -> None:
        self.serial = serial
        self.output_path = output_path
        self.process: subprocess.Popen[str] | None = None
        self.thread: threading.Thread | None = None
        self.events: deque[dict[str, Any]] = deque(maxlen=3000)
        self.events_dropped = 0
        self.lock = threading.Lock()
        self.anr_event = threading.Event()
        self.available = False
        self.truncated = False
        self.bytes_written = 0

    def start(self) -> None:
        self.output_path.parent.mkdir(parents=True, exist_ok=True)
        self.process = subprocess.Popen(
            [
                "adb", "-s", self.serial, "logcat", "-b", "main", "-b", "system", "-b", "events",
                "-v", "epoch",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
            bufsize=1,
        )
        self.thread = threading.Thread(target=self._read_lines, name="pre-menu-logcat", daemon=True)
        self.thread.start()
        self.available = self.process.poll() is None

    def _read_lines(self) -> None:
        assert self.process is not None and self.process.stdout is not None
        with self.output_path.open("a", encoding="utf-8") as output:
            for line in self.process.stdout:
                if not LOGCAT_PATTERN.search(line):
                    continue
                received_ns = time.monotonic_ns()
                record = {
                    "source": "logcat",
                    "host_utc": utc_now(),
                    "host_monotonic_ns": received_ns,
                    "device_epoch": _logcat_epoch(line),
                    "line": line.rstrip("\r\n"),
                    "is_anr": is_anr_line(line),
                }
                with self.lock:
                    if len(self.events) == self.events.maxlen:
                        self.events_dropped += 1
                    self.events.append(record)
                if record["is_anr"]:
                    self.anr_event.set()
                encoded = (json.dumps(record, sort_keys=True) + "\n").encode("utf-8")
                if self.bytes_written + len(encoded) <= 1_000_000:
                    output.write(encoded.decode("utf-8"))
                    output.flush()
                    self.bytes_written += len(encoded)
                else:
                    self.truncated = True

    def snapshot(self) -> list[dict[str, Any]]:
        with self.lock:
            return list(self.events)

    def still_running(self) -> bool:
        return (
            self.available
            and self.process is not None
            and self.process.poll() is None
            and self.thread is not None
            and self.thread.is_alive()
        )

    def stop(self) -> dict[str, Any]:
        if self.process is None:
            return {"available": False, "exit_code": None}
        was_running = self.still_running()
        if self.process.poll() is None:
            self.process.terminate()
        try:
            exit_code = self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            exit_code = self.process.wait(timeout=5)
        if self.thread is not None:
            self.thread.join(timeout=2)
        return {
            "started": self.available,
            "available_at_stop": was_running,
            "exit_code": exit_code,
            "truncated": self.truncated,
            "events_dropped": self.events_dropped,
        }


def is_anr_line(line: str) -> bool:
    if re.search(r"\bno\s+ANR\b", line, re.I):
        return False
    return bool(ANR_PATTERN.search(line))


def _logcat_epoch(line: str) -> float | None:
    token = line.split(maxsplit=1)[0] if line.split() else ""
    try:
        return float(token)
    except ValueError:
        return None


def final_logcat_dump_coverage(
    *,
    exit_code: int | None,
    stdout_available: bool,
    stdout: str,
    window_start_device_epoch: float | None,
    window_end_device_epoch: float | None,
    dump_completed_after_window: bool,
    device_epoch_at_dump: float | None,
) -> dict[str, Any]:
    """Summarize final-buffer evidence without treating a quiet tail as lost coverage."""
    epochs = [epoch for line in stdout.splitlines() if (epoch := _logcat_epoch(line)) is not None]
    first_epoch = min(epochs) if epochs else None
    requested_buffers = ("main", "system", "events")
    active_buffer: str | None = None
    first_epochs_by_buffer: dict[str, float] = {}
    buffer_headers_seen: set[str] = set()
    for line in stdout.splitlines():
        header = re.fullmatch(r"-{3,}\s+beginning of (main|system|events)\s*", line.strip())
        if header:
            active_buffer = header.group(1)
            buffer_headers_seen.add(active_buffer)
            continue
        epoch = _logcat_epoch(line)
        if active_buffer is not None and epoch is not None:
            first_epochs_by_buffer.setdefault(active_buffer, epoch)
    complete_buffer_prefixes = all(
        buffer in buffer_headers_seen
        and isinstance(first_epochs_by_buffer.get(buffer), (int, float))
        and first_epochs_by_buffer[buffer] <= window_start_device_epoch
        for buffer in requested_buffers
    ) if isinstance(window_start_device_epoch, (int, float)) else False
    missing_or_truncated_buffers = [
        buffer for buffer in requested_buffers
        if buffer not in buffer_headers_seen
        or not isinstance(first_epochs_by_buffer.get(buffer), (int, float))
        or (
            isinstance(window_start_device_epoch, (int, float))
            and first_epochs_by_buffer[buffer] > window_start_device_epoch
        )
    ]
    covers_start_by_buffer = {
        buffer: (
            isinstance(window_start_device_epoch, (int, float))
            and isinstance(first_epochs_by_buffer.get(buffer), (int, float))
            and first_epochs_by_buffer[buffer] <= window_start_device_epoch
        )
        for buffer in requested_buffers
    }
    covers_start = (
        stdout_available is True
        and exit_code == 0
        and complete_buffer_prefixes
    )
    covers_end = (
        stdout_available is True
        and exit_code == 0
        and dump_completed_after_window is True
        and isinstance(device_epoch_at_dump, (int, float))
        and isinstance(window_end_device_epoch, (int, float))
        and device_epoch_at_dump >= window_end_device_epoch
    )
    stdout_nonempty = stdout_available is True and bool(stdout)
    return {
        "exit_code": exit_code,
        "stdout_available": stdout_available is True,
        "stdout_nonempty": stdout_nonempty,
        "line_count": len(stdout.splitlines()) if stdout_available else None,
        "epoch_line_count": len(epochs) if stdout_available else None,
        "valid_records": len(epochs) if stdout_available else None,
        "first_device_epoch": first_epoch,
        "buffer_headers_seen": sorted(buffer_headers_seen),
        "first_device_epoch_by_buffer": first_epochs_by_buffer,
        "missing_or_truncated_buffers": missing_or_truncated_buffers,
        "last_device_epoch": max(epochs) if epochs else None,
        "device_epoch_at_dump": device_epoch_at_dump,
        "dump_completed_after_window": dump_completed_after_window,
        "coverage": {
            "covers_start": covers_start,
            "covers_start_by_buffer": covers_start_by_buffer,
            "covers_end": covers_end,
        },
        "complete": bool(stdout_nonempty and epochs and covers_start and covers_end),
    }


def device_epoch_from_snapshot(snapshot: dict[str, Any]) -> float | None:
    if snapshot.get("adb_exit_code") != 0 or snapshot.get("stdout_available") is not True:
        return None
    raw = section_text(snapshot.get("sections", {}), "DEVICE_EPOCH")
    try:
        return float(raw.strip()) if raw is not None else None
    except ValueError:
        return None


def device_epoch_at_host_time(snapshot: dict[str, Any], host_monotonic_ns: int) -> float | None:
    epoch = device_epoch_from_snapshot(snapshot)
    started, ended = snapshot.get("started_monotonic_ns"), snapshot.get("ended_monotonic_ns")
    if epoch is None or not isinstance(started, int) or not isinstance(ended, int):
        return None
    midpoint = (started + ended) // 2
    return epoch + (host_monotonic_ns - midpoint) / 1_000_000_000


def final_logcat_anr_records(stdout: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line in stdout.splitlines():
        if not is_anr_line(line):
            continue
        records.append({
            "source": "final-logcat-dump",
            "device_epoch": _logcat_epoch(line),
            "timestamp_quality": "device-epoch" if _logcat_epoch(line) is not None else "unmapped",
            "package": anr_event_package(line),
            "line": line[:2000],
        })
    return records


def adb_logcat_process_pids(ps_output: str, serial: str) -> list[int]:
    """Return PIDs for direct adb logcat readers attached to the requested serial."""
    matching: set[int] = set()
    for line in ps_output.splitlines():
        try:
            tokens = shlex.split(line)
            pid = int(tokens[0])
        except (ValueError, IndexError):
            continue
        command = tokens[1:]
        if not command or Path(command[0]).name != "adb":
            continue
        serial_index = next(
            (index for index, token in enumerate(command[:-1]) if token == "-s" and command[index + 1] == serial),
            None,
        )
        if serial_index is not None and "logcat" in command[serial_index + 2:]:
            matching.add(pid)
    return sorted(matching)


def process_list_evidence(command: dict[str, Any], serial: str) -> dict[str, Any]:
    """Parse process rows only from a successful, available ps result."""
    exit_code = command.get("exit_code")
    stdout_available = command.get("stdout_available") is True
    raw_stdout = command.get("stdout", "")
    stdout = raw_stdout.decode("utf-8", errors="replace") if isinstance(raw_stdout, bytes) else str(raw_stdout)
    rows = [
        line.strip()
        for line in stdout.splitlines()
        if re.match(r"^\s*\d+\s+\S", line)
    ] if exit_code == 0 and stdout_available else []
    return {
        "exit_code": exit_code,
        "stdout_available": stdout_available,
        "stdout_nonempty": bool(rows),
        "process_row_count": len(rows),
        "process_rows": rows[:64],
        "adb_logcat_pids": adb_logcat_process_pids("\n".join(rows), serial),
    }


def parse_emulator_launch_start(text: str) -> dict[str, str]:
    """Parse the shell's timestamp pair and launch fields without losing inline key/value tokens."""
    launch_state: dict[str, str] = {}
    for line in text.splitlines():
        if line.startswith("HOST_UTC="):
            fields = line.split()
            for field in fields:
                key, separator, value = field.partition("=")
                if separator:
                    launch_state[key] = value
        elif "=" in line:
            key, value = line.split("=", 1)
            launch_state[key] = value
    required = ("HOST_UTC", "HOST_MONOTONIC_NS", "EMULATOR_PID")
    if any(not launch_state.get(key) for key in required):
        raise ValueError("emulator-launch-start.txt is missing required launch metadata")
    try:
        int(launch_state["HOST_MONOTONIC_NS"])
        int(launch_state["EMULATOR_PID"])
    except ValueError as error:
        raise ValueError("emulator-launch-start.txt contains invalid numeric launch metadata") from error
    return launch_state


def summarize_device_clock(command: dict[str, Any] | None) -> dict[str, Any]:
    """Normalize a run_command result into the persisted device-clock schema."""
    if not isinstance(command, dict):
        return {
            "exit_code": None,
            "stdout_available": False,
            "device_epoch_seconds": None,
            "started_monotonic_ns": None,
            "ended_monotonic_ns": None,
        }
    epoch: float | None = None
    if command.get("exit_code") == 0 and command.get("stdout_available") is True:
        try:
            parsed = float(str(command.get("stdout", "")).strip())
            epoch = parsed if math.isfinite(parsed) else None
        except (TypeError, ValueError):
            pass
    return {
        "exit_code": command.get("exit_code"),
        "stdout_available": command.get("stdout_available") is True,
        "device_epoch_seconds": epoch,
        "started_monotonic_ns": command.get("started_monotonic_ns"),
        "ended_monotonic_ns": command.get("ended_monotonic_ns"),
    }


def preboot_logcat_clock_bounds(clock_summary: dict[str, Any] | None) -> tuple[float | None, float | None]:
    if not isinstance(clock_summary, dict):
        return None, None
    epoch = clock_summary.get("device_epoch_seconds")
    if (
        clock_summary.get("exit_code") != 0
        or clock_summary.get("stdout_available") is not True
        or not isinstance(epoch, (int, float))
        or not math.isfinite(float(epoch))
    ):
        return None, None
    return float(epoch) + 1.0, float(epoch) - 1.0


def stop_optional_observer(observer: LogcatObserver | None) -> dict[str, Any] | None:
    return observer.stop() if observer is not None else None


def d4_observer_summary(
    policy: str,
    process_samples: list[dict[str, Any]],
    expected_observer_pid: int | None,
) -> tuple[bool | str, int | str]:
    """Return an observed D4 reader state, preserving unknown process evidence."""
    if not process_samples or any(
        sample.get("exit_code") != 0
        or sample.get("stdout_available") is not True
        or sample.get("stdout_nonempty") is not True
        or not isinstance(sample.get("process_row_count"), int)
        or sample.get("process_row_count", 0) <= 0
        or not isinstance(sample.get("adb_logcat_pids"), list)
        for sample in process_samples
    ):
        return "UNKNOWN", "UNKNOWN"
    if policy == "continuous":
        if (
            not isinstance(expected_observer_pid, int)
            or any(expected_observer_pid not in sample["adb_logcat_pids"] for sample in process_samples)
            or any(sample.get("observer_running") is not True for sample in process_samples)
        ):
            return "UNKNOWN", "UNKNOWN"
        return True, expected_observer_pid
    if policy == "deferred_final_dump":
        pids = sorted({pid for sample in process_samples for pid in sample["adb_logcat_pids"]})
        return (True, pids[0]) if pids else (False, "NONE")
    return "UNKNOWN", "UNKNOWN"


def should_capture_first_home_screenshot(mode: str, focus_state: Any, already_captured: bool) -> bool:
    return (
        mode in {
            "output-control", "output-treatment",
            "preboot-logcat-control", "preboot-logcat-treatment",
        }
        and focus_state == "HOME"
        and not already_captured
    )


def scope_anr_events(
    events: list[dict[str, Any]],
    home_package: str | None,
) -> dict[str, list[dict[str, Any]]]:
    return scope_anr_events_to_packages(events, {home_package} if home_package else set())


def scope_anr_events_to_packages(
    events: list[dict[str, Any]],
    home_packages: set[str],
) -> dict[str, list[dict[str, Any]]]:
    scoped: dict[str, list[dict[str, Any]]] = {"home": [], "other": [], "unknown": []}
    for event in events:
        package = anr_event_package(str(event.get("line", "")))
        if package is None:
            scoped["unknown"].append(event)
        elif package in home_packages:
            scoped["home"].append(event)
        else:
            scoped["other"].append(event)
    return scoped


def anr_event_package(line: str) -> str | None:
    package, _ = anr_event_identity(line)
    return package


def anr_event_identity(line: str) -> tuple[str | None, str | None]:
    match = re.search(r"\bANR in\s+([A-Za-z0-9_.]+)(.*)$", line, re.I)
    if match is not None:
        reason = re.sub(r"\s+", " ", match.group(2)).strip().casefold()
        return match.group(1), reason
    match = re.search(
        r"\bam_anr\s*:\s*\[\s*[^,\]]+,\s*[^,\]]+,\s*([A-Za-z0-9_.]+)",
        line,
        re.I,
    )
    if match is None:
        return None, None
    return match.group(1), ""


def uncorroborated_snapshot_home_anrs(
    snapshot_events: list[dict[str, Any]],
    timestamped_live_events: list[dict[str, Any]],
    home_packages: set[str],
) -> list[dict[str, Any]]:
    scoped = scope_anr_events_to_packages(snapshot_events, home_packages)
    unresolved = list(scoped["unknown"])
    for snapshot_event in scoped["home"]:
        snapshot_package, snapshot_reason = anr_event_identity(str(snapshot_event.get("line", "")))
        matched = any(
            live_event.get("source") == "logcat"
            and isinstance(live_event.get("mapped_host_monotonic_ns"), int)
            and anr_event_identity(str(live_event.get("line", ""))) == (snapshot_package, snapshot_reason)
            for live_event in timestamped_live_events
        )
        if not matched:
            unresolved.append(snapshot_event)
    return unresolved


def selected_anr_observation_window(
    output_forwarding_ab: bool,
    launch_ns: int | None,
    boot_t0_ns: int,
    observed_end_ns: int,
    scheduled_window_end_ns: int,
) -> tuple[int | None, int]:
    if output_forwarding_ab:
        return launch_ns, observed_end_ns
    return boot_t0_ns, scheduled_window_end_ns


def scope_observation_anrs(
    interval_events: list[dict[str, Any]],
    snapshot_events: list[dict[str, Any]],
    timestamped_live_events: list[dict[str, Any]],
    home_packages: set[str],
    *,
    output_forwarding_ab: bool,
) -> dict[str, Any]:
    status_events = interval_events if output_forwarding_ab else interval_events + snapshot_events
    snapshot_corroboration = timestamped_live_events if output_forwarding_ab else interval_events
    return {
        "scoped": scope_anr_events_to_packages(status_events, home_packages),
        "scoped_snapshots": scope_anr_events_to_packages(snapshot_events, home_packages),
        "unresolved_snapshot_events": uncorroborated_snapshot_home_anrs(
            snapshot_events,
            snapshot_corroboration,
            home_packages,
        ),
    }


def stable_control_boot_path(snapshots: list[dict[str, Any]]) -> bool | None:
    """Return false on an observed unhealthy sample, unknown on missing evidence."""
    health = [snapshot.get("stable_pre_menu_health") for snapshot in snapshots]
    if not health:
        return None
    if any(value is False for value in health):
        return False
    if all(value is True for value in health):
        return True
    return None


def elapsed_seconds(start_monotonic_ns: int, end_monotonic_ns: int) -> float:
    return round((end_monotonic_ns - start_monotonic_ns) / 1_000_000_000, 3)


def canonical_animation_values(read_commands: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Use only successful post-observation reads as A/B configuration evidence."""
    values: dict[str, dict[str, Any]] = {}
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        command = read_commands.get(setting, {})
        exit_code = command.get("exit_code")
        stdout_available = command.get("stdout_available") is True
        values[setting] = {
            "exit_code": exit_code,
            "stdout_available": stdout_available,
            "value": str(command.get("stdout", "")).strip()
            if exit_code == 0 and stdout_available
            else None,
            "provenance": "post_observation",
        }
    return values


def _canonical_host_environment(metadata: dict[str, Any]) -> dict[str, Any]:
    raw = metadata.get("host_environment")
    if not isinstance(raw, dict):
        return {}
    roles = {
        str(metadata.get("home")): "runner_home",
        str(metadata.get("sdk_root")): "android_sdk",
        str(metadata.get("avd_home")): "avd_home",
    }
    roles.pop("", None)
    canonical: dict[str, Any] = {}
    for name, entry in raw.items():
        if not isinstance(entry, dict) or entry.get("state") not in {"set", "unset"}:
            canonical[str(name)] = {"state": "unknown"}
            continue
        if entry["state"] == "unset":
            canonical[str(name)] = {"state": "unset"}
            continue
        value = str(entry.get("value", ""))
        normalized = roles.get(value)
        if normalized is None:
            for path, role in sorted(roles.items(), key=lambda item: len(item[0]), reverse=True):
                if value.startswith(path.rstrip("/") + "/"):
                    normalized = role + value[len(path):]
                    break
        canonical[str(name)] = {
            "state": "set",
            "value": normalized if normalized is not None else value,
        }
    return dict(sorted(canonical.items()))


def canonical_configuration(metadata: dict[str, Any], animation_values: dict[str, Any]) -> dict[str, Any]:
    """Build a path/time-independent configuration record for A/B matching."""

    def key_values(raw: str) -> dict[str, str]:
        values: dict[str, str] = {}
        for line in raw.splitlines():
            if "=" not in line or line.lstrip().startswith("#"):
                continue
            key, value = line.split("=", 1)
            normalized_key = key.strip()
            normalized_value = value.strip()
            if "path" in normalized_key.lower() or normalized_value.startswith(("/", "\\\\")):
                continue
            values[normalized_key] = normalized_value
        return dict(sorted(values.items()))

    try:
        launch = shlex.split(str(metadata.get("launch_command", "")))
    except ValueError:
        launch = []
    configuration = {
        "runner_image_os": metadata.get("runner_image_os"),
        "runner_image_version": metadata.get("runner_image_version"),
        "system_image": metadata.get("system_image"),
        "source_properties": key_values(str(metadata.get("source_properties", ""))),
        "platform_tools_source_properties": key_values(
            str(metadata.get("platform_tools_source_properties", ""))
        ),
        "platform_source_properties": key_values(str(metadata.get("platform_source_properties", ""))),
        "libpulse0_package": str(metadata.get("libpulse0_package", "")).strip(),
        "sdkmanager_version": str(metadata.get("sdkmanager_version", "")).strip(),
        "adb_version": str(metadata.get("adb_version", "")).strip(),
        "emulator_version": "\n".join(
            line.strip()
            for line in str(metadata.get("emulator_version", "")).splitlines()
            if line.strip() and not line.startswith("EMULATOR_VERSION_EXIT=")
        ),
        "avd_default_config": key_values(str(metadata.get("avd_default_config", ""))),
        "avd_effective_config": key_values(str(metadata.get("avd_effective_config", ""))),
        "launch_arguments": launch[1:] if launch else [],
        "acceleration": {
            "exit_code": metadata.get("accel_check_exit"),
            "kvm_usable": "KVM" in str(metadata.get("accel_check", ""))
            and "usable" in str(metadata.get("accel_check", "")).lower(),
        },
        "animation_values": animation_values,
    }
    if "host_environment" in metadata:
        configuration["host_environment"] = _canonical_host_environment(metadata)
        configuration["output_capture"] = {
            "tee": True,
            "file_capture": True,
            "capture_file": "emulator.log",
            "stderr_route": "stdout",
        }
    return configuration


REMOTE_SNAPSHOT = r"""set +e
capture() {
  name="$1"; limit="$2"; filter="$3"; shift 3
  value="$("$@" 2>&1)"; status=$?
  printf 'BEGIN:%s:%s\n' "$name" "$status"
  if [ -n "$filter" ]; then
    printf '%s\n' "$value" | grep -E "$filter" | head -n "$limit"
  else
    printf '%s\n' "$value" | head -n "$limit"
  fi
  printf 'END:%s\n' "$name"
}
home_value=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1)
home_status=$?
printf 'BEGIN:HOME:%s\n' "$home_status"
printf '%s\n' "$home_value" | tail -n 4
printf 'END:HOME\n'
home_component=$(printf '%s\n' "$home_value" | tail -n 1 | tr -d '\r')
home_package=${home_component%%/*}
    capture WINDOW 20 'mCurrentFocus|mFocusedWindow|mObscuringWindow|FocusedWindow' dumpsys window
capture ACTIVITY 20 'topResumedActivity|mResumedActivity|ResumedActivity|mFocusedActivity' dumpsys activity activities
capture POWER 16 'mWakefulness|Wakefulness|mInteractive|Display Power' dumpsys power
capture KEYGUARD 24 'mShowingLockscreen|mKeyguard|mDreamingLockscreen|KeyguardServiceDelegate|showing=|inputRestricted|secure=' dumpsys window policy
capture PROVISIONED 4 '' settings get global device_provisioned
capture USER_SETUP 4 '' settings get secure user_setup_complete
capture DEVICE_EPOCH 2 '' date +%s
if [ -n "$home_package" ]; then
  capture PROCESSES 8 "$home_package" ps -A
  capture HOME_PID 2 '' pidof "$home_package"
fi
capture LAST_ANR 24 '' dumpsys activity lastanr
if [ -n "$home_package" ]; then capture EXIT_INFO 24 '' dumpsys activity exit-info "$home_package"; fi
capture BOOT_COMPLETED 2 '' getprop sys.boot_completed
"""


def parse_sections(raw: str) -> dict[str, dict[str, Any]]:
    sections: dict[str, dict[str, Any]] = {}
    active_name: str | None = None
    active_lines: list[str] = []
    for line in raw.splitlines():
        begin = re.fullmatch(r"BEGIN:([A-Z_]+):(-?\d+)", line)
        end = re.fullmatch(r"END:([A-Z_]+)", line)
        if begin:
            active_name = begin.group(1)
            active_lines = []
            sections[active_name] = {"exit_code": int(begin.group(2)), "lines": active_lines}
        elif end and end.group(1) == active_name:
            active_name = None
        elif active_name is not None:
            active_lines.append(line)
    return sections


def section_text(sections: dict[str, dict[str, Any]], name: str) -> str | None:
    section = sections.get(name)
    if section is None or section.get("exit_code") != 0:
        return None
    return "\n".join(section.get("lines", []))


def snapshot_health(sections: dict[str, dict[str, Any]]) -> dict[str, Any]:
    home_text = section_text(sections, "HOME")
    home_component = None
    if home_text:
        home_component = next((line.strip() for line in reversed(home_text.splitlines()) if "/" in line), None)
    home_package = home_component.split("/", 1)[0] if home_component else None

    window = section_text(sections, "WINDOW")
    focus_state = "UNKNOWN"
    focus_line = None
    focus_owner_package = None
    if window is not None:
        current_focus = next((line for line in window.splitlines() if "mCurrentFocus" in line), None)
        focused_window = next((line for line in window.splitlines() if "mFocusedWindow" in line or "FocusedWindow" in line), None)
        current_is_null = current_focus is not None and re.search(r"mCurrentFocus\s*=\s*(null|None)", current_focus, re.I)
        focus_line = focused_window if current_is_null and focused_window else current_focus or focused_window
        if focus_line is not None:
            if re.search(r"(?:mCurrentFocus|mFocusedWindow)\s*=\s*(null|None)", focus_line, re.I):
                focus_state = "NONE"
            else:
                focus_owner = re.search(r"(?<![A-Za-z0-9_.])([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+", focus_line)
                focus_owner_package = focus_owner.group(1) if focus_owner else None
                if home_package and focus_owner_package == home_package:
                    focus_state = "HOME"
                else:
                    focus_state = "OTHER"

    activity = section_text(sections, "ACTIVITY")
    top_is_home: bool | None = None
    if activity is not None and home_package:
        top_line = next(
            (line for line in activity.splitlines() if re.search(r"topResumedActivity|mResumedActivity|ResumedActivity", line)),
            None,
        )
        top_is_home = (
            bool(re.search(rf"(?<![A-Za-z0-9_.]){re.escape(home_package)}(?![A-Za-z0-9_.])", top_line))
            if top_line is not None
            else None
        )

    power = section_text(sections, "POWER")
    awake: bool | None = None
    if power is not None:
        if re.search(r"mWakefulness=Awake|Wakefulness=Awake|mInteractive=true", power, re.I):
            awake = True
        elif re.search(r"mWakefulness=Asleep|Wakefulness=Asleep|mInteractive=false", power, re.I):
            awake = False

    keyguard = section_text(sections, "KEYGUARD")
    unlocked: bool | None = None
    if keyguard is not None:
        showing = re.search(r"(?:mShowingLockscreen|mKeyguardShowing|\bshowing)=(true|false)\b", keyguard, re.I)
        input_restricted = re.search(r"\binputRestricted=(true|false)\b", keyguard, re.I)
        dreaming_lock_screen = re.search(r"mDreamingLockscreen=(true|false)\b", keyguard, re.I)
        if (showing and showing.group(1).lower() == "true") or (
            input_restricted and input_restricted.group(1).lower() == "true"
        ) or (dreaming_lock_screen and dreaming_lock_screen.group(1).lower() == "true"):
            unlocked = False
        elif (
            showing
            and showing.group(1).lower() == "false"
            and input_restricted
            and input_restricted.group(1).lower() == "false"
            and (not dreaming_lock_screen or dreaming_lock_screen.group(1).lower() == "false")
        ):
            unlocked = True

    home_pid_section = sections.get("HOME_PID")
    home_pid_text = section_text(sections, "HOME_PID")
    home_process_alive: bool | None = None
    if home_pid_section is not None and home_pid_section.get("exit_code") == 0 and home_pid_text is not None:
        home_process_alive = bool(re.search(r"\b\d+\b", home_pid_text))

    def setup_value(name: str) -> bool | None:
        value = section_text(sections, name)
        if value is None:
            return None
        value = value.strip()
        return value == "1" if value in {"0", "1"} else None

    provisioned = setup_value("PROVISIONED")
    user_setup = setup_value("USER_SETUP")
    focus_healthy = True if focus_state == "HOME" else False if focus_state in {"NONE", "OTHER"} else None
    facts = [focus_healthy, top_is_home, home_process_alive, awake, unlocked, provisioned, user_setup]
    health: bool | None = True if all(fact is True for fact in facts) else False if any(fact is False for fact in facts) else None
    return {
        "home_component": home_component,
        "home_package": home_package,
        "focus_state": focus_state,
        "focus_line": focus_line,
        "focus_owner_package": focus_owner_package,
        "top_is_home": top_is_home,
        "home_process_alive": home_process_alive,
        "awake": awake,
        "unlocked": unlocked,
        "device_provisioned": provisioned,
        "user_setup_complete": user_setup,
        "stable_pre_menu_health": health,
        "health_facts": facts,
    }


def snapshot_health_for_command(
    sections: dict[str, dict[str, Any]],
    *,
    adb_exit_code: int | None,
    stdout_available: bool,
) -> dict[str, Any]:
    """Do not infer device health from output of a failed outer ADB snapshot."""
    if adb_exit_code != 0 or not stdout_available:
        return {
            "home_component": None,
            "home_package": None,
            "focus_state": None,
            "focus_line": None,
            "focus_owner_package": None,
            "top_is_home": None,
            "home_process_alive": None,
            "awake": None,
            "unlocked": None,
            "device_provisioned": None,
            "user_setup_complete": None,
            "stable_pre_menu_health": None,
            "health_facts": [None] * 7,
        }
    return snapshot_health(sections)


def config_equivalence(metadata: dict[str, Any]) -> dict[str, Any]:
    version = str(metadata.get("emulator_version", ""))
    properties = str(metadata.get("source_properties", ""))
    default_config = str(metadata.get("avd_default_config", ""))
    effective_config = str(metadata.get("avd_effective_config", ""))
    launch_command = str(metadata.get("launch_command", ""))
    accel_check = str(metadata.get("accel_check", ""))
    emulator_log = str(metadata.get("emulator_log", ""))
    try:
        launch_tokens = shlex.split(launch_command)
    except ValueError:
        launch_tokens = []
    expected_launch_tokens = shlex.split(EXPECTED_LAUNCH)
    properties_ok = (
        "AndroidVersion.ApiLevel=35" in properties
        and "SystemImage.Abi=x86_64" in properties
        and "SystemImage.TagId=google_apis" in properties
    )
    expected_effective_config = default_config.rstrip("\n") + "\nhw.cpu.ncore=2\n"
    checks = {
        "emulator_version": metadata.get("emulator_version_exit") == 0
        and EXPECTED_EMULATOR_VERSION in version
        and EXPECTED_EMULATOR_BUILD in version,
        "system_image": metadata.get("system_image") == EXPECTED_IMAGE and properties_ok,
        "avd_only_cpu_override": effective_config == expected_effective_config,
        "launch_command": len(launch_tokens) == len(expected_launch_tokens)
        and Path(launch_tokens[0]).name == "emulator"
        and launch_tokens[1:] == expected_launch_tokens[1:],
        "hardware_acceleration_auto": "KVM" in accel_check and "usable" in accel_check.lower()
        and metadata.get("accel_check_exit") == 0
        and "-no-accel" not in launch_command and "-accel off" not in launch_command,
        "emulator_log_read": metadata.get("emulator_log_available") is True,
        "gfxstream_observed": "gfxstream" in emulator_log.lower(),
    }
    return {"equivalent": all(checks.values()), "checks": checks}


def load_run_metadata(metadata_path: Path) -> dict[str, Any]:
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    emulator_log_path = metadata.get("emulator_log_path")
    try:
        if not emulator_log_path:
            raise OSError("emulator log path is missing")
        metadata["emulator_log"] = Path(emulator_log_path).read_text(
            encoding="utf-8",
            errors="replace",
        )[-1_000_000:]
        metadata["emulator_log_available"] = True
    except OSError as error:
        metadata["emulator_log"] = ""
        metadata["emulator_log_available"] = False
        metadata["emulator_log_error"] = f"{error.__class__.__name__}: {error}"
    return metadata


def snapshot_anr_events(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    if snapshot.get("adb_exit_code") != 0 or snapshot.get("stdout_available") is not True:
        return []
    section = snapshot.get("sections", {}).get("LAST_ANR")
    if not section or section.get("exit_code") != 0:
        return []
    return [
        {
            "line": line,
            "source": f"snapshot:{snapshot.get('label')}:LAST_ANR",
            "host_monotonic_ns": snapshot.get("ended_monotonic_ns"),
            "timestamp_quality": "snapshot-observation-only",
        }
        for line in section.get("lines", [])
        if is_anr_line(line)
    ]


def map_device_epoch_to_host_monotonic_ns(
    event: dict[str, Any],
    snapshots: list[dict[str, Any]],
) -> int | None:
    """Map logcat device time using the closest successful snapshot clock sample."""
    device_epoch = event.get("device_epoch")
    received_ns = event.get("host_monotonic_ns")
    if not isinstance(device_epoch, (int, float)) or not isinstance(received_ns, int):
        return None
    samples: list[tuple[int, float]] = []
    for snapshot in snapshots:
        if snapshot.get("adb_exit_code") != 0 or snapshot.get("stdout_available") is not True:
            continue
        raw_epoch = section_text(snapshot.get("sections", {}), "DEVICE_EPOCH")
        try:
            epoch = float(raw_epoch.strip()) if raw_epoch is not None else None
        except ValueError:
            epoch = None
        started = snapshot.get("started_monotonic_ns")
        ended = snapshot.get("ended_monotonic_ns")
        if epoch is None or not isinstance(started, int) or not isinstance(ended, int):
            continue
        midpoint = (started + ended) // 2
        samples.append((midpoint, epoch))
    if not samples:
        return None
    host_midpoint, device_midpoint = min(samples, key=lambda pair: abs(pair[0] - received_ns))
    return host_midpoint + round((device_epoch - device_midpoint) * 1_000_000_000)


def timestamp_anr_events(
    events: list[dict[str, Any]],
    snapshots: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Keep logcat receipt time while adding device-clock event time when mappable."""
    timestamped: list[dict[str, Any]] = []
    for event in events:
        mapped_ns = map_device_epoch_to_host_monotonic_ns(event, snapshots)
        timestamped.append(
            {
                **event,
                "mapped_host_monotonic_ns": mapped_ns,
                "timestamp_quality": "device-epoch-mapped" if mapped_ns is not None else "unmapped-device-epoch",
            }
        )
    return timestamped


def classify_pre_menu_home_anrs(
    events: list[dict[str, Any]],
    snapshot_events: list[dict[str, Any]],
    home_packages: set[str],
    menu_start_monotonic_ns: int | None,
    observer_complete: bool,
    observer_start_monotonic_ns: int | None,
    snapshot_corroborating_events: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Classify treatment's boot-prefix evidence without trusting logcat receipt time."""
    if (
        not observer_complete
        or not home_packages
        or not isinstance(menu_start_monotonic_ns, int)
        or not isinstance(observer_start_monotonic_ns, int)
        or observer_start_monotonic_ns >= menu_start_monotonic_ns
    ):
        return {"status": "unknown", "events": [], "unresolved_events": []}

    scoped = scope_anr_events_to_packages(events, home_packages)
    pre_menu_events: list[dict[str, Any]] = []
    unresolved_events: list[dict[str, Any]] = []
    for event in scoped["home"]:
        mapped_ns = event.get("mapped_host_monotonic_ns")
        if not isinstance(mapped_ns, int):
            unresolved_events.append(event)
        elif mapped_ns <= menu_start_monotonic_ns:
            pre_menu_events.append(event)
    for event in scoped["unknown"]:
        mapped_ns = event.get("mapped_host_monotonic_ns")
        if not isinstance(mapped_ns, int) or mapped_ns <= menu_start_monotonic_ns:
            unresolved_events.append(event)

    # LAST_ANR is a snapshot of retained device state, not the event's occurrence time.
    unresolved_events.extend(
        uncorroborated_snapshot_home_anrs(
            snapshot_events,
            events if snapshot_corroborating_events is None else snapshot_corroborating_events,
            home_packages,
        )
    )
    if pre_menu_events:
        status = "present"
    elif unresolved_events:
        status = "unknown"
    else:
        status = "absent"
    return {
        "status": status,
        "events": pre_menu_events,
        "unresolved_events": unresolved_events,
    }


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_jsonl(path: Path, values: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(value, sort_keys=True) + "\n" for value in values),
        encoding="utf-8",
    )


def write_result(results: Path, result: dict[str, Any]) -> None:
    write_json(results / "result.json", result)
    info = [
        f"CLASSIFICATION={result.get('classification', 'INCONCLUSIVE')}",
        f"REASON={result.get('reason', 'Probe did not complete classification.')}",
        f"PRE_MENU_ANR={result.get('pre_menu_anr', 'UNKNOWN')}",
        f"POST_MENU_ANR={result.get('post_menu_anr', 'UNKNOWN')}",
        f"MENU_ISSUED={result.get('menu_issued', False)}",
        f"CONFIG_EQUIVALENT={result.get('config_equivalence', {}).get('equivalent', False)}",
        f"EXPERIMENT={result.get('experiment', 'default_skin_emulator_pre_menu')}",
        f"ARM={result.get('arm', 'late-pre-menu')}",
        f"OBSERVATION_COMPLETE={result.get('observation', {}).get('complete', 'NOT_APPLICABLE')}",
    ]
    (results / "run-info.txt").write_text("\n".join(info) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument(
        "--mode",
        choices=(
            "late", "control", "treatment", "output-control", "output-treatment",
            "preboot-logcat-control", "preboot-logcat-treatment",
        ),
        default="late",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    results = args.results.resolve()
    results.mkdir(parents=True, exist_ok=True)
    timeline_path = results / "timeline.jsonl"
    snapshot_dir = results / "snapshots"
    snapshot_dir.mkdir(exist_ok=True)
    metadata = load_run_metadata(args.metadata)
    equivalence = config_equivalence(metadata)
    is_menu_ab = args.mode in {"control", "treatment"}
    is_output_ab = args.mode in {"output-control", "output-treatment"}
    is_logcat_ab = args.mode in {"preboot-logcat-control", "preboot-logcat-treatment"}
    is_ab = is_menu_ab or is_output_ab or is_logcat_ab
    output_arm = args.mode.removeprefix("output-") if is_output_ab else None
    output_forwarding = metadata.get("output_forwarding") if is_output_ab else None
    mark("observer_start", timeline_path, {"serial": args.serial})
    result: dict[str, Any] = {
        "serial": args.serial,
        "mode": args.mode,
        "config_equivalence": equivalence,
        "snapshots": [],
        "commands": [],
        "menu_attempted": False,
        "menu_issued": False,
        "menu_start_monotonic_ns": None,
        "menu_end_monotonic_ns": None,
        "pre_menu_anr": False,
        "post_menu_anr": False,
        "pre_menu_unknown_anr": False,
        "post_menu_unknown_anr": False,
        "screenshots": [],
    }
    if is_output_ab:
        result.update({
            "experiment": "default_skin_emulator_output_forwarding",
            "arm": output_arm,
            "launch_monotonic_ns": output_forwarding.get("applied_monotonic_ns")
            if isinstance(output_forwarding, dict) else None,
            "output_forwarding": output_forwarding,
        })
    if is_logcat_ab:
        result.update({
            "experiment": "default_skin_emulator_preboot_logcat_d4",
            "arm": args.mode.removeprefix("preboot-logcat-"),
            "selected_difference_id": "D4",
            "selected_difference_value": "continuous" if args.mode.endswith("control") else "deferred_final_dump",
            "pre_boot_logcat_observer_enabled": "UNKNOWN",
            "observer_pid": "UNKNOWN",
            "launch_utc": metadata.get("launch_utc"),
            "launch_monotonic_ns": metadata.get("launch_monotonic_ns"),
            "emulator_pid": metadata.get("emulator_pid"),
            "common_emulator_output": metadata.get("common_emulator_output"),
            "bootstrap_events": metadata.get("bootstrap_events"),
        })
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        result["error"] = "Refusing non-emulator ADB serial."
        result["classification"] = "INCONCLUSIVE"
        result["reason"] = result["error"]
        write_result(results, result)
        return 2

    device_state = run_command(["adb", "-s", args.serial, "get-state"], timeout_seconds=30)
    result["commands"].append(device_state)
    if device_state["exit_code"] != 0 or str(device_state["stdout"]).strip() != "device":
        result["error"] = "Disposable emulator ADB transport was not ready."
        result["classification"] = "INCONCLUSIVE"
        result["reason"] = result["error"]
        write_result(results, result)
        return 2

    preboot_clock: dict[str, Any] | None = None
    if is_logcat_ab:
        preboot_clock = run_command(
            ["adb", "-s", args.serial, "shell", "date", "+%s"],
            timeout_seconds=20,
        )
        result["commands"].append(preboot_clock)
        result["preboot_device_clock"] = summarize_device_clock(preboot_clock)
        result["logcat_observer_decision_monotonic_ns"] = time.monotonic_ns()

    observer: LogcatObserver | None = None
    logcat_arm = args.mode.removeprefix("preboot-logcat-") if is_logcat_ab else None
    if not is_logcat_ab or logcat_arm == "control":
        observer = LogcatObserver(args.serial, results / "logcat-filtered.jsonl")
        observer.start()
        atexit.register(observer.stop)
        result["observer_available_at_start"] = observer.available
        observer_started = mark("logcat_observer_started", timeline_path, {"available": observer.available})
        result["logcat_observer_started_monotonic_ns"] = observer_started["monotonic_ns"]
    else:
        observer_started = mark("logcat_observer_deferred", timeline_path, {"available": False})
        result["observer_available_at_start"] = False
        result["logcat_observer_started_monotonic_ns"] = None
    if is_output_ab:
        result["observer_started_monotonic_ns"] = observer_started["monotonic_ns"]
        applied_ns = result.get("launch_monotonic_ns")
        result["launch_to_observer_gap_ns"] = (
            observer_started["monotonic_ns"] - applied_ns if isinstance(applied_ns, int) else None
        )
    animation_values_before_boot_complete: dict[str, Any] = {}
    if is_ab:
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            animation = run_command(
                ["adb", "-s", args.serial, "shell", "settings", "get", "global", setting],
                timeout_seconds=20,
            )
            result["commands"].append(animation)
            animation_values_before_boot_complete[setting] = {
                "exit_code": animation["exit_code"],
                "stdout_available": animation["stdout_available"],
                "value": str(animation["stdout"]).strip()
                if animation["exit_code"] == 0 and animation["stdout_available"]
                else None,
            }
        result["animation_values_before_boot_complete"] = animation_values_before_boot_complete
        result["animation_policy"] = "Read-only; same runtime defaults through the 15-second A/B window."

    def take_snapshot(label: str) -> dict[str, Any]:
        if is_ab and result.get("first_device_observation_after_t0_ns") is None:
            result["first_device_observation_after_t0_ns"] = time.monotonic_ns()
        before = mark(f"snapshot_{label}_start", timeline_path)
        command = run_command(
            ["adb", "-s", args.serial, "shell", "sh"],
            timeout_seconds=35,
            input_text=REMOTE_SNAPSHOT,
        )
        after = mark(f"snapshot_{label}_end", timeline_path, {"exit_code": command["exit_code"]})
        raw = str(command["stdout"])
        sections = parse_sections(raw)
        health = snapshot_health_for_command(
            sections,
            adb_exit_code=command["exit_code"],
            stdout_available=command["stdout_available"],
        )
        snapshot = {
            "label": label,
            "started_monotonic_ns": before["monotonic_ns"],
            "ended_monotonic_ns": after["monotonic_ns"],
            "adb_exit_code": command["exit_code"],
            "stdout_available": command["stdout_available"],
            "stderr_excerpt": str(command["stderr"])[-1000:],
            "sections": sections,
            **health,
        }
        write_json(snapshot_dir / f"{label}.json", snapshot)
        (snapshot_dir / f"{label}.txt").write_text(raw[:16000], encoding="utf-8")
        result["snapshots"].append(snapshot)
        if is_output_ab:
            first_home_captured = "first_home" in result.get("output_screenshots", {})
        elif is_logcat_ab:
            first_home_captured = "first_home" in result.get("preboot_logcat", {}).get("screenshots", {})
        else:
            first_home_captured = False
        if should_capture_first_home_screenshot(args.mode, snapshot.get("focus_state"), first_home_captured):
            if is_output_ab:
                capture_output_screenshot("first_home", f"{args.mode}-first-home")
            else:
                capture = take_screenshot(f"{args.mode}-first-home")
                result["preboot_logcat"].setdefault("screenshots", {})["first_home"] = capture
        return snapshot

    def take_screenshot(label: str) -> dict[str, Any]:
        if is_ab and result.get("first_device_observation_after_t0_ns") is None:
            result["first_device_observation_after_t0_ns"] = time.monotonic_ns()
        entry = mark(f"screenshot_{label}_start", timeline_path)
        capture = run_command(
            ["adb", "-s", args.serial, "exec-out", "screencap", "-p"],
            timeout_seconds=30,
            capture_bytes=True,
        )
        image = capture["stdout"] if isinstance(capture["stdout"], bytes) else b""
        valid_png = capture["exit_code"] == 0 and image.startswith(b"\x89PNG\r\n\x1a\n")
        path = results / f"{label}.png"
        if valid_png:
            path.write_bytes(image)
        record = {
            "label": label,
            "path": path.name,
            "monotonic_ns": entry["monotonic_ns"],
            "bytes": len(image),
            "exit_code": capture["exit_code"],
            "valid_png_signature": valid_png,
            "stderr_excerpt": str(capture["stderr"])[-500:],
        }
        result["screenshots"].append(record)
        mark(f"screenshot_{label}_end", timeline_path, {key: value for key, value in record.items() if key != "stderr_excerpt"})
        return record

    def capture_output_screenshot(role: str, label: str) -> dict[str, Any]:
        capture = take_screenshot(label)
        result.setdefault("output_screenshots", {})[role] = capture
        return capture

    def capture_anr_if_observed() -> None:
        if is_logcat_ab or observer is None or not observer.anr_event.is_set():
            return
        observer.anr_event.clear()
        if is_ab and not result.get("anr_screenshot_capture"):
            result["anr_screenshot_capture"] = take_screenshot(f"{args.mode}-anr")
            if is_output_ab:
                result.setdefault("output_screenshots", {})["anr"] = result["anr_screenshot_capture"]

    def host_logcat_process_proof(label: str) -> dict[str, Any]:
        command = run_command(["ps", "-eo", "pid,args"], timeout_seconds=15)
        proof = {
            "label": label,
            **process_list_evidence(command, args.serial),
            "observer_pid": observer.process.pid if observer and observer.process else None,
            "observer_running": observer.still_running() if observer else False,
        }
        write_json(results / f"host-logcat-process-{label}.json", proof)
        return proof

    if is_logcat_ab:
        first_process_proof = host_logcat_process_proof("preboot")
        result["preboot_logcat"] = {
            "policy": "continuous" if logcat_arm == "control" else "deferred_final_dump",
            "observer_started": logcat_arm == "control" and observer is not None,
            "observer_started_monotonic_ns": result.get("logcat_observer_started_monotonic_ns"),
            "observer_pid": observer.process.pid if observer and observer.process
            else "UNKNOWN" if logcat_arm == "control" else "NONE",
            "pre_boot_logcat_observer_enabled": "UNKNOWN",
            "observer_decision_monotonic_ns": result.get("logcat_observer_decision_monotonic_ns"),
            "observer_decision_epoch_exclusive": preboot_logcat_clock_bounds(
                result.get("preboot_device_clock")
            )[0],
            "coverage_start_device_epoch": preboot_logcat_clock_bounds(
                result.get("preboot_device_clock")
            )[1],
            "process_samples": [first_process_proof],
            "process_proof": first_process_proof,
            "preexisting_home_anr_status": "unknown",
            "final_dump": None,
        }
        enabled, observer_pid_summary = d4_observer_summary(
            result["preboot_logcat"]["policy"],
            result["preboot_logcat"]["process_samples"],
            observer.process.pid if observer and observer.process else None,
        )
        result["preboot_logcat"]["pre_boot_logcat_observer_enabled"] = enabled
        result["preboot_logcat"]["observer_pid"] = observer_pid_summary
        result["pre_boot_logcat_observer_enabled"] = enabled
        result["observer_pid"] = observer_pid_summary

    boot_wait_started = time.monotonic()
    boot_complete = False
    while time.monotonic() - boot_wait_started < 600:
        if observer is not None and not observer.still_running():
            break
        command = run_command(
            ["adb", "-s", args.serial, "shell", "getprop", "sys.boot_completed"],
            timeout_seconds=20,
        )
        result["commands"].append(command)
        if command["exit_code"] == 0 and str(command["stdout"]).strip() == "1":
            boot_complete = True
            break
        time.sleep(2)
    mark(
        "boot_poll_finished",
        timeline_path,
        {"boot_complete": boot_complete, "elapsed_seconds": round(time.monotonic() - boot_wait_started, 2)},
    )
    if not boot_complete:
        result["error"] = "Boot did not complete within the bounded 600-second observation window."
        result["classification"] = "INCONCLUSIVE"
        result["reason"] = result["error"]
        result["observer"] = stop_optional_observer(observer)
        write_result(results, result)
        return 1

    if is_ab:
        if is_menu_ab:
            result["experiment"] = "default_skin_menu_ab"
            result["arm"] = args.mode
        result["config_equivalence"] = equivalence

        # This host-only marker is deliberately followed by MENU in treatment before
        # any screenshot, health query, or snapshot command is sent to the emulator.
        t0 = mark("ab_boot_t0", timeline_path, {"arm": args.mode})
        t0_ns = t0["monotonic_ns"]
        result["boot_t0_monotonic_ns"] = t0_ns
        result["menu_event_count"] = 0
        menu_process: subprocess.Popen[str] | None = None
        menu_finish: dict[str, Any] = {}
        menu_done = threading.Event()
        menu_command = ["adb", "-s", args.serial, "shell", "input", "keyevent", "82"]
        send_menu = args.mode in {
            "treatment", "output-control", "output-treatment",
            "preboot-logcat-control", "preboot-logcat-treatment",
        }
        if send_menu:
            result["menu_attempted"] = True
            result["menu_event_count"] = 1
            mark("ab_menu_command_start", timeline_path, {"command": menu_command})
            result["menu_start_monotonic_ns"] = time.monotonic_ns()
            result["menu_delta_ms"] = round((result["menu_start_monotonic_ns"] - t0_ns) / 1_000_000, 3)
            try:
                menu_process = subprocess.Popen(
                    menu_command,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )

                def drain_menu() -> None:
                    assert menu_process is not None
                    try:
                        stdout, stderr = menu_process.communicate(timeout=120)
                        menu_finish.update({"exit_code": menu_process.returncode, "stdout": stdout, "stderr": stderr})
                    except subprocess.TimeoutExpired:
                        menu_process.kill()
                        stdout, stderr = menu_process.communicate()
                        menu_finish.update({"exit_code": menu_process.returncode, "stdout": stdout, "stderr": stderr, "timed_out": True})
                    finally:
                        menu_finish["ended_monotonic_ns"] = time.monotonic_ns()
                        mark(
                            "ab_menu_command_end",
                            timeline_path,
                            {
                                "exit_code": menu_finish.get("exit_code"),
                                "timed_out": menu_finish.get("timed_out", False),
                            },
                        )
                        menu_done.set()

                threading.Thread(target=drain_menu, name="menu-ab-input", daemon=True).start()
            except OSError as error:
                menu_finish.update({"exit_code": None, "stderr": f"{error.__class__.__name__}: {error}"})
                menu_finish["ended_monotonic_ns"] = time.monotonic_ns()
                mark("ab_menu_command_end", timeline_path, {"error": menu_finish["stderr"]})
                menu_done.set()
        else:
            result["menu_attempted"] = False
            result["menu_issued"] = False

        if is_output_ab:
            capture_output_screenshot("boot", f"{args.mode}-boot")
        take_screenshot(f"{args.mode}-immediate")
        capture_anr_if_observed()
        take_snapshot(f"{args.mode}-00")
        capture_anr_if_observed()
        window_end_ns = t0_ns + 15_000_000_000
        next_sample_ns = t0_ns + 3_000_000_000
        midpoint_capture_ns = t0_ns + 9_000_000_000
        midpoint_captured = False
        sample_index = 1
        while time.monotonic_ns() < window_end_ns:
            capture_anr_if_observed()
            now_ns = time.monotonic_ns()
            if not midpoint_captured and now_ns >= midpoint_capture_ns:
                midpoint_screenshot = take_screenshot(f"{args.mode}-midpoint")
                result["midpoint_screenshot_capture"] = midpoint_screenshot
                if is_output_ab:
                    result.setdefault("output_screenshots", {})["midpoint"] = midpoint_screenshot
                midpoint_captured = True
                continue
            if now_ns >= window_end_ns:
                break
            if now_ns >= next_sample_ns:
                take_snapshot(f"{args.mode}-{sample_index:02d}")
                capture_anr_if_observed()
                sample_index += 1
                next_sample_ns += 3_000_000_000
                continue
            next_deadline_ns = min(
                window_end_ns,
                next_sample_ns,
                midpoint_capture_ns if not midpoint_captured else window_end_ns,
            )
            wait_seconds = max(0.0, (next_deadline_ns - now_ns) / 1_000_000_000)
            if is_logcat_ab or observer is None:
                time.sleep(wait_seconds)
            else:
                observer.anr_event.wait(timeout=wait_seconds)
        take_snapshot(f"{args.mode}-final")
        capture_anr_if_observed()
        if not midpoint_captured:
            result["midpoint_screenshot_capture"] = {
                "captured": False,
                "reason": "The 15-second observation ended before the scheduled midpoint screenshot could be captured.",
            }
        final_screenshot = take_screenshot(f"{args.mode}-final")
        if is_output_ab:
            result.setdefault("output_screenshots", {})["final"] = final_screenshot
        capture_anr_if_observed()
        end_ns = time.monotonic_ns()

        animation_reads_after_observation: dict[str, dict[str, Any]] = {}
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            animation = run_command(
                ["adb", "-s", args.serial, "shell", "settings", "get", "global", setting],
                timeout_seconds=20,
            )
            result["commands"].append(animation)
            animation_reads_after_observation[setting] = animation
        animation_values = canonical_animation_values(animation_reads_after_observation)
        result["animation_values_after_observation"] = animation_values
        result["animation_policy_observation"] = "Read-only values captured after the matched 15-second A/B window."

        final_dump_command: dict[str, Any] | None = None
        final_dump_text = ""
        final_dump_summary: dict[str, Any] | None = None
        final_dump_events: list[dict[str, Any]] = []
        final_dump_device_clock: dict[str, Any] | None = None
        final_process_proof: dict[str, Any] | None = None
        observer_stop: dict[str, Any] | None = None
        if is_logcat_ab:
            final_process_proof = host_logcat_process_proof("post-window-before-final-dump")
            result["preboot_logcat"]["process_samples"].append(final_process_proof)
            result["preboot_logcat"]["process_proof"] = final_process_proof
            result["preboot_logcat"]["observer_running_through_window"] = (
                observer.still_running() if observer else False
            )
            final_dump_command = run_command(
                [
                    "adb", "-s", args.serial, "logcat", "-d", "-b", "main", "-b", "system",
                    "-b", "events", "-v", "epoch",
                ],
                timeout_seconds=90,
            )
            raw_stdout = final_dump_command.get("stdout")
            final_dump_text = raw_stdout if isinstance(raw_stdout, str) else ""
            device_clock_after_dump_command = run_command(
                ["adb", "-s", args.serial, "shell", "date", "+%s"],
                timeout_seconds=20,
            )
            result["commands"].append(device_clock_after_dump_command)
            try:
                final_dump_device_epoch = (
                    float(str(device_clock_after_dump_command["stdout"]).strip())
                    if device_clock_after_dump_command["exit_code"] == 0
                    and device_clock_after_dump_command["stdout_available"]
                    else None
                )
            except ValueError:
                final_dump_device_epoch = None
            final_dump_device_clock = {
                "exit_code": device_clock_after_dump_command["exit_code"],
                "stdout_available": device_clock_after_dump_command["stdout_available"],
                "device_epoch_seconds": final_dump_device_epoch,
                "started_monotonic_ns": device_clock_after_dump_command["started_monotonic_ns"],
                "ended_monotonic_ns": device_clock_after_dump_command["ended_monotonic_ns"],
            }
            final_snapshot = result["snapshots"][-1] if result["snapshots"] else {}
            immediate_snapshot = next(
                (
                    snapshot for snapshot in result["snapshots"]
                    if snapshot.get("label") == f"{args.mode}-00"
                ),
                {},
            )
            window_end_device_epoch = device_epoch_at_host_time(immediate_snapshot, window_end_ns)
            dump_summary = final_logcat_dump_coverage(
                exit_code=final_dump_command["exit_code"],
                stdout_available=final_dump_command["stdout_available"],
                stdout=final_dump_text,
                window_start_device_epoch=result["preboot_logcat"].get("coverage_start_device_epoch"),
                window_end_device_epoch=window_end_device_epoch,
                dump_completed_after_window=final_dump_command["ended_monotonic_ns"] >= window_end_ns,
                device_epoch_at_dump=final_dump_device_epoch,
            )
            artifact_path = results / "logcat-final-dump.txt"
            artifact_saved = False
            if final_dump_text and len(final_dump_text.encode("utf-8")) <= 5_000_000:
                artifact_path.write_text(final_dump_text, encoding="utf-8")
                artifact_saved = True
            elif final_dump_text:
                artifact_path.write_text(
                    final_dump_text[:2_000_000]
                    + "\n[bounded artifact excerpt; full command output was parsed in-memory]\n"
                    + final_dump_text[-2_000_000:],
                    encoding="utf-8",
                )
                artifact_saved = True
            final_dump_events = final_logcat_anr_records(final_dump_text)
            write_jsonl(results / "logcat-final-anr-events.jsonl", final_dump_events)
            final_dump_summary = {
                "command": final_dump_command["command"],
                **dump_summary,
                "stdout_bytes": len(final_dump_text.encode("utf-8")),
                "bounded_raw_artifact": artifact_path.name if artifact_saved else None,
                "raw_artifact_is_excerpt": len(final_dump_text.encode("utf-8")) > 5_000_000,
                "raw_artifact_saved": artifact_saved,
                "anr_record_count": len(final_dump_events),
                "device_clock_after_dump": final_dump_device_clock,
                "window_end_device_epoch_estimate": window_end_device_epoch,
                "device_clock_source": "post-window immediate snapshot plus monotonic interpolation",
            }
            result["preboot_logcat"]["final_dump"] = final_dump_summary
            result["preboot_logcat"]["device_clock_after_dump"] = final_dump_device_clock
            if observer is not None:
                observer_stop = observer.stop()

        configuration = canonical_configuration(metadata, animation_values)
        result["canonical_configuration"] = configuration
        result["canonical_configuration_sha256"] = hashlib.sha256(
            json.dumps(configuration, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()

        if send_menu:
            if not menu_done.wait(timeout=2):
                if menu_process is not None:
                    menu_process.kill()
                menu_done.wait(timeout=2)
                menu_finish["timed_out"] = True
            result["menu_end_monotonic_ns"] = menu_finish.get("ended_monotonic_ns")
            result["menu_issued"] = menu_finish.get("exit_code") == 0 and not menu_finish.get("timed_out", False)
            result["menu_command"] = {
                "command": menu_command,
                "exit_code": menu_finish.get("exit_code"),
                "stdout_available": menu_finish.get("exit_code") is not None,
                "stdout_nonempty": bool(menu_finish.get("stdout")),
                "stderr_excerpt": str(menu_finish.get("stderr", ""))[-500:],
                "timed_out": menu_finish.get("timed_out", False),
            }
        else:
            result["menu_command"] = {"command": [], "exit_code": None}

        snapshots = (
            list(result["snapshots"])
            if is_logcat_ab
            else [
                snapshot
                for snapshot in result["snapshots"]
                if snapshot.get("started_monotonic_ns", window_end_ns + 1) <= window_end_ns
            ]
        )
        home_packages = sorted({
            str(snapshot["home_package"])
            for snapshot in snapshots
            if snapshot.get("home_package")
        })
        if is_logcat_ab:
            dump_events_for_mapping = [
                {
                    **event,
                    "host_monotonic_ns": (
                        final_dump_command["ended_monotonic_ns"]
                        if final_dump_command is not None
                        else None
                    ),
                }
                for event in final_dump_events
            ]
            timestamped_logcat_events = timestamp_anr_events(
                dump_events_for_mapping,
                result["snapshots"],
            )
        else:
            timestamped_logcat_events = timestamp_anr_events(
                [event for event in observer.snapshot() if event["is_anr"]] if observer else [],
                result["snapshots"],
            )
        selected_start_ns, interval_end_ns = selected_anr_observation_window(
            is_output_ab,
            result.get("launch_monotonic_ns"),
            t0_ns,
            end_ns,
            window_end_ns,
        )
        if is_logcat_ab:
            start_epoch = result["preboot_logcat"].get("observer_decision_epoch_exclusive")
            end_epoch = (
                final_dump_summary.get("window_end_device_epoch_estimate")
                if isinstance(final_dump_summary, dict)
                else None
            )
            interval_events = [
                event
                for event in timestamped_logcat_events
                if isinstance(event.get("device_epoch"), (int, float))
                and isinstance(start_epoch, (int, float))
                and isinstance(end_epoch, (int, float))
                and start_epoch <= event["device_epoch"] <= end_epoch
            ]
        else:
            interval_events = [
                event
                for event in timestamped_logcat_events
                if isinstance(event.get("mapped_host_monotonic_ns"), int)
                and isinstance(selected_start_ns, int)
                and selected_start_ns <= event["mapped_host_monotonic_ns"] <= interval_end_ns
            ]
        snapshot_events = [
            {
                "line": line,
                "source": "snapshot",
                "host_monotonic_ns": snapshot.get("ended_monotonic_ns"),
            }
            for snapshot in snapshots
            for event in snapshot_anr_events(snapshot)
            for line in [event["line"]]
        ]
        home_package_set = set(home_packages)
        anr_observations = scope_observation_anrs(
            interval_events,
            snapshot_events,
            timestamped_logcat_events,
            home_package_set,
            output_forwarding_ab=is_output_ab,
        )
        scoped = anr_observations["scoped"]
        scoped_snapshots = anr_observations["scoped_snapshots"]
        all_scoped = scope_anr_events_to_packages(timestamped_logcat_events, home_package_set)
        unresolved_snapshot_events = anr_observations["unresolved_snapshot_events"]
        if is_logcat_ab:
            scoped = scope_anr_events_to_packages(interval_events, home_package_set)
            scoped_snapshots = scope_anr_events_to_packages(snapshot_events, home_package_set)
            unresolved_snapshot_events = uncorroborated_snapshot_home_anrs(
                snapshot_events,
                timestamped_logcat_events,
                home_package_set,
            )
        result["home_packages_observed"] = home_packages
        result["focus_samples"] = [
            {
                "label": snapshot.get("label"),
                "focus_state": snapshot.get("focus_state"),
                "home_package": snapshot.get("home_package"),
                "stable_home_health": snapshot.get("stable_pre_menu_health"),
                "started_monotonic_ns": snapshot.get("started_monotonic_ns"),
            }
            for snapshot in snapshots
        ]
        result["first_home_focus_observed"] = any(
            snapshot.get("focus_state") == "HOME" for snapshot in snapshots
        )
        final_snapshot = max(snapshots, key=lambda snapshot: snapshot.get("started_monotonic_ns", 0)) if snapshots else {}
        result["final_focus_state"] = final_snapshot.get("focus_state")
        result["final_home_healthy"] = final_snapshot.get("stable_pre_menu_health")
        result["home_anr_events"] = scoped["home"]
        result["other_anr_events"] = scoped["other"]
        if is_logcat_ab:
            observer_was_running = bool(result["preboot_logcat"].get("observer_running_through_window"))
            enabled, observer_pid_summary = d4_observer_summary(
                result["preboot_logcat"]["policy"],
                result["preboot_logcat"]["process_samples"],
                observer.process.pid if observer and observer.process else None,
            )
            result["preboot_logcat"]["pre_boot_logcat_observer_enabled"] = enabled
            result["preboot_logcat"]["observer_pid"] = observer_pid_summary
            result["pre_boot_logcat_observer_enabled"] = enabled
            result["observer_pid"] = observer_pid_summary
            process_ok = enabled is (logcat_arm == "control")
            observer_complete = bool(
                final_dump_summary
                and final_dump_summary.get("complete") is True
                and process_ok
                and (observer is None or (observer_stop and observer_stop.get("available_at_stop") is True))
            )
            clock_epoch = (
                result.get("preboot_device_clock", {}).get("device_epoch_seconds")
                if isinstance(result.get("preboot_device_clock"), dict)
                else None
            )
            decision_epoch = result["preboot_logcat"].get("observer_decision_epoch_exclusive")
            unresolved_before_decision = any(
                not isinstance(event.get("device_epoch"), (int, float))
                or event["device_epoch"] < decision_epoch
                for event in all_scoped["unknown"]
                if isinstance(decision_epoch, (int, float))
            )
            if (
                not final_dump_summary
                or final_dump_summary.get("complete") is not True
                or not isinstance(clock_epoch, (int, float))
                or not isinstance(decision_epoch, (int, float))
            ):
                preexisting_status = "unknown"
            elif any(
                isinstance(event.get("device_epoch"), (int, float))
                and event["device_epoch"] < clock_epoch
                for event in all_scoped["home"]
            ):
                preexisting_status = "present"
            elif unresolved_before_decision or any(
                isinstance(event.get("device_epoch"), (int, float))
                and event["device_epoch"] < decision_epoch
                for event in all_scoped["home"]
            ):
                preexisting_status = "unknown"
            else:
                preexisting_status = "absent"
            result["preboot_logcat"]["preexisting_home_anr_status"] = preexisting_status
            result["preboot_logcat"]["process_proof_valid"] = process_ok
            result["preboot_logcat"]["observer_stop"] = observer_stop
            result["preboot_logcat"]["selected_device_epoch_window"] = {
                "start_exclusive": decision_epoch,
                "end_estimate": (
                    final_dump_summary.get("window_end_device_epoch_estimate")
                    if isinstance(final_dump_summary, dict)
                    else None
                ),
            }
        else:
            observer_was_running = observer.still_running() if observer else False
            observer_complete = bool(
                observer_was_running and observer and not observer.truncated and observer.events_dropped == 0
            )
        if args.mode == "treatment":
            prefix = classify_pre_menu_home_anrs(
                timestamped_logcat_events,
                snapshot_events,
                home_package_set,
                result.get("menu_start_monotonic_ns"),
                observer_complete,
                result.get("logcat_observer_started_monotonic_ns"),
                interval_events,
            )
            result["pre_menu_home_anr_status"] = prefix["status"]
            result["pre_menu_home_anr_events"] = prefix["events"]
            result["pre_menu_home_anr_unresolved_events"] = prefix["unresolved_events"]
        result["unresolved_home_anr"] = bool(
            scoped["unknown"]
            or scoped_snapshots["unknown"]
            or unresolved_snapshot_events
            or any(
                (event.get("device_epoch") is None if is_logcat_ab else event.get("mapped_host_monotonic_ns") is None)
                for category in (all_scoped["home"], all_scoped["unknown"])
                for event in category
            )
            or is_logcat_ab and not (final_dump_summary and final_dump_summary.get("complete") is True)
        )
        result["unresolved_snapshot_home_anr_events"] = unresolved_snapshot_events
        result["home_anr"] = bool(scoped["home"])
        if is_logcat_ab and scoped["home"]:
            result["preboot_logcat"]["anr_screenshot"] = take_screenshot("preboot-logcat-anr")
        result["boot_path_stable"] = stable_control_boot_path(snapshots) if args.mode == "control" else None
        result["observer_available_at_decision"] = observer_was_running
        result["observer_complete"] = observer_complete
        if not is_logcat_ab:
            observer_stop = observer.stop() if observer else None
        result["observer"] = observer_stop if observer else None
        result["observation"] = {
            "target_seconds": 15,
            "elapsed_seconds": elapsed_seconds(t0_ns, end_ns),
            "complete": end_ns >= window_end_ns and observer_complete,
            "snapshot_count": len(snapshots),
            "start_monotonic_ns": t0_ns,
            "end_monotonic_ns": end_ns,
        }
        if is_logcat_ab:
            result["observation_device_epoch_end"] = (
                final_dump_summary.get("window_end_device_epoch_estimate")
                if isinstance(final_dump_summary, dict)
                else None
            )
        if is_output_ab:
            result["observation"].update({
                "start_monotonic_ns": t0_ns,
                "end_monotonic_ns": end_ns,
            })
            launch_ns = result.get("launch_monotonic_ns")
            result["launch_to_observer_gap"] = {
                "launch_monotonic_ns": launch_ns,
                "observer_started_monotonic_ns": result.get("observer_started_monotonic_ns"),
                "gap_ns": result.get("launch_to_observer_gap_ns"),
                "note": "Live Logcat starts after ADB transport; mapped buffered events are retained, while unmapped or snapshot-only HOME ANRs remain unresolved.",
            }
            pre_forwarding = [
                event for event in all_scoped["home"]
                if isinstance(event.get("mapped_host_monotonic_ns"), int)
                and isinstance(launch_ns, int)
                and event["mapped_host_monotonic_ns"] < launch_ns
            ]
            unmapped_anrs = [
                event for category in (all_scoped["home"], all_scoped["unknown"])
                for event in category
                if not isinstance(event.get("mapped_host_monotonic_ns"), int)
            ]
            result["pre_forwarding_home_anr_status"] = (
                "present" if pre_forwarding else "unknown" if unmapped_anrs or unresolved_snapshot_events else "absent"
            )
            result["pre_forwarding_home_anr_events"] = pre_forwarding
            result["observer_gap_anr_unknown"] = bool(unmapped_anrs or unresolved_snapshot_events)
            result["output_forwarding"] = {
                **(output_forwarding if isinstance(output_forwarding, dict) else {}),
                "applied_monotonic_ns": result.get("launch_monotonic_ns"),
            }
            selected_events = [
                {**event, "package": anr_event_package(str(event.get("line", "")))}
                for event in scoped["home"]
            ]
            result["home_anr_events"] = selected_events
            result["screenshots"] = result.get("output_screenshots", {})
            result["selection_interval"] = {
                "start_monotonic_ns": launch_ns,
                "end_monotonic_ns": end_ns,
                "event_window_source": "device-epoch-mapped Logcat, not receipt time",
            }
            result["first_device_observation_after_t0_ns"] = result.get(
                "first_device_observation_after_t0_ns"
            )
        result["classification"] = "ARM_COMPLETE" if result["observation"]["complete"] else "INCONCLUSIVE"
        result["reason"] = (
            "The arm completed its matched observation window; A/B interpretation is produced by the comparator."
            if result["observation"]["complete"]
            else (
                "The arm lacks complete final-dump, process-policy, or observation-window evidence."
                if is_logcat_ab
                else "The arm did not complete the full observation window with a live, complete log observer."
            )
        )
        write_result(results, result)
        mark("ab_arm_finished", timeline_path, {"arm": args.mode, "classification": result["classification"]})
        print(json.dumps({"arm": args.mode, "classification": result["classification"], "reason": result["reason"]}, indent=2))
        return 0 if result["observation"]["complete"] else 1

    observation_start = mark("pre_menu_observation_start", timeline_path)
    pre_window_start_ns = observation_start["monotonic_ns"]
    take_screenshot("pre-menu-immediate")
    take_snapshot("pre-immediate")

    def observe_window(prefix: str) -> None:
        started = time.monotonic()
        deadline = started + 15
        next_snapshot = started + 3
        index = 1
        while time.monotonic() < deadline and observer.still_running():
            remaining = next_snapshot - time.monotonic()
            if remaining > 0:
                time.sleep(min(remaining, max(deadline - time.monotonic(), 0)))
                continue
            take_snapshot(f"{prefix}-{index:02d}")
            index += 1
            next_snapshot += 3

    observe_window("pre")
    take_snapshot("pre-final")
    take_screenshot("pre-menu-final")
    pre_menu_mark = mark("pre_menu_decision", timeline_path)
    pre_window_end_ns = pre_menu_mark["monotonic_ns"]

    snapshots_before_menu = list(result["snapshots"])
    home_package = next(
        (snapshot["home_package"] for snapshot in snapshots_before_menu if snapshot.get("home_package")),
        None,
    )
    pre_live_events = [
        event for event in observer.snapshot()
        if event["is_anr"] and pre_window_start_ns <= event["host_monotonic_ns"] <= pre_window_end_ns
    ]
    pre_snapshot_events = [
        event
        for snapshot in snapshots_before_menu
        for event in snapshot_anr_events(snapshot)
        if event["host_monotonic_ns"] is not None
        and pre_window_start_ns <= event["host_monotonic_ns"] <= pre_window_end_ns
    ]
    pre_scoped = scope_anr_events(pre_live_events + pre_snapshot_events, home_package)
    pre_menu_anr = bool(pre_scoped["home"])
    pre_unknown_anr = bool(pre_scoped["unknown"])
    result["resolved_home_component"] = next(
        (snapshot["home_component"] for snapshot in snapshots_before_menu if snapshot.get("home_component")),
        None,
    )
    result["resolved_home_package"] = home_package
    result["pre_menu_anr"] = pre_menu_anr
    result["pre_menu_unknown_anr"] = pre_unknown_anr
    result["pre_menu_anr_events"] = pre_scoped
    result["other_pre_menu_anr_events"] = pre_scoped["other"]

    metadata = load_run_metadata(args.metadata)
    equivalence = config_equivalence(metadata)
    result["config_equivalence"] = equivalence
    health_values = [snapshot["stable_pre_menu_health"] for snapshot in snapshots_before_menu]
    stable_health: bool | None = True if health_values and all(value is True for value in health_values) else False if any(
        value is False for value in health_values
    ) else None
    result["pre_menu_health_samples"] = health_values
    result["stable_pre_menu_health"] = stable_health
    mark(
        "pre_menu_decision",
        timeline_path,
        {
            "pre_menu_anr": pre_menu_anr,
            "pre_menu_unknown_anr": pre_unknown_anr,
            "stable_pre_menu_health": stable_health,
            "home_package": home_package,
            "config_equivalent": equivalence["equivalent"],
        },
    )

    if pre_menu_anr or pre_unknown_anr or not observer.still_running() or not equivalence["equivalent"]:
        observer_was_running = observer.still_running()
        observer_complete = observer_was_running and not observer.truncated and observer.events_dropped == 0
        result["observer_available_at_decision"] = observer_was_running
        result["observer_complete"] = observer_complete
        result["observer"] = observer.stop()
        classification = classify_probe(
            config_equivalent=equivalence["equivalent"],
            observer_available=observer_complete,
            pre_menu_anr=pre_menu_anr,
            stable_pre_menu_health=stable_health,
            stable_post_menu_health=None,
            menu_issued=False,
            post_menu_anr=False,
            unresolved_anr=pre_unknown_anr,
        )
    else:
        menu_command = ["adb", "-s", args.serial, "shell", "input", "keyevent", "82"]
        menu_start = mark("menu_command_start", timeline_path, {"command": menu_command})
        result["menu_attempted"] = True
        result["menu_start_monotonic_ns"] = menu_start["monotonic_ns"]
        menu_started_ns = time.monotonic_ns()
        menu_process = subprocess.Popen(menu_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        inflight_snapshot: dict[str, Any] = {}

        def capture_while_menu_runs() -> None:
            inflight_snapshot.update(take_snapshot("post-menu-inflight"))

        snapshot_thread = threading.Thread(target=capture_while_menu_runs, name="menu-inflight-snapshot")
        snapshot_thread.start()
        menu_timed_out = False
        try:
            menu_stdout, menu_stderr = menu_process.communicate(timeout=120)
        except subprocess.TimeoutExpired:
            menu_timed_out = True
            menu_process.kill()
            menu_stdout, menu_stderr = menu_process.communicate()
        snapshot_thread.join(timeout=40)
        menu_exit = menu_process.returncode
        menu_end = mark(
            "menu_command_end",
            timeline_path,
            {"exit_code": menu_exit, "timed_out": menu_timed_out},
        )
        result["commands"].append({
            "command": menu_command,
            "started_monotonic_ns": menu_started_ns,
            "ended_monotonic_ns": menu_end["monotonic_ns"],
            "exit_code": menu_exit,
            "stdout_available": True,
            "stderr_available": True,
            "stdout_nonempty": bool(menu_stdout),
            "stderr_nonempty": bool(menu_stderr),
            "timed_out": menu_timed_out,
        })
        result["menu_end_monotonic_ns"] = menu_end["monotonic_ns"]
        result["menu_command"] = {
            "exit_code": menu_exit,
            "stdout_available": True,
            "stdout_nonempty": bool(menu_stdout),
            "stderr_excerpt": menu_stderr[-500:],
            "timed_out": menu_timed_out,
            "inflight_snapshot_captured": bool(inflight_snapshot),
        }
        result["menu_issued"] = menu_exit == 0 and not menu_timed_out

        animation_changes = []
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            animation_command = run_command(
                ["adb", "-s", args.serial, "shell", "settings", "put", "global", setting, "0"],
                timeout_seconds=20,
            )
            animation_changes.append({
                "setting": setting,
                "exit_code": animation_command["exit_code"],
                "stdout_available": animation_command["stdout_available"],
                "stderr_excerpt": str(animation_command["stderr"])[-300:],
            })
        result["animation_changes_after_menu"] = animation_changes
        take_screenshot("post-menu-immediate")

        post_start = time.monotonic()
        post_window_start_ns = time.monotonic_ns()
        post_deadline = post_start + 15
        next_snapshot = post_start + 3
        index = 1
        while time.monotonic() < post_deadline and observer.still_running():
            remaining = next_snapshot - time.monotonic()
            if remaining > 0:
                time.sleep(min(remaining, max(post_deadline - time.monotonic(), 0)))
                continue
            take_snapshot(f"post-{index:02d}")
            index += 1
            next_snapshot += 3

        take_snapshot("post-final")
        all_events = observer.snapshot()
        post_end_ns = time.monotonic_ns()
        post_live_events = [
            event for event in all_events
            if event["is_anr"] and menu_start["monotonic_ns"] <= event["host_monotonic_ns"] <= post_end_ns
        ]
        post_snapshot_events = [
            event
            for snapshot in result["snapshots"]
            if snapshot["label"].startswith("post-")
            for event in snapshot_anr_events(snapshot)
            if event["host_monotonic_ns"] is not None
            and post_window_start_ns <= event["host_monotonic_ns"] <= post_end_ns
        ]
        post_scoped = scope_anr_events(post_live_events + post_snapshot_events, home_package)
        result["post_menu_anr_events"] = post_scoped
        result["other_post_menu_anr_events"] = post_scoped["other"]
        result["post_menu_anr"] = bool(post_scoped["home"])
        result["post_menu_unknown_anr"] = bool(post_scoped["unknown"])
        post_health_snapshots = [
            snapshot
            for snapshot in result["snapshots"]
            if snapshot["label"].startswith("post-") and snapshot["label"] != "post-menu-inflight"
        ]
        post_health_values = [snapshot["stable_pre_menu_health"] for snapshot in post_health_snapshots]
        stable_post_health: bool | None = True if post_health_values and all(
            value is True for value in post_health_values
        ) else False if any(value is False for value in post_health_values) else None
        result["post_menu_health_samples"] = post_health_values
        result["stable_post_menu_health"] = stable_post_health
        if result["post_menu_anr"]:
            take_screenshot("post-menu-anr")
        elif stable_post_health is True:
            take_screenshot("post-menu-stable")
        else:
            take_screenshot("post-menu-final")
        observer_was_running = observer.still_running()
        observer_complete = observer_was_running and not observer.truncated and observer.events_dropped == 0
        result["observer_available_at_decision"] = observer_was_running
        result["observer_complete"] = observer_complete
        result["observer"] = observer.stop()
        classification = classify_probe(
            config_equivalent=equivalence["equivalent"],
            observer_available=observer_complete,
            pre_menu_anr=pre_menu_anr,
            stable_pre_menu_health=stable_health,
            stable_post_menu_health=stable_post_health,
            menu_issued=result["menu_issued"],
            post_menu_anr=result["post_menu_anr"],
            unresolved_anr=result["post_menu_unknown_anr"],
        )

    result.update(classification)
    result["observer_available"] = result.get("observer_available_at_decision", observer.available)
    result["logcat_filtered_truncated"] = observer.truncated
    result["experiment_interval"] = {
        "pre_menu_observation_seconds": 15,
        "post_menu_observation_seconds": 15 if result["menu_attempted"] else 0,
        "pre_menu_snapshot_count": len([s for s in result["snapshots"] if s["label"].startswith("pre-")]),
        "post_menu_snapshot_count": len([s for s in result["snapshots"] if s["label"].startswith("post-")]),
    }
    write_result(results, result)
    mark("experiment_finished", timeline_path, {"classification": result["classification"]})
    print(json.dumps({key: result[key] for key in ("classification", "reason", "config_equivalence")}, indent=2))
    return 0 if result["classification"] in {"PRE_MENU_ANR", "MENU_TRIGGERED_ANR", "NO_ANR"} else 1


if __name__ == "__main__":
    sys.exit(main())
