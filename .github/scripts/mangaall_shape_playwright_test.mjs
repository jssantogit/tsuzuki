// Disposable test-only browser adapter for Keiyoushi's MangaFire shape solver.
// Playwright and the pinned Apache-2.0 Keiyoushi solver.html are never bundled
// into the app, the signed Provider, or production runtime.
import fs from 'node:fs';

const ORIGIN = 'https://mangafire.to';
const SOLVER_URL = ORIGIN + '/@waf/solver';
const MAX_RESPONSE_BYTES = 192 * 1024;
const MAX_SOLVE_MS = 30_000;

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
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false });
  let page;
  let closed = false;
  const close = async () => { if (!closed) { closed = true; await browser.close(); } };
  try {
    page = await context.newPage();
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
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
          return cookies.some((cookie) => cookie.name === 'waf_pass' && cookie.value.length > 0);
        } finally {
          clearTimeout(timeout);
        }
      },
      async request(url, options) {
        const parsed = new URL(url);
        if (parsed.origin !== ORIGIN || !parsed.pathname.startsWith('/api/') ||
            parsed.port || parsed.username || parsed.password ||
            String(options?.method || 'GET').toUpperCase() !== 'GET') {
          throw Error('Test-only browser request outside MangaFire API');
        }
        const origHeaders = options?.headers || {};
        const accept = origHeaders.Accept || origHeaders.accept || 'application/json';
        const response = await context.request.get(url, {
          maxRedirects: 0, timeout: 12_000, headers: { Accept: accept },
        });
        const body = await response.body();
        if (body.byteLength > MAX_RESPONSE_BYTES) throw Error('Shape test response cap');
        const original = response.headers();
        return new Response(body, { status: response.status(), headers: {
          'content-type': original['content-type'] || 'application/octet-stream',
          ...(original['cf-mitigated'] ? { 'cf-mitigated': original['cf-mitigated'] } : {}),
        } });
      },
      close,
    };
  } catch (error) {
    await close();
    throw error;
  }
}
