#!/usr/bin/env python3
"""No-network tests for JUnit status and per-work real-catalog provenance."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mangaall_catalog_instrumentation_gate import validate
from validate_mangaall_catalog_attestation import case_id

WORKS = "manga :: Naruto\nmanga :: Death Note\nmanga :: Bleach"
TITLES = ["Naruto", "Death Note", "Bleach"]

def fixture():
    records = []
    for title in TITLES:
        records.append({
            "caseId": case_id(title),
            "query": title,
            "status": "CATALOG_UNAVAILABLE",
        })
    artifact = {
        "schemaVersion": 1,
        "origin": "DIRECT_LIVE_ANDROID_CATALOG_ONLY",
        "canonicalStage": "NOT_EXECUTED_HOST",
        "cases": records,
    }
    raw = (
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        "INSTRUMENTATION_STATUS_CODE: 0\n"
        "INSTRUMENTATION_STATUS: stream=MANGAALL_REAL_CATALOG_BATCH"
        "|selected=3|conclusive=0|catalog=MANGAUPDATES_PRODUCTION"
        "|canonical=NOT_EXECUTED_HOST\n"
        "INSTRUMENTATION_CODE: -1\n"
    )
    return raw, artifact

class CatalogGateTest(unittest.TestCase):
    def test_all_real_unavailable_are_inconclusive_not_broken_instrumentation(self):
        raw, artifact = fixture()
        self.assertEqual(
            validate(raw, artifact, WORKS),
            {"works": 3, "conclusive": 0, "inconclusive": 3},
        )
        self.assertNotIn("OK (1 test)", raw,
            "the missing optional runner summary is not itself a test failure")

    def test_closed_conclusive_count_and_source_status(self):
        raw, artifact = fixture()
        artifact["cases"][0]["status"] = "RESOLVED"
        raw = raw.replace("conclusive=0", "conclusive=1")
        self.assertEqual(validate(raw, artifact, WORKS)["conclusive"], 1)

    def test_corruption_never_becomes_a_success(self):
        raw, artifact = fixture()
        tests = [
            (raw.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2"), artifact),
            (raw.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), artifact),
            (raw.replace("INSTRUMENTATION_STATUS_CODE: 0", ""), artifact),
            (raw + "INSTRUMENTATION_RESULT: shortMsg=failed\n", artifact),
            (raw.replace("selected=3", "selected=2"), artifact),
            (raw.replace("conclusive=0", "conclusive=3"), artifact),
            (raw.replace("MANGAUPDATES_PRODUCTION", "FAKE_SOURCE"), artifact),
            (raw, {**artifact, "origin": "FORGED"}),
            (raw, {**artifact, "cases": artifact["cases"][:-1]}),
            (raw, {**artifact, "cases": list(reversed(artifact["cases"]))}),
            (raw, {**artifact, "cases": [*artifact["cases"][:-1], artifact["cases"][0]]}),
        ]
        for index, (bad_raw, bad_artifact) in enumerate(tests):
            with self.subTest(index=index), self.assertRaises(ValueError):
                validate(bad_raw, bad_artifact, WORKS)
        with self.assertRaises(ValueError):
            validate(raw, artifact, WORKS, exit_code=1)

    def test_shards_and_wrong_title_order_denied(self):
        raw, artifact = fixture()
        with self.assertRaises(ValueError):
            validate(raw, artifact, "manga :: Bleach\nmanga :: Naruto\nmanga :: Death Note")
        with self.assertRaises(ValueError):
            validate(raw, artifact, WORKS, shard_index=0, shard_count=2)

if __name__ == "__main__":
    unittest.main()
