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


# Publisher-attested original identities, fixed only for this diagnostic
# cohort. Never populate provider authors or authorize a reading option.
OFFICIAL_WEBTOON = {15: "1049", 21: "2677", 27: "2135"}
OFFICIAL_VARIANT = {27: "11640"}


def publisher_series_id(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme != "https" or parsed.username is not None
                or parsed.hostname not in {"webtoons.com", "www.webtoons.com", "m.webtoons.com"}
                or parsed.port not in (None, 443) or parsed.fragment
                or not re.fullmatch(r"/[a-z]{2}/[a-z-]+/[a-z0-9-]+/list/?", parsed.path)):
            return None
        ids = urllib.parse.parse_qs(parsed.query, keep_blank_values=True).get("title_no", [])
        if len(ids) == 1 and re.fullmatch(r"[1-9][0-9]{0,9}", ids[0]):
            return ids[0]
    except ValueError:
        pass
    return None


def mangaupdates_series_id(value):
    """Decode only the exact public series route; never fetch source-supplied URLs.

    Modern /series IDs are base36, even when composed entirely of digits.
    The legacy series.html?id= numeric route uses its own identifier space;
    do not assume its value is the API series_id.
    """
    try:
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme != "https" or parsed.username is not None
                or parsed.hostname not in {"mangaupdates.com", "www.mangaupdates.com"}
                or parsed.port not in (None, 443) or parsed.query or parsed.fragment):
            return None
        match = re.fullmatch(r"/series/([a-z0-9]{1,12})(?:/[a-z0-9-]{1,200})?/?", parsed.path)
        if match:
            identity = int(match[1], 36)
            if 0 < identity < 10**18:
                return identity
    except (TypeError, ValueError):
        pass
    return None


def catalog_record_names(record):
    associated = record.get("associated", [])
    if not isinstance(associated, list) or len(associated) > 512:
        return set()
    return {norm(value) for value in [record.get("title")] +
            [row.get("title") for row in associated if isinstance(row, dict)]} - {""}


def independently_resolve_mu(query, original):
    """Search independent of source links; require complete unique Manhwa identity and detail."""
    search = "https://api.mangaupdates.com/v1/series/search"
    hits = {}
    seen = set()
    expected_total = None
    for page in range(1, 13):
        data = dict(search=query, page=page, perpage=25,
                    type=["Manhwa"], filter_types=["drama cd", "novel"])
        code, body = request(search, "application/json", json.dumps(data).encode())
        if body is None:
            return code, None
        try:
            obj = json.loads(body)
            rows = obj.get("results")
            total = obj.get("total_hits")
            if (not isinstance(rows, list) or len(rows) > 25 or type(total) is not int
                    or not 0 <= total <= 300 or obj.get("page") != page
                    or (expected_total is not None and expected_total != total)):
                return "INCOMPLETE_OR_SCHEMA", None
            expected_total = total
            for item in rows:
                record = item.get("record")
                identity = record.get("series_id")
                if type(identity) is not int or not 0 < identity < 10**18 or identity in seen:
                    return "INCOMPLETE_OR_SCHEMA", None
                seen.add(identity)
                # Exact primary or independently published associated name.
                if norm(query) in catalog_record_names(record) or norm(original) in catalog_record_names(record):
                    hits[identity] = record
            if len(seen) == total:
                break
            if not rows or len(seen) > total:
                return "INCOMPLETE_OR_SCHEMA", None
        except (ValueError, TypeError, AttributeError, KeyError):
            return "SCHEMA_ERROR", None
        time.sleep(1)
    if len(seen) != expected_total:
        return "INCOMPLETE_OR_SCHEMA", None
    if len(hits) != 1:
        return "AMBIGUOUS" if hits else "NOT_FOUND", None
    identity = next(iter(hits))
    code, body = request("https://api.mangaupdates.com/v1/series/" + str(identity), "application/json")
    if body is None:
        return code, None
    try:
        row = json.loads(body)
        if (row.get("series_id") != identity or row.get("type") != "Manhwa"
                or not {norm(query), norm(original)} <= catalog_record_names(row)
                or not isinstance(row.get("authors"), list) or not row["authors"]):
            return "DETAIL_UNVERIFIED", None
        if not any(isinstance(author, dict) and isinstance(author.get("name"), str)
                   and author["name"].strip() for author in row["authors"]):
            return "DETAIL_UNVERIFIED", None
    except (ValueError, TypeError, AttributeError):
        return "SCHEMA_ERROR", None
    return "VERIFIED", identity


