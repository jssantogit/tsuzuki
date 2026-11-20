#!/usr/bin/env python3
"""Offline static regression: code9 canonical smoke cannot auto-run or overclaim."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/mangaall-code9-canonical-smoke.yml"


class CanonicalCode9SmokeWorkflowTest(unittest.TestCase):
    def test_only_manual_and_explicitly_authorized_live_sources(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", text)
        for forbidden in ("  push:", "  pull_request:", "  schedule:", "  workflow_run:"):
            self.assertNotIn(forbidden, text)
        self.assertIn("I_ACCEPT_CODE9_CANONICAL_SMOKE", text)
        self.assertIn("mangaall_code9_canonical_smoke_plan.py", text)
        self.assertIn("I_ACCEPT_SIGNED_CODE9_CANONICAL_SMOKE", text)
        self.assertIn("node tooling/verify_index.mjs", text)
        self.assertIn("data['sequence']==35", text)
        self.assertIn("items[0]['versionCode']==9", text)
        self.assertIn("app.tsuzuki.mangaall-9.tsz", text)
        self.assertNotIn("app.tsuzuki.mangaall-8.tsz", text)
        self.assertNotIn("99f3565a1e1edc0d8ed249b7597006c897f02636", text)
        self.assertIn("ref: c35b1bcac1ce02062f80e812517239e39adbcfd8", text)
        self.assertNotIn("ref: 564a2f526bc05761eb51cd2bf0ab63b02f33e466", text)
        registry = (ROOT / "app/src/test/java/eu/kanade/tachiyomi/provider/reading/MangaAllRealSourceReplay.kt").read_text(encoding="utf-8")
        self.assertIn('contentLanguages = setOf("en")', registry)
        self.assertNotIn('contentLanguages = setOf("en", "pt-br")', registry)
        self.assertIn('facets = listOf(ProviderFacetRef(descriptor.id, "en"))', registry)
        self.assertIn('capturedEnglishRegistration(descriptor)', registry)

    def test_one_ordered_work_vector_into_live_source_and_independent_catalog(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("matrix.shard", text)
        self.assertNotIn("matrix:", text)
        self.assertGreaterEqual(text.count("MANGAALL_SHARD_COUNT: '1'"), 2)
        self.assertIn("MANGAALL_BATCH: '1'", text)
        self.assertIn("MANGAALL_WORKS: ${{ inputs.works }}", text)
        self.assertIn("node tooling/mangaall_live_matrix.mjs --plan", text)
        self.assertIn("node tooling/mangaall_live_matrix.mjs --live", text)
        self.assertIn("bash .github/scripts/run_mangaall_live_catalog_android.sh", text)
        self.assertIn("needs: [real-provider-to-host-decoder, independent-catalog-android]", text)

    def test_real_kotlin_collector_sqlite_and_reader_limitations(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("MangaAllRealSourceHostDecoderTest", text)
        self.assertIn("MangaAllRealCanonicalCollectorTest", text)
        self.assertIn("MangaAllRealSqliteRefreshTest", text)
        self.assertIn("mangaall_code9_canonical_smoke_report.py", text)
        self.assertIn("mangaall-code9-canonical-smoke-per-work-summary", text)
        self.assertIn("reader-ui-regression:", text)
        self.assertIn("needs: [canonical-collector-real-replay]", text)
        self.assertIn("run_canonical_v2_android.sh", text)
        self.assertIn("cdnPixels=NOT_TESTED", text)
        self.assertNotIn("PUBLISH_MANGAALL_CODE9", text)


if __name__ == "__main__":
    unittest.main()
