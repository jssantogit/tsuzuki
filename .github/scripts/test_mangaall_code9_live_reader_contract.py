#!/usr/bin/env python3
"""Strict offline wiring regression for the opt-in signed code9 Android CDN gate."""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/mangaall-code9-live-reader-android.yml"
SCRIPT = ROOT / ".github/scripts/run_mangaall_live_pages_android.sh"
PROBE = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/provider/reading/ScriptProviderReadingGatewayIsolationTest.kt"
READER = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/CanonicalReaderSourceSwitchInstrumentedTest.kt"
FIXTURE = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllReaderJourneyFixture.kt"
VERDICT = ROOT / "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllInstrumentationVerdict.kt"

SHA = "d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622"
PIN = "cf93354bbc3290596f9664a222a8d6da5adeeef0"
ACK = "I_ACCEPT_SIGNED_CODE9_LIVE_READER"


class SignedCode9LiveReaderContract(unittest.TestCase):
    def test_live_source_cdn_never_runs_from_pr_or_push(self):
        wf = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", wf)
        self.assertIn("  pull_request:", wf)
        self.assertNotIn("\n  push:", wf)
        self.assertNotIn("\n  schedule:", wf)
        self.assertNotIn("\n  issue_comment:", wf)
        self.assertIn("offline-contract:\n    if: github.event_name == 'pull_request'", wf)
        self.assertIn("signed-code9-live-android:\n    if: github.event_name == 'workflow_dispatch'", wf)
        self.assertIn("I_ACCEPT_SIGNED_CODE9_LIVE_READER", wf)
        self.assertIn("assert chapter.startswith(work.split(':', 1)[0] + ':')", wf)
        self.assertIn("assert os.environ['MANGAALL_LANGUAGE'] == 'en'", wf)
        self.assertIn("if: always()", wf)
        self.assertIn("retention-days: 3", wf)
        self.assertNotIn("mangaall-live-android-batch-summary", wf)

    def test_exact_signed_immutable_code9_not_rebuilt_for_reader(self):
        wf = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(f"ref: {PIN}", wf)
        self.assertIn(f"sha = '{SHA}'", wf)
        self.assertIn("node tooling/verify_index.mjs", wf)
        self.assertIn("payload['sequence'] == 35", wf)
        self.assertIn("entry['versionCode'] == 9", wf)
        self.assertIn("hashlib.sha256(archive.read_bytes()).hexdigest() == sha", wf)
        self.assertIn("hashlib.sha256(Path('/tmp/mangaall-code9-reader-proof.tsz').read_bytes()).hexdigest() == sha", wf)
        self.assertIn("shutil.copyfile(archive, staged)", wf)
        self.assertIn("mangaall/code9-published-package.tsz", wf)
        self.assertNotIn("PUBLISH_MANGAALL_CODE9", wf)
        self.assertNotIn("TSUZUKI_PROVIDER_SIGNING_KEY_PEM", wf)
        self.assertNotIn("allow-cloudflare-bypass", wf)

    def test_existing_code8_gate_kept_and_code9_explicit(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('readonly signed_code="${MANGAALL_SIGNED_CODE:-8}"', script)
        self.assertIn("8|9|10) ;;", script)
        self.assertIn("LIVE_PAGE_HARD_STOP|reason=UNRECOGNIZED_SIGNED_CODE", script)
        self.assertIn('Code${signed_code}LivePages_downloadAndDecodeAtMostTwo', script)
        self.assertIn('Code${signed_code}LiveReaderDisplaysCdnPixels', script)
        self.assertIn('getprop ro.kernel.qemu', script)
        self.assertIn("trap cleanup EXIT", script)
        self.assertIn("readerActivityPixels=REAL_CDN_VISIBLE", script)
        self.assertIn("reason=EXTERNAL_ENVIRONMENT_INCONCLUSIVE", script)

    def test_real_android_source_and_reader_code9_checks_are_isolated(self):
        probe = PROBE.read_text(encoding="utf-8")
        reader = READER.read_text(encoding="utf-8")
        fixture = FIXTURE.read_text(encoding="utf-8")
        self.assertIn("fun mangaAllSignedCode9LivePages_downloadAndDecodeAtMostTwo()", probe)
        self.assertIn("runPublishedMangaAllLivePages(9L)", probe)
        self.assertIn("runPublishedMangaAllLivePages(8L)", probe)
        self.assertIn("fun mangaAllPublishedCode9LiveReaderDisplaysCdnPixels()", reader)
        self.assertIn("fun mangaAllPublishedCode8LiveReaderDisplaysCdnPixels()", reader)
        self.assertIn("runMangaAllPublishedLiveReader(code9 = true)", reader)
        self.assertIn("originalCode9Package = code9", reader)
        self.assertIn("originalCode9Package: Boolean = false", fixture)
        self.assertIn("mangaall/code9-published-package.tsz", fixture)
        self.assertIn("mangaall/code8-published-package.tsz", fixture)
        self.assertIn(SHA, probe)
        self.assertIn(SHA, fixture)
        self.assertIn("CHAPTER_NOT_ATTESTED_IN_BOUND", probe)
        self.assertIn("CHAPTER_IDENTITY_NOT_COMPATIBLE_WITH_TEST_FIXTURE", fixture)
        self.assertIn("MANGAALL_LIVE_PAGE_GATE|outcome=PASS|provenance=DIRECT_LIVE_ANDROID", reader)

    def test_closed_code9_provider_pages_failure_preserves_only_typed_cause(self):
        probe = PROBE.read_text(encoding="utf-8")
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("failure.error.code.name", probe)
        self.assertIn('failure.error.pageFailureStage?.name ?: "NONE"', probe)
        self.assertIn('9L -> "0.5.4"', probe)
        self.assertIn("providerError=(", script)
        self.assertIn("pageFailureStage=(", script)
        matched = re.search(r"grep -oE '([^']+)'", script)
        self.assertIsNotNone(matched, "Only a closed status extractor may export logs")
        extractor = matched.group(1)

        def exported(line):
            result = subprocess.run(
                ["grep", "-oE", extractor],
                input=line + "\n", text=True, capture_output=True, check=False,
            )
            return result.stdout.strip()

        good = (
            "INSTRUMENTATION_STATUS: stream=MANGAALL_LIVE_PAGE_GATE"
            "|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE"
            "|providerError=SOURCE_READ_ERROR|pageFailureStage=AT_HOME_REQUEST_FAILED"
        )
        self.assertEqual(exported(good), good)
        other = good.replace("AT_HOME_REQUEST_FAILED", "CHAPTER_WORK_MISMATCH")
        self.assertEqual(exported(other), other)
        no_stage = good.replace("AT_HOME_REQUEST_FAILED", "NONE")
        self.assertEqual(exported(no_stage), no_stage)

        # Unknown classes, exception messages and signed URLs cannot escape
        # through the exported verdict, even when appended to a valid prefix.
        for altered in (
            good.replace("AT_HOME_REQUEST_FAILED", "SECRET_CDN_TOKEN"),
            good.replace("SOURCE_READ_ERROR", "PRIVATE_HTTP_MESSAGE"),
            good + "|url=https://private.example/image?token=secret",
        ):
            with self.subTest(altered=altered[-45:]):
                output = exported(altered)
                self.assertIn("PROVIDER_PAGES_UNAVAILABLE", output)
                self.assertNotIn("SECRET_CDN_TOKEN", output)
                self.assertNotIn("PRIVATE_HTTP_MESSAGE", output)
                self.assertNotIn("https://", output)

    def test_android_status_accepts_same_closed_code9_page_error_as_bash(self):
        kotlin = VERDICT.read_text(encoding="utf-8")
        self.assertIn('Regex("""MANGAALL_LIVE_PAGE_GATE\\|outcome=INCONCLUSIVE\\|reason=[A-Z_]{3,64}""")',
                      kotlin, "Legacy code8 generic status must stay valid")
        comment = "// Accept only closed Host page diagnostic enums"
        self.assertEqual(kotlin.count(comment), 1)
        block = kotlin.split(comment, 1)[1]
        match = re.search(
            r'Regex\(\n((?:\s+"""[^\n]*?"""(?: \+|,)?\n)+)\s+\),',
            block,
        )
        self.assertIsNotNone(match, "Signed-code9 typed status regex must be structurally present")
        kotlin_regex = "".join(re.findall(r'"""(.*?)"""', match.group(1)))
        self.assertIn(r"\|providerError=(", kotlin_regex)
        self.assertIn(r"\|pageFailureStage=(", kotlin_regex)
        shell = SCRIPT.read_text(encoding="utf-8")
        bash_regexp = re.search(r"grep -oE '([^']+)'", shell).group(1)

        samples = (
            "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE"
            "|providerError=SOURCE_READ_ERROR|pageFailureStage=AT_HOME_REQUEST_FAILED",
            "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE"
            "|providerError=NETWORK_POLICY|pageFailureStage=NONE",
            "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=PROVIDER_PAGES_UNAVAILABLE"
            "|providerError=MALFORMED_RESULT|pageFailureStage=CHAPTER_LANGUAGE_MISMATCH",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertIsNotNone(re.fullmatch(kotlin_regex, sample))
                decorated = "INSTRUMENTATION_STATUS: stream=" + sample
                grep = subprocess.run(
                    ["grep", "-oE", bash_regexp], input=decorated + "\n",
                    text=True, capture_output=True, check=False,
                )
                self.assertEqual(grep.stdout.strip(), decorated)
        for invalid in (
            samples[0].replace("SOURCE_READ_ERROR", "PRIVATE_SCRIPT_EXCEPTION"),
            samples[0].replace("AT_HOME_REQUEST_FAILED", "SECRET_ENDPOINT"),
            samples[0] + "|cdn=https://bad.example/file?token=PRIVATE",
        ):
            with self.subTest(invalid=invalid[-70:]):
                self.assertIsNone(re.fullmatch(kotlin_regex, invalid))
        # A generic code8 outcome is still allowed, but an unknown suffix cannot
        # turn into an authorized typed code9 diagnostic.
        self.assertIn("MANGAALL_LIVE_PAGE_GATE\\|outcome=INCONCLUSIVE\\|reason=[A-Z_]{3,64}",
                      kotlin)
        
    def test_reader_fixture_uses_attested_chapter_number_not_chapter_one_alias(self):
        reader = READER.read_text(encoding="utf-8")
        fixture = FIXTURE.read_text(encoding="utf-8")
        wf = WORKFLOW.read_text(encoding="utf-8")
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("MANGAALL_CHAPTER_NUMBER: ${{ inputs.chapter_number }}", wf)
        self.assertIn("assert re.fullmatch(r'[1-9][0-9]{0,3}', number)", wf)
        self.assertIn('providerChapterNumber "${MANGAALL_CHAPTER_NUMBER:-1}"', script)
        self.assertIn('args.getString("providerChapterNumber")', reader)
        self.assertIn('withFixture(liveChapterNumber = observedChapterNumber)', reader)
        self.assertIn('displayNumber = liveChapterNumber.toString()', reader)
        self.assertIn('baseNumber = liveChapterNumber,', reader)
        self.assertIn('"/reader/$token/chapter-$fixtureChapterNumber"', reader)
        self.assertIn('selected.rawNumber != expectedNumber', fixture)
        self.assertIn('selected.volume != chapter.volume', fixture)
        self.assertNotIn('selected.rawNumber != 1.0', fixture)

    def test_only_instrumentation_can_export_closed_verdict(self):
        wf = WORKFLOW.read_text(encoding="utf-8")
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("MANGAALL_GATE_MODE: ${{ inputs.mode }}", wf)
        self.assertIn("MANGAALL_SIGNED_CODE: '9'", wf)
        self.assertIn("path: .github/results/mangaall-live-pages-android/verdict.txt", wf)
        self.assertNotIn("path: .github/results/mangaall-live-pages-android/instrumentation-private.txt", wf)
        self.assertIn('rm -f "$raw"', script)
        self.assertIn("adb shell pm clear", script)
        self.assertIn("outcome=(INCONCLUSIVE", script)


if __name__ == "__main__":
    unittest.main()
