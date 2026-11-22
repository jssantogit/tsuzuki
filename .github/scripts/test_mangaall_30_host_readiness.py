#!/usr/bin/env python3
"""Offline tests: real Host limit constants and exact 30-work sharding only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mangaall_30_host_readiness import plan_30, source_host_limits
from mangaall_code10_canonical_smoke_plan import ACK

ROOT = Path(__file__).resolve().parents[2]
VECTOR = "\n".join(f"manga :: Synthetic Offline Work {n:02d}" for n in range(1, 31))


class TestThirtyWorkHostCapacity(unittest.TestCase):
    def test_three_shards_of_ten_exact_stable_distinct(self):
        report = plan_30(VECTOR, ACK, ROOT)
        self.assertEqual(report["workCount"], 30)
        self.assertEqual(report["shardCount"], 3)
        self.assertEqual([p["size"] for p in report["perShard"]], [10, 10, 10])
        self.assertEqual(report["perShard"][0]["positions"], list(range(1, 31, 3)))
        self.assertEqual(report["perShard"][1]["positions"], list(range(2, 31, 3)))
        self.assertEqual(report["perShard"][2]["positions"], list(range(3, 31, 3)))
        ids = [case_id for shard in report["perShard"] for case_id in shard["caseIds"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(report, plan_30(VECTOR, ACK, ROOT))
        self.assertTrue(all(case_id.startswith("user-") for case_id in ids))
        self.assertNotIn("Synthetic Offline Work", json.dumps(report))
        self.assertEqual(report["gates"]["editorialIdentity"], "NO_AUTOMATIC_BINDING")
        self.assertEqual(report["gates"]["canonicalSqlite"], "NOT_TESTED_ON_30")
        self.assertEqual(report["gates"]["readerPixels"], "NOT_TESTED_ON_30")

    def test_limits_are_the_actual_host_constants(self):
        limits = source_host_limits(ROOT)
        self.assertEqual(limits["scriptResultChars"], 65536)
        self.assertEqual(limits["httpBodyChars"], 196608)
        self.assertEqual(limits["httpEnvelopeChars"], 262144)
        self.assertEqual(limits["storageValueChars"], 65536)
        self.assertEqual(limits["storageTotalChars"], 524288)
        self.assertEqual(limits["sourceMaxChapterPages"], 32)
        self.assertEqual(limits["sourceMaxChapterObservations"], 16000)
        self.assertEqual(limits["quickJsIsolateLifetime"], "ONE_INVOCATION")
        self.assertEqual(limits["signedCode10StoragePermission"], "DISABLED")

    def test_plan_denies_incorrect_ack_size_and_duplicate(self):
        for title_count in (0, 1, 3, 10, 29, 31):
            bad = "\n".join(f"manga :: Offline {k}" for k in range(title_count))
            with self.assertRaises(ValueError):
                plan_30(bad, ACK, ROOT)
        with self.assertRaises(ValueError):
            plan_30(VECTOR, "UNKNOWN", ROOT)
        with self.assertRaises(ValueError):
            plan_30(VECTOR + "\nmanga :: Synthetic Offline Work 01", ACK, ROOT)
        with self.assertRaises(ValueError):
            plan_30(VECTOR + "\nhttps://example.com/", ACK, ROOT)

    def test_command_line_plan_never_runs_live(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / ".github/scripts/mangaall_30_host_readiness.py"), "--plan"],
            cwd=ROOT, text=True, capture_output=True, timeout=8,
            env={**os.environ, "MANGAALL_WORKS": VECTOR, "MANGAALL_SMOKE_ACK": ACK},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        parsed = json.loads(result.stdout)
        self.assertEqual(parsed["workCount"], 30)
        self.assertEqual(parsed["gates"]["sourceHttp"], "NOT_TESTED_ON_30")
        self.assertEqual(parsed["gates"]["releaseSigning"], "NOT_AUTHORIZED")
        denied = subprocess.run(
            [sys.executable, str(ROOT / ".github/scripts/mangaall_30_host_readiness.py"), "--plan"],
            cwd=ROOT, text=True, capture_output=True, timeout=8,
            env={**os.environ, "MANGAALL_WORKS": VECTOR, "MANGAALL_SMOKE_ACK": ""},
        )
        self.assertEqual(denied.returncode, 2)
        self.assertIn("outcome=DENIED", denied.stderr)


if __name__ == "__main__":
    unittest.main()
