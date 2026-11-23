// Disposable test-only browser adapter for Keiyoushi's MangaFire shape solver.
// Playwright and the pinned Apache-2.0 Keiyoushi solver.html are never bundled
// into the app, the signed Provider, or production runtime.
import fs from 'node:fs';

const ORIGIN = 'https://mangafire.to';
const SOLVER_URL = ORIGIN + '/@waf/solver';
const MAX_RESPONSE_BYTES = 192 * 1024;
const MAX_SOLVE_MS = 30_000;

// All test-only signed API requests use Chromium's *page network stack*.
// BrowserContext.request shares cookies but is an independent API client;
// a successful challenge visit is not proof that client gets API access.
// This function never exposes browser cookies or challenge HTML in artifacts.
export async function requestViaChromiumPage(page, url, options = {}) {
  const parsed = new URL(url);
  if (parsed.origin !== ORIGIN || !parsed.pathname.startsWith('/api/') ||
      parsed.port || parsed.username || parsed.password || parsed.hash ||
      String(options?.method || 'GET').toUpperCase() !== 'GET') {
    throw Error('Test-only browser request outside MangaFire API');
  }
  const originalAccept = options?.headers?.Accept || options?.headers?.accept;
  const accept = typeof originalAccept === 'string' &&
    originalAccept.length > 0 && originalAccept.length <= 128 &&
    /^[\w/.*+;= ,-]+$/.test(originalAccept)
    ? originalAccept : 'application/json';
  const result = await page.evaluate(async ({ apiUrl, acceptHeader, maxBytes }) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12_000);
    try {
      const response = await fetch(apiUrl, {
        method: 'GET', credentials: 'same-origin', redirect: 'error',
        cache: 'no-store', headers: { Accept: acceptHeader },
        signal: controller.signal,
      });
      const declared = Number(response.headers.get('content-length') || 0);
      if (declared > maxBytes) throw Error('Browser response exceeds cap');
      const reader = response.body?.getReader();
      const chunks = [];
      let total = 0;
      if (reader) {
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            total += value.byteLength;
            if (total > maxBytes) throw Error('Browser response exceeds cap');
            chunks.push(value);
          }
        } finally {
          reader.releaseLock();
        }
      }
      const bytes = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return {
        status: response.status,
        body: new TextDecoder().decode(bytes),
        contentType: response.headers.get('content-type') || 'application/octet-stream',
        cfMitigated: response.headers.get('cf-mitigated') || '',
      };
    } finally {
      clearTimeout(timer);
    }
  }, { apiUrl: url, acceptHeader: accept, maxBytes: MAX_RESPONSE_BYTES });
  if (!Number.isInteger(result?.status) || result.status < 200 || result.status > 599 ||
      typeof result.body !== 'string' ||
      new TextEncoder().encode(result.body).byteLength > MAX_RESPONSE_BYTES) {
    throw Error('Invalid bounded Chromium response');
  }
  return new Response([204, 205, 304].includes(result.status) ? null : result.body, {
    status: result.status,
    headers: {
      'content-type': result.contentType || 'application/octet-stream',
      ...(result.cfMitigated ? { 'cf-mitigated': result.cfMitigated } : {}),
    },
  });
}

export function capKeiyoushiSolver(html) {
  if (typeof html !== 'string' || !html.includes('bridge.postMessage(`${complete}`)')) {
    throw Error('Pinned upstream solver bridge contract changed');
  }
  // Upstream uses up to 100 concurrent generate/verify attempts. In our
  // CI experiment, at most 2 rounds x 2 attempts are allowed per shard.
  const rounds = 'for (let i = 0; i < 10; i++)';
  const parallel = 'Array.from({length: 10}, solve)';
  if (html.split(rounds).length !== 2 || html.split(parallel).length !== 2) {
    throw Error('Pinned upstream solver retry contract changed');
  }
  return html.replace(rounds, 'for (let i = 0; i < 2; i++)')
    .replace(parallel, 'Array.from({length: 2}, solve)');
}

