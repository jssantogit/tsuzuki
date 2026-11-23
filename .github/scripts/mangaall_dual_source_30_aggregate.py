#!/usr/bin/env python3
"""Fail-closed 30-work source diagnostics, never signed/canonical approval.

Three GitHub-hosted shards use exactly the same two original adapters and V1
MangaALL router. This aggregator cannot fabricate or promote reader chapters.
"""
import argparse
import hashlib
import json
import os
import unicodedata
from pathlib import Path

ACK = "I_ACCEPT_MANGAALL_TWO_SOURCE_30_LIVE"
ORIGIN_SHA = "195502f981bd776a68feebde3efc6d20f55cca65"
SOURCE_IDS = {"mangadex", "mangadot"}
KIND = "MANGAALL_TWO_SOURCE_UNSIGNED_CI_PREVIEW"
SINGLE = 10
VALID_AUDITS = {
    "COMPLETE", "COMPLETE_EMPTY", "SOURCE_ERROR", "INVALID_PAGE_CONTRACT",
    "INVALID_CHAPTER_ID_OR_LANGUAGE", "DUPLICATE_CHAPTER_ID", "CURSOR_LOOP",
    "PARTIAL_PAGE_LIMIT", "NOT_PROBED",
}


def title_id(title):
    normalized = " ".join(title.strip().split()).lower()
    return "user-" + hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:12]


def normalize_source_title(value):
    if not isinstance(value, str):
        return ""
    return " ".join(unicodedata.normalize("NFKC", value).lower().split())


def source_identity_signals(query_title, candidates):
    # A source-only translated title/alias is not a verified work identity.
    # Distinguish a same-primary-title creator conflict from an alias or
    # different providers listing the same work. Never pick a canonical HID.
    requested = normalize_source_title(query_title)
    matched = [c for c in candidates if c.get("exactMatch") is True]
    primary = [c for c in matched
               if normalize_source_title(c.get("title")) == requested]
    alias_only = [c for c in matched
                  if normalize_source_title(c.get("title")) != requested]
    creator_sets = set()
    for candidate in primary:
        authors = candidate.get("authors")
        if not isinstance(authors, list):
            continue
        creators = tuple(sorted({
            normalize_source_title(author) for author in authors
            if normalize_source_title(author)
        }))
        if creators:
            creator_sets.add(creators)
    return {
        "primaryExactMatches": len(primary),
        "aliasOnlyMatches": len(alias_only),
        "primaryTitleCreatorConflict": len(creator_sets) > 1,
        "canonicalIdentity": "NOT_VERIFIED",
    }


def verify_works(request):
    if not isinstance(request, dict) or set(request) != {"acknowledge", "works"}:
        raise ValueError("WORK_REQUEST_SHAPE")
    if request["acknowledge"] != ACK:
        raise ValueError("CONSENT_NOT_VERIFIED")
    works = request["works"]
    if not isinstance(works, list) or len(works) != 30 or any(
        not isinstance(title, str) or not title.strip() or len(title) > 180
        for title in works
    ) or len({title_id(title) for title in works}) != 30:
        raise ValueError("INVALID_30_WORK_VECTOR")
    return works


def require(test, reason):
    if not test:
        raise ValueError(reason)


