#!/usr/bin/env python3
"""No-network adversarial tests of the issue-comment command bridge."""
import copy
import importlib.util
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest

FILE = Path(__file__).with_name("mangaall_code9_smoke_comment_dispatch.py")
sys.path.insert(0, str(FILE.parent))
spec = importlib.util.spec_from_file_location("smoke_command", FILE)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

THREE = "/mangaall-smoke isolated\nmanga :: Naruto\nmanga :: Death Note\nmanga :: Bleach"
PLAN = THREE.replace("/mangaall-smoke", "/mangaall-plan", 1)


def fixture(body=THREE):
    return {
        "action": "created",
        "repository": {"full_name": "jssantogit/tsuzuki"},
        "issue": {
            "number": 230,
            "title": smoke.ISSUE_TITLE,
            "state": "open",
        },
        "sender": {"login": "jssantogit"},
        "comment": {
            "id": 987654321,
            "body": body,
            "author_association": "OWNER",
            "user": {"login": "jssantogit"},
        },
    }


class CommentBridgeTest(unittest.TestCase):
    def test_owner_command_executes_via_existing_manual_workflow(self):
        plan = smoke.parse_comment_event(fixture())
        self.assertEqual(plan["mode"], "smoke")
        self.assertEqual(plan["tier"], "isolated")
        self.assertEqual(plan["count"], 3)
        self.assertEqual(len(set(plan["case_ids"])), 3)
        payload = smoke.workflow_dispatch_payload(plan)
        self.assertEqual(payload["ref"], "main")
        self.assertEqual(payload["inputs"]["tier"], "isolated")
        self.assertEqual(
            payload["inputs"]["works"],
            "manga :: Naruto\nmanga :: Death Note\nmanga :: Bleach",
        )
        self.assertEqual(payload["inputs"]["acknowledge"], smoke.ACK)

    def test_plan_only_never_dispatches(self):
        parsed = smoke.parse_comment_event(fixture(PLAN))
        self.assertEqual(parsed["mode"], "plan")
        with self.assertRaises(ValueError):
            smoke.workflow_dispatch_payload(parsed)
        with tempfile.TemporaryDirectory() as td:
            event = Path(td) / "event.json"
            output = Path(td) / "dispatch.json"
            event.write_text(json.dumps(fixture(PLAN)))
            old = os.environ.pop("GITHUB_OUTPUT", None)
            try:
                self.assertEqual(smoke.main(["script", str(event), str(output)]), 0)
                self.assertFalse(output.exists())
            finally:
                if old is not None:
                    os.environ["GITHUB_OUTPUT"] = old

    def test_harmless_selftest_dispatch_proves_actions_permission_without_live_source(self):
        event = fixture("/mangaall-selftest-dispatch")
        parsed = smoke.parse_comment_event(event)
        self.assertEqual(parsed["mode"], "selftest")
        self.assertEqual(parsed["count"], 0)
        self.assertEqual(parsed["works"], "")
        self.assertEqual(smoke.workflow_dispatch_payload(parsed), {"ref": "main"})
        for bad in (
            "/mangaall-selftest-dispatch\nNaruto",
            "Please /mangaall-selftest-dispatch",
            "/mangaall-selftest-dispatch --publish",
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                smoke.parse_comment_event(fixture(bad))
        with tempfile.TemporaryDirectory() as td:
            event_file = Path(td) / "event.json"
            payload_file = Path(td) / "payload.json"
            event_file.write_text(json.dumps(event))
            old = os.environ.pop("GITHUB_OUTPUT", None)
            try:
                self.assertEqual(smoke.main(["script", str(event_file), str(payload_file)]), 0)
                self.assertEqual(json.loads(payload_file.read_text()), {"ref": "main"})
            finally:
                if old is not None:
                    os.environ["GITHUB_OUTPUT"] = old

    def test_only_exact_owner_source_scoped_code9_reader_commands_dispatch(self):
        work = "mangadex:239d6260-d71f-43b0-afff-074e3619e3de"
        chapter = "mangadex:109de166-1a6d-4db2-a591-ebdb41647eea"
        for mode in ("transport", "reader"):
            command = (
                "/mangaall-reader " + mode + "\n" +
                "work :: " + work + "\n" +
                "chapter :: " + chapter + "\n" +
                "language :: en"
            )
            plan = smoke.parse_comment_event(fixture(command))
            self.assertEqual(plan["mode"], "reader")
            self.assertEqual(plan["reader_mode"], mode)
            self.assertEqual(plan["count"], 1)
            self.assertEqual(plan["work_id"], work)
            self.assertEqual(plan["chapter_id"], chapter)
            self.assertEqual(smoke.READER_WORKFLOW, "mangaall-code9-live-reader-android.yml")
            payload = smoke.workflow_dispatch_payload(plan)
            self.assertEqual(payload["ref"], "main")
            self.assertEqual(payload["inputs"], {
                "work_id": work, "chapter_id": chapter, "language": "en",
                "mode": mode, "acknowledge": smoke.READER_ACK,
            })
            with tempfile.TemporaryDirectory() as folder:
                event_file = Path(folder) / "event.json"
                dispatch = Path(folder) / "dispatch.json"
                event_file.write_text(json.dumps(fixture(command)))
                old = os.environ.pop("GITHUB_OUTPUT", None)
                try:
                    self.assertEqual(smoke.main(["script", str(event_file), str(dispatch)]), 0)
                    self.assertEqual(json.loads(dispatch.read_text()), payload)
                finally:
                    if old is not None:
                        os.environ["GITHUB_OUTPUT"] = old
        invalid = (
            "/mangaall-reader",
            "/mangaall-reader scale",
            "/mangaall-reader transport",
            "/mangaall-reader transport\nwork :: " + work +
            "\nchapter :: mangadot:123\nlanguage :: en",
            "/mangaall-reader transport\nwork :: " + work +
            "\nchapter :: " + chapter + "\nlanguage :: pt-br",
            "/mangaall-reader transport\nwork :: " + work +
            "\nchapter :: " + chapter + "\nlanguage :: en\nextra",
            "/mangaall-reader transport\nwork :: " + work +
            "\nchapter :: " + chapter + " ; curl bad.example\nlanguage :: en",
            "please /mangaall-reader reader\nwork :: " + work +
            "\nchapter :: " + chapter + "\nlanguage :: en",
        )
        for command in invalid:
            with self.subTest(command=command[:65]), self.assertRaises(ValueError):
                smoke.parse_comment_event(fixture(command))
        attacker = fixture(
            "/mangaall-reader transport\nwork :: " + work +
            "\nchapter :: " + chapter + "\nlanguage :: en"
        )
        attacker["sender"]["login"] = "not-owner"
        with self.assertRaises(ValueError):
            smoke.parse_comment_event(attacker)
        with self.assertRaises(ValueError):
            smoke.parse_comment_event(fixture(
                "/mangaall-reader transport\nwork :: " + work +
                "\nchapter :: " + chapter + "\nlanguage :: en"
            ), run_attempt=2)

    def test_ten_to_thirty_stay_bounded_and_ordered(self):
        for count in (10, 20, 30):
            titles = "\n".join("manga :: Work " + str(i) for i in range(count))
            plan = smoke.parse_comment_event(fixture("/mangaall-smoke scale\n" + titles))
            self.assertEqual(plan["count"], count)
            self.assertEqual(plan["tier"], "scale")
            self.assertTrue(smoke.workflow_dispatch_payload(plan)["inputs"]["works"].endswith("Work " + str(count - 1)))
        for count in (3, 9, 31):
            titles = "\n".join("manga :: Work " + str(i) for i in range(count))
            with self.assertRaises(ValueError):
                smoke.parse_comment_event(fixture("/mangaall-smoke scale\n" + titles))

    def test_only_owner_open_dedicated_issue_new_comment_attempt_one(self):
        cases = [
            ("repository", {"full_name": "attacker/tsuzuki"}),
            ("action", "edited"),
            ("action", "deleted"),
            ("sender", {"login": "other-person"}),
        ]
        for key, value in cases:
            event = fixture()
            event[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                smoke.parse_comment_event(event)
        for field, value in [
            ("number", 207), ("number", 999),
            ("title", "A duplicate control issue"),
            ("state", "closed"),
        ]:
            event = fixture()
            event["issue"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                smoke.parse_comment_event(event)
        for field, value in [
            ("author_association", "CONTRIBUTOR"),
            ("author_association", "COLLABORATOR"),
            ("user", {"login": "attacker"}),
            ("id", -1),
        ]:
            event = fixture()
            event["comment"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                smoke.parse_comment_event(event)
        event = fixture()
        event["issue"]["pull_request"] = {"url": "https://github.com/irrelevant"}
        with self.assertRaises(ValueError):
            smoke.parse_comment_event(event)
        with self.assertRaises(ValueError):
            smoke.parse_comment_event(fixture(), run_attempt=2)

    def test_command_must_be_exact_and_have_authorized_vector(self):
        for body in [
            "Please run " + THREE,
            "/mangaall-smoke isolated\nNaruto\nNaruto\nBleach",
            "/mangaall-smoke isolated\nNaruto\nBleach",
            "/mangaall-smoke isolated\nNaruto\nDeath Note\nBleach\nOne Piece",
            "/mangaall-smoke scale\nNaruto\nDeath Note\nBleach",
            "/mangaall-smoke isolated\nhttps://malicious.example\nDeath Note\nBleach",
            "/mangaall-smoke isolated\nNaruto\nDeath Note\nBleach\nTOKEN=secret",
            "/mangaall-smoke isolated ; curl example.org\nNaruto\nDeath Note\nBleach",
            "/mangaall-smoke isolated\nNaruto\nDeath Note\nBleach\n" + ("A" * 8400),
            "/mangaall-smoke isolated\nNaruto\nDeath Note\nBleach\n--workflow=publish",
            "/mangaall-selftest-dispatch\nNaruto",
        ]:
            with self.subTest(body=body[:38]), self.assertRaises(ValueError):
                smoke.parse_comment_event(fixture(body))

    def test_workflow_always_on_demand_and_uses_owner_authorization(self):
        wf = FILE.parents[1] / "workflows/mangaall-code9-on-demand-command.yml"
        txt = wf.read_text(encoding="utf-8")
        self.assertIn("  issue_comment:", txt)
        self.assertNotIn("  pull_request:", txt)
        self.assertNotIn("  push:", txt)
        self.assertNotIn("  schedule:", txt)
        self.assertNotIn("  workflow_dispatch:", txt)
        self.assertIn("  actions: write", txt)
        self.assertIn("github.event.issue.number == 230", txt)
        self.assertIn("github.event.comment.user.login == 'jssantogit'", txt)
        self.assertIn("github.event.comment.author_association == 'OWNER'", txt)
        self.assertIn("github.run_attempt == 1", txt)
        self.assertIn("steps.command.outputs.mode == 'smoke'", txt)
        self.assertIn("steps.command.outputs.mode == 'plan'", txt)
        self.assertIn("mangaall-code9-canonical-smoke.yml", txt)
        self.assertIn("mangaall-code9-on-demand-selftest.yml", txt)
        self.assertIn("mangaall-code9-live-reader-android.yml", txt)
        self.assertIn("steps.command.outputs.mode == 'reader'", txt)
        self.assertIn("TARGET_WORKFLOW/dispatches", txt)
        self.assertIn("TARGET_NOT_ALLOWLISTED", txt)
        self.assertNotIn("publish.yml/dispatches", txt)
        self.assertIn("204", txt)

    def test_selftest_workflow_never_uses_source_or_credentials(self):
        wf = FILE.parents[1] / "workflows/mangaall-code9-on-demand-selftest.yml"
        txt = wf.read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", txt)
        self.assertNotIn("  issue_comment:", txt)
        self.assertNotIn("  push:", txt)
        self.assertNotIn("  pull_request:", txt)
        self.assertIn("upstream=NONE", txt)
        self.assertIn("android=NOT_RUN", txt)
        self.assertIn("reader=NOT_RUN", txt)
        self.assertNotIn("Authorization: Bearer", txt)
        self.assertNotIn("actions/checkout", txt)
        self.assertNotIn("api.mangadex.org", txt)
        self.assertNotIn("mangafire.to", txt)

    def test_main_does_not_write_workflow_dispatch_when_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            event = Path(td) / "event.json"
            output = Path(td) / "dispatch.json"
            bad = fixture()
            bad["sender"]["login"] = "untrusted"
            event.write_text(json.dumps(bad))
            self.assertEqual(smoke.main(["script", str(event), str(output)]), 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
