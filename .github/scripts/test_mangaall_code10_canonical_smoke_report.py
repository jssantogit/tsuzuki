#!/usr/bin/env python3
"""Offline contract regression for signed code10 canonical smoke correlation.

All examples are synthetic and exercise only the report gate; no network,
canonical editorial authorization, Android Reader or live-source claim.
"""
import copy
import unittest

from mangaall_code10_canonical_smoke_plan import ACK, parse_plan
from mangaall_code10_canonical_smoke_report import SHA, merge

WORKS = "manga :: Naruto\nmanga :: Death Note\nmanga :: Bleach"
TIER = "isolated"
COUNT_FIELDS = (
    "confirmed", "provisionalChapters", "conflicted", "sqliteChapters",
    "sqliteEvidence", "sqliteMapped", "sqliteBindings",
    "uiRepositoryChapters", "sourceAssociations", "readerSampled",
    "readerUnique", "readerAmbiguous", "readerMissing", "replayGaps",
)


def fixtures():
    ids = [row["caseId"] for row in parse_plan(WORKS, TIER, ACK)]
    source = {
        "schemaVersion": 1,
        "captureKind": "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH",
        "origin": "LIVE_SIGNED_PROVIDER_JS",
        "providerId": "app.tsuzuki.mangaall",
        "providerVersionCode": 10,
        "repositoryId": "app.tsuzuki.providers",
        "signedIndexSequence": 36,
        "packageSha256": SHA,
        "selectionSource": "USER_INPUT",
        "shardIndex": 0, "shardCount": 1,
        "cases": [
            {"caseId": case, "records": [], "replayCompleteness": "LOOKUP_CHAPTERS_ONLY"}
            for case in ids
        ],
    }
    matrix = {
        "provider": "app.tsuzuki.mangaall", "version": "0.5.5",
        "shardIndex": 0, "shardCount": 1, "selectionSource": "USER_INPUT",
        "cases": [
            {"case": case, "lookupComplete": False, "httpBySource": {
                name: {"blocked403": int(name == "MANGAFIRE"), "transportFailed": 0}
                for name in ("MANGADEX", "MANGADOT", "MANGAFIRE")
            }}
            for case in ids
        ],
    }
    catalog = {
        "schemaVersion": 1, "origin": "DIRECT_LIVE_ANDROID_CATALOG_ONLY",
        "canonicalStage": "NOT_EXECUTED_HOST", "sha256": "0" * 64,
        "cases": [{"caseId": case, "status": "UNAVAILABLE"} for case in ids],
    }
    collectors = [{"case": case, "gaps": "0"} for case in ids]
    sqlites = [
        {"case": case, "lookupDecisions": "NONE", **{name: "0" for name in COUNT_FIELDS}}
        for case in ids
    ]
    return source, matrix, catalog, collectors, sqlites


class CanonicalCode10ReportTest(unittest.TestCase):
    def test_exact_signed_code10_source_matrix_is_accepted_as_inconclusive(self):
        result = merge(WORKS, TIER, *fixtures())
        self.assertEqual(result["works"], 3)
        self.assertEqual(result["signedIndexSequence"], 36)
        self.assertTrue(result["allThreeSourcesInOneMangaAllLookup"])
        self.assertEqual([r["outcome"] for r in result["results"]], ["INCONCLUSIVE_SOURCE"] * 3)
        self.assertEqual([r["sourceHttp403"] for r in result["results"]], [["MANGAFIRE"]] * 3)
        self.assertEqual(result["readerPixels"], "NOT_TESTED_REAL_CDN")
        self.assertFalse(result["canonicalGrantFromV2"])

    def test_historical_code9_and_unreleased_versions_cannot_pass_as_code10(self):
        for wrong_version in ("0.5.4", "0.5.5-dev", "0.5.6", ""):
            with self.subTest(version=wrong_version):
                values = fixtures()
                values[1]["version"] = wrong_version
                with self.assertRaisesRegex(ValueError, "Source matrix not from"):
                    merge(WORKS, TIER, *values)

    def test_wrong_sequence_or_sha_never_accepts_report(self):
        for field, wrong in (("signedIndexSequence", 35), ("packageSha256", "0" * 64),
                             ("providerVersionCode", 9)):
            with self.subTest(field=field):
                values = fixtures()
                values[0][field] = wrong
                with self.assertRaisesRegex(ValueError, "authentic sequence36"):
                    merge(WORKS, TIER, *values)

    def test_case_substitution_never_becomes_canonical_evidence(self):
        values = fixtures()
        values[1]["cases"][0]["case"] = "user-aaaaaaaaaaaa"
        with self.assertRaisesRegex(ValueError, "case set differs"):
            merge(WORKS, TIER, *values)


if __name__ == "__main__":
    unittest.main()
