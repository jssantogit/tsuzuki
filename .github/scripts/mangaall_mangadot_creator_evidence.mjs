// UNSIGNED research adapter. Never grants an edition or Reader binding.
const SELECTOR = "script[type=application/ld+json]";
const MAX_JSON_CHARS = 64 * 1024;
const exactName = value => typeof value === "string" ? value.normalize("NFKC")
  .trim().toLowerCase().replace(/[‘’]/g, "'").replace(/\s+/g, " ") : "";
const validId = id => typeof id === "string" && /^[1-9][0-9]{0,15}$/.test(id);
const exactUrl = (value, url) => value === url || value === url + "/";
const sameDocumentId = (value, url) => {
  if (exactUrl(value, url)) return true;
  if (typeof value !== "string") return false;
  const parts = value.split("#");
  return parts.length === 2 && exactUrl(parts[0], url) && /^[A-Za-z0-9_-]{1,64}$/.test(parts[1]);
};

function localPersonId(value, url) {
  if (typeof value !== "string") return null;
  const parts = value.split("#");
  if (parts.length !== 2 || !/^[A-Za-z0-9_-]{1,64}$/.test(parts[1])) return null;
  return parts[0] === "" || exactUrl(parts[0], url) ? parts[1] : null;
}

const namedType = (value, type) => value === type ||
  (Array.isArray(value) && value.length <= 8 && value.includes(type));
function declaredNames(value, maxCount, maxLength) {
  const list = value == null ? [] : Array.isArray(value) ? value : [value];
  if (list.length > maxCount || list.some(name => typeof name !== "string" ||
      !name.trim() || name.trim().length > maxLength || /[\u0000-\u001f]/.test(name))) return null;
  return [...new Set(list.map(name => name.trim()))];
}

function extractMangaDotWork(raw, id, title) {
  if (!validId(id) || !exactName(title) || typeof raw !== "string" ||
      raw.length > MAX_JSON_CHARS) return null;
  let doc;
  try { doc = JSON.parse(raw); } catch { return null; }
  const url = "https://mangadot.net/manga/" + id;
  const stack = [[doc, 0]], matches = [], definitions = new Map();
  let visited = 0;
  while (stack.length) {
    const [node, depth] = stack.pop();
    if (node === null || typeof node !== "object") continue;
    if (++visited > 2048 || depth > 12) return null;
    // Only definitions in this exact document are eligible for reference
    // resolution. No external person URL is ever fetched or inferred.
    if (!Array.isArray(node) && typeof node["@id"] === "string" &&
        Object.keys(node).some(key => key !== "@id")) {
      const key = localPersonId(node["@id"], url);
      if (key) {
        const definitionsForId = definitions.get(key) || [];
        definitionsForId.push(node);
        definitions.set(key, definitionsForId);
      }
    }
    if (!Array.isArray(node) && namedType(node["@type"], "ComicSeries")) {
      // A competing ComicSeries object about the same title/HID is ambiguous.
      const names = declaredNames(node.alternateName, 32, 1024) || [];
      if ([node.name, ...names].some(name => exactName(name) === exactName(title)) ||
          exactUrl(node.url, url) || sameDocumentId(node["@id"], url)) matches.push(node);
    }
    for (const value of Object.values(node)) {
      if (value !== null && typeof value === "object") stack.push([value, depth + 1]);
    }
  }
  if (matches.length !== 1) return null;
  const work = matches[0];
  const primary = declaredNames(work.name, 1, 1024);
  const alternate = declaredNames(work.alternateName, 32, 1024);
  if (!primary || primary.length !== 1 || !alternate ||
      ![...primary, ...alternate].some(name => exactName(name) === exactName(title)) ||
      !(exactUrl(work.url, url) || sameDocumentId(work["@id"], url))) return null;
  const aliases = [...primary, ...alternate].filter(name => exactName(name) !== exactName(title));
  if (aliases.length > 32) return null;
  // Any supplied explicit URL must agree, not merely one of the two.
  if (work.url != null && !exactUrl(work.url, url)) return null;
  // JSON-LD entity identifiers may have a fragment on the exact work page.
  // Never accept a different origin/path, query, or unrelated object.
  if (work["@id"] != null && !sameDocumentId(work["@id"], url)) return null;
  // Author remains primary. If omitted, explicitly credited creator or
  // illustrator Persons of THIS ComicSeries may supply corroboration (the
  // independent catalog already includes authors and artists). Do not use
  // publisher, uploader, contributor, unrelated graph Persons or free text.
  const list = value => value == null ? [] : Array.isArray(value) ? value : [value];
  const authors = list(work.author);
  const staff = authors.length ? authors : [...list(work.creator), ...list(work.illustrator)];
  if (staff.length > 8) return null;
  const names = [];
  for (const entry of staff) {
    let person = entry;
    if (entry && typeof entry === "object" && !Array.isArray(entry) &&
        Object.keys(entry).length === 1 && typeof entry["@id"] === "string") {
      const key = localPersonId(entry["@id"], url);
      const resolved = key && definitions.get(key);
      if (!resolved || resolved.length !== 1) return null;
      person = resolved[0];
    }
    if (!namedType(person?.["@type"], "Person") || namedType(person?.["@type"], "Organization")) return null;
    const primaryNames = declaredNames(person.name, 1, 128);
    const alternateNames = declaredNames(person.alternateName, 8, 128);
    if (!primaryNames || primaryNames.length !== 1 || !alternateNames) return null;
    for (const name of [...primaryNames, ...alternateNames]) {
      if (!names.some(old => exactName(old) === exactName(name))) names.push(name);
    }
    if (names.length > 8) return null;
  }
  return {authors:names, aliases};
}

export function extractMangaDotCreators(raw, id, title) {
  const record = extractMangaDotWork(raw, id, title);
  return record?.authors.length ? record.authors : null;
}

export async function observeMangaDotCreators(candidate, host = globalThis.tsuzuki) {
  const match = /^mangadot:([1-9][0-9]{0,15})$/.exec(candidate?.externalWorkId || "");
  if (!match || !exactName(candidate.title)) return { detailOutcome: "IDENTITY_MISMATCH" };
  let handle;
  try {
    const url = "https://mangadot.net/manga/" + match[1];
    handle = await host.http.getResource(url);
  } catch {
    return { detailOutcome: "DETAIL_FAILED" };
  }
  let raw;
  try {
    raw = await host.dom.selectText(handle, SELECTOR);
  } catch {
    return { detailOutcome: "INVALID_RESPONSE" };
  }
  const record = extractMangaDotWork(raw, match[1], candidate.title);
  if (!record) return { detailOutcome: "IDENTITY_MISMATCH" };
  // Verified work identity without a published person is distinct from
  // mismatch. It supplies no author authority and cannot pass the portfolio.
  return {detailOutcome: record.aliases.length ? "VERIFIED_WITH_ALIASES" : "VERIFIED_NO_ALIASES",
    authors:record.authors, ...(record.aliases.length ? {aliases:record.aliases} : {})};
}
export function mangaDotExactTitle(candidate, title) {
  return !!exactName(title) && exactName(candidate?.title) === exactName(title);
}
