#!/usr/bin/env python3
"""No-network smoke-vector acceptance, including rollback and tier boundaries."""
import importlib.util
from pathlib import Path
import unittest

FILE = Path(__file__).with_name("mangaall_code9_canonical_smoke_plan.py")
spec = importlib.util.spec_from_file_location("mangaall_smoke_plan", FILE)
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)

ACK = plan.ACK
THREE = "manga :: Naruto\nmanga :: Death Note\nmanga :: Bleach"


class CanonicalCode9SmokePlanTest(unittest.TestCase):
    def test_three_isolated_in_order_with_stable_digest(self):
        rows = plan.parse_plan(THREE, "isolated", ACK)
        self.assertEqual([r["query"] for r in rows], ["Naruto", "Death Note", "Bleach"])
        self.assertEqual([r["position"] for r in rows], [1, 2, 3])
        self.assertEqual(len(set(r["caseId"] for r in rows)), 3)
        self.assertEqual(plan.parse_plan(THREE, "isolated", ACK), rows)

    def test_scale_accepts_ten_to_thirty_with_order_preserved(self):
        for count in (10, 17, 30):
            rows = plan.parse_plan("\n".join(f"manga :: Work {i}" for i in range(count)), "scale", ACK)
            self.assertEqual(len(rows), count)
            self.assertEqual(rows[-1]["query"], f"Work {count - 1}")
            self.assertEqual(rows[-1]["position"], count)

    def test_all_other_tiers_and_wrong_cardinality_fail_closed(self):
        for values, tier in [
            (THREE, "scale"), (THREE + "\nOne Piece", "isolated"),
            ("Naruto\nDeath Note", "isolated"),
            ("\n".join(f"Work {i}" for i in range(9)), "scale"),
            ("\n".join(f"Work {i}" for i in range(31)), "scale"),
            (THREE, "developer"), (THREE, "all"),
        ]:
            with self.assertRaises(ValueError):
                plan.parse_plan(values, tier, ACK)

    def test_duplicate_unsafe_or_missing_opt_in_never_started(self):
        variants = [
            "Naruto\n naruto \nBleach",
            "https://example.org\nNaruto\nBleach",
            "Naruto\nDeath Note\nAuthorization:token\nBleach",
            "Naruto\nDeath Note\x00\nBleach",
            "manga :: Naruto :: alternate\nDeath Note\nBleach",
            "Naruto\nDeath Note\nBleach" + "X" * 8200,
        ]
        for value in variants:
            with self.assertRaises(ValueError):
                plan.parse_plan(value, "isolated", ACK)
        for consent in ("", "PUBLISH_MANGAALL_CODE9", "I_ACCEPT_LIVE_READ_ONLY"):
            with self.assertRaises(ValueError):
                plan.parse_plan(THREE, "isolated", consent)


if __name__ == "__main__":
    unittest.main()
