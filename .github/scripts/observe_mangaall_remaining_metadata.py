#!/usr/bin/env python3
"""Bounded public metadata diagnostics only. No chapters, pages, raw bodies or source IDs logged."""
import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

from run_mangaall_frozen30_independent_catalog import locked_works
from run_mangaall_frozen30_catalog_plan import plan

MAX_BODY = 2 * 1024 * 1024
FIELDS = ("author", "authors", "creator", "creators", "artist", "artists",
          "illustrator", "writer", "author_name", "artist_name")
EDITION_MARKER = re.compile(r"(?:^|[\s(\[])(?:novel|official|fan[- ]colou?red|full[- ]colou?r|colou?red|one[- ]shot|omnibus|special edition)(?:$|[\s)\]])", re.I)
ROLE_LABEL = re.compile(r"^(?:authors?|artists?|writers?|creators?|illustrators?)\s*:?\s*$", re.I)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def request(url, accept, data=None):
    req = urllib.request.Request(url, data=data,
        headers={"Accept": accept, "Content-Type": "application/json",
                 "User-Agent": "Tsuzuki-CI-public-metadata-observation"})
    try:
        with OPENER.open(req, timeout=15) as response:
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                return "BODY_LIMIT", None
            return "HTTP200", body
    except urllib.error.HTTPError as exc:
        return f"HTTP{exc.code}", None
    except (OSError, ValueError):
        return "TRANSPORT_ERROR", None


def norm(value):
    return " ".join(value.replace("’", "'").casefold().split()) if isinstance(value, str) else ""


class Detail(HTMLParser):
    def __init__(self):
        super().__init__()
        self.json_ld = []
        self.script = None
        self.semantic_roles = 0
        self.role_labels = 0
        self.role_class_nodes = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("type", "").lower() == "application/ld+json":
            self.script = []
        if set((attrs.get("itemprop") or "").split()) & set(FIELDS):
            self.semantic_roles += 1
        tokens = re.split(r"[^a-z]+", (attrs.get("class") or "").lower())
        if set(tokens) & set(FIELDS):
            self.role_class_nodes += 1

    def handle_data(self, data):
        if self.script is not None:
            self.script.append(data)
        elif ROLE_LABEL.fullmatch(data.strip()):
            self.role_labels += 1

    def handle_endtag(self, tag):
        if tag == "script" and self.script is not None:
            try:
                self.json_ld.append(json.loads("".join(self.script)))
            except ValueError:
                pass
            self.script = None


def objects(root, depth=0):
    if depth > 12:
        return
    if isinstance(root, dict):
        yield root
        for value in root.values():
            yield from objects(value, depth + 1)
    elif isinstance(root, list):
        for value in root:
            yield from objects(value, depth + 1)


def names(obj):
    raw = obj.get("alternateName") or []
    return [obj.get("name")] + (raw if isinstance(raw, list) else [raw])


def detail_counts(body, work_url, query):
    parser = Detail()
    parser.feed(body.decode("utf-8", "replace"))
    def exact_ref(value):
        return isinstance(value, str) and value.split("#", 1)[0].rstrip("/") == work_url
    matches = []
    for obj in objects(parser.json_ld):
        kind = obj.get("@type")
        kinds = kind if isinstance(kind, list) else [kind]
        if not any(k in ("ComicSeries", "https://schema.org/ComicSeries", "http://schema.org/ComicSeries") for k in kinds):
            continue
        if not (exact_ref(obj.get("url")) or exact_ref(obj.get("@id"))):
            continue
        if norm(query) not in [norm(name) for name in names(obj)]:
            continue
        matches.append(obj)
    roles = sum(bool(obj.get(field)) for obj in matches for field in FIELDS)
    editions = sum(bool(EDITION_MARKER.search(n)) for obj in matches for n in names(obj) if isinstance(n, str))
    return dict(jsonLd=len(parser.json_ld), exactSeries=len(matches), structuredRoles=roles,
                semanticRoleNodes=parser.semantic_roles, visibleRoleLabels=parser.role_labels,
                roleClassNodes=parser.role_class_nodes, editionMarkers=editions)


