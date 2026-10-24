#!/usr/bin/env python3
"""Fail-closed verification for opt-in Default Skin rendered instrumentation evidence."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
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
    "canonicalDetailsHeroUsesSharedBoundsAndFlowsLongTitle",
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
REQUIRED_CAPTURE_STATES = {
    **{
        f"search-{name}.png": (name, "light", "search_compact_card", f"search-{name}")
        for name in ("two-thirds", "wide", "tall", "intrinsic-whitespace", "failed", "missing")
    },
    **{
        f"details-{name}.png": (
            name,
            "dark" if name == "short-dark" else "light",
            "canonical_details_hero_content",
            f"details-{name}",
        )
        for name in ("short-dark", "long-light", "very-long-title", "missing-artwork", "failed-artwork")
    },
    "settings-light.png": ("settings-root", "light", "settings_surface_account", "settings-root-light"),
    "settings-dark.png": ("settings-root", "dark", "settings_surface_account", "settings-root-dark"),
    "appearance-light.png": (
        "appearance-theme-previews",
        "light",
        "appearance_theme_preview_*",
        "appearance-theme-previews-light",
    ),
    "appearance-dark.png": (
        "appearance-theme-previews",
        "dark",
        "appearance_theme_preview_*",
        "appearance-theme-previews-dark",
    ),
    **{
        f"appearance-mode-{mode}.png": (
            f"theme-mode-{mode}",
            "light",
            "appearance_theme_mode",
            f"appearance-mode-{mode}",
        )
        for mode in ("system", "light", "dark")
    },
    "integrations-root.png": (
        "integrations-runtime-statuses",
        "light",
        "integration_root_row_kitsu",
        "integrations-runtime-statuses",
    ),
    "integrations-mal-configured.png": (
        "integrations-mal-configured",
        "light",
        "integration_root_row_mal",
        "integrations-mal-configured",
    ),
    "integrations-detail.png": (
        "integration-detail-disabled-switch",
        "light",
        "integration_detail_enablement",
        "integration-detail-disabled-switch",
    ),
    "palette-light.png": ("semantic-palette-roles", "light", "palette_canvas", "palette-light"),
    "palette-dark.png": ("semantic-palette-roles", "dark", "palette_canvas", "palette-dark"),
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
    def __init__(self, message: str, result: str = "VALIDATION_FAILURE") -> None:
        super().__init__(message)
        self.result = result


def verify(junit_dir: Path, render_dir: Path) -> dict[str, int]:
    junit_files = sorted(junit_dir.glob("*.xml"))
    if not junit_files:
        raise EvidenceError(
            "No JUnit XML was produced; instrumentation did not demonstrate execution",
            "INFRASTRUCTURE_FAILURE",
        )

    test_names: set[str] = set()
    test_classes: set[str] = set()
    test_count = failures = errors = skipped = 0
    for path in junit_files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise EvidenceError(
                f"Malformed JUnit XML: {path.name}: {exc}",
                "INFRASTRUCTURE_FAILURE",
            ) from exc
        for case in root.iter("testcase"):
            test_count += 1
            test_names.add(case.attrib.get("name", ""))
            test_classes.add(case.attrib.get("classname", ""))
            failures += len(case.findall("failure"))
            errors += len(case.findall("error"))
            skipped += len(case.findall("skipped"))

    missing_tests = sorted(REQUIRED_TESTS - test_names)
    if missing_tests:
        raise EvidenceError(
            f"Required instrumented tests were not reported: {missing_tests}",
            "INFRASTRUCTURE_FAILURE",
        )
    if test_classes != {REQUIRED_TEST_CLASS}:
        raise EvidenceError(
            f"Unexpected instrumented test classes: {sorted(test_classes)}",
            "INFRASTRUCTURE_FAILURE",
        )
    if failures or errors:
        raise EvidenceError(
            f"Instrumented assertions failed: tests={test_count}, failures={failures}, errors={errors}",
            "VALIDATION_FAILURE",
        )
    if skipped:
        raise EvidenceError(
            f"Instrumented cases were skipped: tests={test_count}, skipped={skipped}",
            "INFRASTRUCTURE_FAILURE",
        )

    invalid_pngs: list[str] = []
    for name in sorted(REQUIRED_PNGS):
        path = render_dir / name
        if not path.is_file() or _png_info(path) is None:
            invalid_pngs.append(name)
    if invalid_pngs:
        raise EvidenceError(f"Missing, empty, or malformed PNG render evidence: {invalid_pngs}")

    run_ids: set[str] = set()
    png_hashes: dict[str, str] = {}
    for name in sorted(REQUIRED_PNGS):
        png_path = render_dir / name
        sidecar_path = render_dir / f"{Path(name).stem}.json"
        if not sidecar_path.is_file():
            raise EvidenceError(f"Missing state-bound capture metadata: {sidecar_path.name}")
        try:
            capture = json.loads(sidecar_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise EvidenceError(f"Malformed capture metadata: {sidecar_path.name}: {exc}") from exc
        fixture_id, expected_theme, expected_target, expected_scene = REQUIRED_CAPTURE_STATES[name]
        if capture.get("stateId") != Path(name).stem or capture.get("png") != name:
            raise EvidenceError(f"Capture state/filename mismatch in {sidecar_path.name}")
        if capture.get("fixtureId") != fixture_id or capture.get("expectedTheme") != expected_theme:
            raise EvidenceError(f"Fixture/theme mismatch for {name}: {capture.get('fixtureId')}/{capture.get('expectedTheme')}")
        if capture.get("sceneId") != expected_scene:
            raise EvidenceError(f"Rendered composition scene does not match expected state for {name}")
        try:
            scene_generation = int(capture["sceneGeneration"])
        except (KeyError, TypeError, ValueError) as exc:
            raise EvidenceError(f"Missing scene generation for {name}") from exc
        scene_tag = f"validation_scene_{scene_generation}_{expected_scene}"
        if scene_generation <= 0 or capture.get("sceneTag") != scene_tag:
            raise EvidenceError(f"Rendered scene marker does not match generation/state for {name}")
        target_tag = capture.get("targetTag", "")
        if expected_target.endswith("*"):
            if not target_tag.startswith(expected_target[:-1]):
                raise EvidenceError(f"Unexpected target tag for {name}: {target_tag}")
        elif target_tag != expected_target:
            raise EvidenceError(f"Unexpected target tag for {name}: {target_tag}")
        if capture.get("actualTheme") != expected_theme or capture.get("hostRequestedTheme") != expected_theme:
            raise EvidenceError(f"Rendered theme does not match fixture for {name}")
        if capture.get("capturePath") != "PixelCopy.Window" or capture.get("hardwareAccelerated") is not True:
            raise EvidenceError(f"Capture lacks committed accelerated-window provenance for {name}")
        run_id = capture.get("runId")
        if not isinstance(run_id, str) or not run_id:
            raise EvidenceError(f"Missing capture run identity for {name}")
        run_ids.add(run_id)
        before = capture.get("semanticIdentityBefore")
        after = capture.get("semanticIdentityAfter")
        if not isinstance(before, str) or not before or before != after:
            raise EvidenceError(f"Semantic fixture identity was stale or changed during capture for {name}")
        if f"scene={scene_tag}#{scene_generation}" not in before:
            raise EvidenceError(f"Semantic identity is not bound to the active composition for {name}")
        if not isinstance(capture.get("identityTags"), list) or not capture["identityTags"]:
            raise EvidenceError(f"Missing semantic identity tags for {name}")
        if not isinstance(capture.get("targetBoundsWindow"), dict):
            raise EvidenceError(f"Missing measured target bounds for {name}")
        bounds = capture["targetBoundsWindow"]
        width, height = _png_info(png_path) or (0, 0)
        png_width, png_height = width, height
        try:
            left, top, right, bottom = (float(bounds[key]) for key in ("left", "top", "right", "bottom"))
        except (KeyError, TypeError, ValueError) as exc:
            raise EvidenceError(f"Invalid target bounds for {name}") from exc
        if not all(math.isfinite(value) for value in (left, top, right, bottom)):
            raise EvidenceError(f"Non-finite target bounds for {name}")
        if not (0 <= left < right <= png_width and 0 <= top < bottom <= png_height):
            raise EvidenceError(f"Target bounds exceed captured window for {name}: {bounds} in {png_width}x{png_height}")
        if capture.get("bitmapWidth") != png_width or capture.get("bitmapHeight") != png_height:
            raise EvidenceError(f"PNG dimensions disagree with capture metadata for {name}")
        colors = capture.get("contentColors")
        sample_count = capture.get("contentColorSampleCount")
        if not isinstance(colors, list) or len(set(colors)) < 2 or sample_count != len(set(colors)):
            raise EvidenceError(f"Captured target lacks non-empty app-content pixels for {name}")
        raw_samples = capture.get("contentPixelSamples")
        if not isinstance(raw_samples, list) or len(raw_samples) < 2:
            raise EvidenceError(f"Missing sampled app-content pixels for {name}")
        pixel_probes = capture.get("pixelProbes", {})
        if not isinstance(pixel_probes, dict):
            raise EvidenceError(f"Malformed pixel probes for {name}")
        requested_points: set[tuple[int, int]] = set()
        for sample in raw_samples:
            if not isinstance(sample, dict):
                raise EvidenceError(f"Malformed content sample for {name}")
            try:
                point = int(sample["x"]), int(sample["y"])
            except (KeyError, TypeError, ValueError) as exc:
                raise EvidenceError(f"Content sample lacks coordinates for {name}") from exc
            if not (left <= point[0] < right and top <= point[1] < bottom):
                raise EvidenceError(f"Content sample is outside target bounds for {name}: {point}")
            requested_points.add(point)
        for probe in pixel_probes.values():
            if not isinstance(probe, dict):
                raise EvidenceError(f"Malformed named pixel probe for {name}")
            try:
                point = int(probe["x"]), int(probe["y"])
            except (KeyError, TypeError, ValueError) as exc:
                raise EvidenceError(f"Pixel probe lacks coordinates for {name}") from exc
            if not (left <= point[0] < right and top <= point[1] < bottom):
                raise EvidenceError(f"Pixel probe is outside target bounds for {name}: {point}")
            requested_points.add(point)
        try:
            decoded_width, decoded_height, decoded_pixels = _decode_png_samples(png_path, requested_points)
        except (OSError, ValueError, zlib.error, struct.error) as exc:
            raise EvidenceError(f"Could not inspect rendered pixels in {name}: {exc}") from exc
        if (decoded_width, decoded_height) != (png_width, png_height):
            raise EvidenceError(f"PNG dimensions changed while sampling {name}")
        actual_content_colors: set[str] = set()
        for sample in raw_samples:
            point = int(sample["x"]), int(sample["y"])
            actual_color = decoded_pixels[point]
            if sample.get("color") != actual_color:
                raise EvidenceError(f"Content sample color disagrees with PNG at {name} {point}")
            actual_content_colors.add(actual_color)
        if len(actual_content_colors) < 2 or set(colors) != actual_content_colors:
            raise EvidenceError(f"Captured app-content samples are blank or inconsistent with PNG for {name}")
        if sample_count != len(actual_content_colors):
            raise EvidenceError(f"Captured content color count is inconsistent with PNG for {name}")
        for probe_name, probe in pixel_probes.items():
            point = int(probe["x"]), int(probe["y"])
            if probe.get("color") != decoded_pixels[point]:
                raise EvidenceError(f"Pixel probe {probe_name} color disagrees with PNG at {name} {point}")
        digest = hashlib.sha256(png_path.read_bytes()).hexdigest()
        if capture.get("pngSha256") != digest:
            raise EvidenceError(f"PNG hash does not match its state metadata for {name}")
        png_hashes[name] = digest
        _verify_required_probe(name, capture.get("pixelProbes", {}))
        if name in {"appearance-light.png", "appearance-dark.png"}:
            if (
                capture.get("targetRole") != "RadioButton"
                or capture.get("targetSelected") is not True
                or capture.get("targetHasClickAction") is not True
                or not any(str(value).strip() for value in capture.get("targetContentDescription", []))
            ):
                raise EvidenceError(f"Selected Appearance preview lost its radio/select/name semantics in {name}")
    if len(run_ids) != 1:
        raise EvidenceError(f"Capture sidecars contain mixed or stale run identities: {sorted(run_ids)}")
    if len(set(png_hashes.values())) != len(png_hashes):
        raise EvidenceError("Two rendered states contain duplicate PNG content; stale/cross-state captures are not accepted")

    missing_csvs = sorted(name for name in REQUIRED_CSVS if not _has_measured_rows(render_dir / name))
    if missing_csvs:
        raise EvidenceError(f"Missing measured layout evidence: {missing_csvs}")
    _verify_tealturquoise_preview(render_dir / "appearance-measurements.csv")

    return {
        "junit_files": len(junit_files),
        "tests": test_count,
        "pngs": len(REQUIRED_PNGS),
        "measurements": len(REQUIRED_CSVS),
    }


def _png_info(path: Path) -> tuple[int, int] | None:
    try:
        width, height, _ = _decode_png_samples(path, set())
        return width, height
    except (OSError, ValueError, zlib.error, struct.error):
        return None


def _decode_png_samples(
    path: Path,
    points: set[tuple[int, int]],
) -> tuple[int, int, dict[tuple[int, int], str]]:
    try:
        data = path.read_bytes()
        if len(data) <= len(PNG_SIGNATURE) or data[:8] != PNG_SIGNATURE:
            raise ValueError("invalid PNG signature")
        position = len(PNG_SIGNATURE)
        saw_header = saw_data = saw_end = False
        width = height = 0
        bit_depth = color_type = interlace = 0
        palette = b""
        transparency = b""
        compressed = bytearray()
        while position < len(data):
            if position + 12 > len(data):
                raise ValueError("truncated PNG chunk header")
            length = struct.unpack_from(">I", data, position)[0]
            chunk_type = data[position + 4 : position + 8]
            chunk_start = position + 8
            chunk_end = chunk_start + length
            crc_end = chunk_end + 4
            if crc_end > len(data):
                raise ValueError("truncated PNG chunk")
            chunk_data = data[chunk_start:chunk_end]
            expected_crc = struct.unpack_from(">I", data, chunk_end)[0]
            if zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF != expected_crc:
                raise ValueError("PNG chunk CRC mismatch")
            if not saw_header:
                if chunk_type != b"IHDR" or length != 13:
                    raise ValueError("PNG does not begin with IHDR")
                width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunk_data)
                if width <= 0 or height <= 0:
                    raise ValueError("PNG has empty dimensions")
                if compression != 0 or filtering != 0:
                    raise ValueError("unsupported PNG compression/filter method")
                saw_header = True
            elif chunk_type == b"IHDR":
                raise ValueError("duplicate PNG header")
            if chunk_type == b"IDAT":
                compressed.extend(chunk_data)
                saw_data = True
            elif chunk_type == b"PLTE":
                palette = chunk_data
            elif chunk_type == b"tRNS":
                transparency = chunk_data
            if chunk_type == b"IEND":
                if length != 0 or crc_end != len(data):
                    raise ValueError("malformed PNG end chunk")
                saw_end = True
                break
            position = crc_end
        if not (saw_header and saw_data and saw_end):
            raise ValueError("PNG is missing a required chunk")
        if bit_depth != 8 or interlace != 0:
            raise ValueError(f"unsupported PNG format: bitDepth={bit_depth}, interlace={interlace}")
        channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
        if channels is None:
            raise ValueError(f"unsupported PNG color type: {color_type}")
        bytes_per_pixel = channels
        row_bytes = width * bytes_per_pixel
        raw = zlib.decompress(compressed)
        if len(raw) != height * (row_bytes + 1):
            raise ValueError("decompressed PNG byte count does not match dimensions")
        wanted_by_row: dict[int, list[int]] = {}
        for x, y in points:
            if x < 0 or x >= width or y < 0 or y >= height:
                raise ValueError(f"requested pixel is outside PNG: ({x},{y})")
            wanted_by_row.setdefault(y, []).append(x)
        pixels: dict[tuple[int, int], str] = {}
        previous = bytearray(row_bytes)
        offset = 0
        for y in range(height):
            filter_type = raw[offset]
            offset += 1
            scanline = bytearray(raw[offset : offset + row_bytes])
            offset += row_bytes
            if filter_type == 1:
                for index in range(row_bytes):
                    left = scanline[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                    scanline[index] = (scanline[index] + left) & 0xFF
            elif filter_type == 2:
                for index in range(row_bytes):
                    scanline[index] = (scanline[index] + previous[index]) & 0xFF
            elif filter_type == 3:
                for index in range(row_bytes):
                    left = scanline[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                    scanline[index] = (scanline[index] + ((left + previous[index]) // 2)) & 0xFF
            elif filter_type == 4:
                for index in range(row_bytes):
                    left = scanline[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                    above = previous[index]
                    upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                    scanline[index] = (scanline[index] + _paeth_predictor(left, above, upper_left)) & 0xFF
            elif filter_type != 0:
                raise ValueError(f"unsupported PNG row filter: {filter_type}")
            for x in wanted_by_row.get(y, []):
                start = x * bytes_per_pixel
                sample = scanline[start : start + bytes_per_pixel]
                if color_type == 6:
                    red, green, blue, alpha = sample
                elif color_type == 2:
                    red, green, blue = sample
                    alpha = 255
                elif color_type == 0:
                    red = green = blue = sample[0]
                    alpha = 255
                elif color_type == 4:
                    red = green = blue = sample[0]
                    alpha = sample[1]
                else:
                    palette_index = sample[0] * 3
                    if palette_index + 2 >= len(palette):
                        raise ValueError("palette index outside PNG palette")
                    red, green, blue = palette[palette_index : palette_index + 3]
                    alpha = transparency[sample[0]] if sample[0] < len(transparency) else 255
                pixels[(x, y)] = f"{alpha:02x}{red:02x}{green:02x}{blue:02x}"
            previous = scanline
        return width, height, pixels
    except OSError:
        raise


def _paeth_predictor(left: int, above: int, upper_left: int) -> int:
    estimate = left + above - upper_left
    left_distance = abs(estimate - left)
    above_distance = abs(estimate - above)
    upper_left_distance = abs(estimate - upper_left)
    if left_distance <= above_distance and left_distance <= upper_left_distance:
        return left
    if above_distance <= upper_left_distance:
        return above
    return upper_left


def _verify_required_probe(name: str, probes: object) -> None:
    expected: dict[str, str] = {}
    if name == "search-wide.png":
        expected = {"foreground-left-marker": "fff631a6", "foreground-right-marker": "ff19ddf2"}
    elif name == "palette-light.png":
        expected = {"canvas": "fff7f7f8", "surface": "ffffffff"}
    elif name == "palette-dark.png":
        expected = {"canvas": "ff0b0c0d", "surface": "ff1c1e1f"}
    if not expected:
        return
    if not isinstance(probes, dict):
        raise EvidenceError(f"Missing pixel probes for {name}")
    for probe_name, expected_color in expected.items():
        probe = probes.get(probe_name)
        if not isinstance(probe, dict) or probe.get("color") != expected_color:
            raise EvidenceError(f"Pixel probe {probe_name} does not match expected rendered color in {name}")
        if not isinstance(probe.get("x"), int) or not isinstance(probe.get("y"), int):
            raise EvidenceError(f"Pixel probe {probe_name} has no sampled coordinate in {name}")


def _has_measured_rows(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size == 0:
        return False
    try:
        with path.open(newline="", encoding="utf-8") as stream:
            rows = list(csv.reader(stream))
    except (OSError, UnicodeError, csv.Error):
        return False
    return len(rows) >= 2 and bool(rows[0]) and all(any(cell.strip() for cell in row) for row in rows[1:])


def _verify_tealturquoise_preview(path: Path) -> None:
    try:
        with path.open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream))
    except (OSError, UnicodeError, csv.Error) as exc:
        raise EvidenceError(f"Could not read Appearance preview semantics: {exc}") from exc
    target = next(
        (row for row in rows if row.get("preview") == "appearance_theme_preview_tealturquoise"),
        None,
    )
    if target is None:
        raise EvidenceError("Executed Appearance evidence lacks the exact Teal Turquoise preview semantics row")
    if (
        target.get("role") != "RadioButton"
        or target.get("selected") not in {"true", "false"}
        or target.get("click") != "true"
        or not (target.get("accessible-name") or "").strip()
        or not (target.get("visible-bounds") or "").strip()
        or not (target.get("unclipped-bounds") or "").strip()
        or target.get("viewport-clipped") not in {"true", "false"}
    ):
        raise EvidenceError(
            "Teal Turquoise preview semantics or visible/unclipped bounds evidence were not preserved",
        )


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
        args.summary.write_text(f"RESULT={exc.result}\nREASON={exc}\n", encoding="utf-8")
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
