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

const schemaType = (value, type) => value === type ||
  value === "https://schema.org/" + type || value === "http://schema.org/" + type;
const namedType = (value, type) => schemaType(value, type) ||
  (Array.isArray(value) && value.length <= 8 && value.some(entry => schemaType(entry, type)));
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
  // Explicit work-level credits may coexist (writer, creator and artist).
  // Preserve provenance: only direct or same-document linked Person objects
  // attached via these roles, never a publisher, uploader or unrelated person.
  const list = value => value == null ? [] : Array.isArray(value) ? value : [value];
  const staff = ["author", "creator", "illustrator"].flatMap(role =>
    list(work[role]).map(entry => [role, entry]));
  if (staff.length > 12) return null;
  const resolveLocal = entry => {
    if (entry && typeof entry === "object" && !Array.isArray(entry) &&
        Object.keys(entry).length === 1 && typeof entry["@id"] === "string") {
      const key = localPersonId(entry["@id"], url);
      const resolved = key && definitions.get(key);
      return resolved?.length === 1 ? resolved[0] : null;
    }
    return entry;
  };
  const names = [];
  for (const [role, entry] of staff) {
    let person = resolveLocal(entry);
    // Schema.org Role wrappers must explicitly carry the same relation as
    // their parent; an unrelated nested person or arbitrary role is not a credit.
    if (namedType(person?.["@type"], "Role")) {
      if (namedType(person["@type"], "Organization") || namedType(person["@type"], "Person") ||
          !person[role] || ["author", "creator", "illustrator"].some(other => other !== role && person[other] != null)) return null;
      person = resolveLocal(person[role]);
    }
    if (!namedType(person?.["@type"], "Person") || namedType(person?.["@type"], "Organization")) return null;
    const primaryNames = declaredNames(person.name, 1, 128);
    const alternateNames = declaredNames(person.alternateName, 8, 128);
    // A typed Person can explicitly publish only an alternate name. Do not
    // invent one from the title or accept free text / anonymous Person nodes.
    if (!primaryNames || !alternateNames || primaryNames.length + alternateNames.length === 0) return null;
    for (const name of [...primaryNames, ...alternateNames]) {
      if (!names.some(old => exactName(old) === exactName(name))) names.push(name);
    }
    if (names.length > 8) return null;
  }
  return {authors:names, aliases, catalogReferences: work.sameAs};
}

// Bibliographic links are evidence of a work, not of a particular edition,
// translation, chapter or author. No request follows a source-supplied URL.
// This read-only proof uses the exact validated ComicSeries scope above.
function decodeMangaUpdatesReference(value) {
  if (typeof value !== "string" || value.length > 512) return null;
  const match = /^https:\/\/(?:www\.)?mangaupdates\.com\/series\/([a-z0-9]{1,12})(?:\/[a-z0-9-]{1,200})?\/?$/.exec(value);
  if (!match) return null;
  let id = 0n;
  for (const char of match[1]) {
    id = id * 36n + BigInt(parseInt(char, 36));
  }
  return id > 0n && id < 1000000000000000000n ? id.toString() : null;
}

// This helper deliberately has no side effects and is not yet part of the
// V1 lookup result. A caller must provide the freshly verified independent
// catalog ID, never a value obtained by following this same source's link.
export function assessMangaDotCatalogReference(raw, id, title, independentMangaUpdatesId) {
  const record = extractMangaDotWork(raw, id, title);
  if (!record) return {status:"SOURCE_DETAIL_UNVERIFIED", referenceCount:0};
  const refs = record.catalogReferences == null ? [] :
    Array.isArray(record.catalogReferences) ? record.catalogReferences : [record.catalogReferences];
  if (refs.length > 32 || refs.some(ref => typeof ref !== "string" || ref.length > 512)) {
    return {status:"INVALID_REFERENCES", referenceCount:0};
  }
  const ids = new Set();
  for (const ref of refs) {
    const decoded = decodeMangaUpdatesReference(ref);
    if (decoded) ids.add(decoded);
    else if (/^https:\/\/(?:www\.)?mangaupdates\.com(?:[/:?#]|$)/i.test(ref)) {
      // Unsupported or malformed references on the relevant host are not
      // silently ignored beside a valid one: they could refer to another work.
      return {status:"INVALID_REFERENCES", referenceCount:0};
    }
  }
  const referenceCount = ids.size;
  const expected = independentMangaUpdatesId;
  if (typeof expected !== "string" || !/^[1-9][0-9]{0,17}$/.test(expected)) {
    return {status:"INDEPENDENT_UNVERIFIED", referenceCount};
  }
  if (ids.size === 0) return {status:"NO_SUPPORTED_REFERENCE", referenceCount};
  if (ids.size === 1 && ids.has(expected)) return {status:"EXACT_CATALOG_LINK", referenceCount};
  return {status:ids.has(expected) ? "CONFLICTING_CATALOG_LINKS" : "DIFFERENT_CATALOG_LINK", referenceCount};
}

export function extractMangaDotCreators(raw, id, title) {
  const record = extractMangaDotWork(raw, id, title);
  return record?.authors.length ? record.authors : null;
}

// Conservative fallback for source pages which publish a credit in semantic
// HTML instead of JSON-LD. The ComicSeries itemid must be the very same HID;
// only direct typed Person roles and their direct itemprop=name are eligible.
// No unstructured author label, site metadata, guessed name or external fetch.
const MICRODATA_ROLES = ["author", "creator", "illustrator"];
const MICRODATA_TYPES = ["https://schema.org/", "http://schema.org/"];
async function observeExactMicrodataCredits(host, handle, id) {
  const url = "https://mangadot.net/manga/" + id;
  const selectors = role => MICRODATA_TYPES.flatMap(prefix => [url, url + "/"].map(workUrl =>
    `[itemscope][itemtype="${prefix}ComicSeries"][itemid="${workUrl}"] > [itemprop="${role}"][itemscope][itemtype="${prefix}Person"] > [itemprop="name"]`
  )).join(", ");
  const result = [];
  for (const role of MICRODATA_ROLES) {
    try {
      const value = await host.dom.selectText(handle, ":tsuzuki-unique " + selectors(role));
      if (typeof value !== "string" || value.length > 128 ||
          !/^[\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} .,'’·_()\-]{0,127}$/u.test(value.trim())) continue;
      const name = value.trim();
      if (!result.some(old => exactName(old) === exactName(name))) result.push(name);
    } catch {
      // Missing/ambiguous semantic markup never creates author evidence.
    }
  }
  return result;
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
  const authors = record.authors.length ? record.authors : await observeExactMicrodataCredits(host, handle, match[1]);
  return {detailOutcome: record.aliases.length ? "VERIFIED_WITH_ALIASES" : "VERIFIED_NO_ALIASES",
    authors, ...(record.aliases.length ? {aliases:record.aliases} : {})};
}
export function mangaDotExactTitle(candidate, title) {
  return !!exactName(title) && exactName(candidate?.title) === exactName(title);
}