def check_shard(payload, index, works):
    require(isinstance(payload, dict) and
            payload.get("schema") == 1 and payload.get("kind") == KIND,
            "PROVENANCE_MISSING")
    require(payload.get("hostRepository") == "jssantogit/tsuzuki" and
            payload.get("sourceRepositoryCommit") == ORIGIN_SHA,
            "SOURCE_COMMIT_MISMATCH")
    require(payload.get("originalCode10SignedPackageChanged") is False and
            payload.get("adapterRouterModification") == "SOURCE_IDS_ONLY_IN_MEMORY" and
            payload.get("chapterAdaptation") == "MANGADOT_IN_MEMORY_48K_200_NO_RELEASE" and
            payload.get("lookupAdaptation") == "MANGADOT_THIRTYTWO_SOURCE_PAGES_IN_MEMORY" and
            payload.get("probeBudget") == "UNSIGNED_LOOKUP32_NOT_HOST" and
            payload.get("acquisitionAdaptation") == "UNSIGNED_DOT8M_DEX50_CHAPTERS_CI_ONLY" and
            payload.get("sourceSet") == ["mangadex", "mangadot"],
            "SOURCE_IDENTITY_MISMATCH")
    require(payload.get("shard") == index and payload.get("shardCount") == 3 and
            payload.get("totalWorkCount") == 30 and payload.get("sourceRequestBudget") == 320,
            "SHARD_IDENTITY_MISMATCH")
    require(payload.get("sourceHttp") == "REAL_GITHUB_ACTIONS_FETCH" and
            payload.get("canonical") == "NOT_TESTED" and
            payload.get("readerPixels") == "NOT_TESTED" and
            payload.get("prohibitedThirdSourceRequests") == 0,
            "SOURCE_PROVENANCE_INVALID")
    total = payload.get("sourceRequests")
    require(isinstance(total, dict) and set(total) == SOURCE_IDS and all(
        type(x) is int and 0 <= x <= 320 for x in total.values()
    ), "SOURCE_BUDGET_INVALID")
    rows = payload.get("results")
    require(isinstance(rows, list) and len(rows) == SINGLE,
            "INCOMPLETE_SHARD")
    expected = list(range(index, 30, 3))
    require([row.get("position") for row in rows if isinstance(row, dict)] == expected,
            "ORIGINAL_ORDER_LOST")
    for row, position in zip(rows, expected):
        require(row.get("id") == title_id(works[position]) and
                row.get("title") == works[position], "WORK_IDENTITY_MISMATCH")
        require(row.get("canonicalBinding") == "NOT_AUTHORIZED" and
                row.get("readerPixels") == "NOT_TESTED",
                "UNAUTHORIZED_CANONICAL_PROMOTION")
        http = row.get("httpBySource")
        require(isinstance(http, dict) and set(http) == SOURCE_IDS,
                "SOURCE_HTTP_MISSING")
        fields = ("requests", "http200", "http403", "http429", "otherHttp", "transportError")
        for data in http.values():
            require(isinstance(data, dict) and
                    type(data.get("maxUpstreamBodyBytes")) is int and
                    0 <= data["maxUpstreamBodyBytes"] <= 8 * 1024 * 1024 and
                    type(data.get("upstreamOver192KiB")) is int and
                    0 <= data["upstreamOver192KiB"] <= 320 and all(
                type(data.get(f)) is int and 0 <= data[f] <= 320 for f in fields
            ) and data["requests"] == sum(data[f] for f in fields[1:]),
                "SOURCE_HTTP_COUNTERS_INVALID")
        require(type(row.get("candidateCount")) is int and
                0 <= row["candidateCount"] <= 1_000_000 and
                type(row.get("lookupPages")) is int and 0 <= row["lookupPages"] <= 96,
                "LOOKUP_BUDGET_INVALID")
        candidates = row.get("candidates")
        require(isinstance(candidates, list) and len(candidates) <= 24,
                "CANDIDATES_UNBOUNDED")
        for candidate in candidates:
            require(isinstance(candidate, dict) and
                    candidate.get("source") in SOURCE_IDS and
                    isinstance(candidate.get("workId"), str) and
                    candidate["workId"].startswith(candidate["source"] + ":"),
                    "FOREIGN_CANDIDATE")
        require(row.get("granularChapterPagination") == "UNSIGNED_CI_EPHEMERAL_CACHE_48K_200",
                "GRANULAR_OUTPUT_PROVENANCE_MISSING")
        audits = row.get("sourceChapterAudits")
        require(isinstance(audits, list) and len(audits) <= 4,
                "CHAPTER_AUDIT_UNBOUNDED")
        for audit in audits:
            require(isinstance(audit, dict) and
                    audit.get("source") in SOURCE_IDS and
                    audit.get("authority") == "SOURCE_HID_DIAGNOSTIC_ONLY" and
                    audit.get("outcome") in VALID_AUDITS and
                    type(audit.get("pages")) is int and 0 <= audit["pages"] <= 32 and
                    type(audit.get("chapterCount")) is int and
                    0 <= audit["chapterCount"] <= 16000 * 32 and
                    type(audit.get("maxPageBytes")) is int and
                    0 <= audit["maxPageBytes"] <= 3 * 1024 * 1024,
                    "INVALID_CHAPTER_AUDIT")
            require(audit.get("outcome") not in ("COMPLETE", "COMPLETE_EMPTY") or
                    audit.get("terminal") is True, "FAKE_TERMINAL_CURSOR")
            require("chapterIds" not in audit and "chapterUrl" not in audit,
                    "RAW_CHAPTER_DATA_FORBIDDEN")
    return rows


