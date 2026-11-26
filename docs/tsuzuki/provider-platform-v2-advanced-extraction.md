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

Death Note, Oyasumi Punpun (Goodnight Punpun), Akame ga Kill!; English; two provider lanes, sequential works per lane, one emulator, one debug build and one instrumentation method. Exact title/alias, terminal lookup/chapter pagination (max three lookup and four chapter result pages), chapter 1, one original page through the existing Reader transport and sampled Android bitmap decode. 15s per invocation, 75s per work, 330s instrumentation cap and 35min job cap including cold build. Stop a source on observed 403/429. No synthetic substitution, challenge retries, automatic CDN grant expansion or content logging. Initially MangaBall was gated on a separate approval setting; this historical setting was removed by the user's explicit request for a limited development-only live probe. This does not assert site approval. Pinned experimental source adapters, resource SDK used by MangaBall POST and MangaFire GET. Actual ReaderActivity, editorial binding and SQLite remain pending. No request marker committed, no live executed.

The generic resource SDK now uses encoded-size metadata to recursively project oversized nested JSON objects instead of attempting to return them whole through Binder. It retains the 48 KiB projection cap, bounded operation/depth/item budgets and fail-closed oversized row behavior. This applies to nested chapter/detail responses, not only the two target sources.

## Session and transport hardening

HTTP cookie state remains private per provider and permission snapshot, with bounded session and cookie counts; changing grants discards the former HTTP session. Cross-origin entity replay is rejected, while existing safe GET redirect handling remains. Browser operations respect invocation-derived deadlines and close on interruption. Browser and HTTP are deliberately separate trust domains; no credential transfer, challenge solver or impersonation. These changes do not establish that any blocked source is accessible.

The single live instrumentation method now runs controlled adapter and local protocol conformance checks before the two live lanes, without additional Gradle builds or emulator startup. It publishes one independent contract verdict plus six live results. The controlled phase never accesses either real source. JSON paging now reuses the owned cached parse tree without re-copying the entire input for every projection. ReaderActivity and production SQLite integration still require independent evidence.

Controlled adapter completion now includes a disposable SQLDelight database using the production AndroidX SQLite driver and binding/chapter/progress repositories. It closes and reopens the database, checks both fixture provider bindings remain distinct, rejects a competing binding and verifies chapter identity and progress. Local protocol/session checks run in parallel under separate 45-second budgets before the live lanes. This is a persistence contract, not evidence of actual ReaderActivity display or a live canonical editorial match.

## First integrated execution request

The one-shot marker requests the bounded Android workflow: controlled contracts and live three-work lanes. It does not certify third-party access permission. The current marker is LIVE_THREE_REQUESTED; it records only an explicit developer test request. No signed package is published. The gate cannot pass without both controlled contracts and six unique live observations. This first run is also the compile gate for the implementation branch.

First CI #45725322865 stopped at instrumentation compilation in 4m04s: the disposable SQLite test used the Android convenience overload, unavailable in the test source set. Fix uses the actual FileProvider path lambda, creating its parent directory. Controlled MangaFire now exercises the same resource SDK transport as the live candidate; challenge metadata remains explicit. No live observations existed in the first run.

CI #45726003735 stopped during dependency resolution (Maven Central HTTP 429, build 2m23s); the previous SQLite correction remains unverified. The build wrapper retries exactly once only when Maven Central explicitly reports 429, with a 30-second cooldown and reduced Gradle concurrency. It never retries compiler errors or tests, changes repositories, or masks a failing build. Both live and controlled acceptance criteria remain unchanged.

CI #45727344374 compiled in 5m11s; both controlled contracts passed. MangaFire Punpun passed live (7263ms), Death Note ambiguous, Akame runtime died; MangaBall access not approved. The next revision retires a dead Binder lease by identity, retries only read-only lookup once on RUNTIME_DIED, and applies the existing production alternate-edition exclusion predicate to live extraction candidates. No guessing a preferred HID, no retry on access denial, no editorial certification. Added controlled idle-expiry and edition classification checks and sanitized counters. The precise cause of process death remains unproven.

2026-11-25: User explicitly requested real MangaBall queries for private development, rather than treating a missing CI setting as a technical block. Remove the MANGABALL_API_APPROVED variable and ACCESS_NOT_APPROVED synthetic outcome from this experimental workflow only. No third-party approval is claimed; public endpoint access is tested within existing origin and request limits. HTTP 401/403/429 remain real failures and stop subsequent invocations for that source. No credentials, challenge bypass, new CDN grants, signed release or public app distribution. The two controlled contracts and six live observations remain required.

CI #45728483768: both controlled contracts PASS, MangaFire first real request HTTP 403, other works skipped; MangaBall approval gate still present in that revision. CI #45728852099: build GREEN 4m17s, both controlled contracts PASS, first real lookup for each provider HTTP 403 (MangaBall 444ms, MangaFire 380ms); four subsequent work combinations not attempted. No proof of challenge type or cause. Add opt-in Host request guard scoped by provider, closed diagnostic marker for cf-mitigated: challenge, explicit NOT_ATTEMPTED_HTTP status, and local regressions for 401/403/429 and unrelated-provider isolation. No further live trigger after two consecutive MangaFire denials and initial MangaBall denial; preserve marker unchanged. New code pending validation; prior successful controlled evidence belongs to previous SHA. No access workaround.

A separate controlled-only check validates the newly changed denial guard without making further upstream calls. One Android instrumentation method executes both adapter/SQLite and local protocol/session/denial contracts in airplane mode, 90s method/120s runner cap, 35min job cap. Not a replacement for live acceptance.
