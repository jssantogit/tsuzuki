"""Bounded, anonymous metadata-only investigation; never fetches reading pages.

Default offline: supply --live explicitly. Responses are cached locally; public
exports contain factual projections only, not synopses, credentials or content.
"""
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
UA = 'Tsuzuki-Issue154-Research/1.0 (https://github.com/jssantogit/tsuzuki/issues/154)'
HOSTS = {'kitsu.app', 'api.mangaupdates.com', 'api.bgm.tv', 'api.mangadex.org'}
QUERIES = ['Death Note', 'Nanatsu no Taizai', 'One Punch Man', 'Berserk',
           'One Piece', 'Look Back', 'Solo Leveling', 'Mo Dao Zu Shi',
           'Re:Zero kara Hajimeru Isekai Seikatsu', 'Solanin']
LIVE = False
LAST = {}


def request(source, url, body=None):
    host = urllib.parse.urlsplit(url).hostname
    if host not in HOSTS:
        raise ValueError('Host outside explicit allowlist')
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    method = 'POST' if payload is not None else 'GET'
    # Only the three explicitly allowed POST search endpoints are permitted.
    if method == 'POST' and urllib.parse.urlsplit(url).path not in (
            '/v1/series/search', '/v1/releases/search', '/v0/search/subjects'):
        raise ValueError('POST outside read-only search allowlist')
    accept = 'application/vnd.api+json' if source == 'kitsu' else 'application/json'
    key = hashlib.sha256((method + url + accept).encode() + (payload or b'')).hexdigest()
    cache = ROOT / 'cache' / (key + '.json')
    if cache.exists():
        return json.loads(cache.read_text(encoding='utf8'))
    if not LIVE:
        raise RuntimeError('Cache miss: explicit --live is required')
    log = ROOT / 'requests.jsonl'
    previous = [json.loads(x) for x in log.read_text().splitlines()] if log.exists() else []
    if len(previous) >= 160 or sum(x['source'] == source for x in previous) >= 45:
        raise RuntimeError('Request budget exhausted')
    if any(x['source'] == source and x['status'] in (403, 429) for x in previous):
        raise RuntimeError('Source stopped after access/rate restriction; no bypass')
    time.sleep(max(0, 1.1 - (time.monotonic() - LAST.get(host, 0))))
    headers = {'User-Agent': UA, 'Accept': accept}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    start = time.monotonic()
    timestamp = dt.datetime.now(dt.timezone.utc).isoformat()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=payload, headers=headers), timeout=20) as r:
            data = r.read(4_000_001)
            if len(data) > 4_000_000:
                raise RuntimeError('Response limit exceeded')
            status, content_type = r.status, r.headers.get('Content-Type')
            validators = {k: r.headers.get(k) for k in ['ETag', 'Last-Modified', 'Retry-After', 'X-RateLimit-Limit', 'X-RateLimit-Remaining'] if r.headers.get(k)}
    except urllib.error.HTTPError as e:
        data, status, content_type, validators = e.read(4096), e.code, e.headers.get('Content-Type'), {}
    except (urllib.error.URLError, TimeoutError) as e:
        data, status, content_type, validators = str(type(e).__name__).encode(), 0, None, {}
    LAST[host] = time.monotonic()
    try:
        result = json.loads(data)
    except (ValueError, UnicodeDecodeError):
        result = None
    receipt = {'source': source, 'method': method, 'url': url, 'body': body,
               'observedAt': timestamp, 'status': status, 'elapsedMs': round((time.monotonic()-start)*1000),
               'bytes': len(data), 'responseSha256': hashlib.sha256(data).hexdigest(),
               'contentType': content_type, 'validators': validators}
    cache.parent.mkdir(exist_ok=True)
    cache.write_text(json.dumps({'receipt': receipt, 'data': result}, ensure_ascii=False), encoding='utf8')
    with log.open('a', encoding='utf8') as f:
        f.write(json.dumps(receipt, ensure_ascii=False)+'\n')
    print(f'{source}: {status} {receipt["elapsedMs"]}ms {urllib.parse.urlsplit(url).path}', flush=True)
    return {'receipt': receipt, 'data': result}


def get(source, path, params=None):
    bases = {'kitsu': 'https://kitsu.app/api/edge', 'mu': 'https://api.mangaupdates.com/v1',
             'bgm': 'https://api.bgm.tv/v0', 'md': 'https://api.mangadex.org'}
    return request(source, bases[source]+path+('?' + urllib.parse.urlencode(params, doseq=True) if params else ''))


