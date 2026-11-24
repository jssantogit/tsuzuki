// UNSIGNED research adapter. Never grants an edition or Reader binding.
const SELECTOR = "script[type=application/ld+json]";
const MAX_JSON_CHARS = 64 * 1024;
const exactName = value => typeof value === "string" ? value.normalize("NFKC")
  .trim().toLowerCase().replace(/[‘’]/g, "'").replace(/\s+/g, " ") : "";
const validId = id => typeof id === "string" && /^[1-9][0-9]{0,15}$/.test(id);
const exactUrl = (value, url) => value === url || value === url + "/";

export function extractMangaDotCreators(raw, id, title) {
  if (!validId(id) || !exactName(title) || typeof raw !== "string" ||
      raw.length > MAX_JSON_CHARS) return null;
  let doc;
  try { doc = JSON.parse(raw); } catch { return null; }
  const url = "https://mangadot.net/manga/" + id;
  const stack = [[doc, 0]], matches = [];
  let visited = 0;
  while (stack.length) {
    const [node, depth] = stack.pop();
    if (node === null || typeof node !== "object") continue;
    if (++visited > 2048 || depth > 12) return null;
    if (!Array.isArray(node) && node["@type"] === "ComicSeries") {
      // A competing ComicSeries object about the same title/HID is ambiguous.
      if (exactName(node.name) === exactName(title) ||
          exactUrl(node.url, url) || exactUrl(node["@id"], url)) matches.push(node);
    }
    for (const value of Object.values(node)) {
      if (value !== null && typeof value === "object") stack.push([value, depth + 1]);
    }
  }
  if (matches.length !== 1) return null;
  const work = matches[0];
  if (exactName(work.name) !== exactName(title) ||
      !(exactUrl(work.url, url) || exactUrl(work["@id"], url))) return null;
  // Any supplied explicit URL must agree, not merely one of the two.
  if (work.url != null && !exactUrl(work.url, url)) return null;
  if (work["@id"] != null && !exactUrl(work["@id"], url)) return null;
  const authors = Array.isArray(work.author) ? work.author : [work.author];
  if (authors.length === 0 || authors.length > 8) return null;
  const names = [];
  for (const person of authors) {
    if (person?.["@type"] !== "Person" || typeof person.name !== "string") return null;
    const name = person.name.trim();
    if (!name || name.length > 128 || /[\u0000-\u001f]/.test(name)) return null;
    if (!names.some(old => exactName(old) === exactName(name))) names.push(name);
  }
  return names.length ? names : null;
}

export async function observeMangaDotCreators(candidate, host = globalThis.tsuzuki) {
  const match = /^mangadot:([1-9][0-9]{0,15})$/.exec(candidate?.externalWorkId || "");
  if (!match || !exactName(candidate.title)) return { detailOutcome: "IDENTITY_MISMATCH" };
  try {
    const url = "https://mangadot.net/manga/" + match[1];
    const handle = await host.http.getResource(url);
    const raw = await host.dom.selectText(handle, SELECTOR);
    const authors = extractMangaDotCreators(raw, match[1], candidate.title);
    return authors ? { detailOutcome: "VERIFIED_NO_ALIASES", authors } :
      { detailOutcome: "IDENTITY_MISMATCH" };
  } catch {
    return { detailOutcome: "DETAIL_FAILED" };
  }
}
export function mangaDotExactTitle(candidate, title) {
  return !!exactName(title) && exactName(candidate?.title) === exactName(title);
}