def aggregate(works, payloads):
    require(isinstance(payloads, list) and len(payloads) == 3, "EXACT_THREE_SHARDS_REQUIRED")
    records = [row for shard, p in enumerate(payloads)
               for row in check_shard(p, shard, works)]
    require(len(records) == 30 and
            sorted(row["position"] for row in records) == list(range(30)),
            "MISSING_OR_DUPLICATED_WORK")
    records.sort(key=lambda row: row["position"])
    output = {
        "schema": 1, "mode": "MANGAALL_DUAL_SOURCE_30_CI",
        "sourceEvidence": "REAL_PUBLIC_API_ON_GITHUB_ACTIONS",
        "sources": ["MANGADEX", "MANGADOT"],
        "mangaFire": "EXCLUDED_NOT_TESTED",
        "signedCode10": "UNMODIFIED_NOT_THIS_PREVIEW",
        "canonical": "NOT_TESTED",
        "readerPixels": "NOT_TESTED",
        "workCount": 30, "shards": 3,
        "counts": {
            "completeLookup": 0, "incompleteLookup": 0,
            "noCandidate": 0, "ambiguous": 0, "sameSourceRivals": 0,
            "authorDisagreements": 0, "completeChapterAudits": 0,
            "partialChapterAudits": 0, "oversized64KiB": 0,
            "missingChapterOne": 0, "invalidSourceAudits": 0,
            "http403": 0, "http429": 0, "transportFailures": 0,
            "upstreamOver192KiB": 0, "maxUpstreamMangaDotBytes": 0,
            "crossSourceCandidates": 0,
            "primaryExactMatchCount": 0, "aliasOnlyMatchCount": 0,
            "primaryTitleAuthorConflicts": 0, "aliasOnlyWorkCount": 0,
        },
        "works": [],
    }
    counts = output["counts"]
    for row in records:
        counts["completeLookup" if row.get("lookupComplete") else "incompleteLookup"] += 1
        counts["noCandidate"] += int(row["candidateCount"] == 0)
        counts["crossSourceCandidates"] += int(len({
            x["source"] for x in row["candidates"] if x.get("exactMatch") is True
        }) > 1)
        counts["ambiguous"] += int(bool(row.get("unresolvedCandidates")))
        counts["sameSourceRivals"] += int(bool(row.get("sameSourceRivals")))
        counts["authorDisagreements"] += int(bool(row.get("sourceAuthorDisagreement")))
        identity = source_identity_signals(row["title"], row["candidates"])
        counts["primaryExactMatchCount"] += identity["primaryExactMatches"]
        counts["aliasOnlyMatchCount"] += identity["aliasOnlyMatches"]
        counts["aliasOnlyWorkCount"] += int(identity["aliasOnlyMatches"] > 0)
        counts["primaryTitleAuthorConflicts"] += int(identity["primaryTitleCreatorConflict"])
        issues = []
        if identity["primaryTitleCreatorConflict"]:
            issues.append("PRIMARY_TITLE_AUTHOR_CONFLICT")
        if not row.get("lookupComplete"): issues.append("LOOKUP_INCOMPLETE")
        if row["candidateCount"] == 0: issues.append("NO_SOURCE_CANDIDATE")
        if row.get("candidateSummariesTruncated"): issues.append("CANDIDATES_TRUNCATED")
        for source, http in row["httpBySource"].items():
            counts["http403"] += http["http403"]
            counts["http429"] += http["http429"]
            counts["transportFailures"] += http["transportError"]
            counts["upstreamOver192KiB"] += http["upstreamOver192KiB"]
            if source == "mangadot":
                counts["maxUpstreamMangaDotBytes"] = max(
                    counts["maxUpstreamMangaDotBytes"], http["maxUpstreamBodyBytes"]
                )
            if http["upstreamOver192KiB"]:
                issues.append(source.upper() + "_UPSTREAM_BODY_EXCEEDS_HOST_192K")
            if http["http403"] or http["http429"] or http["transportError"] or http["otherHttp"]:
                issues.append(source.upper() + "_HTTP_OR_TRANSPORT")
        for audit in row["sourceChapterAudits"]:
            if audit["outcome"] == "COMPLETE":
                counts["completeChapterAudits"] += 1
                if not audit.get("chapterOne"):
                    counts["missingChapterOne"] += 1
                    issues.append(audit["source"].upper() + "_CHAPTER_ONE_MISSING")
            else:
                counts["partialChapterAudits"] += 1
                if audit["outcome"] not in ("NOT_PROBED", "COMPLETE_EMPTY"):
                    counts["invalidSourceAudits"] += 1
                    issues.append(audit["source"].upper() + "_" + audit["outcome"])
            if audit.get("host64KiBExceeded"):
                counts["oversized64KiB"] += 1
                issues.append(audit["source"].upper() + "_HOST_64K_RESULT")
        if row.get("auditCandidatesTruncated"):
            issues.append("SOURCE_RIVAL_AUDIT_TRUNCATED")
        output["works"].append({
            "position": row["position"] + 1, "title": row["title"],
            "lookupComplete": bool(row.get("lookupComplete")),
            "candidates": row["candidateCount"],
            "ambiguous": bool(row.get("unresolvedCandidates")),
            "sameSourceRivals": bool(row.get("sameSourceRivals")),
            "identitySignals": identity,
            "chapterAudits": row["sourceChapterAudits"],
            "issues": sorted(set(issues)),
        })
    output["outcome"] = "NEEDS_CORRECTION" if any(
        row["issues"] for row in output["works"]
    ) else "SOURCE_AUDIT_OBSERVED_NOT_CANONICAL"
    return output


