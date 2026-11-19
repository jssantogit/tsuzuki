#!/usr/bin/env python3
"""Sequential, opt-in MangaAll signed-code8 Android live batch.

Only the operator's titles and closed result codes may leave this harness.
Provider lookups, work IDs, chapter IDs, URLs, raw instrumentation, screenshots
and HTTP traces remain transient and are NEVER emitted in GitHub Actions logs.
"""
from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / ".github/mangaall-live-batch.json"
RESULT = ROOT / ".github/results/mangaall-live-pages-android/summary.json"
PACKAGE = "app.tsuzuki.dev"
RUNNER = "app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner"
DISCOVERY_TEST = (
    "eu.kanade.tachiyomi.provider.reading."
    "ScriptProviderReadingGatewayIsolationTest#"
    "mangaAllSignedCode8LiveBatchDiscoverChapterOne"
)
ID_PATTERN = re.compile(r"(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}")
CHAPTER_PATTERN = re.compile(r"[A-Za-z0-9._:-]{1,160}")
LANGUAGE_PATTERN = re.compile(r"[a-z]{2}(?:-[a-z]{2})?")
SELECTED = re.compile(
    r"MANGAALL_BATCH_DISCOVERY\|outcome=SELECTED"
    r"\|workId=((?:mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128})"
    r"\|chapterId=([A-Za-z0-9._:-]{1,160})"
    r"\|language=([a-z]{2}(?:-[a-z]{2})?)"
)
DISCOVERY_REASONS = (
    "LOOKUP_UNAVAILABLE", "AMBIGUOUS_SOURCE_MATCH",
    "LOOKUP_CURSOR_LIMIT", "NO_EXACT_SOURCE_MATCH",
    "INVALID_SOURCE_IDENTITY", "CHAPTERS_UNAVAILABLE",
    "AMBIGUOUS_CHAPTER_ONE", "CHAPTER_CURSOR_LIMIT",
    "CHAPTER_ONE_NOT_ATTESTED", "UPSTREAM_TIMEOUT",
)
DISCOVERY_INCONCLUSIVE = re.compile(
    r"MANGAALL_BATCH_DISCOVERY\|outcome=INCONCLUSIVE\|reason=("
    + "|".join(DISCOVERY_REASONS) + r")"
)
GATE_REASONS = (
    "CHAPTER_ATTESTATION_UNAVAILABLE",
    "CHAPTER_NOT_ATTESTED_IN_FIRST_PAGE",
    "CHAPTER_IDENTITY_NOT_COMPATIBLE_WITH_TEST_FIXTURE",
    "CHAPTER_MAPPING_UNRESOLVED", "CDN_OR_READER_UNAVAILABLE",
    "SCREEN_PIXELS_NOT_ATTESTED", "PROVIDER_PAGES_UNAVAILABLE",
    "NO_HTTP_PAGES", "CDN_FETCH_UNAVAILABLE",
    "INVALID_IMAGE_DIMENSIONS", "IMAGE_DECODE_FAILED",
)
GATE_INCONCLUSIVE = re.compile(
    r"MANGAALL_LIVE_PAGE_GATE\|outcome=INCONCLUSIVE\|reason=("
    + "|".join(GATE_REASONS) + r")"
)
GATE_PASS = re.compile(
    r"MANGAALL_LIVE_PAGE_GATE\|outcome=PASS"
    r"\|provenance=DIRECT_LIVE_ANDROID"
    r"\|pagesDecoded=([12])"
    r"\|readerActivityPixels=(NOT_TESTED|REAL_CDN_VISIBLE)"
)


@dataclass(frozen=True)
class Plan:
    titles: tuple[str, ...]
    source: str
    language: str
    mode: str


