#!/usr/bin/env python3
"""Bounded chapter metadata shape audit, never a Reader or identity verdict.

Use the frozen corpus only as test inputs. Report counts, status and timing;
never log source IDs, URLs, titles, response bodies or chapter images.
"""
import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal, InvalidOperation

from observe_mangaall_remaining_metadata import OPENER, norm, request
from run_mangaall_frozen30_independent_catalog import locked_works

MAX_BODY = 16 * 1024 * 1024
MAX_ROWS = 16000
ID = re.compile(r"[1-9][0-9]{0,15}")
NUMBER = re.compile(r"[0-9]{1,6}(?:\.[0-9]{1,3})?")
INDICES = (8, 15, 21, 27)


def valid_id(value):
    if type(value) in (int, float):
        if not 1 <= value <= 9007199254740991 or value != int(value):
            return None
        value = str(int(value))
    return value if isinstance(value, str) and ID.fullmatch(value) else None


def valid_number(value):
    if type(value) in (int, float):
        try:
            value = format(Decimal(str(value)).normalize(), "f")
        except (InvalidOperation, ValueError):
            return None
    return Decimal(value) if isinstance(value, str) and NUMBER.fullmatch(value) else None


def summarize(rows, language):
    if not isinstance(rows, list):
        return dict(shape="NOT_ARRAY")
    if len(rows) > MAX_ROWS:
        return dict(shape="ROW_LIMIT", rows=len(rows))
    seen = set()
    explicit = unknown = rejected = invalid = duplicate = ones = 0
    for row in rows:
        if not isinstance(row, dict):
            invalid += 1
            continue
        lang = row.get("language")
        if lang is not None and (not isinstance(lang, str) or lang.lower() != language):
            rejected += 1
            continue
        identity = valid_id(row.get("id"))
        number = valid_number(row.get("chapter_number"))
        if identity is None or number is None:
            invalid += 1
            continue
        kind = 0 if row.get("source") in (None, "user") else 1
        if (kind, identity) in seen:
            duplicate += 1
            continue
        seen.add((kind, identity))
        explicit += lang is not None
        unknown += lang is None
        ones += number == 1
    return dict(shape="ARRAY", rows=len(rows), valid=len(seen), explicitFacet=explicit,
                unknownFacet=unknown, rejectedFacet=rejected, invalid=invalid,
                duplicates=duplicate, chapterOne=ones)


def chapter_response(hid):
    if not ID.fullmatch(hid):
        raise ValueError("invalid scope")
    url = "https://mangadot.net/api/manga/" + hid + "/chapters/list?lang=en"
    req = urllib.request.Request(url, headers={
        "Accept": "application/json",
        "User-Agent": "Tsuzuki-CI-public-metadata-observation",
    })
    start = time.monotonic()
    try:
        with OPENER.open(req, timeout=15) as response:
            body = response.read(MAX_BODY + 1)
            result = dict(status="HTTP200", bytes=len(body))
            if len(body) > MAX_BODY:
                result["shape"] = "BODY_LIMIT"
            else:
                try:
                    result.update(summarize(json.loads(body), "en"))
                except (ValueError, UnicodeError, RecursionError):
                    result["shape"] = "INVALID_JSON"
    except urllib.error.HTTPError as exc:
        result = dict(status=f"HTTP{exc.code}")
    except (OSError, ValueError):
        result = dict(status="TRANSPORT_ERROR")
    result["elapsedMs"] = round((time.monotonic() - start) * 1000)
    return result


def observe(index):
    title = locked_works()[index - 1]
    # Known independently published native/alias retrieval hints for the
    # diagnostic corpus only. Never bind an identity using these results.
    queries = [title]
    if index == 21:
        queries += ["입학용병", "Mercenary Enrollment"]
    if index == 27:
        queries += ["재혼황후"]
    found = set()
    for ordinal, query in enumerate(queries, 1):
        for page in (1, 2):
            url = "https://mangadot.net/api/search?" + urllib.parse.urlencode(
                dict(search=query, page=page, limit=56))
            status, body = request(url, "application/json")
            if body is None:
                print(f"DOT_SHAPE_SEARCH|index={index}|query={ordinal}|status={status}", flush=True)
                if status in ("HTTP403", "HTTP429"):
                    return
                break
            try:
                root = json.loads(body)
                rows = root.get("manga_list", root.get("results"))
                if not isinstance(rows, list) or len(rows) > 56:
                    raise ValueError("rows")
                for row in rows:
                    if not isinstance(row, dict) or norm(row.get("title")) != norm(query):
                        continue
                    hid = valid_id(row.get("manga_id", row.get("id")))
                    if hid:
                        found.add(hid)
                if not rows or found:
                    break
            except (ValueError, AttributeError, TypeError):
                print(f"DOT_SHAPE_SEARCH|index={index}|query={ordinal}|status=SCHEMA_ERROR", flush=True)
                break
            time.sleep(1)
        if found:
            break
    print(f"DOT_SHAPE_SEARCH|index={index}|exactRecords={len(found)}|scope=BOUNDED", flush=True)
    if len(found) > 3:
        return
    for ordinal, hid in enumerate(sorted(found), 1):
        result = chapter_response(hid)
        print(f"DOT_CHAPTER_SHAPE|index={index}|ordinal={ordinal}"
              + "".join(f"|{key}={value}" for key, value in result.items()), flush=True)
        if result["status"] in ("HTTP403", "HTTP429"):
            return
        time.sleep(1)


def selftest():
    assert valid_id("9007199254740999") == "9007199254740999"
    assert valid_id(9007199254740999) is None
    assert valid_number("1.001") == Decimal("1.001")
    assert valid_number("1.0001") is None
    assert summarize({}, "en") == {"shape": "NOT_ARRAY"}
    rows = [
        dict(id=1, chapter_number=1, language="EN"),
        dict(id=1, chapter_number=1, language="en"),
        dict(id=1, chapter_number="2", source="external"),
        dict(id=2, chapter_number=3, language="pt-br"),
        dict(id="invalid", chapter_number=1),
    ]
    assert summarize(rows, "en") == dict(shape="ARRAY", rows=5, valid=2,
        explicitFacet=1, unknownFacet=1, rejectedFacet=1, invalid=1, duplicates=1, chapterOne=1)
    print("DOT_CHAPTER_SHAPE|selftest=PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=int, choices=INDICES)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
    elif args.index:
        observe(args.index)
    else:
        parser.error("select --selftest or --index")
