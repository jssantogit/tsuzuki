// Provider Platform advanced extraction revision 1.
// This SDK is a thin wrapper over the existing isolated Host API; it owns no
// transport, cookie, identity, or filesystem authority.
function host() {
  const api = globalThis.tsuzuki;
  if (!api) throw new Error("Provider Host unavailable");
  return api;
}
function method(module, name) {
  const value = host()[module];
  if (typeof value?.[name] !== "function") throw new Error("Host extraction feature unavailable: " + module + "." + name);
  return (...args) => value[name](...args);
}
export async function extractionVersion() {
  return Number(await method("protocol", "extractionVersion")());
}
export async function requestResource(methodName, url, headers = {}, body = undefined) {
  const value = JSON.parse(await method("http", "requestResource")(
    methodName, url, JSON.stringify(headers), body === undefined ? undefined : JSON.stringify(body),
  ));
  if (!Number.isInteger(value?.statusCode)) throw new Error("Malformed resource response");
  if (value.statusCode < 200 || value.statusCode > 299) {
    const error = new Error("Provider HTTP " + value.statusCode);
    error.httpStatus = value.statusCode;
    throw error;
  }
  if (typeof value.resourceHandle !== "string" || !value.resourceHandle.startsWith("res:")) throw new Error("Missing resource");
  return value.resourceHandle;
}
export async function release(handle) {
  return method("binary", "release")(handle);
}
export async function digest(handle) {
  const value = await method("binary", "inspect")(handle, "sha256", "");
  if (!/^[0-9a-f]{64}$/.test(value)) throw new Error("Malformed digest");
  return value;
}
export async function jsonShape(handle, pointer = "") {
  return JSON.parse(await method("binary", "inspect")(handle, "json-shape", pointer));
}
export async function jsonPointer(handle, pointer) {
  return JSON.parse(await method("binary", "inspect")(handle, "json-pointer", pointer));
}
export async function jsonArrayPage(handle, pointer, offset = 0, limit = 128) {
  const page = JSON.parse(await method("binary", "inspect")(handle, "json-array-page", JSON.stringify({pointer, offset, limit})));
  if (!Array.isArray(page.items) || !Number.isInteger(page.total) || page.total < offset ||
      page.items.length > limit || offset + page.items.length > page.total ||
      (page.nextOffset !== null && (page.items.length === 0 || page.nextOffset !== offset + page.items.length)) ||
      (page.nextOffset === null && offset + page.items.length !== page.total)) throw new Error("Malformed array page");
  return page;
}
export async function documentQuery(handle, format, selector, attribute = null) {
  return JSON.parse(await method("binary", "inspect")(handle, "document-query", JSON.stringify({format, selector, attribute})));
}
export async function protobufFields(handle) {
  return JSON.parse(await method("binary", "inspect")(handle, "protobuf-fields", ""));
}
export async function transform(handle, operation) {
  return method("binary", "transform")(handle, operation);
}
export async function renderedQuery(url, selector, attribute = null) {
  return JSON.parse(await method("browser", "query")(url, selector, attribute));
}
export async function composeImage(handle, layout) {
  return method("image", "compose")(handle, JSON.stringify(layout));
}
export async function packManagedCbz(handles) {
  const archive = await method("image", "packPages")(JSON.stringify(handles));
  return method("binary", "promote")(archive, "CBZ");
}

/** Reassemble a bounded top-level API object from one invocation-owned resource. */
export async function readResourceObject(handle, maxItems = 16000) {
  const shape = await jsonShape(handle);
  if (shape.kind !== "object" || !shape.keys) throw new Error("Expected response object");
  const result = Object.create(null);
  let remaining = maxItems;
  for (const [key, kind] of Object.entries(shape.keys)) {
    const pointer = "/" + key.replace(/~/g, "~0").replace(/\//g, "~1");
    if (kind !== "array") {
      result[key] = await jsonPointer(handle, pointer);
      continue;
    }
    const rows = [];
    let offset = 0, total = null;
    for (let round = 0; round < 256; round++) {
      const page = await jsonArrayPage(handle, pointer, offset, 256);
      if (page.total > remaining || (total !== null && total !== page.total)) {
        throw new Error("Resource array limit or inconsistent total");
      }
      total = page.total;
      for (const item of page.items) rows.push(item);
      if (page.nextOffset === null) break;
      offset = page.nextOffset;
    }
    if (total === null || rows.length !== total) throw new Error("Incomplete resource array");
    remaining -= rows.length;
    result[key] = rows;
  }
  return result;
}
