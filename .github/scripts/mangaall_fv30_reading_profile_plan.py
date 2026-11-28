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


def verified_plan(rows, corpus_digest, plan_raw, receipts_raw):
    """Reject incomplete, unreviewed or changed owner proof before any collection."""
    plan, receipts = json.loads(plan_raw), json.loads(receipts_raw)
    assert plan["schemaVersion"] == receipts["schemaVersion"] == 1
    assert plan["scope"] == PLAN_SCOPE
    assert plan["corpusSha256"] == receipts["corpusSha256"] == corpus_digest
    assert receipts["planSha256"] == hashlib.sha256(plan_raw).hexdigest()
    assert [r["index"] for r in plan["works"]] == [r["index"] for r in receipts["works"]] == list(range(1, 31))
    for locked_row, row, receipt in zip(rows, plan["works"], receipts["works"]):
        assert set(row) == {"index", "catalog", "editionReview"}
        assert row["catalog"] in SCOPES and receipt["catalog"] == row["catalog"]
        review = row["editionReview"]
        assert set(review) == {"status", "evidence"}
        assert review["status"] == "VERIFIED"
        assert isinstance(review["evidence"], list) and review["evidence"]
        assert all(isinstance(url, str) and url.startswith("https://") for url in review["evidence"])
        assert receipt["status"] == "CATALOG_IDENTITY_ATTESTED"
        assert re.fullmatch(r"[0-9a-f]{64}", receipt["identityDigest"])
        assert locked_row["index"] == row["index"]
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
    plan = dict(schemaVersion=1, scope=PLAN_SCOPE, corpusSha256=digest,
                works=[dict(index=r["index"], catalog=r["catalog"],
                            editionReview=dict(status="VERIFIED", evidence=["https://example.invalid/fixture"]))
                       for r in rows])
    raw = json.dumps(plan).encode()
    receipts = dict(schemaVersion=1, corpusSha256=digest, planSha256=hashlib.sha256(raw).hexdigest(),
                    works=[dict(index=r["index"], catalog=r["catalog"], status="CATALOG_IDENTITY_ATTESTED",
                                identityDigest=hashlib.sha256(str(r["index"]).encode()).hexdigest()) for r in rows])
    assert len(verified_plan(rows, digest, raw, json.dumps(receipts).encode())) == 30
    def rejected(bad_plan, bad_receipts):
        try:
            verified_plan(rows, digest, bad_plan, json.dumps(bad_receipts).encode())
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
    plan["works"][0]["editionReview"]["status"] = "PENDING"
    invalid = json.dumps(plan).encode()
    rejected(invalid, dict(receipts, planSha256=hashlib.sha256(invalid).hexdigest()))
    print("FV30_PROFILE|geometry=PASS|editionReceiptGuard=PASS|live=NOT_RUN")


if __name__ == "__main__":
    selftest()