export async function createPlaywrightShapeSession({
  htmlPath = process.env.MANGAALL_TEST_ONLY_SOLVER_HTML,
  browserStart,
} = {}) {
  if (!htmlPath || !fs.existsSync(htmlPath)) throw Error('Pinned solver HTML missing');
  const html = capKeiyoushiSolver(fs.readFileSync(htmlPath, 'utf8'));
  const { chromium } = browserStart ? { chromium: { launch: browserStart } } : await import('playwright');
  const browser = await chromium.launch({ headless: process.env.MANGAALL_TEST_ONLY_HEADED !== '1' });
  const context = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false });
  let page;
  let mode = 'shape';
  let closed = false;
  const close = async () => { if (!closed) { closed = true; await browser.close(); } };
  try {
    page = await context.newPage();
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
      if (mode === 'api') {
        // The same Chromium renderer that established the browser session
        // must issue the signed API GET. No separate Playwright HTTP client.
        if (url.origin === ORIGIN && url.pathname.startsWith('/api/') &&
            route.request().method() === 'GET') return route.continue();
        return route.abort();
      }
      if (mode === 'cloudflare') {
        // Standard browser-side Cloudflare JS only: no CAPTCHA clicking or
        // solver injection, proxies, fingerprint changes or cookie export.
        if (url.origin === ORIGIN && (route.request().method() === 'GET' ||
            (route.request().method() === 'POST' &&
             url.pathname.startsWith('/cdn-cgi/challenge-platform/')))) return route.continue();
        if (url.origin === 'https://challenges.cloudflare.com' &&
            ['GET', 'POST'].includes(route.request().method())) return route.continue();
        return route.abort();
      }
      if (url.href === SOLVER_URL) {
        return route.fulfill({ status: 200, contentType: 'text/html', body: html });
      }
      if (url.origin === ORIGIN && url.pathname.startsWith('/@waf/') &&
          ['GET', 'POST'].includes(route.request().method())) return route.continue();
      if (url.origin === 'https://docs.opencv.org' && route.request().method() === 'GET') {
        return route.continue();
      }
      return route.abort();
    });
    let onDone;
    const finished = new Promise((resolve) => { onDone = resolve; });
    await page.exposeFunction('__tsuzukiShapeTestDone', (result) => onDone(result === 'true'));
    await page.addInitScript(() => {
      window.bridge = { postMessage: (result) => window.__tsuzukiShapeTestDone(result) };
    });
    return {
      async openCloudflareSession() {
        mode = 'cloudflare';
        try {
          await page.goto(ORIGIN + '/', { waitUntil: 'domcontentloaded', timeout: 12_000 });
          await page.waitForTimeout(8_000);
          const cookies = await context.cookies(ORIGIN);
          const verified = cookies.some((cookie) =>
            cookie.name === 'cf_clearance' && cookie.value.length > 0);
          if (verified) mode = 'api';
          return verified;
        } catch {
          return false;
        }
      },
      async solve() {
        let timeout;
        try {
          const timer = new Promise((resolve) => {
            timeout = setTimeout(() => resolve(false), MAX_SOLVE_MS);
          });
          await page.goto(SOLVER_URL, { waitUntil: 'domcontentloaded', timeout: 12_000 });
          const successful = await Promise.race([finished, timer]);
          if (!successful) return false;
          // The remote server must set its own clearance cookie. The value
          // stays exclusively inside the disposable Playwright context.
          const cookies = await context.cookies(ORIGIN);
          const verified = cookies.some((cookie) =>
            cookie.name === 'waf_pass' && cookie.value.length > 0);
          if (verified) mode = 'api';
          return verified;
        } finally {
          clearTimeout(timeout);
        }
      },
      async request(url, options) {
        if (mode !== 'api') throw Error('No browser-verified API session');
        return requestViaChromiumPage(page, url, options);
      },
      close,
    };
  } catch (error) {
    await close();
    throw error;
  }
}
