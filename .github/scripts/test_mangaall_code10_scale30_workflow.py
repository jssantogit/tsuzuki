#!/usr/bin/env python3
"""Source-only preflight of the 30-work scale workflow (no network)."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/mangaall-code10-scale30-canonical-smoke.yml"
OLD = ROOT / ".github/workflows/mangaall-code10-first-three-canonical-smoke.yml"


class TestScale30WorkflowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = WORKFLOW.read_text(encoding="utf-8")
        cls.legacy = OLD.read_text(encoding="utf-8")

    def test_on_demand_only_and_three_source_identity(self):
        w = self.data
        header = w.split("\npermissions:", 1)[0]
        self.assertIn("on:\n  push:\n", header)
        self.assertIn("  workflow_dispatch:\n", header)
        self.assertIn("branches: ['probe/mangaall-code10-scale30-*']", header)
        self.assertIn("'.github/mangaall-code10-scale30-request.json'", header)
        self.assertNotRegex(header, r"(?m)^  (?:pull_request|schedule):")
        self.assertIn("I_ACCEPT_SIGNED_CODE10_SCALE30_LIVE", header)
        self.assertIn("test \"$MANGAALL_SCALE30_ACK\" = 'I_ACCEPT_SIGNED_CODE10_SCALE30_LIVE'", w)
        self.assertIn("python3 .github/scripts/mangaall_30_host_readiness.py --plan", w)
        self.assertIn("MANGAALL_SMOKE_TIER: scale", w)
        self.assertNotIn("manga :: Naruto", w, "An on-demand scale workflow cannot silently fix titles")
        self.assertNotIn("manga :: Bleach", w)
        self.assertIn("MANGAALL_SUITE: custom", w)
        self.assertIn("MANGAALL_SHARD_COUNT: '3'", w)
        self.assertIn("shard: [0, 1, 2]", w)
        self.assertEqual(w.count("matrix:\n        shard: [0, 1, 2]"), 3)

    def test_authentic_provider_and_host_catalog_evidence(self):
        w = self.data
        self.assertIn("ref: ab97ebdaeb2a2130fe57b8c349908836a1d17708", w)
        self.assertIn("node tooling/verify_index.mjs", w)
        self.assertIn("node tooling/mangaall_live_matrix.mjs --live", w)
        self.assertIn("app.tsuzuki.mangaall", w)
        self.assertIn("bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f", w)
        self.assertIn("MangaAllRealSourceHostDecoderTest", w)
        self.assertIn("MangaAllIndependentCatalogLiveInstrumentedTest", (ROOT / ".github/scripts/run_mangaall_live_catalog_android.sh").read_text())
        self.assertIn("MangaAllRealCanonicalCollectorTest", w)
        self.assertIn("MangaAllRealSqliteRefreshTest", w)
        self.assertIn("mangaall-code10-canonical-smoke-per-work-summary-${{ matrix.shard }}", w)
        self.assertIn("mangaall-real-host-decoder-${{ matrix.shard }}", w)
        self.assertIn("mangaall-live-catalog-${{ matrix.shard }}", w)
        self.assertIn("mangaall_code10_scale30_aggregate.py", w)
        self.assertIn("needs: [aggregate-exact-30]", w)
        self.assertEqual(w.count("name: Real signed code10"), 1)
        self.assertIn("ReaderActivity source navigation and chapter regression — synthetic offline ONLY", w)

    def test_output_multiline_and_no_synthetic_pass_promotion(self):
        w = self.data
        self.assertIn("works: ${{ steps.approve.outputs.works }}", w)
        self.assertIn("MANGAALL_SCALE30_EVENT: ${{ github.event_name }}", w)
        self.assertIn("MANGAALL_SCALE30_MANUAL_WORKS: ${{ inputs.works }}", w)
        self.assertIn("set(request)=={'works','acknowledge'}", w)
        self.assertIn("MANGAALL_SCALE30_ACK: ${{ steps.request.outputs.acknowledge }}", w)
        self.assertIn("MANGAALL_WORKS: ${{ steps.request.outputs.works }}", w)
        self.assertIn("Only manual or scoped one-shot commit can launch source traffic", w)
        self.assertIn("MANGAALL_WORKS: ${{ needs.plan.outputs.works }}", w)
        self.assertIn("original=os.environ['MANGAALL_WORKS'].rstrip('\\n')", w)
        self.assertIn("f.write(f'works<<{end}\\n{original}\\n{end}\\n')", w)
        self.assertNotIn("GITHUB_TOKEN:", w)
        self.assertNotIn("contents: write", w)
        self.assertNotIn("workflow_dispatch:\n    inputs:\n      publish:", w)
        self.assertIn("readerUi=SYNTHETIC_REGRESSION", w)
        self.assertIn("bindingFromV2=DENIED", (ROOT / ".github/scripts/mangaall_code10_canonical_smoke_report.py").read_text())
        self.assertRegex(w, r"(?m)^  aggregate-exact-30:")
        self.assertIn("test \"${#summaries[@]}\" = 3", w)
        self.assertIn("mangaall-code10-scale30-aggregate", w)

    def test_commit_and_manual_authorization_python_are_executable(self):
        fragment = self.data.split("id: request", 1)[1].split(
            "      - name: Fail closed before external traffic", 1
        )[0]
        script = fragment.split("python3 - <<'PY'\\n", 1)[1].split("\\n          PY", 1)[0]
        code = "\\n".join(line[10:] for line in script.splitlines()) + "\\n"
        compile(code, "<scale30-request-gate>", "exec")
        vector = "\\n".join(f"manga :: Offline Work {i:02d}" for i in range(1, 31))
        ack = "I_ACCEPT_SIGNED_CODE10_SCALE30_LIVE"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".github/scripts").mkdir(parents=True)
            shutil.copy(
                ROOT / ".github/scripts/mangaall_code10_canonical_smoke_plan.py",
                root / ".github/scripts/mangaall_code10_canonical_smoke_plan.py",
            )
            request = root / ".github/mangaall-code10-scale30-request.json"
            output = root / "github_output.txt"

            def run(event, works, acknowledgement):
                import json
                output.write_text("", encoding="utf-8")
                request.write_text(json.dumps({
                    "works": works, "acknowledge": acknowledgement,
                }), encoding="utf-8")
                return subprocess.run(
                    [sys.executable, "-c", code], cwd=root, text=True,
                    capture_output=True, timeout=8,
                    env={
                        **os.environ,
                        "MANGAALL_SCALE30_EVENT": event,
                        "MANGAALL_SCALE30_MANUAL_WORKS": works,
                        "MANGAALL_SCALE30_MANUAL_ACK": acknowledgement,
                        "GITHUB_REF": "refs/heads/probe/mangaall-code10-scale30-audit",
                        "GITHUB_OUTPUT": str(output),
                    },
                )

            result = run("push", vector, ack)
            self.assertEqual(result.returncode, 0, result.stderr)
            written = output.read_text(encoding="utf-8")
            self.assertIn(vector, written)
            self.assertIn("acknowledge=" + ack + "\\n", written)
            self.assertIn("works<<END_", written)
            result = run("workflow_dispatch", vector, ack)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = run("push", vector, "FAKE_CONSENT")
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), "")
            result = run("push", vector.replace("Offline Work 30", "Offline Work 01"), ack)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), "")
            result = run("push", vector + "\\nmanga :: Unexpected Work", ack)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), "")

    def test_original_three_title_workflow_unchanged_and_all_jobs_present(self):
        w = self.data
        o = self.legacy
        self.assertIn("manga :: Naruto", o)
        self.assertIn("manga :: Death Note", o)
        self.assertIn("manga :: Bleach", o)
        self.assertIn("MANGAALL_SMOKE_TIER: isolated", o)
        for job in ("real-provider-to-host-decoder", "independent-catalog-android",
                    "canonical-collector-real-replay", "aggregate-exact-30", "reader-ui-regression"):
            self.assertEqual(w.count(f"  {job}:\n"), 1)
        self.assertIn("source=P256_SIGNED", w)
        self.assertNotIn("release:", w)
        self.assertNotIn("signing:", w)


if __name__ == "__main__":
    unittest.main()
