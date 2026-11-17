#!/usr/bin/env python3
"""Validate Base64 JSON attested by a *real* independent Android catalog run.

This is a transport integrity/sanitization layer, not a title resolver. It
never generates authors, external IDs, or missing catalog rows. The Host must
still independently verify the provenance before using any author credit.
"""
import base64
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

LINE = re.compile(r"MANGAALL_CATALOG_ATTESTATION\|([A-Za-z0-9+/=]+)")
STATUSES = {
    "RESOLVED", "CATALOG_AMBIGUOUS", "CATALOG_NOT_FOUND", "CATALOG_UNAVAILABLE",
    "CATALOG_INCOMPLETE", "CATALOG_UNVERIFIED", "CATALOG_FORMAT_UNSUPPORTED",
}
FIELDS = {
    "schemaVersion", "mode", "caseId", "query", "status", "catalogSource",
    "provider", "providerId", "title", "authors", "artists", "externalIds",
    "creatorsAttested", "catalogsQueried", "catalogsFailed", "canonical",
}
STRING_FIELDS = ("caseId", "query", "status", "catalogSource", "provider",
                 "providerId", "title", "mode", "canonical")
ID = re.compile(r"[A-Za-z0-9._:-]{1,256}\Z")


def case_id(title):
    normalized = unicodedata.normalize("NFKC", title).strip()
    normalized = re.sub(r"\s+", " ", normalized).lower()
    return "user-" + hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:12]


def _unique_pairs(pairs):
    record = {}
    for key, value in pairs:
        if key in record:
            raise ValueError("Duplicate JSON key")
        record[key] = value
    return record


def _check_list(value):
    if not isinstance(value, list) or len(value) > 16:
        raise ValueError("Invalid creator list")
    if any(not isinstance(name, str) or not name.strip() or
           len(name) > 128 or "\n" in name or "\r" in name for name in value):
        raise ValueError("Malformed creator credit")


def _validate(record):
    if not isinstance(record, dict) or set(record) != FIELDS:
        raise ValueError("Unexpected/missing catalog attestation fields")
    if any(not isinstance(record[field], str) for field in STRING_FIELDS):
        raise ValueError("Invalid string field")
    if record["schemaVersion"] != 1 or type(record["schemaVersion"]) is not int:
        raise ValueError("Unsupported catalog attestation schema")
    if record["mode"] != "DIRECT_LIVE_ANDROID_CATALOG_ONLY" or record["canonical"] != "NOT_EXECUTED_HOST":
        raise ValueError("Catalog attestation cannot report canonical/Reader success")
    if record["catalogSource"] != "mangaupdates" or record["status"] not in STATUSES:
        raise ValueError("Unexpected independent catalog source or status")
    title = record["query"]
    if not title.strip() or len(title) > 180 or re.search(r"[\x00-\x1f\x7f]", title):
        raise ValueError("Invalid original title")
    if record["caseId"] != case_id(title):
        raise ValueError("Catalog case does not match signed Provider query hash")
    for key in ("authors", "artists"):
        _check_list(record[key])
    if not isinstance(record["externalIds"], dict) or len(record["externalIds"]) > 16:
        raise ValueError("Invalid external identity mapping")
    for source, identity in record["externalIds"].items():
        if not isinstance(source, str) or not ID.fullmatch(source) or source.startswith("app.tsuzuki."):
            raise ValueError("Forbidden external source")
        if not isinstance(identity, str) or not ID.fullmatch(identity):
            raise ValueError("Invalid external ID")
    if type(record["creatorsAttested"]) is not bool or (
        type(record["catalogsQueried"]) is not int or type(record["catalogsFailed"]) is not int
    ):
        raise ValueError("Invalid attestation provenance")
    if not (0 <= record["catalogsFailed"] <= record["catalogsQueried"] <= 8):
        raise ValueError("Invalid catalog coverage")
    if record["status"] == "RESOLVED":
        if record["provider"] != "mangaupdates" or not ID.fullmatch(record["providerId"]):
            raise ValueError("Only a real independent catalog can issue an identity")
        if not record["title"].strip() or len(record["title"]) > 1024:
            raise ValueError("Invalid owner-reported title")
        if record["creatorsAttested"] != bool(record["authors"] or record["artists"]):
            raise ValueError("Unattested creator claim")
        if record["catalogsQueried"] < 1 or record["catalogsFailed"]:
            raise ValueError("Incomplete catalog cannot resolve identity")
        if record["externalIds"].get(record["provider"], record["providerId"]) != record["providerId"]:
            raise ValueError("Contradictory provider identity")
    else:
        if any((record["provider"], record["providerId"], record["title"],
                record["authors"], record["artists"], record["externalIds"],
                record["creatorsAttested"])):
            raise ValueError("Inconclusive catalog may not carry selected identity")
    return record


def decode_log(log_text):
    if len(log_text.encode("utf-8")) > 1_000_000:
        raise ValueError("Live catalog log exceeds bound")
    rows, seen = [], set()
    for matched in LINE.finditer(log_text):
        token = matched.group(1)
        if len(token) > 16_000:
            raise ValueError("Catalog attestation payload exceeds bound")
        try:
            parsed = json.loads(base64.b64decode(token, validate=True),
                                object_pairs_hook=_unique_pairs)
        except (ValueError, UnicodeError) as error:
            raise ValueError("Invalid base64 attestation payload") from error
        row = _validate(parsed)
        if row["caseId"] in seen:
            raise ValueError("Duplicate catalog work result")
        rows.append(row)
        seen.add(row["caseId"])
        if len(rows) > 30:
            raise ValueError("Too many catalog attestations")
    if not rows and not re.search(
        r"MANGAALL_REAL_CATALOG_BATCH\\|selected=0\\|", log_text
    ):
        raise ValueError("No attestation from the independent Android catalog")
    return rows


def export(input_path, output_path):
    rows = decode_log(Path(input_path).read_text(encoding="utf-8"))
    batch = {
        "schemaVersion": 1,
        "origin": "DIRECT_LIVE_ANDROID_CATALOG_ONLY",
        "canonicalStage": "NOT_EXECUTED_HOST",
        "cases": rows,
    }
    canonical = json.dumps(batch, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    result = {
        **batch,
        "sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
    }
    Path(output_path).write_text(
        json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: validate_mangaall_catalog_attestation.py RAW_INSTRUMENTATION_LOG OUT_JSON")
    export(sys.argv[1], sys.argv[2])
