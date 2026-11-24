// Offline only: obtain exact lookup request URLs from the pinned unmodified adapter.
// No real network calls; the bridge returns an explicitly empty synthetic fixture.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
const root = path.resolve(process.argv[2]);
const out = process.argv[3];
const titles = ["Oyasumi Punpun", "Akame ga Kill!", "Koe no Katachi"];
const manifest = JSON.parse(fs.readFileSync(path.join(root, "providers/mangaall-ball/manifest.json"), "utf8"));
assert.equal(manifest.id, "app.tsuzuki.mangaall.mangaball");
assert.equal(manifest.publication, "development");
assert.deepEqual(manifest.permissions.network.origins, ["https://mangaball.com"]);
const adapter = await import(pathToFileURL(path.join(root, "providers/mangaall-ball/modules/mangaball.js")).href);
const urls = [];
for (const title of titles) {
  let calls = 0;
  globalThis.tsuzuki = { http: { request: async (method, url, headers, body) => {
    assert.equal(++calls, 1);
    assert.equal(method, "GET");
    assert.equal(body, undefined);
    const parsed = new URL(url);
    assert.equal(parsed.origin, "https://mangaball.com");
    assert.equal(parsed.pathname, "/api/v1/title/search-advanced");
    assert.equal(parsed.searchParams.get("keyword"), title);
    urls.push(url);
    return JSON.stringify({ statusCode: 200, body: JSON.stringify({ code: 200, data: [], pagination: { current_page: 1, last_page: 0 } }) });
  } } };
  assert.deepEqual(await adapter.lookup({ titles: [title], cursor: null }), { items: [], nextCursor: null });
  assert.equal(calls, 1);
}
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(urls));
console.log("MANGABALL_ADAPTER_CAPTURE|requests=3|network=OFFLINE|publication=DEVELOPMENT");