def observe_dot(works):
    # Frozen diagnostics only; do not infer absence from bounded title searches.
    for index in (15, 21, 27):
        query = works[index - 1]
        found = {}
        state = "BOUNDED"
        for page in (1, 2):
            url = "https://mangadot.net/api/search?" + urllib.parse.urlencode(
                dict(search=query, page=page, limit=56))
            code, body = request(url, "application/json")
            if body is None:
                state = code
                break
            try:
                obj = json.loads(body)
                rows = obj.get("manga_list", obj.get("results"))
                if not isinstance(rows, list) or len(rows) > 56:
                    raise ValueError("rows")
                for row in rows:
                    if not isinstance(row, dict) or norm(row.get("title")) != norm(query):
                        continue
                    hid = str(row.get("manga_id", row.get("id")))
                    if not re.fullmatch(r"[1-9][0-9]{0,15}", hid):
                        continue
                    found[hid] = sum(bool(row.get(k)) for k in FIELDS)
                pagination = obj.get("pagination") or {}
                total = pagination.get("total_pages", pagination.get("totalPages", pagination.get("last_page")))
                if type(total) is int and page >= total:
                    state = "COMPLETE"
                    break
                if not rows:
                    break
            except (ValueError, TypeError, AttributeError):
                state = "SCHEMA_ERROR"
                break
            time.sleep(1)
        print(f"DOT_WIRE|index={index}|state={state}|exactRecords={len(found)}", flush=True)
        if state in ("HTTP403", "HTTP429"):
            return
        for ordinal, (hid, fields) in enumerate(list(found.items())[:3], 1):
            url = "https://mangadot.net/manga/" + hid
            time.sleep(1)
            code, body = request(url, "text/html")
            counts = detail_counts(body, url, query) if body is not None else {}
            print(f"DOT_DETAIL_WIRE|index={index}|ordinal={ordinal}|status={code}|searchCreditFields={fields}"
                  + "".join(f"|{key}={value}" for key, value in counts.items()), flush=True)
            if code in ("HTTP403", "HTTP429"):
                return


def observe_catalog(rows):
    for index in (28, 29):
        row = rows[index - 1]
        data = dict(search=row["searchTitle"], page=1, perpage=25,
                    type=["Manhwa"], filter_types=["drama cd", "novel"])
        code, body = request("https://api.mangaupdates.com/v1/series/search",
                             "application/json", json.dumps(data).encode())
        count = -1
        if body is not None:
            try:
                results = json.loads(body).get("results")
                if not isinstance(results, list) or len(results) > 25:
                    raise ValueError("rows")
                count = len(results)
            except (ValueError, TypeError, AttributeError):
                code = "SCHEMA_ERROR"
        print(f"MU_WIRE|index={index}|status={code}|firstPageRows={count}|identity=NOT_TESTED", flush=True)
        if code in ("HTTP403", "HTTP429"):
            return
        time.sleep(2)


def selftest():
    url = "https://mangadot.net/manga/42"
    series = {"@type": "ComicSeries", "name": "Fictional work", "url": url,
              "author": {"@type": "Person", "name": "Fictional author"}}
    html = ('<script type="application/ld+json">' + json.dumps(series) +
            '</script><div class="author">Author:</div>').encode()
    good = detail_counts(html, url, "Fictional work")
    assert good["exactSeries"] == good["structuredRoles"] == good["visibleRoleLabels"] == 1
    assert detail_counts(html, url + "0", "Fictional work")["exactSeries"] == 0
    assert detail_counts(html, url, "Other work")["structuredRoles"] == 0
    print("MANGAALL_METADATA_WIRE|selftest=PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("dot", "catalog", "selftest"))
    args = parser.parse_args()
    works = locked_works()
    rows, _ = plan()
    if args.mode == "selftest":
        selftest()
    elif args.mode == "dot":
        observe_dot(works)
    else:
        observe_catalog(rows)
