#!/usr/bin/env node
// MangaALL: nonshipping, two-source live investigation in Tsuzuki GitHub Actions.
// The signed code10 archive is NEVER rebuilt, edited, re-signed or published.
// The only in-memory change is the source list of its original V1 router.
// Every MangaDex/MangaDot adapter is imported unchanged at a pinned Git SHA.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ACK = 'I_ACCEPT_MANGAALL_TWO_SOURCE_30_LIVE';
const SOURCES = ['mangadex', 'mangadot'];
const PROVIDER_SHA = '195502f981bd776a68feebde3efc6d20f55cca65';
const SOURCE_TOKEN = 'const SOURCE_IDS = Object.freeze(["mangadex", "mangadot", "mangafire"]);';
const ACTIVE_TOKEN = 'const SOURCE_IDS = Object.freeze(["mangadex", "mangadot"]);';
const ORIGINS = Object.freeze({
  'https://api.mangadex.org': 'mangadex',
  'https://mangadot.net': 'mangadot',
});
const raw = JSON.parse(readFileSync('.github/mangaall-dual-source-30-request.json', 'utf8'));
assert.ok(raw && typeof raw === 'object' && !Array.isArray(raw));
assert.deepEqual(Object.keys(raw).sort(), ['acknowledge', 'works']);
assert.equal(raw.acknowledge, ACK);
assert.ok(Array.isArray(raw.works) && raw.works.length === 30);
assert.ok(raw.works.every(v => typeof v === 'string' &&
  v.length > 0 && v.length <= 180 && !/https?:\/\/|[\r\n\x00-\x1f]/i.test(v)));
const formatted = raw.works.map(v => 'manga :: ' + v).join('\n');
const providerRoot = path.resolve('signed-code10');
const sourceRoot = path.join(providerRoot, 'providers/mangaall');
const importFromProvider = async p => import(pathToFileURL(path.join(providerRoot, p)).href);
const { parseWorks, shardWorks } = await importFromProvider('tooling/mangaall_generic_works.mjs');
const works = parseWorks(formatted);
assert.equal(works.length, 30);
assert.equal(new Set(works.map(w => w.id)).size, 30);
const shard = Number(process.env.MANGAALL_DUAL30_SHARD ?? '0');
const count = Number(process.env.MANGAALL_DUAL30_SHARD_COUNT ?? '3');
assert.ok(Number.isInteger(shard) && Number.isInteger(count) &&
  count === 3 && shard >= 0 && shard < 3);
const assigned = shardWorks(works, shard, count);
assert.equal(assigned.length, 10);
assert.deepEqual(assigned.map(x => x.id),
  works.filter((_, i) => i % 3 === shard).map(x => x.id));

const routerFile = path.join(sourceRoot, 'modules/router.js');
const original = readFileSync(routerFile, 'utf8');
assert.equal(original.split(SOURCE_TOKEN).length, 2,
  'Original exact MangaALL router source list changed; audit required');
assert.ok(!original.includes('import ') && !original.includes('from "./'),
  'Router is no longer standalone; refuse unsafe in-memory adaptation');
const adapted = original.replace(SOURCE_TOKEN, ACTIVE_TOKEN);
const router = await import('data:text/javascript;base64,' +
  Buffer.from(adapted, 'utf8').toString('base64'));
assert.equal(typeof router.createMangaAllReading, 'function');
const [dexModule, dotModule] = await Promise.all([
  importFromProvider('providers/mangaall/modules/sources/mangadex/main.js'),
  importFromProvider('providers/mangaall/modules/sources/mangadot/main.js'),
]);
assert.deepEqual(Object.keys(dexModule.default.reading).sort(), ['chapters', 'lookup', 'pages']);
assert.deepEqual(Object.keys(dotModule.default.reading).sort(), ['chapters', 'lookup', 'pages']);
// The unchanged signed MangaDot module stops lookup after 3 broad source
// pages, even where the search response explicitly advertises more pages.
// Lift only that bound to 8 in this UNSIGNED analysis to match the current
// Host V1 lookup-page budget. Page 9 remains INCOMPLETE, never "no match".
const originalDotSource = readFileSync(path.join(
  sourceRoot, 'modules/sources/mangadot/modules/mangadot.js',
), 'utf8');
const sourcePageLimit = 'if (n > 3) throw new Error("MangaAll bounded source search exhausted");';
assert.equal(originalDotSource.split(sourcePageLimit).length, 2,
  'MangaDot source bound changed: review the pinned adapter before live traffic');
