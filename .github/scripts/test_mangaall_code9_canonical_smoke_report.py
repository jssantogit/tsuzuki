#!/usr/bin/env python3
"""Pure contract tests; never synthesize a successful external smoke result."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

FILE = Path(__file__).with_name("mangaall_code9_canonical_smoke_report.py")
spec = importlib.util.spec_from_file_location("smoke_report", FILE)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

WORKS = "manga :: Naruto\nmanga :: Death Note\nmanga :: Bleach"
IDS = [v["caseId"] for v in report.parse_plan(WORKS, "isolated", report.ACK)]


def incomplete_vector():
    source = {
        "schemaVersion": 1,
        "captureKind": "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH",
        "origin": "LIVE_SIGNED_PROVIDER_JS",
        "providerId": "app.tsuzuki.mangaall",
        "providerVersionCode": 9,
        "repositoryId": "app.tsuzuki.providers",
        "signedIndexSequence": 35,
        "packageSha256": report.SHA,
        "selectionSource": "USER_INPUT",
        "shardIndex": 0,
        "shardCount": 1,
        "cases": [{"caseId": cid, "replayCompleteness": "LOOKUP_CHAPTERS_ONLY"} for cid in IDS],
    }
    matrix = {
        "provider": "app.tsuzuki.mangaall", "version": "0.5.4",
        "shardCount": 1, "shardIndex": 0, "selectionSource": "USER_INPUT",
        "cases": [{
            "case": cid, "lookupComplete": False,
            "httpBySource": {
                "MANGADEX": {"blocked403": 0, "transportFailed": 0},
                "MANGADOT": {"blocked403": 0, "transportFailed": 0},
                "MANGAFIRE": {"blocked403": 1, "transportFailed": 0},
            },
        } for cid in IDS],
    }
    catalog = {
        "schemaVersion": 1, "origin": "DIRECT_LIVE_ANDROID_CATALOG_ONLY",
        "canonicalStage": "NOT_EXECUTED_HOST", "sha256": "0" * 64,
        "cases": [{"caseId": cid, "status": "RESOLVED"} for cid in IDS],
    }
    collector = [{"case": cid, "gaps": "0"} for cid in IDS]
    sqlite = [{"case": cid, "replayGaps": "0", "confirmed": "20"} for cid in IDS]
    return source, matrix, catalog, collector, sqlite


class CanonicalCode9ReportSafetyTest(unittest.TestCase):
    def test_failed_third_source_never_becomes_chapter_success(self):
        vector = incomplete_vector()
        status = report.merge(WORKS, "isolated", *vector)
        self.assertEqual(status["works"], 3)
        self.assertEqual(len(status["results"]), 3)
        self.assertEqual([r["position"] for r in status["results"]], [1, 2, 3])
        self.assertTrue(all(r["outcome"] == "INCONCLUSIVE_SOURCE" for r in status["results"]))
        self.assertTrue(all(r["sourceIncomplete"] for r in status["results"]))
        self.assertFalse(status["canonicalGrantFromV2"])
        self.assertEqual(status["readerPixels"], "NOT_TESTED_REAL_CDN")

    def test_internal_replay_gap_must_fail_even_if_source_already_inconclusive(self):
        from copy import deepcopy
        original = incomplete_vector()
        partial = report.merge(WORKS, "isolated", *original)
        self.assertFalse(report.has_internal_replay_gap(partial),
                         "source 403 is inconclusive, not a missing Host request")
        broken = deepcopy(original)
        broken[3][0]["gaps"] = "1"
        broken[4][1]["replayGaps"] = "2"
        summary = report.merge(WORKS, "isolated", *broken)
        self.assertEqual([r["outcome"] for r in summary["results"]],
                         ["REPLAY_GAP", "REPLAY_GAP", "INCONCLUSIVE_SOURCE"])
        self.assertTrue(report.has_internal_replay_gap(summary))
        self.assertFalse(summary["canonicalGrantFromV2"])

    def test_missing_or_foreign_evidence_is_rejected_even_if_other_stages_are_healthy(self):
        from copy import deepcopy
        original = incomplete_vector()
        for tweak in ("captureCode8", "indexRollback", "archiveMismatch",
                      "catalogMissing", "sqliteDuplicate", "matrixForeignSource",
                      "collectorMissing", "sourceMissing"):
            variant = deepcopy(original)
            if tweak == "captureCode8":
                variant[0]["providerVersionCode"] = 8
            elif tweak == "indexRollback":
                variant[0]["signedIndexSequence"] = 34
            elif tweak == "archiveMismatch":
                variant[0]["packageSha256"] = "0" * 64
            elif tweak == "catalogMissing":
                variant[2]["cases"].pop()
            elif tweak == "sqliteDuplicate":
                variant[4].append(variant[4][0])
            elif tweak == "matrixForeignSource":
                variant[1]["cases"][0]["httpBySource"]["MANGAFIRE2"] = {}
            elif tweak == "collectorMissing":
                variant[3].pop()
            elif tweak == "sourceMissing":
                variant[0]["cases"].pop()
            with self.assertRaises(ValueError, msg=tweak):
                report.merge(WORKS, "isolated", *variant)

    def test_junit_closed_output_rejects_untrusted_markup_and_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "TEST.xml"
            cid = IDS[0]
            path.write_text(
                '<testsuite tests="1" failures="0" errors="0">'
                '<testcase name="smoke" />'
                '<system-out><![CDATA[MANGAALL_REAL_SQLITE_REFRESH|case=' +
                cid + '|confirmed=0|replayGaps=0]]></system-out></testsuite>'
            )
            self.assertEqual(report.closed_lines(path, report.SQL)[0]["case"], cid)
            path.write_text(path.read_text().replace('failures="0"', 'failures="1"'))
            with self.assertRaises(ValueError):
                report.closed_lines(path, report.SQL)


if __name__ == "__main__":
    unittest.main()
