#!/usr/bin/env python3
"""Verify an earlier successful Japanese plan run, not a diagnostic comparison.

A live invocation must explicitly identify the GitHub Actions run. The catalog
and canonical gate must share the identical source tree except for the canonical
workflow opt-in file. Download only the strict plan's sanitized shard verdicts.
"""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from mangaall_research_cohort import load_cohort
from run_mangaall_frozen30_catalog_plan import plan

CATALOG_WORKFLOW = ".github/workflows/mangaall-japanese-manga-30-catalog-plan.yml"
CANONICAL_WORKFLOW = ".github/workflows/mangaall-japanese-manga-30-canonical-persistence.yml"
MARKER = "RUN_JAPANESE30_CANONICAL_LIVE"


def require(condition, code):
    if not condition:
        raise SystemExit(code)


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def run_id_from_message(message):
    matches = re.findall(r"(?<!\S)CATALOG_GATE_RUN=(\d+)(?!\S)", message)
    require(MARKER in message.split() and len(matches) == 1, "CATALOG_GATE_RUN_REQUIRED")
    return matches[0]


def validate_run(run, current_branch):
    require(run.get("path") == CATALOG_WORKFLOW, "CATALOG_GATE_WRONG_WORKFLOW")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "CATALOG_GATE_NOT_SUCCESSFUL")
    require(run.get("event") == "push" and run.get("head_branch") == current_branch,
            "CATALOG_GATE_WRONG_BRANCH")
    sha = run.get("head_sha")
    require(isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{40}", sha), "CATALOG_GATE_INVALID_SHA")
    return sha


def validate_changes(changes):
    require(set(changes) <= {CANONICAL_WORKFLOW}, "CATALOG_GATE_SOURCE_CHANGED")


def verify_verdicts(root, cohort, rows, digest):
    identities = []
    for shard in range(3):
        path = root / f"mangaall-japanese-plan-shard-{shard}" / "verdict.json"
        require(path.is_file(), "CATALOG_GATE_ARTIFACT_MISSING")
        doc = json.loads(path.read_text())
        expected = rows[shard*10:(shard+1)*10]
        found = doc.get("works", [])
        require(doc.get("schemaVersion") == 1 and doc.get("scope") == "EXPLICIT_OWNER_PLAN"
                and doc.get("corpusSha256") == cohort.digest and doc.get("planSha256") == digest
                and doc.get("shard") == shard and doc.get("outcome") == "PASS"
                and doc.get("processesPassed") is True and doc.get("invalidAttestations") == 0,
                "CATALOG_GATE_VERDICT_INVALID")
        require([(r.get("index"), r.get("catalog")) for r in found] ==
                [(r["index"], r["catalog"]) for r in expected], "CATALOG_GATE_WRONG_PLAN")
        for row in found:
            identity = row.get("identityDigest")
            require(row.get("status") == "CATALOG_IDENTITY_ATTESTED"
                    and isinstance(identity, str) and re.fullmatch(r"[0-9a-f]{64}", identity),
                    "CATALOG_GATE_UNATTESTED_WORK")
            identities.append(identity)
    require(len(identities) == len(set(identities)) == 30, "CATALOG_GATE_DUPLICATE_IDENTITY")
    return identities


def write_attested_identities(output, cohort, rows, digest, identities):
    require(len(identities) == 30 and len(set(identities)) == 30, "CATALOG_GATE_DUPLICATE_IDENTITY")
    doc = dict(schemaVersion=1, corpusSha256=cohort.digest, planSha256=digest,
               works=[dict(index=r["index"], catalog=r["catalog"], identityDigest=identity)
                      for r, identity in zip(rows, identities, strict=True)])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(doc, indent=2)+"\n")


