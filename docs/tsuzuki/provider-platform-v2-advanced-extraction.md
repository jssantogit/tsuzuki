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
* `http.requestResource(method, url, headersJson, body)` supports the existing HTTP methods and policy, returning only status and a scoped resource handle on 2xx. Error responses are never presented as data. Binary and POST responses need not cross Binder as text.
* `binary.inspect(handle, "json-array-page", JSON.stringify({pointer,offset,limit}))` reads array slices with exact nextOffset/total, max 256 items and 48 KiB output; it fails closed if one item exceeds the envelope. Handles are invocation-scoped, not persistent cursors.
* JSON shape inspection, SHA-256 and explicit resource release support safe paging and bounded memory. A single parsed JSON tree is reused inside the invocation only; releasing its handle invalidates the cache.
* HTTP preserves a declared bare JSON Content-Type without implicitly appending a charset.

Pure extraction works inside the isolated runtime with already acquired inline inputs; it does not grant any extra host authority. No source-specific rules or anti-bot circumvention.

## Remaining implementation and integrated acceptance

Add reviewed browser/HTTP session controls if required by a permitted source, reusable SDK integration in the provider repository and end-to-end source adapters. General WebAssembly, arbitrary browser execution, and DRM bypass are out of scope. Do not bridge arbitrary JavaScript evaluation or copy challenge credentials. Keep V1 compatibility and signed packages untouched.

Use MangaBall and MangaFire as independent acceptance lanes in one opt-in Android workflow after implementation. For each: validate manifest, QuickJS/Binder, allowed origins, exact bibliographic identity, chapter-one and terminal pagination, source-scoped IDs, reading page materialization, actual Reader decoding/render and SQLite restart. Require independent source success; a blocked upstream is INCONCLUSIVE/RED, not a synthetic pass. In the same gate, controlled fixtures must exercise each new primitive, malformed responses, limits, cancellation, cross-provider isolation and unchanged V1 behavior. No payload/cookie/URL logging. No automatic CAPTCHA bypass, and access only where authorized. The first controlled gate is prepared below; full implementation and live acceptance remain incomplete.

## Controlled Android gate (prepared, not run)

One instrumentation method, two independent adapter lanes. Pinned provider source
89b5979fff297c2a099359258c9f9249e0089db4 is checked out and packaged into transient
unsigned test-only ZIPs. MangaBall's existing prototype pages export and resource
POST SDK are wired only in its fixture; MangaFire uses its existing API module.
Both are wrapped only for test image composition and managed CBZ verification.
All HTTP is intercepted; no source traffic or automatic challenge bypass.
Checks cover exact source lookup, large POST (>192 KiB), terminal chapter pagination,
page metadata, transformed pixels, managed archive and provider ownership.
The controlled workflow is gated on the exact branch and an explicit
.github/provider-advanced-extraction-gate.request containing OFFLINE_CONTROLLED_ONLY.
No marker is committed with the implementation.

This is not the full live acceptance gate. Browser, protobuf, XML, compression,
negative limits, cancellation, Reader UI, canonical resolution and SQLite restart
still need integrated coverage. Production SDK adoption and permitted live source
access must be completed before declaring the feature ready. MangaBall API
authorization remains pending; a controlled pass cannot change that status.

## Focused live gate — three works

Death Note, Oyasumi Punpun (Goodnight Punpun), Akame ga Kill!; English; two provider lanes, sequential works per lane, one emulator, one debug build and one instrumentation method. Exact title/alias, terminal lookup/chapter pagination (max three lookup and four chapter result pages), chapter 1, one original page through the existing Reader transport and sampled Android bitmap decode. 15s per invocation, 75s per work, 330s instrumentation cap and 35min job cap including cold build. Stop a source on observed 403/429. No synthetic substitution, challenge retries, automatic CDN grant expansion or content logging. MangaBall approval is an explicit external setting, not inferred; missing approval cannot pass. Pinned experimental source adapters, resource SDK used by MangaBall POST and MangaFire GET. Actual ReaderActivity, editorial binding and SQLite remain pending. No request marker committed, no live executed.

The generic resource SDK now uses encoded-size metadata to recursively project oversized nested JSON objects instead of attempting to return them whole through Binder. It retains the 48 KiB projection cap, bounded operation/depth/item budgets and fail-closed oversized row behavior. This applies to nested chapter/detail responses, not only the two target sources.

## Session and transport hardening

HTTP cookie state remains private per provider and permission snapshot, with bounded session and cookie counts; changing grants discards the former HTTP session. Cross-origin entity replay is rejected, while existing safe GET redirect handling remains. Browser operations respect invocation-derived deadlines and close on interruption. Browser and HTTP are deliberately separate trust domains; no credential transfer, challenge solver or impersonation. These changes do not establish that any blocked source is accessible.

The single live instrumentation method now runs controlled adapter and local protocol conformance checks before the two live lanes, without additional Gradle builds or emulator startup. It publishes one independent contract verdict plus six live results. The controlled phase never accesses either real source. JSON paging now reuses the owned cached parse tree without re-copying the entire input for every projection. ReaderActivity and production SQLite integration still require independent evidence.

Controlled adapter completion now includes a disposable SQLDelight database using the production AndroidX SQLite driver and binding/chapter/progress repositories. It closes and reopens the database, checks both fixture provider bindings remain distinct, rejects a competing binding and verifies chapter identity and progress. Local protocol/session checks run in parallel under separate 45-second budgets before the live lanes. This is a persistence contract, not evidence of actual ReaderActivity display or a live canonical editorial match.
