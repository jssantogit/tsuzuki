#!/usr/bin/env python3
"""Route an explicit OWNER issue comment to the *existing* manual code9 smoke.

This is a safe bridge for ChatGPT's GitHub issue-comment integration, which
can write issue comments but has no native workflow_dispatch action.

No commits, PR comments, user mentions, old comments, timer, or test failures
can launch a real source query. This script never contacts the network.
"""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

from mangaall_code9_canonical_smoke_plan import ACK, parse_plan

REPOSITORY = "jssantogit/tsuzuki"
OWNER = "jssantogit"
ISSUE_NUMBER = 230
ISSUE_TITLE = "MangaAll — controle de smoke canônico sob demanda (3 ou 10–30 obras)"
WORKFLOW = "mangaall-code9-canonical-smoke.yml"
SELFTEST_WORKFLOW = "mangaall-code9-on-demand-selftest.yml"
SELFTEST_COMMAND = "/mangaall-selftest-dispatch"
READER_WORKFLOW = "mangaall-code9-live-reader-android.yml"
READER_ACK = "I_ACCEPT_SIGNED_CODE9_LIVE_READER"
READER_COMMAND = re.compile(r"/mangaall-reader (transport|reader)\Z")
READER_WORK_ID = re.compile(r"(?:mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}\Z")
READER_CHAPTER_ID = re.compile(r"[A-Za-z0-9._:-]{1,160}\Z")
MAX_COMMENT_BYTES = 8300
COMMAND = re.compile(r"/mangaall-(smoke|plan) (isolated|scale)\Z")


def parse_comment_event(event, run_attempt=1):
    """Fail closed without mutating anything until *all* checks pass."""
    if not isinstance(event, dict) or type(run_attempt) is not int or run_attempt != 1:
        raise ValueError("EVENT_OR_RERUN_DENIED")
    repository = event.get("repository")
    issue = event.get("issue")
    comment = event.get("comment")
    sender = event.get("sender")
    if (event.get("action") != "created" or not isinstance(repository, dict) or
            repository.get("full_name") != REPOSITORY or
            not isinstance(issue, dict) or issue.get("number") != ISSUE_NUMBER or
            issue.get("title") != ISSUE_TITLE or issue.get("state") != "open" or
            "pull_request" in issue or
            not isinstance(comment, dict) or
            not isinstance(sender, dict) or
            not isinstance(comment.get("user"), dict) or
            comment["user"].get("login") != OWNER or
            sender.get("login") != OWNER or
            comment.get("author_association") != "OWNER" or
            type(comment.get("id")) is not int or comment["id"] < 1):
        raise ValueError("UNTRUSTED_COMMENT_OR_ISSUE")
    body = comment.get("body")
    if not isinstance(body, str) or len(body.encode("utf-8")) > MAX_COMMENT_BYTES:
        raise ValueError("INVALID_COMMENT_SIZE")
    if body == SELFTEST_COMMAND:
        return {
            "mode": "selftest", "tier": "none", "works": "", "count": 0,
            "case_ids": (), "comment_id": comment["id"],
            "fingerprint": hashlib.sha256(body.encode("utf-8")).hexdigest()[:16],
        }
    lines = body.splitlines()
    if not lines:
        raise ValueError("COMMAND_MISSING")
    reader_mode = READER_COMMAND.fullmatch(lines[0])
    if reader_mode:
        if (len(lines) != 4 or not lines[1].startswith("work :: ") or
                not lines[2].startswith("chapter :: ") or lines[3] != "language :: en"):
            raise ValueError("INVALID_READER_VECTOR")
        work_id = lines[1][len("work :: "):]
        chapter_id = lines[2][len("chapter :: "):]
        if (not READER_WORK_ID.fullmatch(work_id) or
                not READER_CHAPTER_ID.fullmatch(chapter_id) or
                chapter_id.split(":", 1)[0] != work_id.split(":", 1)[0]):
            raise ValueError("INVALID_READER_IDENTITY")
        return {
            "mode": "reader", "tier": "none", "works": "", "count": 1,
            "case_ids": (), "reader_mode": reader_mode.group(1),
            "work_id": work_id, "chapter_id": chapter_id, "language": "en",
            "comment_id": comment["id"],
            "fingerprint": hashlib.sha256(body.encode("utf-8")).hexdigest()[:16],
        }
    match = COMMAND.fullmatch(lines[0])
    if not match:
        raise ValueError("COMMAND_UNRECOGNIZED")
    mode, tier = match.groups()
    works = "\n".join(lines[1:])
    rows = parse_plan(works, tier, ACK)
    # Keep the original line order and exact title/format input in the
    # dispatch, not a fabricated or alphabetically sorted catalog.
    return {
        "mode": mode,
        "tier": tier,
        "works": works,
        "count": len(rows),
        "case_ids": tuple(row["caseId"] for row in rows),
        "comment_id": comment["id"],
        "fingerprint": hashlib.sha256((tier + "\n" + works).encode("utf-8")).hexdigest()[:16],
    }


def workflow_dispatch_payload(plan):
    if plan["mode"] == "selftest":
        return {"ref": "main"}
    if plan["mode"] == "reader":
        return {
            "ref": "main",
            "inputs": {
                "work_id": plan["work_id"],
                "chapter_id": plan["chapter_id"],
                "language": "en",
                "mode": plan["reader_mode"],
                "acknowledge": READER_ACK,
            },
        }
    if plan["mode"] != "smoke":
        raise ValueError("PLAN_ONLY_NEVER_DISPATCH")
    return {
        "ref": "main",
        "inputs": {
            "tier": plan["tier"],
            "works": plan["works"],
            "acknowledge": ACK,
        },
    }


def main(argv):
    if len(argv) != 3:
        print("usage: dispatch.py GITHUB_EVENT_PATH OUT_JSON", file=sys.stderr)
        return 2
    try:
        attempt = int(os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
        event = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
        plan = parse_comment_event(event, attempt)
    except (ValueError, KeyError, OSError, UnicodeError, json.JSONDecodeError) as error:
        # Only an allowlisted closed reason can leave the runner; never raw
        # arbitrary comment text, token, title or upstream links.
        reason = str(error)
        reason = reason if re.fullmatch(r"[A-Z0-9_]{1,90}", reason) else "INVALID_VECTOR"
        print("MANGAALL_ON_DEMAND_COMMAND|outcome=DENIED|reason=" + reason, file=sys.stderr)
        return 2
    print(
        "MANGAALL_ON_DEMAND_COMMAND|outcome=ACCEPTED|mode=" + plan["mode"].upper() +
        "|tier=" + plan["tier"].upper() + "|works=" + str(plan["count"]) +
        "|ordered=TRUE|sources=" + ("1" if plan["mode"] == "reader" else "3") +
        "|network=" + ("TO_BE_DISPATCHED" if plan["mode"] in ("smoke", "reader") else "NONE") +
        "|fingerprint=" + plan["fingerprint"]
    )
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write("mode=" + plan["mode"] + "\n")
            stream.write("fingerprint=" + plan["fingerprint"] + "\n")
            stream.write(
                "workflow=" + (SELFTEST_WORKFLOW if plan["mode"] == "selftest" else
                               READER_WORKFLOW if plan["mode"] == "reader" else
                               WORKFLOW if plan["mode"] == "smoke" else "none") + "\n"
            )
    if plan["mode"] in ("smoke", "selftest", "reader"):
        # Only the trusted owner comment may materialize an Actions dispatch.
        Path(argv[2]).write_text(
            json.dumps(workflow_dispatch_payload(plan), ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
