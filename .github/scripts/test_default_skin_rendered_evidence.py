#!/usr/bin/env python3
"""Contract tests for fail-closed rendered artifact verification."""

from __future__ import annotations

import tempfile
import struct
import unittest
import zlib
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_default_skin_rendered_evidence import (
    EvidenceError,
    REQUIRED_CSVS,
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
        for name in REQUIRED_PNGS:
            (self.renders / name).write_bytes(valid_png())
        for name in REQUIRED_CSVS:
            (self.renders / name).write_text("fixture,bounds\nsample,0;0;1;1\n", encoding="utf-8")

    def test_complete_non_skipped_instrumentation_and_render_evidence_passes(self) -> None:
        self.write_evidence()

        result = verify(self.junit, self.renders)

        self.assertEqual(len(REQUIRED_TESTS), result["tests"])
        self.assertEqual(len(REQUIRED_PNGS), result["pngs"])

    def test_zero_test_report_is_rejected(self) -> None:
        (self.junit / "TEST-empty.xml").write_text("<testsuite tests='0' />", encoding="utf-8")
        with self.assertRaises(EvidenceError):
            verify(self.junit, self.renders)

    def test_incomplete_render_is_rejected(self) -> None:
        self.write_evidence()
        (self.renders / sorted(REQUIRED_PNGS)[0]).unlink()
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


def valid_png() -> bytes:
    signature = b"\x89PNG\r\n\x1a\n"

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    pixel = zlib.compress(b"\x00\xff\x00\x00\xff")
    return signature + chunk(b"IHDR", header) + chunk(b"IDAT", pixel) + chunk(b"IEND", b"")


if __name__ == "__main__":
    unittest.main()
