#!/usr/bin/env python3
"""Offline fail-closed contracts for the UNIFIED three-source MangaAll batch."""
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mangaall_live_batch as batch

PARITY = {"titleParity": "CONSISTENT", "authorParity": "UNVERIFIED"}
HANDOFFS = {
    "mangadex": ("mangadex:11111111-2222-4333-8444-555555555555",
                 "mangadex:aaaa1111-2222-4333-8444-555555555555"),
    "mangadot": ("mangadot:4321", "mangadot:uploads:99"),
    "mangafire": ("mangafire:z1r3l", "mangafire:abcde1"),
}


def record(source, outcome="READY", candidates=1):
    handoff = HANDOFFS[source] if outcome == "READY" else None
    return {
        "source": source, "discovery": outcome, "candidates": candidates,
        "handoff": handoff,
    }


def transcript(statuses=("READY", "READY", "READY"), counts=(1, 1, 1),
               title_parity="CONSISTENT", author_parity="UNVERIFIED"):
    lines = []
    for source, outcome, count in zip(batch.SOURCES, statuses, counts):
        line = "MANGAALL_BATCH_SOURCE|source=%s|outcome=%s|candidates=%d" % (
            source, outcome, count,
        )
        if outcome == "READY":
            work_id, chapter_id = HANDOFFS[source]
            line += "|workId=%s|chapterId=%s|language=en" % (work_id, chapter_id)
        lines.append("INSTRUMENTATION_STATUS: stream=" + line)
    lines.append(
        "INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_AUDIT|outcome=COMPLETE"
        "|sources=3|titleParity=%s|authorParity=%s" %
        (title_parity, author_parity)
    )
    return "\n".join(lines + ["OK (1 test)", "INSTRUMENTATION_CODE: -1"])


class MangaAllUnifiedBatchContractTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "mode": "reader", "language": "en",
            "titles": ["Death Note", "Naruto", "Bleach"],
        }

    def test_ordered_inputs(self):
        for value in (
            "Death Note, Naruto, Bleach",
            "Death Note\nNaruto\nBleach",
            '["Death Note","Naruto","Bleach"]',
        ):
            with self.subTest(value=value):
                plan = batch.parse_plan({**self.data, "titles": value})
                self.assertEqual(plan.titles, tuple(self.data["titles"]))
                self.assertEqual(plan.language, "en")
                self.assertEqual(batch.SOURCES, ("mangadex", "mangadot", "mangafire"))

    def test_old_source_selector_cannot_disable_other_sources(self):
        for source in ("mangafire", "mangadex", "mangadot"):
            with self.subTest(source=source):
                with self.assertRaisesRegex(ValueError, "UNEXPECTED_PLAN_FIELDS"):
                    batch.parse_plan({**self.data, "source": source})

    def test_input_validation_and_all_three_share_only_english(self):
        self.assertEqual(len(batch.parse_plan({
            **self.data, "titles": [f"Manga {i}" for i in range(30)]
        }).titles), 30)
        for values in (
            {**self.data, "titles": []},
            {**self.data, "titles": ["Naruto", "naruto"]},
            {**self.data, "titles": ["Naruto", "Demon|Slayer"]},
            {**self.data, "titles": ["Naruto", "\x00Bleach"]},
            {**self.data, "language": "pt-br"},
            {**self.data, "mode": "unsafe"},
            {**self.data, "token": "never allowed"},
        ):
            with self.subTest(values=values):
                with self.assertRaises(ValueError):
                    batch.parse_plan(values)

    def test_commit_reads_manifest_only_in_explicit_opt_in_branch(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "batch.json"
            path.write_text(json.dumps(self.data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "UNAUTHORIZED_PUSH_BRANCH"):
                batch.read_plan({
                    "GITHUB_EVENT_NAME": "push",
                    "GITHUB_REF": "refs/heads/main",
                }, path)
            plan = batch.read_plan({
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REF": "refs/heads/mangaall-live/batch-unified",
            }, path)
            self.assertEqual(plan.titles, tuple(self.data["titles"]))
            with self.assertRaisesRegex(ValueError, "EVENT_NOT_ALLOWED"):
                batch.read_plan({"GITHUB_EVENT_NAME": "pull_request"}, path)

    def test_complete_three_source_handoff_checks_all_id_provenance(self):
        plan = batch.parse_plan(self.data)
        parsed = batch._parse_discovery(transcript(), 0, plan)
        self.assertEqual(parsed[0], "COMPLETE")
        self.assertEqual(parsed[3], PARITY)
        self.assertEqual([r["source"] for r in parsed[2]], list(batch.SOURCES))
        self.assertEqual(parsed[2][1]["handoff"], HANDOFFS["mangadot"])
        self.assertEqual(batch._parse_discovery(transcript(), 1, plan)[0], "FAIL")

    def test_mangafire_ambiguity_does_not_hide_mangadex_or_mangadot(self):
        plan = batch.parse_plan(self.data)
        data = transcript(
            ("READY", "READY", "AMBIGUOUS"), (1, 1, 4),
        )
        parsed = batch._parse_discovery(data, 0, plan)
        self.assertEqual(parsed[0], "COMPLETE")
        self.assertIsNotNone(parsed[2][0]["handoff"])
        self.assertIsNotNone(parsed[2][1]["handoff"])
        self.assertEqual(parsed[2][2]["discovery"], "AMBIGUOUS")
        self.assertIsNone(parsed[2][2]["handoff"])

    def test_reject_missing_source_duplicate_and_fake_success(self):
        plan = batch.parse_plan(self.data)
        good = transcript()
        for corrupt in (
            good.replace("MANGAALL_BATCH_SOURCE|source=mangadot", "MANGAALL_BATCH_SOURCE|source=mangafire"),
            good.replace("INSTRUMENTATION_STATUS: stream=", ""),
            good.replace("mangadot:4321", "mangafire:4321"),
            good.replace("chapterId=mangadot:", "chapterId=mangafire:"),
            good.replace("MANGAALL_BATCH_SOURCE|source=mangafire|outcome=READY|candidates=1", ""),
            good.replace("sources=3", "sources=2"),
            good.replace("candidates=1", "candidates=0"),
        ):
            with self.subTest(corrupt=corrupt[:80]):
                self.assertEqual(batch._parse_discovery(corrupt, 0, plan)[0], "FAIL")
        self.assertEqual(
            batch._parse_discovery(good.replace("mangadot:4321", "mangafire:4321"), 0, plan)[0],
            "FAIL",
        )

    def test_partial_unified_lookup_is_inconclusive_not_three_empty_sources(self):
        output = (
            "INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_AUDIT|"
            "outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE"
            "\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
        )
        parsed = batch._parse_discovery(output, 0, batch.parse_plan(self.data))
        self.assertEqual(parsed, ("INCONCLUSIVE", "LOOKUP_UNAVAILABLE", [], {}))

    def test_unavailable_lookup_retains_only_safe_host_error_telemetry(self):
        plan = batch.parse_plan(self.data)
        for code, kind, http_status, retry in (
            ("SOURCE_READ_ERROR", "CHALLENGE_BLOCKED", "403", "true"),
            ("SOURCE_READ_ERROR", "HTTP_ERROR", "503", "true"),
            ("TIMEOUT", "NONE", "NONE", "true"),
            ("SCRIPT_ERROR", "NONE", "NONE", "false"),
        ):
            marker = (
                "INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_AUDIT|"
                "outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE"
                f"|code={code}|sourceFailure={kind}|httpStatus={http_status}"
                f"|retryable={retry}"
                "\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
            )
            outcome, reason, records, detail = batch._parse_discovery(marker, 0, plan)
            self.assertEqual((outcome, reason, records),
                             ("INCONCLUSIVE", "LOOKUP_UNAVAILABLE", []))
            self.assertEqual(detail["lookupError"]["code"], code)
            self.assertEqual(detail["lookupError"]["sourceFailure"], kind)
        forged = marker.replace("code=SCRIPT_ERROR", "code=SECRETS")
        self.assertEqual(batch._parse_discovery(forged, 0, plan)[0], "FAIL")
        invalid = marker.replace("sourceFailure=NONE", "sourceFailure=HTTP_ERROR")
        self.assertEqual(batch._parse_discovery(invalid, 0, plan)[0], "FAIL")

    def test_sequential_title_batch_keeps_all_three_source_results(self):
        plan = batch.parse_plan(self.data)
        with tempfile.TemporaryDirectory() as root:
            out = Path(root) / "summary.json"
            ready = [record(src) for src in batch.SOURCES]
            partial = [record("mangadex"), record("mangadot"),
                       record("mangafire", "AMBIGUOUS", 3)]
            with (
                mock.patch.object(batch, "RESULT", out),
                mock.patch.object(batch, "_install_test_apks"),
                mock.patch.object(batch, "_exec") as cmd,
                mock.patch.object(batch, "_discover") as discover,
                mock.patch.object(batch, "_gate") as gate,
            ):
                cmd.return_value = mock.Mock(returncode=0, stdout="Success")
                discover.side_effect = [
                    ("COMPLETE", "TRI_SOURCE_LOOKUP", ready, PARITY),
                    ("COMPLETE", "TRI_SOURCE_LOOKUP", partial, PARITY),
                    ("COMPLETE", "TRI_SOURCE_LOOKUP", ready, {
                        "titleParity": "VARIANCE", "authorParity": "UNVERIFIED",
                    }),
                ]
                gate.return_value = ("PASS", "READER_CDN_PIXELS")
                # Unit-test synthetic PASS markers must never resemble LIVE
                # outcomes in the GitHub Actions open build logs.
                with redirect_stdout(io.StringIO()) as captured:
                    self.assertEqual(batch.execute(plan), 2)
                self.assertIn("MANGAALL_BATCH|summary=COMPLETE", captured.getvalue())
                self.assertEqual(
                    [call.args[0] for call in discover.call_args_list],
                    self.data["titles"],
                )
                summary = json.loads(out.read_text(encoding="utf-8"))
                self.assertEqual(summary["schemaVersion"], 2)
                self.assertEqual(summary["provider"], "app.tsuzuki.mangaall")
                self.assertEqual(summary["sources"], list(batch.SOURCES))
                self.assertEqual(summary["editorialIdentityVerified"], False)
                self.assertEqual(
                    [x["outcome"] for x in summary["cases"]],
                    ["PASS", "INCONCLUSIVE", "INCONCLUSIVE"],
                )
                self.assertEqual(len(summary["cases"][1]["sources"]), 3)
                self.assertEqual(
                    [x["outcome"] for x in summary["cases"][1]["sources"]],
                    ["PASS", "PASS", "INCONCLUSIVE"],
                )
                # Only locally authored titles and closed outcomes leave CI.
                for source in HANDOFFS:
                    self.assertNotIn(HANDOFFS[source][0], out.read_text(encoding="utf-8"))
                self.assertEqual(gate.call_count, 8)

    def test_live_reader_gate_mode_is_not_faked_by_transport(self):
        plan = batch.parse_plan(self.data)
        passed = (
            "MANGAALL_LIVE_PAGE_GATE|outcome=PASS|provenance=DIRECT_LIVE_ANDROID"
            "|pagesDecoded=1|readerActivityPixels=REAL_CDN_VISIBLE"
        )
        self.assertEqual(batch._parse_gate(passed, 0, plan), ("PASS", "READER_CDN_PIXELS"))
        self.assertEqual(batch._parse_gate(passed, 2, plan)[0], "FAIL")
        transport = batch.parse_plan({**self.data, "mode": "transport"})
        self.assertEqual(batch._parse_gate(passed, 0, transport)[0], "FAIL")
        self.assertEqual(batch._parse_gate(
            "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CDN_OR_READER_UNAVAILABLE",
            2, plan,
        ), ("INCONCLUSIVE", "CDN_OR_READER_UNAVAILABLE"))


if __name__ == "__main__":
    unittest.main()
