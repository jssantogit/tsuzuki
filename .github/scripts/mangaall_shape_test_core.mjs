// Test-only MangaFire shape CAPTCHA transport for the signed MangaALL Node harness.
// Not imported by app/Provider production code. The only automatic case is the
// MangaFire API's explicit JSON {error:"captcha_required"} response.
const MANGAFIRE_ORIGIN = 'https://mangafire.to';
const MAX_CHALLENGE_BYTES = 4096;

function isAllowedApi(url, options) {
  try {
    const target = new URL(url);
    const method = String(options?.method || 'GET').toUpperCase();
    return target.origin === MANGAFIRE_ORIGIN && !target.username &&
      !target.password && !target.hash && !target.port &&
      target.pathname.startsWith('/api/') && method === 'GET';
  } catch {
    return false;
  }
}

async function boundedJsonError(response) {
  const type = response.headers.get('content-type') || '';
  if (!/^(application\/json|[\w.+-]+\/[\w.+-]+\+json)\b/i.test(type)) return null;
  const length = Number(response.headers.get('content-length') || '0');
  if (length > MAX_CHALLENGE_BYTES) return null;
  const clone = response.clone();
  if (!clone.body) return null;
  const reader = clone.body.getReader();
  let chunks = [], total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_CHALLENGE_BYTES) return null;
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const json = JSON.parse(new TextDecoder().decode(bytes));
    return json && typeof json === 'object' && !Array.isArray(json) ? json.error : null;
  } catch {
    return null;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export async function classifyMangaFireChallenge(response) {
  if (response.status !== 403) return 'NONE';
  if (response.headers.get('cf-mitigated')?.toLowerCase() === 'challenge') return 'CLOUDFLARE_CHALLENGE';
  return (await boundedJsonError(response)) === 'captcha_required'
    ? 'SHAPE_CAPTCHA_REQUIRED' : 'UNCLASSIFIED_403';
}

export function makeTestOnlyMangaFireFetch({ fetchImpl, browserFactory, enabled }) {
  if (typeof fetchImpl !== 'function' || typeof browserFactory !== 'function') {
    throw Error('Test-only shape solver requires explicit transports');
  }
  if (enabled !== true) throw Error('Shape solver must be explicitly enabled for test only');
  let started = false;
  let browser = null;
  let attempts = 0;
  let browserAttempts = 0;
  let lastOutcome = 'NOT_TRIGGERED';
  const fetchWithShapeRecovery = async (url, options = {}) => {
    if (!isAllowedApi(url, options)) return fetchImpl(url, options);
    if (browser) {
      const browserResponse = await browser.request(url, options);
      // Never silently revert to unverified HTTP after browser session failure.
      if (browserResponse.status === 403) lastOutcome = await classifyMangaFireChallenge(browserResponse);
      return browserResponse;
    }
    const response = await fetchImpl(url, options);
    if (response.status !== 403) return response;
    const detected = await classifyMangaFireChallenge(response);
    if (started) return response; // Preserve the first failed solve's diagnostic.
    lastOutcome = detected;
    if (!['SHAPE_CAPTCHA_REQUIRED', 'CLOUDFLARE_CHALLENGE'].includes(detected)) return response;
    started = true;
    if (detected === 'SHAPE_CAPTCHA_REQUIRED') attempts++;
    else browserAttempts++;
    let candidate;
    try {
      candidate = await browserFactory();
      const solved = detected === 'SHAPE_CAPTCHA_REQUIRED'
        ? await candidate.solve() : await candidate.openCloudflareSession();
      if (!solved) {
        lastOutcome = detected === 'SHAPE_CAPTCHA_REQUIRED'
          ? 'SHAPE_SOLVE_FAILED' : 'CLOUDFLARE_BROWSER_UNRESOLVED';
        await candidate.close();
        candidate = null;
        return response;
      }
      const retry = await candidate.request(url, options);
      if (retry.status !== 200 || !/\b(?:application\/json|\w+\/\w+\+json)\b/i.test(
        retry.headers.get('content-type') || '')) {
        lastOutcome = retry.status === 403
          ? await classifyMangaFireChallenge(retry) : 'RETRY_NOT_JSON_200';
        await candidate.close();
        candidate = null;
        return response;
      }
      // The original signed API response, not a solver flag or cookie, is
      // the only proof that the session works for this particular request.
      browser = candidate;
      candidate = null;
      lastOutcome = detected === 'SHAPE_CAPTCHA_REQUIRED'
        ? 'SIGNED_API_JSON_200_AFTER_SHAPE' : 'SIGNED_API_JSON_200_AFTER_BROWSER';
      return retry;
    } catch {
      lastOutcome = detected === 'SHAPE_CAPTCHA_REQUIRED'
        ? 'SHAPE_SOLVE_ERROR' : 'CLOUDFLARE_BROWSER_ERROR';
      if (candidate) await candidate.close().catch(() => {});
      return response;
    }
  };
  return {
    fetch: fetchWithShapeRecovery,
    get status() { return { mode: 'TEST_ONLY', shapeAttempts: attempts, cloudflareBrowserAttempts: browserAttempts, outcome: lastOutcome }; },
    async close() { if (browser) { const b = browser; browser = null; await b.close(); } },
  };
}
