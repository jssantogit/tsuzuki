#!/usr/bin/env python3
"""Correlate signed code10 source, independent catalog and Kotlin SQLite verdicts.

This reads actual artifacts *only* after the manually requested execution.
Never infers canonical identity from matching text, invents chapter IDs, or
marks a challenged source as an exhaustive empty source.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from mangaall_code10_canonical_smoke_plan import parse_plan, ACK

SHA = "bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f"
SQL = "MANGAALL_REAL_SQLITE_REFRESH|"
COL = "MANGAALL_CANONICAL_COLLECTOR|"
CASE = re.compile(r"user-[a-f0-9]{12}\Z")
SAFE = re.compile(r"[A-Za-z0-9_.,:-]{1,500}\Z")


def json_object(path, limit=4_000_000):
    raw = Path(path).read_bytes()
    if len(raw) > limit:
        raise ValueError("Oversized canonical smoke artifact")
    obj = json.loads(raw)
    if not isinstance(obj, dict):
        raise ValueError("Unexpected artifact object")
    return obj


def closed_lines(xml_file, marker):
    raw = Path(xml_file).read_bytes()
    if len(raw) > 4_000_000:
        raise ValueError("Oversized JUnit XML")
    root = ET.fromstring(raw)
    if root.attrib.get("failures", "0") != "0" or root.attrib.get("errors", "0") != "0":
        raise ValueError("JUnit reports failing canonical/SQLite execution")
    found = []
    for node in root.iter():
        if node.tag.split("}")[-1] != "system-out" or not node.text:
            continue
        for text in node.text.splitlines():
            if marker not in text:
                continue
            line = text.split(marker, 1)[1].strip()
            fields = {}
            for part in line.split("|"):
                if "=" not in part:
                    raise ValueError("Malformed closed Host verdict")
                key, value = part.split("=", 1)
                if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key) or not value or not SAFE.fullmatch(value):
                    raise ValueError("Unsafe Host verdict attribute")
                fields[key] = value
            if not CASE.fullmatch(fields.get("case", "")):
                if fields.get("EMPTY_SHARD") is None:
                    raise ValueError("Host verdict missing valid case ID")
            else:
                found.append(fields)
    return found


def _by_case(rows, column):
    values = {}
    for row in rows:
        key = row.get(column)
        if not isinstance(key, str) or not CASE.fullmatch(key) or key in values:
            raise ValueError("Duplicated or forged canonical smoke case")
        values[key] = row
    return values


def bounded_count(value, label):
    """Only finite, nonnegative closed counts are fit for a user-facing report."""
    if type(value) is int and 0 <= value <= 999_999_999:
        return value
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,9}", value):
        return int(value)
    raise ValueError("Invalid closed counter: " + label)


# Independent diagnostic axis. A technically incomplete upstream response must not
# hide why chapter evidence remained provisional, nor become an editorial grant.
LOOKUP_DECISIONS = frozenset((
    "SELECTED", "AUTHOR_CORROBORATED", "UNIQUE_READABLE_SOURCE",
    "NO_MATCH", "MULTIPLE_PRIMARY_IDENTITIES", "AMBIGUOUS_EDITION",
))


def parse_lookup_decisions(raw):
    if raw == "NONE":
        return {}
    if not isinstance(raw, str) or not raw or len(raw) > 300:
        raise ValueError("Missing or oversized closed editorial decisions")
    decisions = {}
    for entry in raw.split(","):
        code, sep, count = entry.partition(":")
        if sep != ":" or code not in LOOKUP_DECISIONS or code in decisions:
            raise ValueError("Unknown or duplicated editorial decision")
        decisions[code] = bounded_count(count, "lookup decision")
        if decisions[code] == 0:
            raise ValueError("Zero editorial decision count")
    return decisions


def editorial_observation(counts, decisions):
    # These describe observed Host/SQLite evidence, not permission to bind a
    # source edition or a claim that remote chapter inventory is complete.
    if counts["confirmed"] > 0:
        return "CONFIRMED_ROWS_OBSERVED_NOT_READER_PROOF"
    if counts["sqliteBindings"] > 0:
        return "BOUND_WITHOUT_CONFIRMED_ROWS"
    if counts["sourceAssociations"] or counts["provisionalChapters"]:
        return "PROVISIONAL_ASSOCIATION_ONLY"
    if any(decisions.get(code, 0) for code in ("MULTIPLE_PRIMARY_IDENTITIES", "AMBIGUOUS_EDITION")):
        return "AMBIGUOUS_SOURCE_EDITION"
    return "NO_CHAPTER_BINDING_OBSERVED"


def reader_observation(counts):
    if counts["readerAmbiguous"] > 0:
        return "AMBIGUOUS_OPTIONS_IN_SAMPLE"
    if counts["readerUnique"] > 0:
        return "SAMPLED_OPTIONS_NOT_CDN_PIXELS"
    return "NO_UNIQUE_OPTIONS_IN_SAMPLE"


# Snapshot-only observations from the original signed V1 capture. No source
# work/chapter IDs or titles leave this function; source facts do not certify
# the independent catalog's work, an editorial edition, or Reader pages.
SOURCE_FAMILIES = ("MANGADEX", "MANGADOT", "MANGAFIRE")
SOURCE_WORK_ID = re.compile(r"(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}\Z")
SOURCE_CHAPTER_ID = re.compile(r"(mangadex|mangadot|mangafire):[A-Za-z0-9._:-]{1,200}\Z")
VERIFIED_DETAILS = frozenset(("VERIFIED_WITH_ALIASES", "VERIFIED_NO_ALIASES"))


def bounded_source_observations(source_case):
    records = source_case.get("records")
    if not isinstance(records, list) or len(records) > 128:
        raise ValueError("Missing or oversized signed source record stream")
    work_records = {name: {} for name in SOURCE_FAMILIES}
    chapter_ids = {name: set() for name in SOURCE_FAMILIES}
    lookup_pages = {name: 0 for name in SOURCE_FAMILIES}
    chapter_pages = {name: 0 for name in SOURCE_FAMILIES}
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("Malformed source observation record")
        operation = record.get("operation")
        if operation not in ("lookup", "chapters", "pages"):
            raise ValueError("Unknown source operation in signed record")
        if record.get("outcome") != "RESULT" or operation == "pages":
            continue
        response = record.get("response")
        if not isinstance(response, dict) or not isinstance(response.get("items"), list):
            raise ValueError("Signed source result lacks an observed item list")
        items = response["items"]
        if len(items) > (300 if operation == "lookup" else 4000):
            raise ValueError("Oversized signed source result item list")
        if operation == "lookup":
            represented = set()
            for item in items:
                if not isinstance(item, dict):
                    raise ValueError("Malformed signed lookup candidate")
                identity = item.get("externalWorkId")
                match = SOURCE_WORK_ID.fullmatch(identity) if isinstance(identity, str) else None
                if not match:
                    raise ValueError("Invalid signed source candidate identity")
                name = match.group(1).upper()
                represented.add(name)
                authors = item.get("authors", [])
                if not isinstance(authors, list) or len(authors) > 32 or any(
                    not isinstance(author, str) or not author for author in authors
                ):
                    raise ValueError("Invalid signed source author evidence")
                verified = isinstance(item.get("detailOutcome"), str) and item["detailOutcome"] in VERIFIED_DETAILS
                coverage = item.get("coverageOutcome") == "OBSERVED"
                observed_count = item.get("coverageObservedCount")
                if observed_count is not None and (
                    type(observed_count) is not int or not 0 <= observed_count <= 1_000_000_000
                ):
                    raise ValueError("Invalid signed source coverage count")
                positive = coverage and observed_count is not None and observed_count > 0
                observed = work_records[name].setdefault(identity, [False, False, False])
                observed[0] |= bool(authors)
                observed[1] |= verified
                observed[2] |= positive
            for name in represented:
                lookup_pages[name] += 1
        else:
            binding = record.get("request", {}).get("binding")
            identity = binding.get("externalWorkId") if isinstance(binding, dict) else None
            match = SOURCE_WORK_ID.fullmatch(identity) if isinstance(identity, str) else None
            if not match:
                raise ValueError("Invalid source-scoped chapter request")
            name = match.group(1).upper()
            chapter_pages[name] += 1
            for item in items:
                chapter = item.get("providerChapterId") if isinstance(item, dict) else None
                cm = SOURCE_CHAPTER_ID.fullmatch(chapter) if isinstance(chapter, str) else None
                if cm is None or cm.group(1) != match.group(1):
                    raise ValueError("Foreign or malformed source chapter observation")
                chapter_ids[name].add(chapter)
    return {
        name: {
            "lookupPagesWithCandidates": lookup_pages[name],
            "candidatesObserved": len(work_records[name]),
            "withSourceAuthors": sum(fields[0] for fields in work_records[name].values()),
            "withVerifiedSourceDetail": sum(fields[1] for fields in work_records[name].values()),
            "withPositiveSourceCoverage": sum(fields[2] for fields in work_records[name].values()),
            "chapterPagesObserved": chapter_pages[name],
            "chapterIdsObserved": len(chapter_ids[name]),
        } for name in SOURCE_FAMILIES
    }


def merge(works, tier, source, matrix, catalog, collector_rows, sqlite_rows):
    vector = parse_plan(works, tier, ACK)
    if (source.get("schemaVersion") != 1 or
            source.get("captureKind") != "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH" or
            source.get("origin") != "LIVE_SIGNED_PROVIDER_JS" or
            source.get("providerId") != "app.tsuzuki.mangaall" or
            source.get("providerVersionCode") != 10 or
            source.get("repositoryId") != "app.tsuzuki.providers" or
            source.get("signedIndexSequence") != 36 or
            source.get("packageSha256") != SHA or
            source.get("selectionSource") != "USER_INPUT" or
            source.get("shardIndex") != 0 or source.get("shardCount") != 1):
        raise ValueError("Not an authentic sequence36/code10 capture identity")
    if (catalog.get("schemaVersion") != 1 or
            catalog.get("origin") != "DIRECT_LIVE_ANDROID_CATALOG_ONLY" or
            catalog.get("canonicalStage") != "NOT_EXECUTED_HOST" or
            not isinstance(catalog.get("sha256"), str)):
        raise ValueError("Catalog proof cannot be promoted or spoofed")
    if (matrix.get("provider") != "app.tsuzuki.mangaall" or
            matrix.get("version") != "0.5.5" or
            matrix.get("shardCount") != 1 or matrix.get("shardIndex") != 0 or
            matrix.get("selectionSource") != "USER_INPUT"):
        raise ValueError("Source matrix not from the same code10 vector")
    chosen = [v["caseId"] for v in vector]
    ids = set(chosen)
    sources = _by_case(source.get("cases", []), "caseId")
    catalogs = _by_case(catalog.get("cases", []), "caseId")
    matrices = _by_case(matrix.get("cases", []), "case")
    collectors = _by_case(collector_rows, "case")
    sqlites = _by_case(sqlite_rows, "case")
    if any(set(group) != ids for group in (sources, catalogs, matrices, collectors, sqlites)):
        raise ValueError("Source/catalog/Host/SQLite case set differs; no cross-work substitution")

    rows = []
    for title in vector:
        cid = title["caseId"]
        probe = matrices[cid]
        health = probe.get("httpBySource")
        if not isinstance(health, dict) or set(health) != {"MANGADEX", "MANGADOT", "MANGAFIRE"}:
            raise ValueError("Smoke must attribute HTTP health to exactly three sources")
        source_incomplete = sources[cid].get("replayCompleteness") != "LOOKUP_CHAPTERS_ONLY" or any(
            isinstance(record, dict) and (
                (record.get("blocked403") or 0) > 0 or
                (record.get("transportFailed") or 0) > 0
            ) for record in health.values()
        ) or probe.get("lookupComplete") is not True
        cat = catalogs[cid].get("status")
        col = collectors[cid]
        sql = sqlites[cid]
        if not isinstance(cat, str) or not re.fullmatch(r"[A-Z_]+", cat):
            raise ValueError("Unclosed catalog status")
        # These are only closed counters emitted by production Host tests.
        # A partial, real source may persist provisional chapters and sampled
        # Reader options without conferring canonical or CDN pixel authority.
        counts = {key: bounded_count(sql.get(key), key) for key in (
            "confirmed", "provisionalChapters", "conflicted", "sqliteChapters",
            "sqliteEvidence", "sqliteMapped", "sqliteBindings",
            "uiRepositoryChapters", "sourceAssociations", "readerSampled",
            "readerUnique", "readerAmbiguous", "readerMissing", "replayGaps",
        )}
        decisions = parse_lookup_decisions(sql.get("lookupDecisions"))
        source_observations = bounded_source_observations(sources[cid])
        collector_gaps = bounded_count(col.get("gaps"), "collector gaps")
        blocked = sorted(
            key for key in health
            if bounded_count(health[key].get("blocked403"), key + " blocked403") > 0
        )
        for key in health:
            if not isinstance(health[key], dict):
                raise ValueError("Malformed source health")
            bounded_count(health[key].get("transportFailed"), key + " transportFailed")
        if (counts["sqliteMapped"] > counts["sqliteEvidence"] or
                counts["confirmed"] + counts["provisionalChapters"] + counts["conflicted"] !=
                counts["sqliteChapters"] or
                counts["uiRepositoryChapters"] != counts["sqliteChapters"] or
                counts["readerUnique"] + counts["readerAmbiguous"] + counts["readerMissing"] !=
                counts["readerSampled"]):
            raise ValueError("Inconsistent canonical SQLite/Reader observation counters")
        if collector_gaps or counts["replayGaps"]:
            outcome = "REPLAY_GAP"
        elif source_incomplete:
            outcome = "INCONCLUSIVE_SOURCE"
        elif cat != "RESOLVED":
            outcome = "INCONCLUSIVE_CATALOG"
        elif counts["confirmed"] > 0:
            outcome = "CHAPTER_EVIDENCE_OBSERVED_NEEDS_READER"
        else:
            outcome = "INCONCLUSIVE_CANONICAL_CHAPTERS"
        rows.append({
            "position": title["position"],
            "caseId": cid,
            "outcome": outcome,
            "sourceIncomplete": source_incomplete,
            "sourceHttp403": blocked,
            "catalog": cat,
            "sqliteConfirmed": counts["confirmed"],
            "sqliteProvisional": counts["provisionalChapters"],
            "sqliteChapters": counts["sqliteChapters"],
            "sqliteEvidence": counts["sqliteEvidence"],
            "sqliteMapped": counts["sqliteMapped"],
            "sqliteBindings": counts["sqliteBindings"],
            "sqliteConflicted": counts["conflicted"],
            "uiRepositoryChapters": counts["uiRepositoryChapters"],
            "sourceAssociations": counts["sourceAssociations"],
            "sourceWorkEvidence": source_observations,
            "lookupDecisions": decisions,
            "editorialObservation": editorial_observation(counts, decisions),
            "readerOptionsSampled": counts["readerSampled"],
            "readerOptionsUnique": counts["readerUnique"],
            "readerOptionsAmbiguous": counts["readerAmbiguous"],
            "readerOptionsMissing": counts["readerMissing"],
            "readerObservation": reader_observation(counts),
            "readerPixels": "NOT_TESTED_REAL_CDN",
        })
    return {
        "schemaVersion": 1, "runScope": "CODE10_SIGNED_V1_CAPTURE_TO_CANONICAL_SQLITE",
        "signedIndexSequence": 36, "providerCode": 10, "works": len(rows),
        "ordered": True, "catalogIndependent": True, "allThreeSourcesInOneMangaAllLookup": True,
        "readerPixels": "NOT_TESTED_REAL_CDN",
        "canonicalGrantFromV2": False,
        "results": rows,
    }

def has_internal_replay_gap(summary):
    """Missing exact Host request/response tape is an internal failure, not source 403."""
    return any(row["outcome"] == "REPLAY_GAP" for row in summary["results"])


def main():
    if len(sys.argv) != 7:
        raise SystemExit("usage: report.py SOURCE.json MATRIX.json CATALOG.json COLLECTOR.xml SQLITE.xml OUT.json")
    source, matrix, catalog = map(json_object, sys.argv[1:4])
    result = merge(os.environ["MANGAALL_WORKS"], os.environ["MANGAALL_SMOKE_TIER"],
                   source, matrix, catalog,
                   closed_lines(sys.argv[4], COL),
                   closed_lines(sys.argv[5], SQL))
    Path(sys.argv[6]).parent.mkdir(parents=True, exist_ok=True)
    Path(sys.argv[6]).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    counts = {}
    for row in result["results"]:
        counts[row["outcome"]] = counts.get(row["outcome"], 0) + 1
    print("MANGAALL_CODE10_CANONICAL_SMOKE_REPORT|works=" + str(result["works"]) +
          "|outcomes=" + ",".join(f"{key}:{value}" for key, value in sorted(counts.items())) +
          "|readerCdnPixels=NOT_TESTED|bindingFromV2=DENIED")
    if has_internal_replay_gap(result):
        print("MANGAALL_CODE10_CANONICAL_SMOKE_GATE|outcome=FAIL|reason=REPLAY_GAP|readerCdnPixels=NOT_TESTED",
              file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
