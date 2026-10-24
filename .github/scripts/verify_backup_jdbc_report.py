#!/usr/bin/env python3
"""Fail closed if the canonical SQLite restore suite was silently undiscovered."""

import sys
from pathlib import Path
from xml.etree import ElementTree


def verify_report(root: Path, minimum_tests: int = 25) -> int:
    files = list(root.rglob("TEST-*CanonicalCoreBackupImporterTest.xml"))
    if len(files) != 1:
        raise ValueError(f"Expected one canonical importer JUnit XML report, found {len(files)}")
    suite = ElementTree.parse(files[0]).getroot()
    cases = list(suite.iter("testcase"))
    if len(cases) < minimum_tests:
        raise ValueError(
            f"Only {len(cases)} canonical importer tests were discovered "
            f"(minimum {minimum_tests})"
        )
    if any(case.find("skipped") is not None for case in cases):
        raise ValueError("Canonical importer tests were skipped")
    if any(case.find("failure") is not None or case.find("error") is not None for case in cases):
        raise ValueError("Canonical importer test failures are present")
    print(f"Canonical SQLite restore coverage: {len(cases)} tests discovered and executed")
    return len(cases)


if __name__ == "__main__":
    try:
        verify_report(Path(sys.argv[1]))
    except (IndexError, ValueError, ElementTree.ParseError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
