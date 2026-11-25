#!/usr/bin/env python3
"""Ephemeral offline research packages; never publish or sign these."""
import json
import pathlib
import subprocess
import sys
import zipfile

source = pathlib.Path(sys.argv[1]).resolve()
live = len(sys.argv) == 3 and sys.argv[2] == "--live"
assert len(sys.argv) == (3 if live else 2)
expected = "89b5979fff297c2a099359258c9f9249e0089db4"
assert subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip() == expected
sdk = pathlib.Path("docs/tsuzuki/provider-sdk/advanced-extraction.mjs").read_text()
assets = pathlib.Path("app/src/androidTest/assets")
assets.mkdir(parents=True, exist_ok=True)

wrapper = '''
import source from "./original.js";
import { extractionVersion, requestResource, composeImage, packManagedCbz } from "./modules/advanced-extraction.mjs";
export default {reading: {...source.reading, pages: async input => {
  if (await extractionVersion() !== 1) throw new Error("Extraction ABI");
  const result = await source.reading.pages(input);
  if (result.pages.length !== 1) throw new Error("Fixture page count");
  const page = result.pages[0];
  const handle = await requestResource("GET", page.url, page.headers || {});
  const composed = await composeImage(handle, {width:2,height:1,tiles:[
    {sx:1,sy:0,dx:0,dy:0,width:1,height:1},
    {sx:0,sy:0,dx:1,dy:0,width:1,height:1}
  ]});
  if (await tsuzuki.image.pixel(composed, 0, 0) !== "FF0000FF") throw new Error("Pixel mismatch");
  const managed = await packManagedCbz([composed]);
  return {...result, testManaged:managed};
}}};
'''
for name in ("mangaall-ball", "mangafire"):
    folder = source / "providers" / name
    manifest = json.loads((folder / "manifest.json").read_text())
    manifest.pop("publication", None)
    manifest["permissions"]["browser"] = {"origins": []}
    files = {
        p.relative_to(folder).as_posix(): p.read_bytes()
        for directory in ("modules", "assets")
        for p in (folder / directory).rglob("*") if p.is_file()
    }
    original = (folder / "main.js").read_text()
    if name == "mangaall-ball":
        old = "chapters: source.chapters"
        assert original.count(old) == 1
        original = original.replace(old, old + ", pages: source.pages")
        manifest["capabilities"].append({"id": "reading.pages", "version": 1})
        path = "modules/http.js"
        http = files[path].decode()
        anchor = "    const result = await globalThis.tsuzuki.http.request("
        assert http.count(anchor) == 1
        http = 'import { requestResource, readResourceObject, release } from "./advanced-extraction.mjs";\n' + http.replace(
            anchor,
            '''    if (method === "POST") {
      const handle = await requestResource(method, url, headers, body);
      try { return await readResourceObject(handle); }
      finally { await release(handle); }
    }
''' + anchor,
        )
        files[path] = http.encode()
    if live and name == "mangafire":
        path = "modules/api.js"
        api = files[path].decode()
        start = api.index("async function browserJson(")
        end = api.index("export async function searchTitles(", start)
        api = api[:start] + '''export async function apiJson(path, params = {}) {
  const url = signedApiUrl(BASE_URL, path, pairsFromObject(params));
  const handle = await requestResource("GET", url, {Accept:"application/json"});
  try {
    const value = await readResourceObject(handle);
    if (value.error === "captcha_required") throw new Error("MangaFire challenge required");
    return value;
  } finally { await release(handle); }
}

''' + api[end:]
        files[path] = ('import { requestResource, readResourceObject, release } from "./advanced-extraction.mjs";\n' + api).encode()
    files["manifest.json"] = json.dumps(manifest).encode()
    files["main.js"] = ('import source from "./original.js";\nexport default source;\n' if live else wrapper).encode()
    files["original.js"] = original.encode()
    files["modules/advanced-extraction.mjs"] = sdk.encode()
    target = assets / ("advanced-" + ("live-" if live else "") + name + "-unsigned.zip")
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, content in sorted(files.items()):
            archive.writestr(path, content)
    print("CANDIDATE=" + name + "|UNSIGNED=true|LIVE=" + str(live).lower())
