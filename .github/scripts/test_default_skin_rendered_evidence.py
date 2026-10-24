#!/usr/bin/env python3
"""Contract tests for fail-closed rendered artifact verification."""

from __future__ import annotations

import tempfile
import hashlib
import json
import struct
import unittest
import zlib
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_default_skin_rendered_evidence import (
    EvidenceError,
    REQUIRED_CSVS,
    REQUIRED_CAPTURE_STATES,
    REQUIRED_PNGS,
    REQUIRED_TEST_CLASS,
    REQUIRED_TESTS,
    verify,
)


class DefaultSkinRenderedEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.junit = self.root / "junit"
        self.renders = self.root / "renders"
        self.junit.mkdir()
        self.renders.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_evidence(self, test_class: str = REQUIRED_TEST_CLASS) -> None:
        cases = "".join(f'<testcase classname="{test_class}" name="{name}" />' for name in REQUIRED_TESTS)
        (self.junit / "TEST-rendered.xml").write_text(f"<testsuite tests='{len(REQUIRED_TESTS)}'>{cases}</testsuite>", encoding="utf-8")
        for index, name in enumerate(sorted(REQUIRED_PNGS), start=1):
            pixel_values = {
                (0, 0): (index, 1, 1, 255),
                (1, 0): (1, 1, index, 255),
                (0, 1): (index, 2, 3, 255),
                (1, 1): (4, 5, index, 255),
            }
            probes = {}
            if name == "search-wide.png":
                pixel_values[(0, 0)] = (246, 49, 166, 255)
                pixel_values[(1, 0)] = (25, 221, 242, 255)
                probes = {
                    "foreground-left-marker": {"x": 0, "y": 0, "color": "fff631a6"},
                    "foreground-right-marker": {"x": 1, "y": 0, "color": "ff19ddf2"},
                }
            elif name == "palette-light.png":
                pixel_values[(0, 0)] = (247, 247, 248, 255)
                pixel_values[(1, 1)] = (255, 255, 255, 255)
                probes = {
                    "canvas": {"x": 0, "y": 0, "color": "fff7f7f8"},
                    "surface": {"x": 1, "y": 1, "color": "ffffffff"},
                }
            elif name == "palette-dark.png":
                pixel_values[(0, 0)] = (11, 12, 13, 255)
                pixel_values[(1, 1)] = (28, 30, 31, 255)
                probes = {
                    "canvas": {"x": 0, "y": 0, "color": "ff0b0c0d"},
                    "surface": {"x": 1, "y": 1, "color": "ff1c1e1f"},
                }
            png = valid_png(pixel_values)
            png_path = self.renders / name
            png_path.write_bytes(png)
            fixture_id, theme, target_tag, scene_id = REQUIRED_CAPTURE_STATES[name]
            if target_tag.endswith("*"):
                target_tag = f"{target_tag[:-1]}tealturquoise"
            content_samples = [
                {"x": x, "y": y, "color": f"{alpha:02x}{red:02x}{green:02x}{blue:02x}"}
                for (x, y), (red, green, blue, alpha) in pixel_values.items()
            ]
            colors = sorted({sample["color"] for sample in content_samples})
            identity = f"{fixture_id}|{theme}|{target_tag}"
            scene_generation = index
            scene_tag = f"validation_scene_{scene_generation}_{scene_id}"
            semantic_identity = f"scene={scene_tag}#{scene_generation};{identity}"
            metadata = {
                "runId": "test-run",
                "stateId": png_path.stem,
                "png": name,
                "expectedTheme": theme,
                "actualTheme": theme,
                "hostRequestedTheme": theme,
                "fixtureId": fixture_id,
                "sceneId": scene_id,
                "sceneTag": scene_tag,
                "sceneGeneration": scene_generation,
                "targetTag": target_tag,
                "identityTags": [target_tag],
                "semanticIdentityBefore": semantic_identity,
                "semanticIdentityAfter": semantic_identity,
                "targetRole": "RadioButton" if target_tag.startswith("appearance_theme_preview_") else "",
                "targetSelected": True if target_tag.startswith("appearance_theme_preview_") else None,
                "targetHasClickAction": target_tag.startswith("appearance_theme_preview_"),
                "targetText": [],
                "targetContentDescription": ["fixture theme"] if target_tag.startswith("appearance_theme_preview_") else [],
                "targetVisibleBoundsRoot": {"left": 0, "top": 0, "right": 2, "bottom": 2},
                "targetRawBoundsRoot": {"left": 0, "top": 0, "right": 2, "bottom": 2},
                "composeOriginInWindow": [0, 0],
                "targetRawBoundsWindow": {"left": 0, "top": 0, "right": 2, "bottom": 2},
                "targetBoundsWindow": {"left": 0, "top": 0, "right": 2, "bottom": 2},
                "bitmapWidth": 2,
                "bitmapHeight": 2,
                "capturePath": "PixelCopy.Window",
                "hardwareAccelerated": True,
                "contentColors": colors,
                "contentColorSampleCount": len(colors),
                "contentPixelSamples": content_samples,
                "pixelProbes": probes,
                "pngSha256": hashlib.sha256(png).hexdigest(),
            }
            (self.renders / f"{png_path.stem}.json").write_text(json.dumps(metadata), encoding="utf-8")
        for name in REQUIRED_CSVS:
            if name == "appearance-measurements.csv":
                content = (
                    "preview,ratio,visible-bounds,unclipped-bounds,viewport-clipped,role,selected,click,accessible-name\n"
                    "appearance_theme_preview_tealturquoise,2.5,0;0;2;1,0;0;2;1,false,RadioButton,false,true,Teal Turquoise\n"
                )
            else:
                content = "fixture,bounds\nsample,0;0;1;1\n"
            (self.renders / name).write_text(content, encoding="utf-8")

    def test_complete_non_skipped_instrumentation_and_render_evidence_passes(self) -> None:
        self.write_evidence()

        result = verify(self.junit, self.renders)

        self.assertEqual(len(REQUIRED_TESTS), result["tests"])
        self.assertEqual(len(REQUIRED_PNGS), result["pngs"])

    def test_zero_test_report_is_rejected(self) -> None:
        (self.junit / "TEST-empty.xml").write_text("<testsuite tests='0' />", encoding="utf-8")
        with self.assertRaises(EvidenceError) as raised:
            verify(self.junit, self.renders)
        self.assertEqual("INFRASTRUCTURE_FAILURE", raised.exception.result)

    def test_executed_instrumented_assertion_failure_is_not_an_infrastructure_failure(self) -> None:
        self.write_evidence()
        path = self.junit / "TEST-rendered.xml"
        tree = ET.parse(path)
        case = next(tree.getroot().iter("testcase"))
        ET.SubElement(case, "failure", message="fixture assertion failed")
        tree.write(path, encoding="utf-8", xml_declaration=True)

        with self.assertRaises(EvidenceError) as raised:
            verify(self.junit, self.renders)

        self.assertEqual("VALIDATION_FAILURE", raised.exception.result)

    def test_incomplete_render_is_rejected(self) -> None:
        self.write_evidence()
        (self.renders / sorted(REQUIRED_PNGS)[0]).unlink()
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_missing_state_sidecar_is_rejected(self) -> None:
        self.write_evidence()
        (self.renders / "settings-light.json").unlink()
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_sidecar_state_id_must_match_its_png(self) -> None:
        self.write_evidence()
        sidecar = self.renders / "settings-light.json"
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
        metadata["stateId"] = "settings-dark"
        sidecar.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_fake_png_signature_and_payload_are_rejected(self) -> None:
        self.write_evidence()
        (self.renders / sorted(REQUIRED_PNGS)[0]).write_bytes(b"\x89PNG\r\n\x1a\nrender")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_wrong_test_class_is_rejected(self) -> None:
        self.write_evidence(test_class="unrelated.Test")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_skipped_instrumented_case_is_rejected(self) -> None:
        self.write_evidence()
        path = self.junit / "TEST-rendered.xml"
        tree = ET.parse(path)
        case = next(
            item for item in tree.getroot().iter("testcase")
            if item.attrib.get("name") == "searchArtworkFixturesKeepTheForegroundInsideOneStableMeasuredFrame"
        )
        ET.SubElement(case, "skipped")
        tree.write(path, encoding="utf-8", xml_declaration=True)
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_header_only_measurement_is_rejected(self) -> None:
        self.write_evidence()
        (self.renders / sorted(REQUIRED_CSVS)[0]).write_text("fixture,bounds\n", encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_fixture_theme_mismatch_is_rejected(self) -> None:
        self.write_evidence()
        sidecar = self.renders / "settings-light.json"
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
        metadata["fixtureId"] = "settings-dark"
        sidecar.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_stale_semantics_identity_is_rejected(self) -> None:
        self.write_evidence()
        sidecar = self.renders / "search-wide.json"
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
        metadata["semanticIdentityAfter"] = "different fixture"
        sidecar.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_uniform_png_rejects_fabricated_distinct_declared_pixel_samples(self) -> None:
        self.write_evidence()
        png_path = self.renders / "settings-light.png"
        uniform_color = (12, 34, 56, 255)
        png = valid_png({(x, y): uniform_color for y in range(2) for x in range(2)})
        png_path.write_bytes(png)
        sidecar = self.renders / "settings-light.json"
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
        metadata["contentPixelSamples"] = [
            {"x": 0, "y": 0, "color": "ff0c2238"},
            {"x": 1, "y": 1, "color": "ffabcdef"},
        ]
        metadata["contentColors"] = ["ff0c2238", "ffabcdef"]
        metadata["contentColorSampleCount"] = 2
        metadata["pngSha256"] = hashlib.sha256(png).hexdigest()
        sidecar.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_duplicate_render_hash_is_rejected(self) -> None:
        self.write_evidence()
        first = self.renders / sorted(REQUIRED_PNGS)[0]
        second = self.renders / sorted(REQUIRED_PNGS)[1]
        second.write_bytes(first.read_bytes())
        metadata_path = second.with_suffix(".json")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["pngSha256"] = hashlib.sha256(first.read_bytes()).hexdigest()
        first_capture = json.loads(first.with_suffix(".json").read_text(encoding="utf-8"))
        metadata["contentColors"] = first_capture["contentColors"]
        metadata["contentColorSampleCount"] = first_capture["contentColorSampleCount"]
        metadata["contentPixelSamples"] = first_capture["contentPixelSamples"]
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)


def valid_png(pixels: dict[tuple[int, int], tuple[int, int, int, int]]) -> bytes:
    signature = b"\x89PNG\r\n\x1a\n"

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
    raw = bytearray()
    for y in range(2):
        raw.append(0)
        for x in range(2):
            raw.extend(pixels[(x, y)])
    pixel = zlib.compress(bytes(raw))
    return signature + chunk(b"IHDR", header) + chunk(b"IDAT", pixel) + chunk(b"IEND", b"")


if __name__ == "__main__":
    unittest.main()