def compare_catalog_links(refs, expected):
    ids = {identity for ref in refs if (identity := mangaupdates_series_id(ref))}
    # No inference from sameAs pointing to a domain or a different record.
    if expected is None:
        return "INDEPENDENT_UNVERIFIED", len(ids)
    if not ids:
        return "NO_SUPPORTED_REFERENCE", 0
    if ids == {expected}:
        return "EXACT_CATALOG_LINK", 1
    if expected in ids:
        return "CONFLICTING_CATALOG_LINKS", len(ids)
    return "DIFFERENT_CATALOG_LINK", len(ids)


def detail_counts(body, work_url, query, index=None, expected_mu=None):
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
    # Only exact structured references from the matched work. A host or a
    # URL alone is a diagnostic, not cross-catalog identity authority.
    refs = []
    for obj in matches:
        value = obj.get("sameAs")
        if isinstance(value, str):
            refs.append(value)
        elif isinstance(value, list):
            refs.extend(v for v in value[:32] if isinstance(v, str))
    trusted = {"www.webtoons.com", "webtoons.com", "comic.naver.com",
               "m.comic.naver.com", "shikimori.io", "www.mangaupdates.com",
               "mangaupdates.com", "mangadex.org"}
    reference_hosts = set()
    for value in refs[:32]:
        try:
            parsed = urllib.parse.urlsplit(value)
            if parsed.scheme == "https" and parsed.username is None and parsed.hostname in trusted:
                reference_hosts.add(parsed.hostname)
        except ValueError:
            continue
    spin_off = sum(bool(re.search(r"\b(?:spin[- ]?off|sequel|side story)\b", n, re.I))
                   for obj in matches for n in names(obj) if isinstance(n, str))
    crosswalk, mu_count = compare_catalog_links(refs[:32], expected_mu) if len(matches) == 1 else ("AMBIGUOUS_DETAIL", 0)
    publisher_ids = {identity for ref in refs[:32] if (identity := publisher_series_id(ref))}
    original = OFFICIAL_WEBTOON.get(index)
    variant = OFFICIAL_VARIANT.get(index)
    return dict(jsonLd=len(parser.json_ld), exactSeries=len(matches), structuredRoles=roles,
                semanticRoleNodes=parser.semantic_roles, visibleRoleLabels=parser.role_labels,
                roleClassNodes=parser.role_class_nodes, editionMarkers=editions,
                spinOffMarkers=spin_off, structuredSameAs=len(refs),
                knownCatalogHosts=len(reference_hosts),
                catalogHostNames=",".join(sorted(reference_hosts)) or "NONE",
                exactOriginalPublisher=int(original is not None and original in publisher_ids),
                exactVariantPublisher=int(variant is not None and variant in publisher_ids),
                otherPublisherIds=len(publisher_ids - {original, variant}),
                muReferenceIds=mu_count, muCrosswalk=crosswalk)


