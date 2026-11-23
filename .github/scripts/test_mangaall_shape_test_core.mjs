import assert from 'node:assert/strict';
import { classifyMangaFireChallenge, makeTestOnlyMangaFireFetch } from './mangaall_shape_test_core.mjs';
import { capKeiyoushiSolver, requestViaChromiumPage } from './mangaall_shape_playwright_test.mjs';
const url = 'https://mangafire.to/api/titles?keyword=Oyasumi';
const response = (status, value, headers = {}) => new Response(JSON.stringify(value), {
  status, headers: { 'content-type': 'application/json', ...headers },
});
const cloudflare = response(403, { error: 'captcha_required' }, { 'cf-mitigated': 'challenge' });
assert.equal(await classifyMangaFireChallenge(cloudflare), 'CLOUDFLARE_CHALLENGE');
assert.equal(await classifyMangaFireChallenge(response(403, { error: 'captcha_required' })), 'SHAPE_CAPTCHA_REQUIRED');
assert.equal(await classifyMangaFireChallenge(response(403, { error: 'invalid_vrf' })), 'UNCLASSIFIED_403');
assert.equal(await classifyMangaFireChallenge(response(200, { error: 'captcha_required' })), 'NONE');
assert.equal(await classifyMangaFireChallenge(new Response('<html>cf</html>', {
  status: 403, headers: { 'content-type': 'text/html' },
})), 'UNCLASSIFIED_403');
assert.equal(await classifyMangaFireChallenge(response(403, { error: 'captcha_required' },
  { 'content-length': '5000' })), 'UNCLASSIFIED_403');

function createScenario({ first, retry = response(200, { items: [] }), solved = true } = {}) {
  let raw = 0, browserCount = 0, closed = 0, browserReads = 0;
  const recovery = makeTestOnlyMangaFireFetch({
    enabled: true,
    fetchImpl: async () => { raw++; return first; },
    browserFactory: async () => {
      browserCount++;
      return {
        async solve() { return solved; },
        async openCloudflareSession() { return solved; },
        async request() { browserReads++; return retry; },
        async close() { closed++; },
      };
    },
  });
  return { recovery, counters: () => ({ raw, browserCount, browserReads, closed }) };
}
{
  const s = createScenario({ first: cloudflare });
  assert.equal((await s.recovery.fetch(url)).status, 200);
  assert.equal(s.recovery.status.outcome, 'SIGNED_API_JSON_200_AFTER_BROWSER');
  assert.equal(s.recovery.status.shapeAttempts, 0, 'CF only attempts native browser JS');
  assert.equal(s.recovery.status.cloudflareBrowserAttempts, 1);
  assert.equal(s.counters().browserCount, 1);
  await s.recovery.close();
}
{
  const s = createScenario({ first: cloudflare, solved: false });
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal(s.recovery.status.outcome, 'CLOUDFLARE_BROWSER_UNRESOLVED');
  assert.equal(s.counters().closed, 1);
}
{
  const s = createScenario({ first: response(403, { error: 'captcha_required' }) });
  assert.equal((await s.recovery.fetch(url)).status, 200);
  assert.equal((await s.recovery.fetch(url)).status, 200);
  assert.deepEqual(s.counters(), { raw: 1, browserCount: 1, browserReads: 2, closed: 0 });
  assert.equal(s.recovery.status.outcome, 'SIGNED_API_JSON_200_AFTER_SHAPE');
  await s.recovery.close();
  assert.equal(s.counters().closed, 1);
}
{
  const s = createScenario({ first: response(403, { error: 'captcha_required' }), solved: false });
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal(s.recovery.status.outcome, 'SHAPE_SOLVE_FAILED');
  assert.equal(s.counters().browserCount, 1, 'only one automated session attempt');
  assert.equal(s.counters().closed, 1);
  await s.recovery.close();
}
{
  const s = createScenario({ first: response(403, { error: 'captcha_required' }),
    retry: response(200, { error: 'captcha_required' }) });
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal(s.recovery.status.outcome, 'RETRY_NOT_VALID_API_JSON_200');
}
{
  const s = createScenario({ first: cloudflare,
    retry: response(200, { title: 'looks valid, but missing items' }) });
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal(s.recovery.status.outcome, 'RETRY_NOT_VALID_API_JSON_200');
}
{
  const s = createScenario({ first: response(403, { error: 'captcha_required' }),
    retry: response(403, { error: 'captcha_required' }) });
  assert.equal((await s.recovery.fetch(url)).status, 403);
  assert.equal(s.counters().closed, 1);
  assert.equal(s.recovery.status.outcome, 'SHAPE_CAPTCHA_REQUIRED');
  await s.recovery.close();
}
{
  const s = createScenario({ first: response(403, { error: 'captcha_required' }) });
  assert.equal((await s.recovery.fetch('https://mangadex.org/api/titles')).status, 403);
  assert.equal((await s.recovery.fetch('https://mangafire.to/@waf/generate')).status, 403);
  assert.equal((await s.recovery.fetch(url, { method: 'POST' })).status, 403);
  assert.equal(s.counters().browserCount, 0, 'non GET API traffic cannot launch solver');
}
assert.throws(() => makeTestOnlyMangaFireFetch({ fetchImpl: () => {}, browserFactory: () => {} }),
  /explicitly enabled/);