def fixture(works):
    payloads = []
    for shard in range(3):
        rows = []
        for i in range(shard, 30, 3):
            rows.append({
                "position": i, "id": title_id(works[i]), "title": works[i],
                "lookupComplete": True, "lookupPages": 1, "candidateCount": 1,
                "candidates": [{"source": "mangadex", "workId": "mangadex:valid"}],
                "granularChapterPagination": "UNSIGNED_CI_EPHEMERAL_CACHE_48K_200",
                "sourceChapterAudits": [{
                    "source": "mangadex", "authority": "SOURCE_HID_DIAGNOSTIC_ONLY",
                    "outcome": "COMPLETE", "pages": 1, "chapterCount": 2,
                    "terminal": True, "chapterOne": True, "maxPageBytes": 128,
                    "host64KiBExceeded": False,
                }], "canonicalBinding": "NOT_AUTHORIZED",
                "readerPixels": "NOT_TESTED", "httpBySource": {
                    source: {"requests": 1, "http200": 1, "http403": 0,
                             "http429": 0, "otherHttp": 0, "transportError": 0,
                             "maxUpstreamBodyBytes": 100, "upstreamOver192KiB": 0}
                    for source in SOURCE_IDS
                },
            })
        payloads.append({
            "schema": 1, "kind": KIND, "hostRepository": "jssantogit/tsuzuki",
            "sourceRepositoryCommit": ORIGIN_SHA, "originalCode10SignedPackageChanged": False,
            "adapterRouterModification": "SOURCE_IDS_ONLY_IN_MEMORY",
            "chapterAdaptation": "MANGADOT_IN_MEMORY_48K_200_NO_RELEASE",
            "lookupAdaptation": "MANGADOT_THIRTYTWO_SOURCE_PAGES_IN_MEMORY",
            "probeBudget": "UNSIGNED_LOOKUP32_NOT_HOST",
            "acquisitionAdaptation": "UNSIGNED_DOT8M_DEX50_CHAPTERS_CI_ONLY",
            "sourceSet": ["mangadex", "mangadot"], "shard": shard,
            "shardCount": 3, "totalWorkCount": 30, "sourceRequestBudget": 320,
            "sourceHttp": "REAL_GITHUB_ACTIONS_FETCH", "canonical": "NOT_TESTED",
            "readerPixels": "NOT_TESTED", "prohibitedThirdSourceRequests": 0,
            "sourceRequests": {"mangadex": 1, "mangadot": 1},
            "results": rows,
        })
    return payloads


