// Launches the exact P-256 signed code10 source runner with a test-only
// in-memory shape-solver transport. The signed code and manifest are unchanged.
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { makeTestOnlyMangaFireFetch } from './mangaall_shape_test_core.mjs';
import { createPlaywrightShapeSession } from './mangaall_shape_playwright_test.mjs';

if (process.env.MANGAALL_TEST_ONLY_AUTO_SHAPE !== 'I_ACCEPT_TEST_ONLY_AUTO_SHAPE') {
  throw Error('Test-only MangaFire auto-solver is not enabled or authorized');
}
const rawFetch = globalThis.fetch.bind(globalThis);
const recovery = makeTestOnlyMangaFireFetch({
  fetchImpl: rawFetch, browserFactory: createPlaywrightShapeSession, enabled: true,
});
globalThis.fetch = recovery.fetch;
try {
  await import(pathToFileURL(path.resolve(process.cwd(), 'tooling/mangaall_live_matrix.mjs')).href);
} finally {
  await recovery.close();
  console.log('MANGAALL_MANGAFIRE_SHAPE_TEST|' + JSON.stringify({ ...recovery.status, browser: process.env.MANGAALL_TEST_ONLY_HEADED === '1' ? 'HEADED_XVFB' : 'HEADLESS' }));
  globalThis.fetch = rawFetch;
}
