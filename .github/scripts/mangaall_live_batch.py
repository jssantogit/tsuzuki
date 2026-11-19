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
SOURCES = ("mangadex", "mangadot", "mangafire")
DISCOVERY_TEST = (
    "eu.kanade.tachiyomi.provider.reading."
    "ScriptProviderReadingGatewayIsolationTest#"
    "mangaAllSignedCode8LiveBatchAuditAllSources"
)
ID_PATTERN = re.compile(r"(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}")
CHAPTER_PATTERN = re.compile(r"[A-Za-z0-9._:-]{1,160}")
LANGUAGE_PATTERN = re.compile(r"[a-z]{2}(?:-[a-z]{2})?")
SOURCE_LINE = re.compile(
    r"(?m)^INSTRUMENTATION_STATUS: stream=(MANGAALL_BATCH_SOURCE\|[^\r\n]+)$"
)
AUDIT_LINE = re.compile(
    r"(?m)^INSTRUMENTATION_STATUS: stream=(MANGAALL_BATCH_AUDIT\|[^\r\n]+)$"
)
SOURCE_READY = re.compile(
    r"MANGAALL_BATCH_SOURCE\|source=(mangadex|mangadot|mangafire)"
    r"\|outcome=READY\|candidates=1"
    r"\|workId=((?:mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128})"
    r"\|chapterId=([A-Za-z0-9._:-]{1,160})\|language=en"
)
SOURCE_OTHER = re.compile(
    r"MANGAALL_BATCH_SOURCE\|source=(mangadex|mangadot|mangafire)"
    r"\|outcome=(MISSING|AMBIGUOUS|CHAPTERS_UNAVAILABLE|CHAPTER_ONE_NOT_ATTESTED|"
    r"CHAPTER_CURSOR_LIMIT|AMBIGUOUS_CHAPTER_ONE|INVALID_SOURCE_IDENTITY)"
    r"\|candidates=([0-9]{1,5})"
)
AUDIT_COMPLETE = re.compile(
    r"MANGAALL_BATCH_AUDIT\|outcome=COMPLETE\|sources=3"
    r"\|titleParity=(CONSISTENT|VARIANCE|UNVERIFIED)"
    r"\|authorParity=(CONSISTENT|VARIANCE|UNVERIFIED)"
)
AUDIT_INCONCLUSIVE = re.compile(
    r"MANGAALL_BATCH_AUDIT\|outcome=INCONCLUSIVE\|reason="
    r"(LOOKUP_UNAVAILABLE|LOOKUP_CURSOR_LIMIT|INVALID_SOURCE_IDENTITY|"
    r"SOURCE_LANGUAGE_CONFLICT|SOURCE_IDENTITY_CONFLICT|UPSTREAM_TIMEOUT)"
)
HOST_FAILURE_CODES = frozenset((
    "UNAVAILABLE", "HOST_ERROR", "PACKAGE_INVALID", "MALFORMED_REQUEST",
    "SOURCE_TOO_LARGE", "SOURCE_READ_ERROR", "INVOCATION_CONFLICT",
    "PERMISSION_DENIED", "HOST_API_UNSUPPORTED", "SCRIPT_ERROR",
    "TIMEOUT", "RUNTIME_DIED", "NETWORK_POLICY",
    "CHAPTER_IDENTITY_UNVERIFIED", "NETWORK_ERROR", "BROWSER_ERROR",
    "RESOURCE_LIMIT", "MALFORMED_RESULT", "AUTH_REQUIRED",
    "ACQUISITION_FAILED",
))
AUDIT_LOOKUP_FAILURE = re.compile(
    r"MANGAALL_BATCH_AUDIT\|outcome=INCONCLUSIVE\|reason=LOOKUP_UNAVAILABLE"
    r"\|code=([A-Z_]{3,40})"
    r"\|sourceFailure=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|UNCLASSIFIED_ERROR)"
    r"\|httpStatus=(NONE|[1-5][0-9]{2})"
    r"\|retryable=(true|false)"
)
GATE_REASONS = (
    "CHAPTER_ATTESTATION_UNAVAILABLE",
    "CHAPTER_NOT_ATTESTED_IN_FIRST_PAGE",
    "CHAPTER_NOT_ATTESTED_IN_BOUND", "CHAPTER_CURSOR_LIMIT",
    "CHAPTER_AMBIGUOUS",
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
    if set(data) != {"titles", "language", "mode"}:
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
    language, mode = data["language"], data["mode"]
    # All three published code8 sub-sources announce English. Portuguese is a
    # different, two-source audit, never mislabeled "tri-source complete".
    if language != "en":
        raise ValueError("INVALID_TRI_SOURCE_FACET")
    if mode not in ("reader", "transport"):
        raise ValueError("INVALID_MODE")
    return Plan(titles, language, mode)


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
    """Require a COMPLETE three-source audit, never a single-source handoff."""
    if not _instrumentation_good(stdout, code):
        return "FAIL", "DISCOVERY_INSTRUMENTATION", [], {}
    audits = AUDIT_LINE.findall(stdout)
    if len(audits) != 1:
        return "FAIL", "MISSING_OR_CONFLICTING_AUDIT", [], {}
    detailed = AUDIT_LOOKUP_FAILURE.fullmatch(audits[0])
    if detailed:
        code, source_kind, status, retryable = detailed.groups()
        if (
            code not in HOST_FAILURE_CODES
            or (source_kind == "NONE" and status != "NONE")
            or (source_kind != "NONE" and code != "SOURCE_READ_ERROR")
        ):
            return "FAIL", "INVALID_LOOKUP_ERROR", [], {}
        return "INCONCLUSIVE", "LOOKUP_UNAVAILABLE", [], {
            "lookupError": {
                "code": code,
                "sourceFailure": source_kind,
                "httpStatus": None if status == "NONE" else int(status),
                "retryable": retryable == "true",
            },
        }
    if AUDIT_INCONCLUSIVE.fullmatch(audits[0]):
        reason = AUDIT_INCONCLUSIVE.fullmatch(audits[0]).group(1)
        return "INCONCLUSIVE", reason, [], {}
    summary = AUDIT_COMPLETE.fullmatch(audits[0])
    if summary is None:
        return "FAIL", "INVALID_AUDIT_CONTRACT", [], {}

    lines = SOURCE_LINE.findall(stdout)
    if len(lines) != len(SOURCES):
        return "FAIL", "MISSING_SOURCE_VERDICTS", [], {}
    records = []
    for source_expected, line in zip(SOURCES, lines):
        ready = SOURCE_READY.fullmatch(line)
        other = SOURCE_OTHER.fullmatch(line)
        if ready:
            source, work_id, chapter_id = ready.groups()
            if (
                source != source_expected
                or not ID_PATTERN.fullmatch(work_id)
                or not work_id.startswith(source + ":")
                or not CHAPTER_PATTERN.fullmatch(chapter_id)
                or not chapter_id.startswith(source + ":")
            ):
                return "FAIL", "INVALID_SOURCE_HANDOFF", [], {}
            records.append({
                "source": source, "discovery": "READY", "candidates": 1,
                "handoff": (work_id, chapter_id),
            })
        elif other:
            source, outcome, count_text = other.groups()
            count = int(count_text)
            if (
                source != source_expected
                or (outcome == "MISSING" and count != 0)
                or (outcome == "AMBIGUOUS" and count <= 1)
                or (outcome not in {"MISSING", "AMBIGUOUS"} and count != 1)
            ):
                return "FAIL", "INVALID_SOURCE_VERDICT", [], {}
            records.append({
                "source": source, "discovery": outcome, "candidates": count,
                "handoff": None,
            })
        else:
            return "FAIL", "INVALID_SOURCE_VERDICT", [], {}

    return "COMPLETE", "TRI_SOURCE_LOOKUP", records, {
        "titleParity": summary.group(1), "authorParity": summary.group(2),
    }


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
        return "FAIL", "APP_RESET_FAILED", [], {}
    cmd = [
        "adb", "shell", "am", "instrument", "-w", "-r",
        "-e", "class", DISCOVERY_TEST,
        "-e", "mangaAllLiveBatchOptIn", "true",
        "-e", "mangaAllBatchTitleBase64", base64.urlsafe_b64encode(title.encode("utf-8")).decode("ascii"),
        "-e", "facetLanguage", plan.language,
        RUNNER,
    ]
    try:
        outcome = _exec(cmd, 520)
    except subprocess.TimeoutExpired:
        return "INCONCLUSIVE", "DISCOVERY_TIMEOUT", [], {}
    except OSError:
        return "FAIL", "DISCOVERY_INSTRUMENTATION", [], {}
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
            380, env,
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
            print(f"MANGAALL_BATCH|case={index}/{len(plan.titles)}|stage=UNIFIED_LOOKUP")
            discovery, reason, records, parity = _discover(title, plan)
            source_results = []
            if discovery == "COMPLETE":
                for record in records:
                    source = record["source"]
                    status, source_reason = "INCONCLUSIVE", record["discovery"]
                    if record["handoff"] is not None:
                        status, source_reason = _gate(*record["handoff"], plan)
                    elif record["discovery"] == "INVALID_SOURCE_IDENTITY":
                        status, source_reason = "FAIL", "INVALID_SOURCE_IDENTITY"
                    source_results.append({
                        "source": source, "candidates": record["candidates"],
                        "discovery": record["discovery"],
                        "outcome": status, "reason": source_reason,
                    })
                    print(
                        f"MANGAALL_BATCH|case={index}/{len(plan.titles)}|source={source}"
                        f"|candidates={record['candidates']}|outcome={status}|reason={source_reason}"
                    )
                if any(x["outcome"] == "FAIL" for x in source_results):
                    outcome, reason = "FAIL", "SOURCE_PIPELINE_FAILURE"
                elif "VARIANCE" in parity.values():
                    outcome, reason = "INCONCLUSIVE", "CROSS_SOURCE_METADATA_VARIANCE"
                elif all(x["outcome"] == "PASS" for x in source_results):
                    # Transport/pixel acceptance, NOT editorial work/edition proof.
                    outcome, reason = "PASS", "TRI_SOURCE_READER_OR_TRANSPORT"
                else:
                    outcome, reason = "INCONCLUSIVE", "SOURCE_COVERAGE_OR_EDITION_AMBIGUITY"
            else:
                outcome = discovery
            results.append({
                "index": index, "title": title, "outcome": outcome,
                "reason": reason, "lookupComplete": discovery == "COMPLETE",
                "parity": {
                    key: parity[key] for key in ("titleParity", "authorParity")
                    if key in parity
                },
                "lookupError": parity.get("lookupError"),
                "sources": source_results,
            })
            print(
                f"MANGAALL_BATCH|case={index}/{len(plan.titles)}"
                f"|outcome={outcome}|reason={reason}"
                f"|titleParity={parity.get('titleParity', 'UNVERIFIED')}"
                f"|authorParity={parity.get('authorParity', 'UNVERIFIED')}"
            )
    finally:
        for args in (
            ["adb", "shell", "am", "force-stop", PACKAGE],
            ["adb", "shell", "pm", "clear", PACKAGE],
        ):
            try:
                _exec(args, 15)
            except (OSError, subprocess.TimeoutExpired):
                pass
        (RESULT.parent / "instrumentation-private.txt").unlink(missing_ok=True)
        RESULT.write_text(json.dumps({
            "schemaVersion": 2, "provider": "app.tsuzuki.mangaall",
            "sources": list(SOURCES), "mode": plan.mode, "language": plan.language,
            "editorialIdentityVerified": False,
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
            "INVALID_TRI_SOURCE_FACET", "INVALID_MODE", "UNAUTHORIZED_PUSH_BRANCH", "EVENT_NOT_ALLOWED",
            "APK_NOT_UNIQUE", "NOT_DISPOSABLE_EMULATOR", "APK_INSTALL_FAILED",
        }
        print("MANGAALL_BATCH|outcome=FAIL|reason=" + (
            reason if reason in permitted else "INVALID_INPUT"
        ), file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
