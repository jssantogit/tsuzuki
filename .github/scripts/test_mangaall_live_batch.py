#!/usr/bin/env python3
"""Offline contract tests for the opt-in MangaAll sequential live batch."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mangaall_live_batch as batch


class MangaAllBatchContractTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "mode": "reader",
            "source": "mangafire",
            "language": "en",
            "titles": ["Attack on Titan", "Boku no Hero", "Dragon Ball"],
        }

    def test_ordered_comma_newlines_and_json_array_inputs(self):
        for item in (
            "Attack on Titan, Boku no Hero, Dragon Ball",
            "Attack on Titan\nBoku no Hero\nDragon Ball",
            '["Attack on Titan","Boku no Hero","Dragon Ball"]',
        ):
            with self.subTest(item=item):
                plan = batch.parse_plan({**self.data, "titles": item})
                self.assertEqual(plan.titles, tuple(self.data["titles"]))
                self.assertEqual(plan.source, "mangafire")
                self.assertEqual(plan.mode, "reader")

    def test_duplicates_and_ambiguity_are_not_silently_reordered(self):
        for titles in (
            [],
            ["Dragon Ball", "dragon ball"],
            ["Attack on Titan", "AttAck on Titan"],
            ["X"] * 31,
            ["Attack on Titan", "Demon|Slayer"],
            ["Attack on Titan", "\x00Dragon Ball"],
        ):
            with self.subTest(titles=titles):
                with self.assertRaises(ValueError):
                    batch.parse_plan({**self.data, "titles": titles})

    def test_limits_and_facets(self):
        self.assertEqual(len(batch.parse_plan({
            **self.data, "titles": [f"Manga {i}" for i in range(1, 31)]
        }).titles), 30)
        for values in (
            {**self.data, "source": "unknown"},
            {**self.data, "source": "mangafire", "language": "pt-br"},
            {**self.data, "mode": "unsafe"},
            {**self.data, "token": "never allowed"},
        ):
            with self.assertRaises(ValueError):
                batch.parse_plan(values)

    def test_commit_trigger_reads_only_dedicated_branch_and_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "batch.json"
            path.write_text(json.dumps(self.data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "UNAUTHORIZED_PUSH_BRANCH"):
                batch.read_plan({
                    "GITHUB_EVENT_NAME": "push",
                    "GITHUB_REF": "refs/heads/main",
                }, path)
            selected = batch.read_plan({
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REF": "refs/heads/mangaall-live/batch-aot",
            }, path)
            self.assertEqual(selected.titles[0], "Attack on Titan")
            with self.assertRaisesRegex(ValueError, "EVENT_NOT_ALLOWED"):
                batch.read_plan({"GITHUB_EVENT_NAME": "pull_request"}, path)

    def test_discovery_handoff_checks_source_and_instrumentation_success(self):
        plan = batch.parse_plan(self.data)
        marker = (
            "MANGAALL_BATCH_DISCOVERY|outcome=SELECTED|workId=mangafire:z1r3l"
            "|chapterId=mangafire:chapter-example-1|language=en"
        )
        output = f"INSTRUMENTATION_STATUS: stream={marker}\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
        result = batch._parse_discovery(output, 0, plan)
        self.assertEqual(result[0], "SELECTED")
        self.assertEqual(result[2], ("mangafire:z1r3l", "mangafire:chapter-example-1"))
        self.assertEqual(batch._parse_discovery(output, 1, plan)[0], "FAIL")
        # A println/log message is not an AndroidJUnitRunner status record.
        # Accept only statuses sent via Instrumentation.sendStatus(...).
        without_status = output.replace("INSTRUMENTATION_STATUS: stream=", "")
        self.assertEqual(batch._parse_discovery(without_status, 0, plan)[0], "FAIL")
        absent = (
            "INSTRUMENTATION_STATUS: stream=MANGAALL_BATCH_DISCOVERY|"
            "outcome=INCONCLUSIVE|reason=LOOKUP_UNAVAILABLE"
            "\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
        )
        self.assertEqual(
            batch._parse_discovery(absent, 0, plan)[:2], ("INCONCLUSIVE", "LOOKUP_UNAVAILABLE")
        )
        self.assertEqual(
            batch._parse_discovery(output.replace("mangafire:z1r3l", "mangadot:z1r3l"), 0, plan)[0],
            "FAIL",
        )

    def test_batch_continues_after_inconclusive_and_keeps_ordered_summary(self):
        plan = batch.parse_plan(self.data)
        with tempfile.TemporaryDirectory() as root:
            out = Path(root) / "summary.json"
            with (
                mock.patch.object(batch, "RESULT", out),
                mock.patch.object(batch, "_install_test_apks"),
                mock.patch.object(batch, "_exec") as command,
                mock.patch.object(batch, "_discover") as discover,
                mock.patch.object(batch, "_gate") as gate,
            ):
                command.return_value = mock.Mock(returncode=0, stdout="Success")
                discover.side_effect = [
                    ("SELECTED", "EXACT_SOURCE_CHAPTER_ONE", ("mangafire:abc", "chapter-1")),
                    ("INCONCLUSIVE", "AMBIGUOUS_SOURCE_MATCH", None),
                    ("SELECTED", "EXACT_SOURCE_CHAPTER_ONE", ("mangafire:def", "chapter-1")),
                ]
                gate.side_effect = [("PASS", "READER_CDN_PIXELS"), ("PASS", "READER_CDN_PIXELS")]
                exit_code = batch.execute(plan)
                self.assertEqual(exit_code, 2)
                self.assertEqual([call.args[0] for call in discover.call_args_list], self.data["titles"])
                summary = json.loads(out.read_text(encoding="utf-8"))
                self.assertEqual(summary["completed"], 3)
                self.assertEqual([x["outcome"] for x in summary["cases"]], [
                    "PASS", "INCONCLUSIVE", "PASS",
                ])
                self.assertEqual(summary["inconclusive"], 1)
                self.assertNotIn("mangafire:abc", out.read_text(encoding="utf-8"))

    def test_gate_accepts_only_correct_mode_and_closed_status(self):
        plan = batch.parse_plan(self.data)
        pass_reader = (
            "MANGAALL_LIVE_PAGE_GATE|outcome=PASS|provenance=DIRECT_LIVE_ANDROID"
            "|pagesDecoded=1|readerActivityPixels=REAL_CDN_VISIBLE"
        )
        self.assertEqual(batch._parse_gate(pass_reader, 0, plan), ("PASS", "READER_CDN_PIXELS"))
        self.assertEqual(batch._parse_gate(pass_reader, 2, plan)[0], "FAIL")
        self.assertEqual(
            batch._parse_gate(pass_reader, 0, batch.parse_plan({**self.data, "mode": "transport"}))[0],
            "FAIL",
        )
        self.assertEqual(
            batch._parse_gate(
                "MANGAALL_LIVE_PAGE_GATE|outcome=INCONCLUSIVE|reason=CDN_OR_READER_UNAVAILABLE",
                2, plan,
            ), ("INCONCLUSIVE", "CDN_OR_READER_UNAVAILABLE"),
        )


if __name__ == "__main__":
    unittest.main()
