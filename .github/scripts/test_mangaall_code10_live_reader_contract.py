#!/usr/bin/env python3
"""Offline, fail-closed wiring regression for manually dispatched signed code10 CDN/Reader proof."""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/mangaall-code10-live-reader-android.yml"
SCRIPT = ROOT / ".github/scripts/run_mangaall_live_pages_android.sh"
GATEWAY = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/provider/reading/ScriptProviderReadingGatewayIsolationTest.kt"
READER = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/CanonicalReaderSourceSwitchInstrumentedTest.kt"
FIXTURE = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllReaderJourneyFixture.kt"
VERDICT = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllInstrumentationVerdict.kt"

SHA = "bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f"
PIN = "195502f981bd776a68feebde3efc6d20f55cca65"
ACK = "I_ACCEPT_SIGNED_CODE10_LIVE_READER"
CODE9_SHA = "d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622"


class SignedCode10LiveReaderContract(unittest.TestCase):
    def test_only_operator_dispatch_may_touch_source_or_cdn(self):
        wf = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("\n  workflow_dispatch:", wf)
        self.assertIn("\n  pull_request:", wf)
        for forbidden in ("\n  push:", "\n  schedule:", "\n  issue_comment:", "workflow_run:"):
            self.assertNotIn(forbidden, wf)
        self.assertIn("offline-contract:\n    if: github.event_name == 'pull_request'", wf)
        self.assertIn("signed-code10-live-android:\n    if: github.event_name == 'workflow_dispatch'", wf)
        self.assertIn(ACK, wf)
        self.assertIn("assert chapter.startswith(work.split(':', 1)[0] + ':')", wf)
        self.assertIn("assert os.environ['MANGAALL_LANGUAGE'] == 'en'", wf)
        self.assertIn("assert os.environ['MANGAALL_GATE_MODE'] in ('transport', 'reader')", wf)
        self.assertIn("getprop ro.kernel.qemu", SCRIPT.read_text(encoding="utf-8"))
        self.assertIn("retention-days: 3", wf)

    def test_exact_p256_signed_code10_is_immutable_and_not_republished(self):
        wf = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(f"ref: {PIN}", wf)
        self.assertIn(f"sha = '{SHA}'", wf)
        self.assertIn("node tooling/verify_index.mjs", wf)
        self.assertIn("payload['sequence'] == 36", wf)
        self.assertIn("entry['versionCode'] == 10", wf)
        self.assertIn("entry['versionName'] == '0.5.5'", wf)
        self.assertIn("hashlib.sha256(archive.read_bytes()).hexdigest() == sha", wf)
        self.assertIn("hashlib.sha256(Path('/tmp/mangaall-code10-reader-proof.tsz').read_bytes()).hexdigest() == sha", wf)
        self.assertIn("mangaall/code10-published-package.tsz", wf)
        self.assertIn("dist/providers/app.tsuzuki.mangaall-10.tsz", wf)
        self.assertIn("|code=10|sequence=36", wf)
        self.assertNotIn("dist/providers/app.tsuzuki.mangaall-9.tsz", wf)
        self.assertIn("MANGAALL_SIGNED_CODE: '10'", wf)
        for forbidden in ("PUBLISH_MANGAALL_CODE10", "TSUZUKI_PROVIDER_SIGNING_KEY_PEM",
                          "allow-cloudflare-bypass", "curl -k", "export COOKIES"):
            self.assertNotIn(forbidden, wf)

    def test_android_gates_are_actual_code10_and_no_implicit_installation(self):
        gateway = GATEWAY.read_text(encoding="utf-8")
        reader = READER.read_text(encoding="utf-8")
        fixture = FIXTURE.read_text(encoding="utf-8")
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("8|9|10) ;;", script)
        self.assertIn("fun mangaAllSignedCode10LivePages_downloadAndDecodeAtMostTwo()", gateway)
        self.assertIn("runPublishedMangaAllLivePages(10L)", gateway)
        self.assertIn("fun mangaAllPublishedCode10LiveReaderDisplaysCdnPixels()", reader)
        self.assertIn("runMangaAllPublishedLiveReader(code9 = false, code10 = true)", reader)
        self.assertIn("originalCode10Package = originalCode10Package", reader)
        self.assertIn("originalCode10Package = code10", reader)
        self.assertIn("originalCode10Package: Boolean = false", fixture)
        self.assertIn("mangaall/code10-published-package.tsz", gateway)
        self.assertIn("mangaall/code10-published-package.tsz", fixture)
        self.assertIn(SHA, gateway)
        self.assertIn(SHA, fixture)
        # A previous signed generation must not be replaced or silently upgraded.
        self.assertIn(CODE9_SHA, gateway)
        self.assertIn(CODE9_SHA, fixture)
        self.assertIn("CHAPTER_NOT_ATTESTED_IN_BOUND", gateway)
        self.assertIn("CHAPTER_MAPPING_UNRESOLVED", fixture)
        self.assertIn("readerActivityPixels=REAL_CDN_VISIBLE", script)
        self.assertIn("INSTRUMENTATION_CODE: -1", script)
        self.assertIn("MANGAALL_LIVE_PAGE_GATE|outcome=PASS|provenance=DIRECT_LIVE_ANDROID", reader)
        self.assertIn("MANGAALL_LIVE_PAGE_GATE", VERDICT.read_text(encoding="utf-8"))

    def test_shell_wiring_and_closed_verdict_do_not_export_private_cdn_data(self):
        sh = SCRIPT.read_text(encoding="utf-8")
        subprocess.run(["bash", "-n", str(SCRIPT)], cwd=ROOT, check=True)
        self.assertIn("trap cleanup EXIT", sh)
        self.assertIn("rm -f \"$raw\"", sh)
        self.assertIn("readerActivityPixels=REAL_CDN_VISIBLE", sh)
        self.assertIn("reason=EXTERNAL_ENVIRONMENT_INCONCLUSIVE", sh)
        matched = re.search(r"grep -oE '([^']+)'", sh)
        self.assertIsNotNone(matched)
        extractor = matched.group(1)
        for suffix in ("|providerError=SOURCE_READ_ERROR|pageFailureStage=EXTERNAL_CHAPTER",
                       "|providerError=SOURCE_READ_ERROR|pageFailureStage=AT_HOME_REQUEST_FAILED"):
            value = ("INSTRUMENTATION_STATUS: stream=MANGAALL_LIVE_PAGE_GATE"
                     "|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE" + suffix)
            result = subprocess.run(["grep", "-oE", extractor], input=value + "\n",
                                    text=True, capture_output=True, check=False)
            self.assertEqual(result.stdout.strip(), value)
            private = value + "|url=https://cdn.example/path?token=NOT_FOR_LOGS"
            result = subprocess.run(["grep", "-oE", extractor], input=private + "\n",
                                    text=True, capture_output=True, check=False)
            self.assertEqual(result.stdout.strip(), value)


if __name__ == "__main__":
    unittest.main()