const originalHttpImport = 'from "./http.js"';
assert.equal(originalDotSource.split(originalHttpImport).length, 2,
  'MangaDot source import changed');
// Upstream MangaDot's 2MiB *source adapter* response guard is independent
// of the CI bridge's separate 8MiB guard. Bleach returned 2,406,521 bytes
// and was falsely limited by this earlier guard. Override neither the
// signed archive nor the production Host: build a nonshipping in-memory copy
// of the EXACT immutable HTTP helper with a bounded experimental 8MiB ceiling.
const originalDotHttp = readFileSync(path.join(
  sourceRoot, 'modules/sources/mangadot/modules/http.js',
), 'utf8');
const dotCeiling = 'const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;';
assert.equal(originalDotHttp.split(dotCeiling).length, 2,
  'MangaDot signed source response bound changed; independent audit required');
const adaptedDotHttp = originalDotHttp.replace(
  dotCeiling, 'const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;',
);
const adaptedDotHttpUri = 'data:text/javascript;base64,' +
  Buffer.from(adaptedDotHttp, 'utf8').toString('base64');
const experimentalDotCode = originalDotSource
  .replace(sourcePageLimit,
    'if (n > 8) throw new Error("MangaAll bounded source search exhausted");')
  .replace(originalHttpImport, 'from "' + adaptedDotHttpUri + '"');
const extendedMangaDot = await import('data:text/javascript;base64,' +
  Buffer.from(experimentalDotCode, 'utf8').toString('base64'));
assert.equal(typeof extendedMangaDot.lookup, 'function');
assert.equal(typeof extendedMangaDot.chapters, 'function');

// MangaDex chapter JSON for certain prolific works exceeded the original
// 192KiB transport ceiling with 100 entries. Trial a conservative 50-entry
// original-source page (normal nextCursor/offset pagination stays intact).
// No chapter is dropped or inferred. The 32-page audition cap is independent.
const originalDexSource = readFileSync(path.join(
  sourceRoot, 'modules/sources/mangadex/modules/mangadex.js',
), 'utf8');
const dexPageSize = 'const CHAPTER_PAGE_SIZE = 100;';
const dexImport = 'from "./http.js"';
assert.equal(originalDexSource.split(dexPageSize).length, 2,
  'MangaDex chapter page size changed; audit before injecting');
assert.equal(originalDexSource.split(dexImport).length, 2,
  'MangaDex chapter helper import changed');
const dexHttpModuleUri = pathToFileURL(path.join(
  sourceRoot, 'modules/sources/mangadex/modules/http.js',
)).href;
const experimentalDexCode = originalDexSource
  .replace(dexPageSize, 'const CHAPTER_PAGE_SIZE = 50;')
  .replace(dexImport, 'from "' + dexHttpModuleUri + '"');
const extendedMangaDex = await import('data:text/javascript;base64,' +
  Buffer.from(experimentalDexCode, 'utf8').toString('base64'));