def selftest():
    cohort = load_cohort("manga")
    rows, digest = plan(cohort)
    def rejects(action, expected):
        try:
            action()
        except SystemExit as error:
            assert str(error) == expected
        else:
            raise AssertionError("Expected rejection: " + expected)

    assert run_id_from_message(MARKER + " CATALOG_GATE_RUN=123") == "123"
    rejects(lambda: run_id_from_message(MARKER), "CATALOG_GATE_RUN_REQUIRED")
    rejects(lambda: run_id_from_message(MARKER + " CATALOG_GATE_RUN=1 CATALOG_GATE_RUN=2"),
            "CATALOG_GATE_RUN_REQUIRED")
    run = dict(path=CATALOG_WORKFLOW, status="completed", conclusion="success",
               event="push", head_branch="probe", head_sha="a"*40)
    assert validate_run(run, "probe") == "a"*40
    rejects(lambda: validate_run(dict(run, path=CATALOG_WORKFLOW.replace("plan", "probes")), "probe"),
            "CATALOG_GATE_WRONG_WORKFLOW")
    rejects(lambda: validate_run(dict(run, conclusion="failure"), "probe"), "CATALOG_GATE_NOT_SUCCESSFUL")
    validate_changes([CANONICAL_WORKFLOW])
    rejects(lambda: validate_changes(["domain/src/Changed.kt"]), "CATALOG_GATE_SOURCE_CHANGED")
    with TemporaryDirectory() as tmp:
        root = Path(tmp)
        for shard in range(3):
            p = root / f"mangaall-japanese-plan-shard-{shard}"
            p.mkdir()
            found = [dict(index=r["index"], catalog=r["catalog"], status="CATALOG_IDENTITY_ATTESTED",
                          identityDigest=f"{r['index']:064x}") for r in rows[shard*10:(shard+1)*10]]
            doc = dict(schemaVersion=1, scope="EXPLICIT_OWNER_PLAN", corpusSha256=cohort.digest,
                       planSha256=digest, shard=shard, outcome="PASS", processesPassed=True,
                       invalidAttestations=0, works=found)
            (p / "verdict.json").write_text(json.dumps(doc))
        verified = verify_verdicts(root, cohort, rows, digest)
        assert verified == [f"{i:064x}" for i in range(1,31)]
        output = root / "identities.json"
        write_attested_identities(output, cohort, rows, digest, verified)
        emitted = json.loads(output.read_text())
        assert emitted["planSha256"] == digest and emitted["corpusSha256"] == cohort.digest
        assert [r["identityDigest"] for r in emitted["works"]] == verified
        assert [r["catalog"] for r in emitted["works"]] == [r["catalog"] for r in rows]
        path = root / "mangaall-japanese-plan-shard-0" / "verdict.json"
        doc = json.loads(path.read_text())
        doc["works"][0]["identityDigest"] = doc["works"][1]["identityDigest"]
        path.write_text(json.dumps(doc))
        rejects(lambda: verify_verdicts(root, cohort, rows, digest), "CATALOG_GATE_DUPLICATE_IDENTITY")
        doc["planSha256"] = "0"*64
        path.write_text(json.dumps(doc))
        rejects(lambda: verify_verdicts(root, cohort, rows, digest), "CATALOG_GATE_VERDICT_INVALID")
    print("MANGAALL_JAPANESE_CATALOG_GATE|selftest=PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return
    repo = os.environ["GITHUB_REPOSITORY"]
    branch = os.environ["GITHUB_REF_NAME"]
    head = os.environ["GITHUB_SHA"]
    require(command("git", "rev-parse", "HEAD") == head, "CATALOG_GATE_HEAD_MISMATCH")
    run_id = run_id_from_message(command("git", "log", "-1", "--format=%B"))
    run = json.loads(command("gh", "api", f"repos/{repo}/actions/runs/{run_id}"))
    sha = validate_run(run, branch)
    require(subprocess.run(["git", "merge-base", "--is-ancestor", sha, head]).returncode == 0,
            "CATALOG_GATE_NOT_ANCESTOR")
    validate_changes(command("git", "diff", "--name-only", sha, head).splitlines())
    cohort = load_cohort("manga")
    rows, digest = plan(cohort)
    with TemporaryDirectory() as tmp:
        subprocess.run(["gh", "run", "download", run_id, "--repo", repo,
                        "--pattern", "mangaall-japanese-plan-shard-*", "--dir", tmp], check=True)
        identities = verify_verdicts(Path(tmp), cohort, rows, digest)
        if args.output is not None:
            write_attested_identities(args.output, cohort, rows, digest, identities)
    print(f"MANGAALL_JAPANESE_CATALOG_GATE|run={run_id}|attested=30|sameSourceTree=YES|outcome=PASS")


if __name__ == "__main__":
    main()
