"""Contract tests: no synthesized work data can become a LIVE catalog attestation."""
import base64
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "attestation", Path(__file__).with_name("validate_mangaall_catalog_attestation.py")
)
attestation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(attestation)


def line(record):
    return "INSTRUMENTATION_STATUS: stream=MANGAALL_CATALOG_ATTESTATION|" + (
        base64.b64encode(json.dumps(record).encode("utf-8")).decode("ascii")
    )


def resolved():
    return {
        "schemaVersion": 1, "mode": "DIRECT_LIVE_ANDROID_CATALOG_ONLY",
        "caseId": "user-45cb5cff87aa", "query": "One Piece",
        "status": "RESOLVED", "catalogSource": "mangaupdates",
        "provider": "mangaupdates", "providerId": "123",
        "title": "One Piece", "authors": ["Published Creator"],
        "artists": [], "externalIds": {},
        "creatorsAttested": True, "catalogsQueried": 1, "catalogsFailed": 0,
        "canonical": "NOT_EXECUTED_HOST",
    }


class CatalogAttestationContractTest(unittest.TestCase):
    def test_published_owner_and_authors_remain_distinct_from_reader_source(self):
        row = resolved()
        row["caseId"] = attestation.case_id(row["query"])
        found = attestation.decode_log(line(row))
        self.assertEqual(found[0]["authors"], ["Published Creator"])
        self.assertEqual(found[0]["provider"], "mangaupdates")

    def test_ambiguous_and_inconclusive_never_carry_selected_identity(self):
        for status in ("CATALOG_AMBIGUOUS", "CATALOG_INCOMPLETE",
                       "CATALOG_UNAVAILABLE", "CATALOG_UNVERIFIED",
                       "CATALOG_NOT_FOUND", "CATALOG_FORMAT_UNSUPPORTED"):
            row = resolved()
            row.update(status=status, provider="", providerId="", title="",
                       authors=[], artists=[], externalIds={}, creatorsAttested=False)
            row["caseId"] = attestation.case_id(row["query"])
            self.assertEqual(attestation.decode_log(line(row))[0]["status"], status)

    def test_reject_forged_provider_and_case_identifier(self):
        row = resolved()
        row["caseId"] = attestation.case_id(row["query"])
        row["provider"] = "app.tsuzuki.mangaall"
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))
        row["provider"] = "mangaupdates"
        row["caseId"] = "user-deadbeef"
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))

    def test_reject_creator_without_attestation_and_misleading_complete_flag(self):
        row = resolved()
        row["caseId"] = attestation.case_id(row["query"])
        row["creatorsAttested"] = False
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))
        row["creatorsAttested"] = True
        row["canonical"] = "CHAPTERS_READY"
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))

    def test_reject_duplicates_and_bare_unvalidated_http_record(self):
        row = resolved()
        row["caseId"] = attestation.case_id(row["query"])
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row) + "\n" + line(row))
        with self.assertRaises(ValueError):
            attestation.decode_log('{"caseId": "user-123"}')

    def test_reject_external_id_conflicts_and_unbounded_text(self):
        row = resolved()
        row["caseId"] = attestation.case_id(row["query"])
        row["externalIds"] = {"mangaupdates": "DIFFERENT"}
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))
        row["externalIds"] = {}
        row["authors"] = ["A" * 200]
        with self.assertRaises(ValueError):
            attestation.decode_log(line(row))


if __name__ == "__main__":
    unittest.main()
