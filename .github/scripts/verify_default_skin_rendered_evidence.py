#!/usr/bin/env python3
"""Fail-closed verification for opt-in Default Skin rendered instrumentation evidence."""

from __future__ import annotations

import argparse
import csv
import struct
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
REQUIRED_TEST_CLASS = "eu.kanade.presentation.tsuzuki.DefaultSkinRenderedValidationInstrumentedTest"
REQUIRED_TESTS = {
    "searchArtworkFixturesKeepTheForegroundInsideOneStableMeasuredFrame",
    "measuredLayerAssertionRejectsAnIntentionallyMisalignedFixture",
    "settingsRootRendersSixGroupedSurfacesWithCompactRowsAndSectionRhythm",
    "defaultSkinCanvasAndGroupSurfaceRenderSemanticLightAndDarkRoles",
    "appearanceUsesCompactAccessibleThemeSamplesAndKeepsThemeModeSeparate",
    "integrationRootIsNavigationOnlyWhileDetailOwnsTheEnablementSwitch",
    "canonicalDetailsHeroSharesMeasuredArtworkFadeBoundsAndFlowsLongTitleBeforeFollowingContent",
}
REQUIRED_PNGS = {
    *(f"search-{name}.png" for name in ("two-thirds", "wide", "tall", "intrinsic-whitespace", "failed", "missing")),
    *(f"details-{name}.png" for name in ("short-dark", "long-light", "very-long-title", "missing-artwork", "failed-artwork")),
    "settings-light.png",
    "settings-dark.png",
    "appearance-light.png",
    "appearance-dark.png",
    *(f"appearance-mode-{mode}.png" for mode in ("system", "light", "dark")),
    "integrations-root.png",
    "integrations-mal-configured.png",
    "integrations-detail.png",
    "palette-light.png",
    "palette-dark.png",
}
REQUIRED_CSVS = {
    "search-measurements.csv",
    "details-measurements.csv",
    "settings-measurements.csv",
    "appearance-measurements.csv",
    "integrations-measurements.csv",
    "palette-measurements.csv",
}


class EvidenceError(RuntimeError):
    pass


def verify(junit_dir: Path, render_dir: Path) -> dict[str, int]:
    junit_files = sorted(junit_dir.glob("*.xml"))
    if not junit_files:
        raise EvidenceError("No JUnit XML was produced; instrumentation did not demonstrate execution")

    test_names: set[str] = set()
    test_classes: set[str] = set()
    test_count = failures = errors = skipped = 0
    for path in junit_files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise EvidenceError(f"Malformed JUnit XML: {path.name}: {exc}") from exc
        for case in root.iter("testcase"):
            test_count += 1
            test_names.add(case.attrib.get("name", ""))
            test_classes.add(case.attrib.get("classname", ""))
            failures += len(case.findall("failure"))
            errors += len(case.findall("error"))
            skipped += len(case.findall("skipped"))

    missing_tests = sorted(REQUIRED_TESTS - test_names)
    if missing_tests:
        raise EvidenceError(f"Required instrumented tests were not reported: {missing_tests}")
    if test_classes != {REQUIRED_TEST_CLASS}:
        raise EvidenceError(f"Unexpected instrumented test classes: {sorted(test_classes)}")
    if failures or errors or skipped:
        raise EvidenceError(f"JUnit was not green: tests={test_count}, failures={failures}, errors={errors}, skipped={skipped}")

    invalid_pngs: list[str] = []
    for name in sorted(REQUIRED_PNGS):
        path = render_dir / name
        if not path.is_file() or not _valid_png(path):
            invalid_pngs.append(name)
    if invalid_pngs:
        raise EvidenceError(f"Missing, empty, or malformed PNG render evidence: {invalid_pngs}")

    missing_csvs = sorted(name for name in REQUIRED_CSVS if not _has_measured_rows(render_dir / name))
    if missing_csvs:
        raise EvidenceError(f"Missing measured layout evidence: {missing_csvs}")

    return {
        "junit_files": len(junit_files),
        "tests": test_count,
        "pngs": len(REQUIRED_PNGS),
        "measurements": len(REQUIRED_CSVS),
    }


def _valid_png(path: Path) -> bool:
    try:
        data = path.read_bytes()
        if len(data) <= len(PNG_SIGNATURE) or data[:8] != PNG_SIGNATURE:
            return False
        position = len(PNG_SIGNATURE)
        saw_header = saw_data = saw_end = False
        width = height = 0
        compressed = bytearray()
        while position < len(data):
            if position + 12 > len(data):
                return False
            length = struct.unpack_from(">I", data, position)[0]
            chunk_type = data[position + 4 : position + 8]
            chunk_start = position + 8
            chunk_end = chunk_start + length
            crc_end = chunk_end + 4
            if crc_end > len(data):
                return False
            chunk_data = data[chunk_start:chunk_end]
            expected_crc = struct.unpack_from(">I", data, chunk_end)[0]
            if zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF != expected_crc:
                return False
            if not saw_header:
                if chunk_type != b"IHDR" or length != 13:
                    return False
                width, height = struct.unpack_from(">II", chunk_data)
                if width <= 0 or height <= 0:
                    return False
                saw_header = True
            elif chunk_type == b"IHDR":
                return False
            if chunk_type == b"IDAT":
                compressed.extend(chunk_data)
                saw_data = True
            if chunk_type == b"IEND":
                if length != 0 or crc_end != len(data):
                    return False
                saw_end = True
                break
            position = crc_end
        if not (saw_header and saw_data and saw_end):
            return False
        # Decompression distinguishes structurally valid chunks from a fake PNG header.
        zlib.decompress(compressed)
        return True
    except (OSError, ValueError, zlib.error, struct.error):
        return False


def _has_measured_rows(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size == 0:
        return False
    try:
        with path.open(newline="", encoding="utf-8") as stream:
            rows = list(csv.reader(stream))
    except (OSError, UnicodeError, csv.Error):
        return False
    return len(rows) >= 2 and bool(rows[0]) and all(any(cell.strip() for cell in row) for row in rows[1:])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--junit-dir", type=Path, required=True)
    parser.add_argument("--render-dir", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.junit_dir, args.render_dir)
    except EvidenceError as exc:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(f"RESULT=FAIL\nREASON={exc}\n", encoding="utf-8")
        print(f"::error::{exc}")
        return 1
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        "RESULT=PASS\n" + "".join(f"{key.upper()}={value}\n" for key, value in result.items()),
        encoding="utf-8",
    )
    print("Default Skin rendered evidence:", result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