assert.equal(typeof extendedMangaDex.chapters, 'function');
// Test-only correction: the signed MangaDot adapter yields a complete
// upstream chapter list in one Host-sized result (often >64KiB). Preserve all
// original source items and split only OUTPUT into <=48KiB/200-item pages.
// Cache is per CI process, NOT a shippable persistence mechanism.
const chapterCache = new Map();
function granularPage(items, offset, fingerprint) {
  if (!Number.isInteger(offset) || offset < 0 || offset > items.length) {
    throw Error('MangaAll invalid cursor');
  }
  const page = [];
  for (let index = offset; index < items.length && page.length < 200; index++) {
    const next = index + 1 < items.length ? 'g.' + (index + 1) + '.' + fingerprint : null;
    const candidate = [...page, items[index]];
    if (Buffer.byteLength(JSON.stringify({ items: candidate, nextCursor: next }), 'utf8') >
        48 * 1024) {
      if (!page.length) throw Error('MangaAll single chapter exceeds Host page budget');
      break;
    }
    page.push(items[index]);
  }
  const newOffset = offset + page.length;
  return {
    items: page,
    nextCursor: newOffset < items.length
      ? 'g.' + newOffset + '.' + fingerprint : null,
  };
}
const granularMangaDotAdapter = {
  ...dotModule.default.reading,
  lookup: extendedMangaDot.lookup,
  async chapters(input) {
    const workId = input?.binding?.externalWorkId;
    const language = input?.binding?.facetId;
    if (typeof workId !== 'string' ||
        !/^mangadot:[1-9][0-9]{0,15}$/.test(workId) || language !== 'en') {
      throw Error('MangaAll invalid work binding');
    }
    const key = workId + '|' + language;
    const fingerprint = createHash('sha256').update(key).digest('hex').slice(0, 12);
    const cursor = input?.cursor ?? null;
    let offset = 0;
    if (cursor != null) {
      const match = /^g\.([0-9]{1,5})\.([a-f0-9]{12})$/.exec(cursor);
      if (!match || match[2] !== fingerprint) throw Error('MangaAll invalid cursor');
      offset = Number(match[1]);
    }
    let rows = chapterCache.get(key);
    if (!rows) {
      const page = await extendedMangaDot.chapters({ ...input, cursor: null });
      if (!Array.isArray(page?.items) || page.items.length > 16000 ||
          page.nextCursor != null) throw Error('MangaAll invalid chapter response');
      rows = page.items;
      chapterCache.set(key, rows);
    }
    if (offset === 0 && cursor != null) throw Error('MangaAll invalid cursor');
    return granularPage(rows, offset, fingerprint);
  },
};
const dualReading = router.createMangaAllReading({
  mangadex: { ...dexModule.default.reading, chapters: extendedMangaDex.chapters },
  mangadot: granularMangaDotAdapter,
});

function safeTestId(value) {
  return typeof value === 'string' && /^(mangadex|mangadot):[A-Za-z0-9._-]{1,128}$/.test(value);
}
// Source work IDs and chapter IDs have distinct grammars.
function safeChapterId(value, source) {
  if (typeof value !== 'string') return false;
  return source === 'mangadot'
    ? /^mangadot:(uploads|chapters):[1-9][0-9]{0,15}$/.test(value)
    : source === 'mangadex' &&
      /^mangadex:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
}
function sourceOf(id) { return id.split(':', 1)[0]; }
function closedError(err) {
  const message = typeof err?.message === 'string' ? err.message : '';
  const http = /\bHTTP ([1-5]\d{2})\b/.exec(message);
  if (http) return 'HTTP_' + http[1];
  if (/invalid (?:cursor|chapter|source|binding|language|response)/i.test(message)) {
    return 'INVALID_CONTRACT';
  }
  if (/response too large/i.test(message)) return 'OVERSIZED_UPSTREAM_RESPONSE';
  if (/request limit/i.test(message)) return 'SOURCE_REQUEST_BUDGET';
  if (/timeout/i.test(message) || err?.name === 'TimeoutError') return 'TIMEOUT';
  return 'UPSTREAM_OR_RUNTIME_ERROR';
}
function requireClosedPage(value) {
  return value && typeof value === 'object' && !Array.isArray(value) &&
    Array.isArray(value.items) && value.items.length <= 16000 &&
    (value.nextCursor == null || (typeof value.nextCursor === 'string' &&
      value.nextCursor.length > 0 && value.nextCursor.length <= 512));
}

