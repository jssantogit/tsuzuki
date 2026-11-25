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

/**
 * Read a structured response without sending a large nested object through
 * Binder. Small values use direct projection; large arrays are sliced.
 * An oversized individual array row remains an explicit failure.
 */
export async function readResourceObject(handle, maxItems = 16000) {
  if (!Number.isSafeInteger(maxItems) || maxItems < 1 || maxItems > 16000) {
    throw new Error("Invalid resource item budget");
  }
  let remaining = maxItems, calls = 0;
  function budget() {
    if (++calls > 220) throw new Error("Resource extraction operation budget");
  }
  async function read(pointer, depth, knownShape = null) {
    if (depth > 16) throw new Error("Resource extraction depth limit");
    if (!knownShape) budget();
    const shape = knownShape || await jsonShape(handle, pointer);
    if (!Number.isSafeInteger(shape?.bytes) || shape.bytes < 0) throw new Error("Malformed resource shape");
    if (shape.kind === "array") {
      if (!Number.isSafeInteger(shape.size) || shape.size < 0 || shape.size > remaining) {
        throw new Error("Resource array limit");
      }
      remaining -= shape.size;
      const rows = [];
      let offset = 0;
      do {
        budget();
        const page = await jsonArrayPage(handle, pointer, offset, 256);
        if (page.total !== shape.size) throw new Error("Inconsistent array total");
        rows.push(...page.items);
        if (page.nextOffset === null) break;
        offset = page.nextOffset;
      } while (true);
      if (rows.length !== shape.size) throw new Error("Incomplete resource array");
      return rows;
    }
    if (shape.bytes <= 48 * 1024) {
      budget();
      return jsonPointer(handle, pointer);
    }
    if (shape.kind !== "object" || !shape.keys) throw new Error("Oversized resource scalar");
    const result = Object.create(null);
    for (const key of Object.keys(shape.keys)) {
      const child = pointer + "/" + key.replace(/~/g, "~0").replace(/\//g, "~1");
      result[key] = await read(child, depth + 1);
    }
    return result;
  }
  budget();
  const root = await jsonShape(handle);
  if (root.kind !== "object") throw new Error("Expected response object");
  return read("", 0, root);
}
