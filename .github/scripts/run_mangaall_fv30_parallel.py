#!/usr/bin/env python3
"""Parallel mixed-corpus calibration; original FV30 runner remains unchanged."""
import argparse
import hashlib
import json
import re
from pathlib import Path

import run_mangaall_fv30_first_pass as core

LOCK = Path(".github/mangaall-fv30-parallel-cohort-lock.json")
LOCK_BLOB = "46abb005fcd96940a05ea4221d7f718926d6990f"
BASELINE = "c17347072ff8d4445cdbc905173289c0cd0c760b"
KEYS = ("fv30b", "fv30c")


def title_key(title):
    return re.sub(r"[^\w]+", "", title.casefold())


def locked():
    raw = LOCK.read_bytes()
    assert hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == LOCK_BLOB
    doc = json.loads(raw)
    assert doc["schemaVersion"] == 1 and doc["baselineSha"] == BASELINE
    assert doc["purpose"] == "PARALLEL_CALIBRATION_FIRST_PASS"
    assert doc["oracleStatus"] == "EDITION_AND_CREDIT_REVIEW_PENDING"
    assert tuple(c["key"] for c in doc["cohorts"]) == KEYS
    prior, _ = core.locked()
    historical = {title_key(r["title"]) for r in prior}
    for path in (".github/mangaall-dual-source-30-cohort-lock.json",
                 ".github/mangaall-japanese-manga-30-cohort-lock.json"):
        historical.update(title_key(t) for t in json.loads(Path(path).read_text())["works"])
    profiles = {"manga": ("ja", "shikimori"), "manhwa": ("ko", "mangaupdates"),
                "manhua": ("zh", "mangaupdates")}
    result = {}
    for cohort in doc["cohorts"]:
        key, rows = cohort["key"], cohort["works"]
        assert cohort["cohort"] == key.upper() + "_FIXED_20261128"
        assert [r["index"] for r in rows] == list(range(1, 31))
        assert all(set(r) == {"index", "title", "format", "originalLanguage", "catalog"} for r in rows)
        assert all(profiles[r["format"]] == (r["originalLanguage"], r["catalog"]) for r in rows)
        assert [sum(r["format"] == f for r in rows) for f in profiles] == [24, 4, 2]
        names = {title_key(r["title"]) for r in rows}
        assert len(names) == 30 and not names.intersection(historical)
        historical.update(names)
        # Different cohort hashes even if receipts are accidentally mixed.
        digest = hashlib.sha256(json.dumps(cohort, sort_keys=True, ensure_ascii=False,
                                           separators=(",", ":")).encode()).hexdigest()
        result[key] = rows, digest
    assert len({v[1] for v in result.values()}) == 2
    return result


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cohort", choices=KEYS)
    modes = p.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check-lock", action="store_true")
    modes.add_argument("--shard", type=int, choices=range(3))
    modes.add_argument("--aggregate", action="store_true")
    args = p.parse_args()
    cohorts = locked()
    if args.check_lock:
        for key, (rows, digest) in cohorts.items():
            core.selftest(rows)
            print(f"FV30_PARALLEL|cohort={key}|works=30|hash={digest}|hints=NONE|oracle=PENDING")
        return True
    if args.cohort is None:
        p.error("--cohort required for execution or aggregation")
    rows, digest = cohorts[args.cohort]
    core.OUT = Path("fv30-parallel-results") / args.cohort
    print(f"FV30_PARALLEL|cohort={args.cohort}|baseline={BASELINE}|scope=CATALOG_FIRST_PASS_ONLY")
    if args.aggregate:
        ok = core.aggregate(rows, digest)
        path = core.OUT / "first-pass-summary.json"
        data = json.loads(path.read_text())
        data.update(cohort=args.cohort, functionalBaselineSha=BASELINE,
                    purpose="PARALLEL_CALIBRATION_FIRST_PASS")
        path.write_text(json.dumps(data, indent=2) + "\n")
        return ok
    return core.run_shard(rows, digest, args.shard)


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
