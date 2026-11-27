#!/usr/bin/env python3
"""Observe bounded inert JSON work records, not arbitrary JavaScript or images.

Never export data values, raw documents, IDs, URLs, or creator names.
A positive observation is a parser-design hint, not an identity verdict.
"""
import argparse
import json
import time
import urllib.parse
from html.parser import HTMLParser

from observe_mangaall_chapter_shape import valid_id
from observe_mangaall_remaining_metadata import FIELDS, norm, request
from run_mangaall_frozen30_independent_catalog import locked_works

INDICES = (15, 21, 27)
MAX_SCRIPTS = 32
MAX_NODES = 16000
MAX_DEPTH = 20


class Embedded(HTMLParser):
    def __init__(self):
        super().__init__()
        self.scripts = []
        self.current = None
        self.overflow = False

    def handle_starttag(self, tag, attrs):
        if tag != "script":
            return
        attrs = dict(attrs)
        if (attrs.get("type", "").lower() == "application/json"
                or attrs.get("id") == "__NEXT_DATA__"):
            self.current = []
        else:
            self.current = None

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag != "script" or self.current is None:
            return
        if len(self.scripts) >= MAX_SCRIPTS:
            self.overflow = True
        else:
            self.scripts.append("".join(self.current))
        self.current = None


def count_record_fields(row, hid, query):
    # Require both exact source-local identifier and an explicit title field.
    # Generic nested person or chapter objects must not lend their credits.
    ids = [row[key] for key in ("id", "manga_id", "mangaId") if key in row]
    if not ids or any(valid_id(value) != hid for value in ids):
        return None
    titles = [row.get(key) for key in ("title", "name", "manga_title", "mangaTitle")]
    if norm(query) not in [norm(title) for title in titles]:
        return None
    fields = [row.get(key) for key in FIELDS if row.get(key)]
    return len(fields)


def summarize(body, hid, query):
    parser = Embedded()
    parser.feed(body.decode("utf-8", "replace"))
    visited = exact = credit_records = credit_fields = invalid = 0
    bounded = parser.overflow
    for raw in parser.scripts:
        try:
            root = json.loads(raw)
        except (ValueError, RecursionError):
            invalid += 1
            continue
        stack = [(root, 0)]
        while stack:
            node, depth = stack.pop()
            if not isinstance(node, (dict, list)):
                continue
            visited += 1
            if visited > MAX_NODES:
                bounded = True
                stack.clear()
                break
            if depth > MAX_DEPTH:
                bounded = True
                continue
            if isinstance(node, dict):
                fields = count_record_fields(node, hid, query)
                if fields is not None:
                    exact += 1
                    credit_records += int(fields > 0)
                    credit_fields += fields
                children = node.values()
            else:
                children = node
            stack.extend((child, depth + 1) for child in children
                         if isinstance(child, (dict, list)))
        if visited > MAX_NODES:
            break
    return dict(jsonScripts=len(parser.scripts), invalidScripts=invalid,
                exactRecords=exact, creditRecords=credit_records,
                creditFields=credit_fields, scope="BOUNDED" if bounded else "COMPLETE")


def observe(index):
    title = locked_works()[index - 1]
    queries = [title]
    if index == 21:
        queries += ["Mercenary Enrollment", "입학용병"]
    found = {}
    for query in queries:
        for page in (1, 2):
            url = "https://mangadot.net/api/search?" + urllib.parse.urlencode(
                dict(search=query, page=page, limit=56))
            status, body = request(url, "application/json")
            if body is None:
                print(f"DOT_EMBEDDED_SEARCH|index={index}|status={status}", flush=True)
                if status in ("HTTP403", "HTTP429"):
                    return
                break
            try:
                obj = json.loads(body)
                rows = obj.get("manga_list", obj.get("results"))
                if not isinstance(rows, list) or len(rows) > 56:
                    raise ValueError("rows")
                for row in rows:
                    if not isinstance(row, dict) or norm(row.get("title")) != norm(query):
                        continue
                    hid = valid_id(row.get("manga_id", row.get("id")))
                    if hid:
                        found[hid] = query
            except (ValueError, TypeError, AttributeError, RecursionError):
                print(f"DOT_EMBEDDED_SEARCH|index={index}|status=SCHEMA_ERROR", flush=True)
                break
            if found or not rows:
                break
            time.sleep(1)
        if found:
            break
    print(f"DOT_EMBEDDED_SEARCH|index={index}|exactRecords={len(found)}", flush=True)
    if len(found) > 3:
        return
    for ordinal, (hid, query) in enumerate(sorted(found.items()), 1):
        status, body = request("https://mangadot.net/manga/" + hid, "text/html")
        counts = summarize(body, hid, query) if body is not None else {}
        print(f"DOT_EMBEDDED_CREDITS|index={index}|ordinal={ordinal}|status={status}"
              + "".join(f"|{key}={value}" for key, value in counts.items()), flush=True)
        if status in ("HTTP403", "HTTP429"):
            return
        time.sleep(1)


def selftest():
    def html(root):
        return ('<script type="application/json">' + json.dumps(root)
                + '</script>').encode()
    target = dict(manga_id=42, title="Fictional Work", authors=[{"name": "Creator"}])
    assert summarize(html(target), "42", "Fictional Work")["creditRecords"] == 1
    assert summarize(html(dict(target, id=43)), "42", "Fictional Work")["exactRecords"] == 0
    assert summarize(html(dict(target, title="Other")), "42", "Fictional Work")["exactRecords"] == 0
    assert summarize(html({"work": dict(target, authors=[]), "person": target["authors"]}),
                     "42", "Fictional Work")["creditRecords"] == 0
    assert summarize(b'<script>window.data={"id":42}</script>', "42", "Fictional Work")["jsonScripts"] == 0
    print("DOT_EMBEDDED_CREDITS|selftest=PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--index", type=int, choices=INDICES)
    args = parser.parse_args()
    if args.selftest:
        selftest()
    elif args.index:
        observe(args.index)
    else:
        parser.error("select --selftest or --index")