def _titles_from_text(raw: str) -> list[str]:
    raw = raw.strip()
    if raw.startswith("["):
        values = json.loads(raw)
        if not isinstance(values, list):
            raise ValueError("TITLES_NOT_ARRAY")
        return values
    if "\n" in raw:
        return raw.splitlines()
    # Single-line "Attack on Titan, Boku no Hero, Dragon Ball" is supported;
    # use JSON arrays for actual manga titles containing commas.
    return raw.split(",")


def _validated_title(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("INVALID_TITLE")
    normalized = unicodedata.normalize("NFC", value.strip())
    if not 2 <= len(normalized) <= 120:
        raise ValueError("INVALID_TITLE")
    if any(
        unicodedata.category(ch).startswith("C") or ch in "|<>"
        for ch in normalized
    ):
        raise ValueError("INVALID_TITLE")
    return normalized


def parse_plan(data: object) -> Plan:
    if not isinstance(data, dict):
        raise ValueError("INVALID_PLAN")
    if set(data) != {"titles", "source", "language", "mode"}:
        raise ValueError("UNEXPECTED_PLAN_FIELDS")
    raw_titles = data["titles"]
    if isinstance(raw_titles, str):
        raw_titles = _titles_from_text(raw_titles)
    if not isinstance(raw_titles, list) or not 1 <= len(raw_titles) <= 30:
        raise ValueError("INVALID_TITLE_COUNT")
    titles = tuple(_validated_title(title) for title in raw_titles)
    names = [unicodedata.normalize("NFKD", t).casefold() for t in titles]
    if len(set(names)) != len(names):
        raise ValueError("DUPLICATE_TITLE")
    source, language, mode = (data[k] for k in ("source", "language", "mode"))
    if source not in ("mangadex", "mangadot", "mangafire"):
        raise ValueError("INVALID_SOURCE")
    if not isinstance(language, str) or not LANGUAGE_PATTERN.fullmatch(language):
        raise ValueError("INVALID_LANGUAGE")
    if source == "mangafire" and language != "en":
        raise ValueError("UNSUPPORTED_FACET")
    if mode not in ("reader", "transport"):
        raise ValueError("INVALID_MODE")
    return Plan(titles, source, language, mode)


def read_plan(environ: dict[str, str], manifest: Path = MANIFEST) -> Plan:
    event = environ.get("GITHUB_EVENT_NAME", "workflow_dispatch")
    if event == "push":
        if not environ.get("GITHUB_REF", "").startswith("refs/heads/mangaall-live/"):
            raise ValueError("UNAUTHORIZED_PUSH_BRANCH")
        return parse_plan(json.loads(manifest.read_text(encoding="utf-8")))
    if event != "workflow_dispatch":
        raise ValueError("EVENT_NOT_ALLOWED")
    return parse_plan({
        "titles": environ.get("MANGAALL_BATCH_TITLES", ""),
        "source": environ.get("MANGAALL_BATCH_SOURCE", "mangafire"),
        "language": environ.get("MANGAALL_BATCH_LANGUAGE", "en"),
        "mode": environ.get("MANGAALL_BATCH_MODE", "reader"),
    })


def _exec(argv: list[str], timeout: int, env: dict | None = None):
    # Capture raw output ONLY in process memory, never print it.
    return subprocess.run(
        argv, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", timeout=timeout,
        check=False,
    )


def _instrumentation_good(stdout: str, returncode: int) -> bool:
    return (
        returncode == 0
        and "INSTRUMENTATION_CODE: -1" in stdout
        and "OK (1 test)" in stdout
    )


def _parse_discovery(stdout: str, code: int, plan: Plan):
    if not _instrumentation_good(stdout, code):
        return "FAIL", "DISCOVERY_INSTRUMENTATION", None
    selected = set(SELECTED.findall(stdout))
    if selected:
        if len(selected) != 1:
            return "FAIL", "CONFLICTING_DISCOVERY_RECORDS", None
        work_id, chapter_id, language = next(iter(selected))
        if (
            not ID_PATTERN.fullmatch(work_id)
            or not work_id.startswith(plan.source + ":")
            or not CHAPTER_PATTERN.fullmatch(chapter_id)
            or language != plan.language
        ):
            return "FAIL", "INVALID_DISCOVERY_HANDOFF", None
        return "SELECTED", "EXACT_SOURCE_CHAPTER_ONE", (work_id, chapter_id)
    inconclusive = set(DISCOVERY_INCONCLUSIVE.findall(stdout))
    if len(inconclusive) == 1:
        return "INCONCLUSIVE", next(iter(inconclusive)), None
    return "FAIL", "MISSING_DISCOVERY_VERDICT", None


def _parse_gate(stdout: str, code: int, plan: Plan):
    if code not in (0, 2):
        return "FAIL", "GATE_INSTRUMENTATION"
    passed = set(GATE_PASS.findall(stdout))
    if passed and code == 0:
        if len(passed) != 1:
            return "FAIL", "CONFLICTING_GATE_RECORDS"
        _, pixels = next(iter(passed))
        if pixels == ("REAL_CDN_VISIBLE" if plan.mode == "reader" else "NOT_TESTED"):
            return "PASS", "READER_CDN_PIXELS" if plan.mode == "reader" else "HOST_CDN_DECODE"
        return "FAIL", "WRONG_GATE_MODE"
    reasons = set(GATE_INCONCLUSIVE.findall(stdout))
    if len(reasons) == 1 and code == 2:
        return "INCONCLUSIVE", next(iter(reasons))
    return "FAIL", "MISSING_GATE_VERDICT"


def _apk_exact(pattern: str) -> Path:
    files = list(ROOT.glob(pattern))
    if len(files) != 1:
        raise ValueError("APK_NOT_UNIQUE")
    return files[0]


def _install_test_apks() -> None:
    # Debug build uses a dedicated disposable application ID. Never install
    # to a physical A54 or an authenticated user's production application.
    app_apks = list((ROOT / "app/build/outputs/apk/debug").rglob("*universal*.apk"))
    app_apks += list((ROOT / "app/build/outputs/apk/debug").rglob("app-debug.apk"))
    tests = list((ROOT / "app/build/outputs/apk/androidTest/debug").rglob("*.apk"))
    if len(app_apks) != 1 or len(tests) != 1:
        raise ValueError("APK_NOT_UNIQUE")
    emulator = _exec(["adb", "shell", "getprop", "ro.kernel.qemu"], 15)
    if emulator.returncode or emulator.stdout.strip() != "1":
        raise ValueError("NOT_DISPOSABLE_EMULATOR")
    for apk in (app_apks[0], tests[0]):
        if _exec(["adb", "install", "-r", str(apk)], 90).returncode:
            raise ValueError("APK_INSTALL_FAILED")


def _discover(title: str, plan: Plan):
    clean = _exec(["adb", "shell", "pm", "clear", PACKAGE], 20)
    if clean.returncode or "Success" not in clean.stdout:
        return "FAIL", "APP_RESET_FAILED", None
    cmd = [
        "adb", "shell", "am", "instrument", "-w", "-r",
        "-e", "class", DISCOVERY_TEST,
        "-e", "mangaAllLiveBatchOptIn", "true",
        "-e", "mangaAllBatchTitleBase64", base64.urlsafe_b64encode(title.encode("utf-8")).decode("ascii"),
        "-e", "mangaAllBatchSource", plan.source,
        "-e", "facetLanguage", plan.language,
        RUNNER,
    ]
    try:
        outcome = _exec(cmd, 250)
    except subprocess.TimeoutExpired:
        return "INCONCLUSIVE", "DISCOVERY_TIMEOUT", None
    except OSError:
        return "FAIL", "DISCOVERY_INSTRUMENTATION", None
    return _parse_discovery(outcome.stdout, outcome.returncode, plan)


def _gate(work_id: str, chapter_id: str, plan: Plan):
    env = os.environ.copy()
    env.update({
        "MANGAALL_WORK_ID": work_id,
        "MANGAALL_CHAPTER_ID": chapter_id,
        "MANGAALL_LANGUAGE": plan.language,
        "MANGAALL_GATE_MODE": plan.mode,
    })
    try:
        result = _exec(
            ["bash", ".github/scripts/run_mangaall_live_pages_android.sh"],
            300, env,
        )
    except subprocess.TimeoutExpired:
        return "INCONCLUSIVE", "GATE_TIMEOUT"
    except OSError:
        return "FAIL", "GATE_INSTRUMENTATION"
    return _parse_gate(result.stdout, result.returncode, plan)


def execute(plan: Plan) -> int:
    RESULT.parent.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        _install_test_apks()
        for index, title in enumerate(plan.titles, 1):
            print(f"MANGAALL_BATCH|case={index}/{len(plan.titles)}|stage=START")
            status, reason, handoff = _discover(title, plan)
            if handoff is not None:
                status, reason = _gate(*handoff, plan)
            results.append({
                "index": index, "title": title, "outcome": status, "reason": reason,
            })
            print(f"MANGAALL_BATCH|case={index}/{len(plan.titles)}|outcome={status}|reason={reason}")
    finally:
        # Even failed batches cannot leave source IDs, cookies, raw logs or
        # test app data on the runner/emulator.
        for args in (
            ["adb", "shell", "am", "force-stop", PACKAGE],
            ["adb", "shell", "pm", "clear", PACKAGE],
        ):
            try:
                _exec(args, 15)
            except (OSError, subprocess.TimeoutExpired):
                pass
        raw = RESULT.parent / "instrumentation-private.txt"
        raw.unlink(missing_ok=True)
        RESULT.write_text(json.dumps({
            "schemaVersion": 1, "mode": plan.mode,
            "source": plan.source, "language": plan.language,
            "total": len(plan.titles), "completed": len(results),
            "passed": sum(x["outcome"] == "PASS" for x in results),
            "inconclusive": sum(x["outcome"] == "INCONCLUSIVE" for x in results),
            "failed": sum(x["outcome"] == "FAIL" for x in results),
            "cases": results,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "MANGAALL_BATCH|summary=COMPLETE|total=%d|passed=%d|inconclusive=%d|failed=%d"
        % (
            len(plan.titles), sum(x["outcome"] == "PASS" for x in results),
            sum(x["outcome"] == "INCONCLUSIVE" for x in results),
            sum(x["outcome"] == "FAIL" for x in results),
        )
    )
    # Explicitly fail-closed for unresolved or partial batches.
    return 0 if len(results) == len(plan.titles) and all(
        x["outcome"] == "PASS" for x in results
    ) else 2


def main() -> int:
    try:
        plan = read_plan(os.environ)
        if "--check-plan" in sys.argv:
            print("MANGAALL_BATCH|plan=VALID|cases=%d|mode=%s" % (len(plan.titles), plan.mode))
            return 0
        return execute(plan)
    except (ValueError, json.JSONDecodeError) as error:
        # Only locally defined closed validation codes are printed.
        reason = str(error)
        permitted = {
            "TITLES_NOT_ARRAY", "INVALID_TITLE", "INVALID_PLAN",
            "UNEXPECTED_PLAN_FIELDS", "INVALID_TITLE_COUNT", "DUPLICATE_TITLE",
            "INVALID_SOURCE", "INVALID_LANGUAGE", "UNSUPPORTED_FACET",
            "INVALID_MODE", "UNAUTHORIZED_PUSH_BRANCH", "EVENT_NOT_ALLOWED",
            "APK_NOT_UNIQUE", "NOT_DISPOSABLE_EMULATOR", "APK_INSTALL_FAILED",
        }
        print("MANGAALL_BATCH|outcome=FAIL|reason=" + (
            reason if reason in permitted else "INVALID_INPUT"
        ), file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