def searches():
    output = []
    for q in QUERIES:
        for source in ['kitsu', 'mu', 'bgm', 'md']:
            try:
                if source == 'kitsu':
                    r = get(source, '/manga', {'filter[text]': q, 'page[limit]': 8})
                    candidates = [{'id': x['id'], 'title': x['attributes'].get('canonicalTitle'),
                                   'titles': x['attributes'].get('titles'), 'format': x['attributes'].get('subtype'),
                                   'status': x['attributes'].get('status'), 'count': x['attributes'].get('chapterCount'),
                                   'start': x['attributes'].get('startDate')} for x in (r['data'] or {}).get('data', [])]
                elif source == 'mu':
                    r = request(source, 'https://api.mangaupdates.com/v1/series/search', {'search': q, 'perpage': 8, 'page': 1})
                    candidates = [{'id': x['record'].get('series_id'), 'title': x['record'].get('title'),
                                   'type': x['record'].get('type'), 'year': x['record'].get('year')} for x in (r['data'] or {}).get('results', [])]
                elif source == 'bgm':
                    r = request(source, 'https://api.bgm.tv/v0/search/subjects?limit=8&offset=0',
                                {'keyword': q, 'filter': {'type': [1], 'nsfw': False}})
                    candidates = [{'id': x.get('id'), 'title': x.get('name'), 'name_cn': x.get('name_cn'),
                                   'date': x.get('date'), 'type': x.get('type')} for x in (r['data'] or {}).get('data', [])]
                else:
                    r = get(source, '/manga', {'title': q, 'limit': 8, 'contentRating[]': ['safe', 'suggestive']})
                    candidates = [{'id': x['id'], 'title': x['attributes'].get('title'), 'altTitles': x['attributes'].get('altTitles'),
                                   'language': x['attributes'].get('originalLanguage'), 'status': x['attributes'].get('status'),
                                   'year': x['attributes'].get('year'), 'links': x['attributes'].get('links')} for x in (r['data'] or {}).get('data', [])]
                output.append({'query': q, 'source': source, 'receipt': r['receipt'], 'candidates': candidates})
            except RuntimeError as e:
                output.append({'query': q, 'source': source, 'notRun': str(e)})
        (ROOT/'search-results.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf8')


def inventories():
    corpus = json.loads((ROOT/'corpus.json').read_text(encoding='utf8'))
    results = []
    for work in corpus:
        for source, external_id in work['ids'].items():
            if source == 'kitsu':
                r = get(source, f'/manga/{external_id}/chapters', {'page[limit]': 20, 'page[offset]': 0})
            elif source == 'mu':
                r = request(source, 'https://api.mangaupdates.com/v1/releases/search',
                            {'search': str(external_id), 'search_type': 'series', 'page': 1, 'perpage': 25, 'include_metadata': True})
            elif source == 'bgm':
                r = get(source, '/episodes', {'subject_id': external_id, 'limit': 200, 'offset': 0})
            else:
                r = get(source, f'/manga/{external_id}/feed', {'limit': 100, 'offset': 0, 'order[chapter]': 'asc',
                        'contentRating[]': ['safe', 'suggestive', 'erotica'], 'includeFutureUpdates': 0})
            results.append(project(work['key'], source, external_id, r))
            (ROOT/'inventory-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf8')


def project(work, source, external_id, r):
    d = r['data'] or {}
    if source == 'mu':
        rows = [{'id': x['record'].get('id'), 'chapter': x['record'].get('chapter'),
                 'volume': x['record'].get('volume'), 'date': x['record'].get('release_date'),
                 'seriesId': (x.get('metadata') or {}).get('series', {}).get('series_id'),
                 'groupIds': [g.get('group_id') for g in x['record'].get('groups', [])]} for x in d.get('results', [])]
        total, next_page = d.get('total_hits'), d.get('page', 1)*d.get('per_page',25) < d.get('total_hits',0)
    elif source == 'bgm':
        rows = [{'id': x.get('id'), 'number': x.get('ep'), 'sort': x.get('sort'), 'name': x.get('name'),
                 'name_cn': x.get('name_cn'), 'type': x.get('type'), 'date': x.get('airdate')} for x in d.get('data', [])]
        total, next_page = d.get('total'), len(rows)+d.get('offset',0) < d.get('total',0)
    elif source == 'kitsu':
        rows = [{'id': x.get('id'), 'number': x['attributes'].get('number'), 'volume': x['attributes'].get('volumeNumber'),
                 'title': x['attributes'].get('canonicalTitle'), 'titles': x['attributes'].get('titles'),
                 'published': x['attributes'].get('published'), 'attributesPresent': sorted(x['attributes'].keys())} for x in d.get('data', [])]
        total, next_page = d.get('meta',{}).get('count'), d.get('links',{}).get('next')
    else:
        rows = [{'id': x.get('id'), 'chapter': x['attributes'].get('chapter'), 'volume': x['attributes'].get('volume'),
                 'title': x['attributes'].get('title'), 'language': x['attributes'].get('translatedLanguage'),
                 'publishAt': x['attributes'].get('publishAt'), 'updatedAt': x['attributes'].get('updatedAt'),
                 'version': x['attributes'].get('version'), 'externalLink': bool(x['attributes'].get('externalUrl')),
                 'pagesReported': x['attributes'].get('pages')} for x in d.get('data', [])]
        total, next_page = d.get('total'), len(rows)+d.get('offset',0) < d.get('total',0)
    return {'work': work, 'source': source, 'externalId': external_id, 'receipt': r['receipt'],
            'totalReportedByEndpoint': total, 'returned': len(rows), 'hasNext': bool(next_page), 'rows': rows}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--live', action='store_true')
    p.add_argument('mode', choices=['search', 'inventory'])
    args = p.parse_args()
    LIVE = args.live
    searches() if args.mode == 'search' else inventories()
