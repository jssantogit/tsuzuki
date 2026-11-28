#!/usr/bin/env python3
"""FV30 profile geometry; offline until reviewed catalog receipts exist."""
import hashlib
import json
import re
from pathlib import Path

from run_mangaall_fv30_first_pass import locked

PROFILES = ("ja", "ko", "zh")
PLAN_SCOPE = "FV30_VERIFIED_EDITIONS_EXPLICIT_OWNERS"
SCOPES = ("shikimori", "mangaupdates")


def slices(rows):
    return {(shard, lang): tuple(row["index"] for row in rows
                                if (row["index"] - 1) // 10 == shard and row["originalLanguage"] == lang)
            for shard in range(3) for lang in PROFILES
            if any((row["index"] - 1) // 10 == shard and row["originalLanguage"] == lang for row in rows)}


def verified_plan(rows, corpus_digest, plan_raw, receipts_raw, history_raw):
    """Require exact historical candidates and independently reviewed creator evidence."""
    plan, receipts, history = map(json.loads, (plan_raw, receipts_raw, history_raw))
    assert plan["schemaVersion"] == receipts["schemaVersion"] == history["schemaVersion"] == 1
    assert plan["scope"] == PLAN_SCOPE
    assert history["scope"] == "FV30_ACCUMULATED_CATALOG_CANDIDATES_ONLY"
    assert plan["corpusSha256"] == receipts["corpusSha256"] == history["corpusSha256"] == corpus_digest
    assert plan["catalogHistorySha256"] == hashlib.sha256(history_raw).hexdigest()
    assert receipts["planSha256"] == hashlib.sha256(plan_raw).hexdigest()
    assert history["uniqueCandidates"] == 30 and history["pending"] == []
    assert history["duplicateGroups"] == [] and not history["conflicts"]
    assert [r["index"] for r in plan["works"]] == [r["index"] for r in receipts["works"]] == [
        r["index"] for r in history["works"]
    ] == list(range(1, 31))
    for locked_row, row, receipt, entry in zip(rows, plan["works"], receipts["works"], history["works"]):
        assert set(row) == {"index", "catalog", "editionReview"}
        candidate = entry["candidate"]
        assert candidate in entry["observations"]
        assert row["catalog"] in SCOPES and receipt["catalog"] == row["catalog"] == candidate["catalog"]
        assert candidate["index"] == locked_row["index"] == row["index"]
        assert candidate["status"] == receipt["status"] == "CATALOG_IDENTITY_ATTESTED"
        assert candidate["identityDigest"] == receipt["identityDigest"]
        assert re.fullmatch(r"[0-9a-f]{64}", receipt["identityDigest"])
        assert type(candidate["runId"]) is int and candidate["runId"] > 0
        assert receipt["runId"] == candidate["runId"]
        actual = candidate["creatorDigests"]
        assert actual and len(set(actual)) == len(actual)
        assert all(re.fullmatch(r"[0-9a-f]{64}", d) for d in actual)
        assert receipt["creatorDigests"] == actual
        review = row["editionReview"]
        assert set(review) == {"status", "evidence", "requiredCreatorDigests"}
        assert review["status"] == "VERIFIED"
        assert isinstance(review["evidence"], list) and review["evidence"]
        assert all(isinstance(url, str) and url.startswith("https://") for url in review["evidence"])
        groups = review["requiredCreatorDigests"]
        assert isinstance(groups, list) and 1 <= len(groups) <= 8
        assert all(isinstance(g, list) and 1 <= len(g) <= 8 for g in groups)
        flattened = [d for g in groups for d in g]
        assert len(set(flattened)) == len(flattened)
        assert all(re.fullmatch(r"[0-9a-f]{64}", d) for d in flattened)
        assert all(set(g).intersection(actual) for g in groups)
    assert len({r["identityDigest"] for r in receipts["works"]}) == 30
    return {r["index"]: r["catalog"] for r in plan["works"]}


def selftest():
    rows, digest = locked()
    actual = slices(rows)
    assert set(actual) == {(0, "ja"), (0, "ko"), (1, "ja"), (2, "ja"), (2, "ko"), (2, "zh")}
    assert sorted(i for batch in actual.values() for i in batch) == list(range(1, 31))
    assert actual[0, "ko"] == (4,)
    assert actual[2, "ko"] == (26, 27, 28)
    assert actual[2, "zh"] == (29, 30)
    for (shard, lang), indices in actual.items():
        assert all(rows[i - 1]["originalLanguage"] == lang for i in indices)
        print(f"FV30_PROFILE|shard={shard}|language={lang}|works={len(indices)}|live=NOT_RUN")
    # Synthetic fixture only; it is never persisted or staged as evidence.
    credit = hashlib.sha256(b"fixture creator").hexdigest()
    candidates = [dict(index=r["index"], catalog=r["catalog"], status="CATALOG_IDENTITY_ATTESTED",
                       identityDigest=hashlib.sha256(str(r["index"]).encode()).hexdigest(),
                       creatorDigests=[credit], runId=1) for r in rows]
    history = dict(schemaVersion=1, scope="FV30_ACCUMULATED_CATALOG_CANDIDATES_ONLY",
                   corpusSha256=digest, uniqueCandidates=30, pending=[], duplicateGroups=[], conflicts={},
                   works=[dict(index=c["index"], candidate=c, observations=[c]) for c in candidates])
    history_raw = json.dumps(history).encode()
    plan = dict(schemaVersion=1, scope=PLAN_SCOPE, corpusSha256=digest,
                catalogHistorySha256=hashlib.sha256(history_raw).hexdigest(),
                works=[dict(index=r["index"], catalog=r["catalog"],
                            editionReview=dict(status="VERIFIED", evidence=["https://example.invalid/fixture"],
                                               requiredCreatorDigests=[[credit]]))
                       for r in rows])
    raw = json.dumps(plan).encode()
    receipts = dict(schemaVersion=1, corpusSha256=digest, planSha256=hashlib.sha256(raw).hexdigest(),
                    works=candidates)
    assert len(verified_plan(rows, digest, raw, json.dumps(receipts).encode(), history_raw)) == 30
    def rejected(bad_plan, bad_receipts, bad_history=history_raw):
        try:
            verified_plan(rows, digest, bad_plan, json.dumps(bad_receipts).encode(), bad_history)
        except (AssertionError, KeyError, ValueError, TypeError):
            return
        raise AssertionError("unverified or inconsistent plan accepted")
    rejected(raw, dict(receipts, works=receipts["works"][:-1]))
    rejected(raw, dict(receipts, planSha256="0"*64))
    duplicate = dict(receipts, works=[dict(r) for r in receipts["works"]])
    duplicate["works"][1]["identityDigest"] = duplicate["works"][0]["identityDigest"]
    rejected(raw, duplicate)
    wrong_owner = dict(receipts, works=[dict(r) for r in receipts["works"]])
    wrong_owner["works"][0]["catalog"] = "mangaupdates"
    rejected(raw, wrong_owner)
    forged = dict(receipts, works=[dict(r) for r in receipts["works"]])
    forged["works"][0]["identityDigest"] = "a"*64
    rejected(raw, forged)
    wrong_run = dict(receipts, works=[dict(r) for r in receipts["works"]])
    wrong_run["works"][0]["runId"] = 2
    rejected(raw, wrong_run)
    wrong_credit = dict(receipts, works=[dict(r) for r in receipts["works"]])
    wrong_credit["works"][0]["creatorDigests"] = ["a"*64]
    rejected(raw, wrong_credit)
    rejected(raw, receipts, json.dumps(dict(history, uniqueCandidates=26)).encode())
    plan["works"][0]["editionReview"]["requiredCreatorDigests"] = [["a"*64]]
    invalid = json.dumps(plan).encode()
    rejected(invalid, dict(receipts, planSha256=hashlib.sha256(invalid).hexdigest()))
    plan["works"][0]["editionReview"]["requiredCreatorDigests"] = [[credit]]
    plan["works"][0]["editionReview"]["status"] = "PENDING"
    invalid = json.dumps(plan).encode()
    rejected(invalid, dict(receipts, planSha256=hashlib.sha256(invalid).hexdigest()))
    print("FV30_PROFILE|geometry=PASS|editionReceiptGuard=PASS|live=NOT_RUN")


if __name__ == "__main__":
    selftest()