// No automatic binding to a canonical edition. Diagnose chapter inventories
// under every *exact-match* source HID, even when cross-source ambiguity makes
// a single editorial choice unauthorized. Each observation uses a real source.
async function auditSourceChapters(candidate) {
  const source = candidate.source;
  const audit = {
    source, authority: 'SOURCE_HID_DIAGNOSTIC_ONLY', outcome: 'NOT_PROBED',
    pages: 0, chapterCount: 0, chapterOne: false, terminal: false,
    duplicateIds: false, invalidContract: false, cursorLoop: false,
    numberingGapObserved: false, maxPageBytes: 0, host64KiBExceeded: false,
  };
  const binding = {
    providerId: 'app.tsuzuki.mangaall', facetId: 'en',
    externalWorkId: candidate.workId,
  };
  let cursor = null;
  const seenCursors = new Set(), seenIds = new Set(), numbers = new Set();
  for (let i = 0; i < 32; i++) {
    let page;
    try {
      page = await dualReading.chapters({ binding, cursor });
    } catch (err) {
      audit.outcome = 'SOURCE_ERROR';
      audit.error = closedError(err);
      return audit;
    }
    audit.pages++;
    const bytes = Buffer.byteLength(JSON.stringify(page), 'utf8');
    audit.maxPageBytes = Math.max(bytes, audit.maxPageBytes);
    if (bytes > 64 * 1024) audit.host64KiBExceeded = true;
    if (!requireClosedPage(page)) {
      audit.invalidContract = true;
      audit.outcome = 'INVALID_PAGE_CONTRACT';
      return audit;
    }
    for (const item of page.items) {
      const id = item?.providerChapterId;
      if (!safeChapterId(id, source) || sourceOf(id) !== source ||
        !Number.isFinite(item.rawNumber) || item.rawNumber < 0 ||
        (item.language != null && item.language !== 'en')) {
        audit.invalidContract = true;
        audit.outcome = 'INVALID_CHAPTER_ID_OR_LANGUAGE';
        return audit;
      }
      if (seenIds.has(id)) {
        audit.duplicateIds = true;
        audit.outcome = 'DUPLICATE_CHAPTER_ID';
        return audit;
      }
      seenIds.add(id);
      numbers.add(item.rawNumber);
      if (item.rawNumber === 1) audit.chapterOne = true;
    }
    audit.chapterCount = seenIds.size;
    if (page.nextCursor == null) {
      audit.terminal = true;
      audit.outcome = audit.chapterCount ? 'COMPLETE' : 'COMPLETE_EMPTY';
      break;
    }
    if (page.nextCursor === cursor || seenCursors.has(page.nextCursor)) {
      audit.cursorLoop = true;
      audit.outcome = 'CURSOR_LOOP';
      return audit;
    }
    seenCursors.add(page.nextCursor);
    cursor = page.nextCursor;
    audit.outcome = 'PARTIAL_PAGE_LIMIT';
  }
  const positive = [...numbers].filter(n => Number.isInteger(n) && n > 0).sort((a,b) => a-b);
  audit.numberingGapObserved = positive.some((n,i) => i && n > positive[i-1] + 1);
  return audit;
}