def test_self():
    import copy
    works = ["Offline Manga " + str(i) for i in range(30)]
    data = fixture(works)
    assert aggregate(works, data)["outcome"] == "SOURCE_AUDIT_OBSERVED_NOT_CANONICAL"
    collision = copy.deepcopy(data)
    collision_row = collision[0]["results"][0]
    collision_row["candidateCount"] = 3
    collision_row["candidates"] = [
        {"source": "mangadex", "workId": "mangadex:first",
         "title": works[0], "authors": ["Creator Alpha"], "exactMatch": True},
        {"source": "mangadex", "workId": "mangadex:second",
         "title": works[0], "authors": ["Creator Beta"], "exactMatch": True},
        {"source": "mangadot", "workId": "mangadot:112",
         "title": "Unrelated Work Sharing Alias", "authors": [],
         "exactMatch": True},
    ]
    conflict_report = aggregate(works, collision)
    assert conflict_report["counts"]["primaryTitleAuthorConflicts"] == 1
    assert conflict_report["counts"]["aliasOnlyMatchCount"] == 1
    assert conflict_report["works"][0]["identitySignals"] == {
        "primaryExactMatches": 2, "aliasOnlyMatches": 1,
        "primaryTitleCreatorConflict": True,
        "canonicalIdentity": "NOT_VERIFIED",
    }
    assert conflict_report["outcome"] == "NEEDS_CORRECTION"
    assert "PRIMARY_TITLE_AUTHOR_CONFLICT" in conflict_report["works"][0]["issues"]

    cases = [
        ("FOREIGN_SOURCE", lambda p: p[0].update(sourceSet=["mangadex", "mangafire"])),
        ("FAKE_LARGE_INPUT_PROOF", lambda p: p[0].update(acquisitionAdaptation="SIGNED_HOST_8M")),
        ("FALSE_HOST_LOOKUP_BUDGET", lambda p: p[0].update(probeBudget="HOST_LOOKUP32_VERIFIED")),

        ("FALSE_CANONICAL", lambda p: p[0]["results"][0].update(canonicalBinding="READY")),
        ("FORGED_CURSOR", lambda p: p[0]["results"][0]["sourceChapterAudits"][0].update(terminal=False)),
        ("DUPLICATE_WORK", lambda p: p[1]["results"][0].update(position=0)),
        ("FAKE_SOURCE_COMMIT", lambda p: p[2].update(sourceRepositoryCommit="HEAD")),
        ("FIRE_REQUEST", lambda p: p[0].update(prohibitedThirdSourceRequests=1)),
        ("BAD_HTTP", lambda p: p[0]["results"][0]["httpBySource"]["mangadex"].update(http403=1)),
        ("EXCESSIVE_PAGES", lambda p: p[2]["results"][0]["sourceChapterAudits"][0].update(pages=33)),
    ]
    for name, mutate in cases:
        trial = copy.deepcopy(data)
        mutate(trial)
        try:
            aggregate(works, trial)
        except ValueError:
            continue
        raise AssertionError("Mutation accepted: " + name)
    print(f"MANGAALL_DUAL30_CONTRACT|outcome=PASS|negativeCases={len(cases)}|network=NOT_STARTED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--input-dir", default=".github/results/mangaall-dual-source-30")
    parser.add_argument("--output", default=".github/results/mangaall-dual-source-30/aggregate.json")
    args = parser.parse_args()
    if args.self_test:
        test_self()
        return 0
    try:
        works = verify_works(json.loads(Path(
            ".github/mangaall-dual-source-30-request.json"
        ).read_text(encoding="utf-8")))
        root = Path(args.input_dir)
        paths = [root / f"shard-{i}.json" for i in range(3)]
        if any(not x.is_file() or x.stat().st_size > 5_000_000 for x in paths):
            raise ValueError("SHARD_INPUT_MISSING_OR_UNBOUNDED")
        data = [json.loads(p.read_text(encoding="utf-8")) for p in paths]
        report = aggregate(works, data)
        dest = Path(args.output)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8")
        counts = report["counts"]
        print("MANGAALL_DUAL30_GATE|outcome=" + report["outcome"] +
              "|works=30|sources=2|completeLookup=" + str(counts["completeLookup"]) +
              "|ambiguous=" + str(counts["ambiguous"]) +
              "|completeChapterAudits=" + str(counts["completeChapterAudits"]) +
              "|oversized64K=" + str(counts["oversized64KiB"]) +
              "|canonical=NOT_TESTED|mangafire=EXCLUDED")
        if os.getenv("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
                f.write("## MangaALL 30-work two-source diagnostic (NOT signed release)\n\n")
                f.write("| Metric | Observation |\n|---|---:|\n")
                for key, value in counts.items():
                    f.write(f"| {key} | {value} |\n")
                f.write("\n**Canonical and Reader approval: NOT TESTED**. ")
                f.write("MangaFire excluded; source assets immutable, router adapted only in memory.\n")
        return 0 if report["outcome"] != "NEEDS_CORRECTION" else 3
    except (ValueError, json.JSONDecodeError, OSError) as err:
        reason = str(err).split(":", 1)[0].upper()[:70]
        print("MANGAALL_DUAL30_GATE|outcome=HARD_STOP|reason=" +
              "".join(c for c in reason if c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ_"))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
