#!/usr/bin/env python3
"""Strict live-only acceptance. Never accepts a synthetic or skipped result."""
import pathlib
import re
import sys

SOURCES = ("mangaall-ball", "mangafire")
WORKS = ("death-note", "punpun", "akame")
EXPECTED = {(s, w) for s in SOURCES for w in WORKS}
ROW = re.compile(r"ADVANCED_LIVE\|([a-z-]+)\|([a-z-]+)\|PASS\|ms=([0-9]+)")

def valid(lines):
    if any(line.startswith("ADVANCED_DENIAL|") for line in lines):
        return False
    if [l for l in lines if l.startswith("ADVANCED_CONTRACT|")] != ["ADVANCED_CONTRACT|PASS"]:
        return False
    details = [l for l in lines if l.startswith("ADVANCED_CONTRACT_DETAIL|")]
    if sorted(details) != sorted(["ADVANCED_CONTRACT_DETAIL|ADAPTERS_SQLITE|PASS",
                                   "ADVANCED_CONTRACT_DETAIL|PROTOCOL_SESSION|PASS"]):
        return False
    if any(l.startswith("ADVANCED_CONTRACT_FAILURE|") for l in lines):
        return False
    rows = [ROW.fullmatch(l) for l in lines if l.startswith("ADVANCED_LIVE|")]
    return len(rows) == 6 and all(rows) and {(r[1], r[2]) for r in rows} == EXPECTED

def self_test():
    base = ["ADVANCED_CONTRACT|PASS", "ADVANCED_CONTRACT_DETAIL|ADAPTERS_SQLITE|PASS",
            "ADVANCED_CONTRACT_DETAIL|PROTOCOL_SESSION|PASS"]
    rows = [f"ADVANCED_LIVE|{s}|{w}|PASS|ms=1" for s,w in sorted(EXPECTED)]
    cases = [
        (base + rows, True),
        (base, False),
        (base + rows[:-1], False),
        (base + rows[:-1] + [rows[0]], False),
        (base + rows[:-1] + [rows[-1].replace("PASS", "LOOKUP_HTTP_403")], False),
        (base + rows[:-1] + [rows[-1].replace("PASS", "NOT_ATTEMPTED_HTTP_403")], False),
        (base + rows + ["ADVANCED_DENIAL|mangafire|HTTP_403|challenge_marker=true"], False),
        (base + rows + ["ADVANCED_CONTRACT_FAILURE|PROTOCOL_SESSION|ASSERTION"], False),
        ([base[0]] + rows, False),
    ]
    for lines, expected in cases:
        if bool(valid(lines)) != expected:
            raise SystemExit("Live verdict self-test failed")
    print("ADVANCED_VERDICT_CONTRACT|SELF_TEST=PASS|cases=9")

if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        if not valid(pathlib.Path(sys.argv[1]).read_text().splitlines()):
            raise SystemExit("ADVANCED_LIVE|STRICT_VERDICT=FAIL")
        print("ADVANCED_LIVE|STRICT_VERDICT=PASS")
    else:
        raise SystemExit("Expected one verdict file or --self-test")
