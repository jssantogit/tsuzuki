# Advanced Extraction Engine — implementation branch

This branch is independent of MangaALL PR #275 and uses the existing V1 contracts. Do not merge or publish until the integrated Android acceptance gate is green.

## Implemented first increment

* `tsuzuki.protocol.jsonPointer(json, pointer)`: RFC 6901 traversal, JSON-encoded result, missing path rejected, maximum depth 64.
* `tsuzuki.protocol.protobufFields(base64)`: bounded schema-neutral wire decoder, repeated fields retained, varint/fixed values exact decimal strings, length-delimited fields returned as base64. Not schema-aware, not a general binary resource API.
* `tsuzuki.document.query(document, format, selector, attributeOrNull)`: bounded inline HTML/XML text or attribute selection; limited CSS subset; JSON array, absent attributes null.
* Existing `browser.readText` waits for a late DOM selector within its original deadline, invalidates previous-navigation callbacks and retains origin/challenge enforcement.
* Existing image crop checks overflow-safe coordinates and dimension limits.
* Host-backed `binary.inspect(handle, operation, argument)` supports JSON pointer, protobuf fields and structured document queries up to 2 MiB input; `binary.transform(handle, operation)` supports bounded gzip and base64; ownership and existing host budget preserved.
* `image.compose(handle, layoutJson)` composes non-overlapping, fully covering rectangles from one image using bounded Android bitmaps and PNG output; no network or external URLs.
* ZIP entry skipping is bounded by cumulative expanded bytes and entry count; directory entries handled.
* `browser.query(url, selector, attributeOrNull)` reads an ordered bounded array of text/attributes from a rendered page, preserving browser origin policy and the deadline. No general page-context evaluation or session-cookie export.
* `image.packPages(handlesJson)` packs only Host-created transformed PNG handles into a bounded CBZ resource, which can use the existing `binary.promote(handle, "CBZ")` managed delivery contract.
* `protocol.extractionVersion()` exposes additive extraction revision 1; SDK feature detection must still check the required modules.

Pure extraction works inside the isolated runtime with already acquired inline inputs; it does not grant any extra host authority. No source-specific rules or anti-bot circumvention.

## Remaining implementation and integrated acceptance

Add reviewed browser/HTTP session controls if required by a permitted source, reusable SDK integration in the provider repository and end-to-end source adapters. General WebAssembly, arbitrary browser execution, and DRM bypass are out of scope. Do not bridge arbitrary JavaScript evaluation or copy challenge credentials. Keep V1 compatibility and signed packages untouched.

Use MangaBall and MangaFire as independent acceptance lanes in one opt-in Android workflow after implementation. For each: validate manifest, QuickJS/Binder, allowed origins, exact bibliographic identity, chapter-one and terminal pagination, source-scoped IDs, reading page materialization, actual Reader decoding/render and SQLite restart. Require independent source success; a blocked upstream is INCONCLUSIVE/RED, not a synthetic pass. In the same gate, controlled fixtures must exercise each new primitive, malformed responses, limits, cancellation, cross-provider isolation and unchanged V1 behavior. No payload/cookie/URL logging. No automatic CAPTCHA bypass, and access only where authorized. No tests or workflows added in this increment; implementation is not yet complete.