// This offline probe creates synthetic adapters inside the same two-source
// router. It proves that no path can invoke MangaFire or silently complete a
// failed lookup before any source traffic is authorized.
async function offlineContracts() {
  const syntheticChapters = Array.from({length: 501}, (_,i) => ({
    providerChapterId: 'mangadot:chapters:' + (i + 1),
    rawNumber: i + 1, language: null,
  }));
  let start = 0, pageCount = 0, count = 0;
  const cursorMark = 'abcdef012345';
  do {
    const page = granularPage(syntheticChapters, start, cursorMark);
    assert.ok(page.items.length > 0 && page.items.length <= 200);
    assert.ok(Buffer.byteLength(JSON.stringify(page), 'utf8') <= 48 * 1024);
    assert.ok(page.items.every(x => safeChapterId(x.providerChapterId, 'mangadot')));
    count += page.items.length;
    pageCount++;
    if (page.nextCursor == null) break;
    const m = /^g\.([0-9]{1,5})\.([a-f0-9]{12})$/.exec(page.nextCursor);
    assert.equal(m[2], cursorMark);
    start = Number(m[1]);
  } while (pageCount < 32);
  assert.equal(count, 501);
  assert.equal(pageCount, 3);
  assert.throws(() => granularPage(syntheticChapters, 600, cursorMark));
  assert.equal(safeChapterId('mangadot:uploads:123', 'mangadot'), true);
  assert.equal(safeChapterId('mangadot:123', 'mangadot'), false);
  const called = [];
  const candidate = (source, id) => ({
    externalWorkId: source + ':' + id, title: 'Akame ga Kill!',
    aliases: [], authors: ['Test Author'], language: 'en',
  });
  const mocked = router.createMangaAllReading({
    mangadex: { async lookup() { called.push('mangadex'); return {
      items: [candidate('mangadex', 'abc')], nextCursor: null,
    }; } },
    mangadot: { async lookup() { called.push('mangadot'); return {
      items: [candidate('mangadot', '101')], nextCursor: null,
    }; } },
    mangafire: { async lookup() { throw Error('FORBIDDEN_THIRD_SOURCE'); } },
  });
  const found = await mocked.lookup({ titles: ['Akame ga Kill!'],
    requestedLanguage: 'en', diagnosticsVersion: 1 });
  assert.equal(found.items.length, 2);
  assert.equal(found.nextCursor, null);
  assert.deepEqual(called.sort(), SOURCES);
  assert.ok(found.items.every(x => safeTestId(x.externalWorkId)));
  await assert.rejects(() => mocked.chapters({
    binding: { providerId: 'app.tsuzuki.mangaall', facetId: 'en',
      externalWorkId: 'mangafire:zk12' }, cursor: null,
  }));
  const failure = router.createMangaAllReading({
    mangadex: { async lookup() { throw Error('MangaAll HTTP 503'); } },
    mangadot: { async lookup() { throw Error('MangaAll HTTP 503'); } },
  });
  let cursor = null, last;
  for (let attempt = 0; attempt < 3; attempt++) {
    last = await failure.lookup({ titles: ['Akame ga Kill!'], requestedLanguage: 'en',
      cursor, diagnosticsVersion: 1 });
    if (!last.nextCursor) break;
    cursor = last.nextCursor;
  }
  assert.equal(last.lookupFailureCode, 'HTTP_ERROR');
  assert.equal(last.lookupHttpStatus, 503);
  assert.equal(last.items.length, 0);
  assert.equal(last.nextCursor, null);
  console.log('MANGAALL_DUAL30_OFFLINE|outcome=PASS|sources=MANGADEX_MANGADOT' +
    '|mangafire=NOT_CALLED|router=ORIGINAL_MODULE_ADAPTED_IN_MEMORY|signedPackage=UNTOUCHED');
}
const mode = process.argv[2];
if (mode === '--offline') {
  await offlineContracts();
  process.exit(0);
}
if (mode === '--plan') {
  console.log(JSON.stringify({
    scope: 'UNSIGNED_TWO_SOURCE_CI_PREVIEW_FROM_IMMUTABLE_CODE10_MODULES',
    sourceRepositoryCommit: PROVIDER_SHA, sources: SOURCES, workCount: 30,
    selectedShard: shard, shardCount: count,
    assigned: assigned.map(w => ({ id: w.id, title: w.names[0] })),
    network: 'NOT_STARTED', signedSourceReissued: false,
    canonicalBinding: 'NOT_AUTHORIZED',
  }));
  process.exit(0);
}
if (mode !== '--live' || process.env.MANGAALL_DUAL30_ACK !== ACK) {
  throw Error('Live MangaALL two-source 30 requires explicit opt-in');
}
const { createHttpBridge } = await importFromProvider('tooling/mangaall_live_probe_core.mjs');
const { probeJourney, journeyVerdict } =
  await importFromProvider('tooling/mangaall_live_journey_core.mjs');