const minimal = 'for (let i = 0; i < 10; i++) Array.from({length: 10}, solve) bridge.postMessage(`${complete}`)';
const capped = capKeiyoushiSolver(minimal);
assert.match(capped, /i < 2/);
assert.match(capped, /length: 2/);
assert.throws(() => capKeiyoushiSolver('<html>changed upstream</html>'), /bridge contract/);

// Browser-page transport is not the Playwright request client. Offline test
// evaluates the same browser callback with a mock fetch and rejects fallbacks.
{
  const originalFetch = globalThis.fetch;
  const page = { evaluate: async (callback, args) => callback(args) };
  try {
    const observed = [];
    globalThis.fetch = async (address, options) => {
      observed.push({ address, options });
      return response(200, { items: [{ id: 'fixture' }] });
    };
    const parsed = await requestViaChromiumPage(page, url);
    assert.equal(parsed.status, 200);
    assert.deepEqual(await parsed.json(), { items: [{ id: 'fixture' }] });
    assert.equal(observed.length, 1);
    assert.equal(observed[0].address, url);
    assert.equal(observed[0].options.credentials, 'same-origin');
    assert.equal(observed[0].options.method, 'GET');
    assert.equal(observed[0].options.redirect, 'error');

    globalThis.fetch = async () => new Response('<html>challenge</html>', {
      status: 403, headers: { 'content-type': 'text/html', 'cf-mitigated': 'challenge' },
    });
    const blocked = await requestViaChromiumPage(page, url);
    assert.equal(blocked.status, 403);
    assert.equal(await classifyMangaFireChallenge(blocked), 'CLOUDFLARE_CHALLENGE');

    globalThis.fetch = async () => new Response('x'.repeat(192 * 1024 + 1));
    await assert.rejects(() => requestViaChromiumPage(page, url), /exceeds cap/);
    await assert.rejects(() => requestViaChromiumPage(page,
      'https://mangafire.to/@waf/solver'), /outside MangaFire API/);
    await assert.rejects(() => requestViaChromiumPage(page,
      'https://mangafire.to.evil.invalid/api/titles'), /outside MangaFire API/);
    await assert.rejects(() => requestViaChromiumPage(page,
      url, { method: 'POST' }), /outside MangaFire API/);
    await assert.rejects(() => requestViaChromiumPage(page,
      url + '#fragment'), /outside MangaFire API/);
  } finally {
    globalThis.fetch = originalFetch;
  }
}
console.log('MangaALL test-only MangaFire shape solver offline contract: PASS');
