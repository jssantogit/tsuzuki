#!/usr/bin/env python3
"""Fail-closed 30-work source diagnostics, never signed/canonical approval.

Three GitHub-hosted shards use exactly the same two original adapters and V1
MangaALL router. This aggregator cannot fabricate or promote reader chapters.
"""
import argparse
import hashlib
import json
import os
import re
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


def normalized_creator_name(value):
    if not isinstance(value, str):
        return ""
    # Japanese romanized credits sometimes swap given/surname order or use
    # accent marks. Token normalization is diagnostic, not authority to bind.
    folded = unicodedata.normalize("NFKD", value).casefold()
    without_marks = "".join(c for c in folded if not unicodedata.combining(c))
    return " ".join(sorted(re.findall(r"[^\W_]+", without_marks, flags=re.UNICODE)))


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
    creator_sets = []
    for candidate in primary:
        authors = candidate.get("authors")
        if not isinstance(authors, list):
            continue
        creators = frozenset(filter(None, (normalized_creator_name(a) for a in authors)))
        if creators:
            creator_sets.append(creators)
    # A list of co-creators on one source and one shared author on another
    # is not evidence of conflicting works. Require two disjoint sets.
    disjoint_creators = any(
        left.isdisjoint(right)
        for i, left in enumerate(creator_sets)
        for right in creator_sets[i + 1:]
    )
    return {
        "primaryExactMatches": len(primary),
        "aliasOnlyMatches": len(alias_only),
        "primaryTitleCreatorConflict": disjoint_creators,
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
            ) and data["requests"] == sum(data[f] for f in fields[1:]) and
            type(data.get("searchPagesObserved", 0)) is int and
            0 <= data.get("searchPagesObserved", 0) <= data["http200"] and
            type(data.get("searchRowsObserved", 0)) is int and
            0 <= data.get("searchRowsObserved", 0) <= 100 * data.get("searchPagesObserved", 0),
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
        closed_lookup_error = row.get("lookupError")
        require(closed_lookup_error is None or (
            isinstance(closed_lookup_error, str) and
            re.fullmatch(r"[A-Z0-9_]{1,45}", closed_lookup_error)
        ), "LOOKUP_ERROR_NOT_CLOSED")
        failures = row.get("sourceLookupFailures", [])
        require(isinstance(failures, list) and len(failures) <= 16 and all(
            isinstance(error, dict) and set(error) == {"source", "reason"} and
            error["source"] in SOURCE_IDS and
            isinstance(error["reason"], str) and
            re.fullmatch(r"[A-Z0-9_]{1,45}", error["reason"])
            for error in failures
        ) and type(row.get("sourceLookupFailuresTruncated", False)) is bool,
                "SOURCE_LOOKUP_FAILURES_NOT_CLOSED")
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
            "chapterOneVerifiedWorks": 0, "noSourceChapterOneWorks": 0,
            "partialLookupWithCompleteChapters": 0,
            "lookupUpstreamErrors": 0,
            "multisourceParallelWithoutSameSourceRivals": 0,
            "sourceLookupFailuresObserved": 0,
            "sourceSearchPagesObserved": 0, "sourceSearchRowsObserved": 0,
            "noCandidateDespiteUpstreamSearchRows": 0,
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
        exact_sources = {
            candidate["source"] for candidate in row["candidates"]
            if candidate.get("exactMatch") is True
        }
        if identity["primaryTitleCreatorConflict"]:
            multiplicity = "PRIMARY_TITLE_CREATOR_CONFLICT"
        elif row.get("sameSourceRivals"):
            multiplicity = "SAME_SOURCE_RIVALS_UNRESOLVED"
        elif len(exact_sources) > 1:
            multiplicity = "MULTISOURCE_PARALLEL_UNRESOLVED"
            counts["multisourceParallelWithoutSameSourceRivals"] += 1
        elif identity["aliasOnlyMatches"]:
            multiplicity = "ALIAS_ONLY_REQUIRES_REVIEW"
        else:
            multiplicity = "NO_OBSERVED_RIVAL"
        counts["lookupUpstreamErrors"] += int(bool(row.get("lookupError")))
        counts["sourceLookupFailuresObserved"] += len(row.get("sourceLookupFailures", []))
        counts["primaryExactMatchCount"] += identity["primaryExactMatches"]
        counts["aliasOnlyMatchCount"] += identity["aliasOnlyMatches"]
        counts["aliasOnlyWorkCount"] += int(identity["aliasOnlyMatches"] > 0)
        counts["primaryTitleAuthorConflicts"] += int(identity["primaryTitleCreatorConflict"])
        issues = []
        if identity["primaryTitleCreatorConflict"]:
            issues.append("PRIMARY_TITLE_AUTHOR_CONFLICT")
        if not row.get("lookupComplete"): issues.append("LOOKUP_INCOMPLETE")
        if row.get("lookupError"):
            issues.append("LOOKUP_ERROR_" + row["lookupError"])
        for source_error in row.get("sourceLookupFailures", []):
            issues.append(source_error["source"].upper() + "_LOOKUP_" + source_error["reason"])
        if row.get("sourceLookupFailuresTruncated"):
            issues.append("SOURCE_LOOKUP_DIAGNOSTICS_TRUNCATED")
        if row["candidateCount"] == 0: issues.append("NO_SOURCE_CANDIDATE")
        source_search_rows = sum(
            x.get("searchRowsObserved", 0) for x in row["httpBySource"].values()
        )
        source_search_pages = sum(
            x.get("searchPagesObserved", 0) for x in row["httpBySource"].values()
        )
        counts["sourceSearchRowsObserved"] += source_search_rows
        counts["sourceSearchPagesObserved"] += source_search_pages
        if row["candidateCount"] == 0 and source_search_rows > 0:
            counts["noCandidateDespiteUpstreamSearchRows"] += 1
            issues.append("UPSTREAM_SEARCH_ROWS_WITHOUT_EXACT_MATCH")
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
        # A complete inventory of one explicitly identified source HID is
        # useful even when title discovery remains bounded/incomplete.
        # It does not grant canonical association or Reader pixel success.
        complete_chapter_one = any(
            audit["outcome"] == "COMPLETE" and
            audit["terminal"] is True and audit.get("chapterOne") is True
            for audit in row["sourceChapterAudits"]
        )
        counts["chapterOneVerifiedWorks"] += int(complete_chapter_one)
        counts["noSourceChapterOneWorks"] += int(not complete_chapter_one)
        counts["partialLookupWithCompleteChapters"] += int(
            complete_chapter_one and not row.get("lookupComplete")
        )
        if not complete_chapter_one:
            issues.append("NO_SOURCE_CHAPTER_ONE_ATTESTED")
        output["works"].append({
            "position": row["position"] + 1, "title": row["title"],
            "lookupComplete": bool(row.get("lookupComplete")),
            "lookupState": row.get("lookup"),
            "lookupError": row.get("lookupError"),
            "sourceLookupFailures": row.get("sourceLookupFailures", []),
            "sourceLookupFailuresTruncated": row.get("sourceLookupFailuresTruncated", False),
            "candidates": row["candidateCount"],
            "sourceSearchRowsObserved": {
                source: http.get("searchRowsObserved", 0)
                for source, http in row["httpBySource"].items()
            },
            "ambiguous": bool(row.get("unresolvedCandidates")),
            "sameSourceRivals": bool(row.get("sameSourceRivals")),
            "candidateMultiplicityClass": multiplicity,
            "identitySignals": identity,
            "atLeastOneCompleteSourceChapterOne": complete_chapter_one,
            "chapterOneAuthority": "SOURCE_HID_ONLY_NOT_CANONICAL",
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
    same_author = copy.deepcopy(collision)
    # Explicit same-source rivals in this fixture; candidate-count alone
    # cannot establish a same-source conflict.
    same_author[0]["results"][0]["sameSourceRivals"] = True
    same_author[0]["results"][0]["candidates"][0]["authors"] = ["Naoki Urasawa"]
    same_author[0]["results"][0]["candidates"][1]["authors"] = ["Urasawa Naoki"]
    same_author_report = aggregate(works, same_author)
    assert same_author_report["counts"]["primaryTitleAuthorConflicts"] == 0
    assert same_author_report["works"][0]["candidateMultiplicityClass"] == (
        "SAME_SOURCE_RIVALS_UNRESOLVED"
    )
    coauthors = copy.deepcopy(collision)
    coauthors[0]["results"][0]["candidates"][0]["authors"] = ["Author Alpha", "Illustrator Gamma"]
    coauthors[0]["results"][0]["candidates"][1]["authors"] = ["Illustrator Gamma"]
    assert aggregate(works, coauthors)["counts"]["primaryTitleAuthorConflicts"] == 0
    missing_chapter_one = copy.deepcopy(data)
    missing_chapter_one[0]["results"][0]["sourceChapterAudits"][0]["chapterOne"] = False
    missing_report = aggregate(works, missing_chapter_one)
    assert missing_report["counts"]["noSourceChapterOneWorks"] == 1
    assert missing_report["counts"]["chapterOneVerifiedWorks"] == 29
    assert "NO_SOURCE_CHAPTER_ONE_ATTESTED" in missing_report["works"][0]["issues"]
    assert missing_report["outcome"] == "NEEDS_CORRECTION"
    partial_but_readable = copy.deepcopy(data)
    partial_but_readable[0]["results"][0]["lookupComplete"] = False
    partial_but_readable[0]["results"][0]["lookupPages"] = 32
    partial_report = aggregate(works, partial_but_readable)
    assert partial_report["counts"]["partialLookupWithCompleteChapters"] == 1
    assert partial_report["works"][0]["atLeastOneCompleteSourceChapterOne"] is True
    assert "LOOKUP_INCOMPLETE" in partial_report["works"][0]["issues"]
    upstream_error = copy.deepcopy(partial_but_readable)
    upstream_error[0]["results"][0]["lookupError"] = "UPSTREAM_OR_RUNTIME_ERROR"
    upstream_report = aggregate(works, upstream_error)
    assert upstream_report["counts"]["lookupUpstreamErrors"] == 1
    assert "LOOKUP_ERROR_UPSTREAM_OR_RUNTIME_ERROR" in upstream_report["works"][0]["issues"]
    source_error = copy.deepcopy(upstream_error)
    source_error[0]["results"][0]["sourceLookupFailures"] = [
        {"source": "mangadot", "reason": "SOURCE_PAGINATION_INVALID"}
    ]
    source_report = aggregate(works, source_error)
    assert "MANGADOT_LOOKUP_SOURCE_PAGINATION_INVALID" in source_report["works"][0]["issues"]

    # A successful upstream search may contain nonmatching titles. It is
    # incorrect to report this as an empty source catalog; it is evidence that
    # the router did not observe an exact title/alias in those returned pages.
    raw_search = copy.deepcopy(data)
    missing = raw_search[0]["results"][0]
    missing["candidateCount"] = 0
    missing["candidates"] = []
    missing["httpBySource"]["mangadot"]["searchPagesObserved"] = 1
    missing["httpBySource"]["mangadot"]["searchRowsObserved"] = 5
    raw_search_report = aggregate(works, raw_search)
    assert raw_search_report["counts"]["noCandidateDespiteUpstreamSearchRows"] == 1
    assert raw_search_report["counts"]["sourceSearchRowsObserved"] == 5
    assert "UPSTREAM_SEARCH_ROWS_WITHOUT_EXACT_MATCH" in raw_search_report["works"][0]["issues"]

    cases = [
        ("OVERFLOW_RAW_ROW_COUNT", lambda p: p[0]["results"][0]["httpBySource"]["mangadot"].update(
            searchPagesObserved=1, searchRowsObserved=101)),
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
        ("UNSAFE_ERROR_TEXT", lambda p: p[0]["results"][0].update(lookupError="HTTP_ERROR:url")),
        ("UNSAFE_SOURCE_FAILURE", lambda p: p[0]["results"][0].update(
            sourceLookupFailures=[{"source": "mangadot", "reason": "header: secret"}])),
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
        # Safe, non-sensitive per-work reasons in live job logs allow analysis
        # without raw API bodies or ephemeral artifact downloads.
        for row in report["works"]:
            print("MANGAALL_DUAL30_ISSUE|position=" + str(row["position"]) +
                  "|title=" + row["title"] +
                  "|lookup=" + str(row["lookupState"]) +
                  "|lookupError=" + str(row["lookupError"] or "NONE") +
                  "|sourceErrors=" + ",".join(
                      error["source"].upper() + "_" + error["reason"]
                      for error in row["sourceLookupFailures"]
                  ) +
                  "|multiplicity=" + row["candidateMultiplicityClass"] +
                  "|chapterOne=" + str(row["atLeastOneCompleteSourceChapterOne"]) +
                  "|rawSearchRows=" + str(sum(row["sourceSearchRowsObserved"].values())) +
                  "|issues=" + ",".join(row["issues"]))
        print("MANGAALL_DUAL30_GATE|outcome=" + report["outcome"] +
              "|works=30|sources=2|completeLookup=" + str(counts["completeLookup"]) +
              "|ambiguous=" + str(counts["ambiguous"]) +
              "|completeChapterAudits=" + str(counts["completeChapterAudits"]) +
              "|oversized64K=" + str(counts["oversized64KiB"]) +
              "|rawSearchRows=" + str(counts["sourceSearchRowsObserved"]) +
              "|emptyMatchWithRawRows=" + str(counts["noCandidateDespiteUpstreamSearchRows"]) +
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