const stats = [], wireRecords = [], bridges = new Map();
for (const [origin, source] of Object.entries(ORIGINS)) {
  bridges.set(origin, createHttpBridge({
    origin, maxRequests: 320,
    maxResponseBytes: source === 'mangadot' ? 8 * 1024 * 1024 : 192 * 1024,
    timeoutMs: 12000,
    fetchImpl: async (url, options) => {
      const start = performance.now();
      try {
        const response = await fetch(url, options);
        stats.push({ source, status: response.status,
          elapsedMs: Math.round(performance.now() - start) });
        return response;
      } catch (err) {
        stats.push({ source, status: null, elapsedMs: Math.round(performance.now() - start) });
        throw err;
      }
    },
  }));
}
let prohibitedCalls = 0;
globalThis.tsuzuki = { http: { async request(method,url,headers,body) {
  let origin;
  try { origin = new URL(url).origin; } catch { throw Error('Invalid HTTP destination'); }
  const bridge = bridges.get(origin);
  if (!bridge) {
    prohibitedCalls++;
    throw Error('Unauthorized two-source HTTP origin');
  }
  const encoded = await bridge.request(method,url,headers,body);
  const response = JSON.parse(encoded);
  wireRecords.push({
    source: ORIGINS[origin],
    bytes: typeof response.body === 'string' ? Buffer.byteLength(response.body, 'utf8') : 0,
  });
  return encoded;
} } };
const grants = [
  'https://api.mangadex.org', 'https://uploads.mangadex.org',
  'https://*.mangadex.network', 'https://mangadot.net',
];
const results = [];
for (const [index, work] of assigned.entries()) {
  const before = stats.length;
  const beforeWire = wireRecords.length;
  let probe, failure = null;
  try {
    probe = await probeJourney(dualReading, work, grants, {
      lookupContract: 'v1', lookupPageLimit: 8, chapterPageLimit: 8,
      includeCandidates: true, includeCandidateCoverage: true,
      candidatePreviewLimit: 3, candidateChapterPageLimit: 2,
    });
  } catch (err) {
    failure = closedError(err);
    probe = { case: work.id, lookup: 'INTERNAL_ERROR', lookupComplete: false,
      chapters: 'NOT_TESTED', pages: 'NOT_TESTED', candidateCount: 0 };
  }
  const candidates = (probe.candidateSummaries || [])
    .filter(x => x.exactMatch === true && safeTestId(x.workId))
    .filter(x => SOURCES.includes(x.source));
  // Source-scoped audits only. An unresolved rival is never auto-bound.
  const audits = [];
  for (const source of SOURCES) {
    const sourceCandidates = candidates.filter(x => x.source === source);
    for (const candidate of sourceCandidates.slice(0, 2)) {
      audits.push(await auditSourceChapters(candidate));
    }
  }
  const observations = stats.slice(before);
  const wireObserved = wireRecords.slice(beforeWire);
  const bySource = Object.fromEntries(SOURCES.map(source => [source, {
    maxUpstreamBodyBytes: Math.max(0, ...wireObserved.filter(x => x.source === source).map(x => x.bytes)),
    upstreamOver192KiB: wireObserved.filter(x => x.source === source && x.bytes > 192 * 1024).length,
    requests: observations.filter(x => x.source === source).length,
    http200: observations.filter(x => x.source === source && x.status === 200).length,
    http403: observations.filter(x => x.source === source && x.status === 403).length,
    http429: observations.filter(x => x.source === source && x.status === 429).length,
    otherHttp: observations.filter(x => x.source === source && x.status != null &&
      ![200,403,429].includes(x.status)).length,
    transportError: observations.filter(x => x.source === source && x.status == null).length,
  }]));
  const verdict = failure ? 'INTERNAL_ERROR' : journeyVerdict(probe);
  const record = {
    id: work.id, title: work.names[0], position: works.findIndex(x => x.id === work.id),
    format: 'manga', verdict, failure, lookup: probe.lookup,
    lookupPages: probe.lookupPages ?? 0, lookupComplete: probe.lookupComplete === true,
    candidateCount: probe.candidateCount ?? 0,
    candidateSummariesTruncated: probe.candidateSummariesTruncated === true,
    candidates: (probe.candidateSummaries || []).filter(x => SOURCES.includes(x.source)),
    unresolvedCandidates: probe.unresolvedCandidates === true,
    sameSourceRivals: probe.sameSourceRivals === true,
    sourceAuthorDisagreement: probe.sourceAuthorDisagreement === true,
    candidateChapterPreviews: (probe.candidateChapterPreviews || []).filter(x =>
      SOURCES.includes(x.source)),
    chapters: probe.chapters, chapterPages: probe.chapterPages || 0,
    chapterCount: probe.chapterCount || 0, chapterOnePresent: probe.chapterOnePresent === true,
    chapterHasMore: probe.chapterHasMore === true, numberingGapObserved:
      probe.numberingGapObserved === true,
    sourceChapterAudits: audits,
    granularChapterPagination: 'UNSIGNED_CI_EPHEMERAL_CACHE_48K_200',
    auditCandidatesTruncated: candidates.some(x =>
      candidates.filter(y => y.source === x.source).length > 2),
    httpBySource: bySource,
    canonicalBinding: 'NOT_AUTHORIZED',
    readerPixels: 'NOT_TESTED',
  };
  results.push(record);
  console.log('MANGAALL_DUAL30_WORK|shard=' + shard + '|position=' + record.position +
    '|lookup=' + String(record.lookup).replace(/[^A-Z_]/g,'') +
    '|candidates=' + record.candidateCount + '|ambiguous=' + record.unresolvedCandidates +
    '|audited=' + audits.length + '|verdict=' + verdict);
}
assert.equal(results.length, 10);
assert.equal(prohibitedCalls, 0, 'An attempt to access a prohibited third provider occurred');
const report = {
  schema: 1, kind: 'MANGAALL_TWO_SOURCE_UNSIGNED_CI_PREVIEW',
  hostRepository: 'jssantogit/tsuzuki', sourceRepositoryCommit: PROVIDER_SHA,
  originalCode10SignedPackageChanged: false, adapterRouterModification: 'SOURCE_IDS_ONLY_IN_MEMORY',
  chapterAdaptation: 'MANGADOT_IN_MEMORY_48K_200_NO_RELEASE',
  lookupAdaptation: 'MANGADOT_EIGHT_SOURCE_PAGES_IN_MEMORY',
  acquisitionAdaptation: 'UNSIGNED_DOT8M_DEX50_CHAPTERS_CI_ONLY',
  sourceSet: SOURCES, shard, shardCount: count, totalWorkCount: 30,
  sourceHttp: 'REAL_GITHUB_ACTIONS_FETCH', canonical: 'NOT_TESTED',
  readerPixels: 'NOT_TESTED', sourceRequestBudget: 320,
  results, prohibitedThirdSourceRequests: prohibitedCalls,
  sourceRequests: Object.fromEntries(SOURCES.map(s => [s, bridges.get(
    Object.keys(ORIGINS).find(origin => ORIGINS[origin] === s)).requestCount])),
};
const out = process.env.MANGAALL_DUAL30_OUTPUT ||
  '.github/results/mangaall-dual-source-30/shard-' + shard + '.json';
mkdirSync(path.dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
console.log('MANGAALL_DUAL30_SHARD|shard=' + shard + '|works=10|sources=2' +
  '|output=RECORDED|mangafire=NOT_CALLED|canonical=NOT_TESTED|signedCode10=UNTOUCHED');
