"""Checks that SQLite backup test discovery fails closed."""

import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

from verify_backup_jdbc_report import verify_report


class BackupJdbcReportGuardTest(unittest.TestCase):
    def test_missing_suite_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "Expected one"):
                verify_report(Path(folder))

    def test_empty_or_skipped_suite_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self._write(root, 1, skipped=True)
            with self.assertRaisesRegex(ValueError, "Only 1"):
                verify_report(root)
            self._write(root, 25, skipped=True)
            with self.assertRaisesRegex(ValueError, "skipped"):
                verify_report(root)

    def test_complete_suite_is_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self._write(root, 28)
            self.assertEqual(verify_report(root), 28)

    def test_reported_failure_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self._write(root, 25, failed=True)
            with self.assertRaisesRegex(ValueError, "failures"):
                verify_report(root)

    @staticmethod
    def _write(root: Path, count: int, skipped: bool = False, failed: bool = False):
        suite = ElementTree.Element("testsuite", name="CanonicalCoreBackupImporterTest")
        for index in range(count):
            case = ElementTree.SubElement(suite, "testcase", name=f"case{index}")
            if skipped and index == 0:
                ElementTree.SubElement(case, "skipped")
            if failed and index == 0:
                ElementTree.SubElement(case, "failure")
        ElementTree.ElementTree(suite).write(
            root / "TEST-eu.kanade.tachiyomi.data.backup.restore.CanonicalCoreBackupImporterTest.xml"
        )


if __name__ == "__main__":
    unittest.main()