def observe_dot(works):
    # Obtain an independent catalog identity before inspecting any source link.
    # Do not change the existing catalog owner plan or treat an observation
    # as a verified canonical binding. Retry only via a future explicit run.
    expected = {}
    for index in (15, 21, 27):
        status, identity = independently_resolve_mu(works[index - 1], works[index - 1])
        print(f"MU_INDEPENDENT|index={index}|status={status}", flush=True)
        expected[index] = identity
        if status in ("HTTP403", "HTTP429"):
            break
        time.sleep(1)
    # Frozen diagnostics only; do not infer absence from bounded title searches.
    # Korean publisher's exact native title for Teenage Mercenary:
    # https://comic.naver.com/webtoon/list?mobile=y&titleId=758150
    # Both spellings are retrieval hints only; record-specific credits
    # or explicit independent links must still prove identity.
    for index, query, scope in ((15, works[14], "PRIMARY"),
                                (21, "입학용병", "NATIVE"),
                                (21, "Mercenary Enrollment", "ENGLISH_ALIAS"),
                                (27, works[26], "PRIMARY")):
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
        print(f"DOT_WIRE|index={index}|scope={scope}|state={state}|exactRecords={len(found)}", flush=True)
        if state in ("HTTP403", "HTTP429"):
            return
        for ordinal, (hid, fields) in enumerate(list(found.items())[:3], 1):
            url = "https://mangadot.net/manga/" + hid
            time.sleep(1)
            code, body = request(url, "text/html")
            counts = detail_counts(body, url, query, index, expected.get(index)) if body is not None else {}
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
    series["sameAs"] = ["https://www.webtoons.com/en/test/list?title_no=42",
                        "https://www.webtoons.com.evil.invalid/42",
                        "https://user@comic.naver.com/webtoon/list?titleId=42"]
    series["alternateName"] = ["Fictional work: Spin-off"]
    linked = ('<script type="application/ld+json">' + json.dumps(series) + '</script>').encode()
    observed = detail_counts(linked, url, "Fictional work")
    assert observed["knownCatalogHosts"] == observed["spinOffMarkers"] == 1
    assert publisher_series_id("https://www.webtoons.com/en/fantasy/test/list?title_no=42") == "42"
    assert publisher_series_id("https://www.webtoons.com/en/fantasy/test/list?title_no=42&title_no=43") is None
    assert publisher_series_id("https://www.webtoons.com.evil.invalid/en/fantasy/test/list?title_no=42") is None
    assert publisher_series_id("https://user@www.webtoons.com/en/fantasy/test/list?title_no=42") is None
    series["sameAs"] = [
        "https://www.webtoons.com/en/fantasy/example/list?title_no=2135",
        "https://www.webtoons.com/en/romance/example-spin-off/list?title_no=11640",
    ]
    rival = ('<script type="application/ld+json">' + json.dumps(series) + '</script>').encode()
    observed_rival = detail_counts(rival, url, "Fictional work", 27)
    assert observed_rival["exactOriginalPublisher"] == observed_rival["exactVariantPublisher"] == 1
    assert detail_counts(linked, url + "0", "Fictional work")["knownCatalogHosts"] == 0
    assert detail_counts(html, url + "0", "Fictional work")["exactSeries"] == 0
    assert detail_counts(html, url, "Other work")["structuredRoles"] == 0
    mu = "https://www.mangaupdates.com/series/abc123/example"
    identity = int("abc123", 36)
    assert mangaupdates_series_id(mu) == identity
    assert mangaupdates_series_id("https://www.mangaupdates.com/series/123/example") == int("123", 36)
    for invalid in ("https://www.mangaupdates.com.evil.invalid/series/abc123/example",
                    "https://user@www.mangaupdates.com/series/abc123/example",
                    "https://www.mangaupdates.com/series.html?id=123",
                    "https://www.mangaupdates.com/series/abc123/example?x=1",
                    "https://www.mangaupdates.com:8443/series/abc123/example"):
        assert mangaupdates_series_id(invalid) is None
    assert compare_catalog_links([mu], identity) == ("EXACT_CATALOG_LINK", 1)
    assert compare_catalog_links([mu], None) == ("INDEPENDENT_UNVERIFIED", 1)
    assert compare_catalog_links([mu], identity + 1) == ("DIFFERENT_CATALOG_LINK", 1)
    assert compare_catalog_links([mu, "https://www.mangaupdates.com/series/abc124/example"], identity) == ("CONFLICTING_CATALOG_LINKS", 2)
    assert compare_catalog_links([mu, mu], identity) == ("EXACT_CATALOG_LINK", 1)
    assert catalog_record_names({"title":"Work", "associated":[{"title":"Alias"}]}) == {"work", "alias"}
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
