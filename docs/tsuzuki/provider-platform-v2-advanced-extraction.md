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

CI #45730822315 RED before instrumentation: Kotlin disallows ambiguous ConcurrentHashMap.contains syntax (two sites). Use explicit containsKey in both guards. Completion criterion confirmed by user: both original providers must complete the six real live observations; controlled results alone are insufficient. Resolve access through supported methods; no simulated live pass or replacing a denied source. Offline verification only while external denials remain unresolved.

CI #45731552411 GREEN only offline (5m41s build), controlled adapters and local protocol/session passed. User requested resumption of live with grouped corrections and strict RED on any 403. This revision uses bounded resource transport for all MangaBall GET/POST responses, preserves typed HTTP status and challenge marker from Reader transport, records source denials through one scoped mechanism, and adds an independent strict verdict validator with self-test (missing, duplicate, skipped, controlled-only, contradictory denial and six PASS). No header changes, access bypass or automatic grant expansion; real upstream access is still unproven. A single bounded live recheck is requested; stop a source on denial and require all six PASS.

## Live recheck and access status

[CI #45732844967](https://github.com/jssantogit/tsuzuki/actions/runs/45732844967) at `969259c8` ran both controlled Android contracts successfully (`ADAPTERS_SQLITE` and `PROTOCOL_SESSION`). Live acceptance was **RED (0/6)**. Each lane stopped on its first lookup:

| Provider | Death Note | Oyasumi Punpun | Akame ga Kill! |
| --- | --- | --- | --- |
| MangaBall | HTTP 403 (387 ms); `cf-mitigated: challenge` observed | Not attempted after 403 | Not attempted after 403 |
| MangaFire | HTTP 403 (360 ms); no explicit challenge marker observed | Not attempted after 403 | Not attempted after 403 |

Both denials occurred before the adapters received catalog data for the lookup. The MangaBall marker identifies a Cloudflare challenge without identifying the rule that triggered it. For MangaFire, the absence of the marker does not establish the reason for the denial. The earlier Punpun pass in [CI #45727344374](https://github.com/jssantogit/tsuzuki/actions/runs/45727344374) is historical evidence only.

A one-time, user-authorized REA observation outside CI found HTTP 200 on both public homepages in Chromium. The capture covered the main document responses; its subresource coverage was limited. It did not test adapter endpoints, chapter delivery, Android decoding, or access from GitHub Actions. No supported automation interface was confirmed for either provider. Confirm a supported access path for each adapter and CI environment before spending another live run on the same denied requests.

The [MangaBall site rules](https://mangaball.com/article/site-rule) require explicit API approval for automated scraping and direct rule questions to its moderation team. Its [About page](https://mangaball.com/article/about-us) links the [official Discord](https://discord.gg/A4ktFmpqnH); no approved access or authenticated contact session was available. The [MangaFire contact form](https://mangafire.to/contact) was reachable but required human verification; no request was submitted. These observations establish the current access dependencies, not the exact rules that triggered the CI responses.

The live gate now validates the package and native work/chapter IDs in both lanes, requires distinct work IDs within each lane, and retains evidence of alternate editions across lookup pages. It rejects an adapter-declared lookup failure before considering any candidates in the same response and stops the affected lane on a reported access denial. These checks address acceptance gaps; they did not cause either observed 403. Merge and release remain pending on the six live results and both controlled contracts.

[CI #45736914342](https://github.com/jssantogit/tsuzuki/actions/runs/45736914342) at `cd84332c6` compiled and executed the controlled Android test successfully in airplane mode: `controlled_adapters=PASS`, `local_protocol_session=PASS`, `live=NOT_RUN`.

## Supported access architecture

The current adapters use the bounded Host HTTP resource API. This API returns a handle only after an upstream 2xx response, so changing resource representation, parser, or QuickJS runtime cannot fix a denial before receipt of catalog data. The platform also has a separate, origin-scoped WebView for bounded DOM reads. Public page availability in a browser establishes neither permission to automate the reader nor access from Actions. Changing the client transport is relevant only if the operator provides a supported browser or HTTP contract for that workflow.

An operator-documented HTTPS API would fit the existing Host transport and scoped provider secret permissions. The default Host secret resolver returns no credential; a credentialed API needs a provisioned, provider-scoped CI secret and a wired resolver as well as an adapter for the documented schema. Chapter delivery and Reader decoding would still be required in CI. A provider-approved network allowlist is another possibility only if the operator offers it as an access method. The current public personal repository uses a standard GitHub-hosted runner and has no registered self-hosted runner. GitHub's [static-IP hosted runner option](https://docs.github.com/en/actions/how-tos/manage-runners/larger-runners/manage-larger-runners#creating-static-ip-addresses-for-larger-runners) requires an Enterprise Cloud organization. There is no evidence that IP is the cause of either latest denial or that provisioning different egress would be authorized.

### MangaBall

The [pinned Tsuzuki adapter](https://github.com/jssantogit/providers-tsuzuki/blob/89b5979fff297c2a099359258c9f9249e0089db4/providers/mangaall-ball/modules/mangaball.js), [Keiyoushi extension](https://github.com/keiyoushi/extensions-source/blob/92906c0382547f30a03b3176f2f2429be6b54dce/src/all/mangaball/src/eu/kanade/tachiyomi/extension/all/mangaball/MangaBall.kt#L98-L247), and [Kotatsu-Redo parser](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/blob/bafb2e0d9a0bc60250cd3f71d2253442a4cf69d2/src/main/kotlin/org/koitharu/kotatsu/parsers/site/all/MangaBall.kt#L136-L250) all use the site's internal JSON route family. The native implementations normalize permalinks to source IDs and distinguish content language from original language. Those differences affect identity and filtering after a successful request, not the observed 403 at search. An [older extension CI run](https://github.com/jssantogit/tsuzuki/actions/runs/35944402938) found pt-BR search candidates; it did not prove the required English journey or current API access. Porting either native extension or using the public WebView would still require the explicit automation approval specified in the MangaBall rules. An approved API, its allowed origins and scope, and any credentials or network prerequisites remain external dependencies.

### MangaFire

The [Keiyoushi Kotlin extension](https://github.com/keiyoushi/extensions-source/blob/de6282d728ab65098f55477afbe0d43ef135121b/src/all/mangafire/src/eu/kanade/tachiyomi/extension/all/mangafire/MangaFire.kt#L33-L55) uses native OkHttp and [typed JSON models](https://github.com/keiyoushi/extensions-source/blob/de6282d728ab65098f55477afbe0d43ef135121b/src/all/mangafire/src/eu/kanade/tachiyomi/extension/all/mangafire/Dto.kt#L114-L164), unlike the Tsuzuki adapter's QuickJS and bounded Host resources. The reviewed [Yūzōnō extension](https://github.com/yuzono/tachiyomi-extensions/tree/fbcca004dbc0983b988ae779fa9811a300e62a07/src/all/mangafire) shares the native implementation; the [Mangayomi adapter](https://github.com/Swakshan/mangayomi-swak-extensions/blob/06e41ce0ff62f4c59aab3bfa526bd55df7c83abe/javascript/manga/src/all/mangafire.js#L49-L96) also consumes site JSON. None establishes a supported third-party contract. [Tsuzuki's native extension test](../research/mangafire-real-extension-android-test.md) demonstrates registration and loading, not CI catalog access. The same provider pin previously decoded a real page for Punpun, so the bounded transport is viable when the request is accepted. Typed models can improve data handling, but do not explain why the later search was denied.

MangaFire's [first-party progress sync](https://mangafire.to/user/import-export?tab=sync) covers linked titles and reading progress. It does not supply search, chapter inventory or page bytes. No documented browser session or API that supports the full automated CI journey was found. The remaining dependency is a documented integration or explicit permission for the required requests from CI, including chapter and image delivery.

No live rerun was requested by this review: neither comparison established a new supported access path. The last live verdict remains RED, with 0/6 accepted in that run.

### MangaFire: differential CI baseline

In [#45727344374](https://github.com/jssantogit/tsuzuki/actions/runs/45727344374), Death Note reached identity matching (`LOOKUP_IDENTITY_NOT_UNIQUE`), Punpun passed through chapter inventory and real Android image decoding, and Akame ended with `RUNTIME_DIED`. In [#45728483768](https://github.com/jssantogit/tsuzuki/actions/runs/45728483768), [#45728852099](https://github.com/jssantogit/tsuzuki/actions/runs/45728852099), and [#45732844967](https://github.com/jssantogit/tsuzuki/actions/runs/45732844967), Death Note was denied during lookup and Punpun was not reached. Those later runs do not tell us how a Punpun lookup would have behaved. The latest MangaFire response lacked an explicit challenge marker; that absence does not identify the cause of the 403.

The successful Punpun job ran on runner image `20261116.376.1` in Azure `westus2`; the three later live jobs all ran on `20261116.133.1` in AWS `us-west-2`. They shared the `ubuntu-24.04` label, runner version, emulator and fresh API 35 AVD configuration, Java/Gradle versions, provider commit and Gradle cache key. The four live jobs did not overlap. A later job on the Azure image was controlled-only and did not reach MangaFire. The live comparison therefore changes application SHA, runner image, cloud and time together; it does not isolate egress IP, cloud platform, runner image, or an upstream policy. The logs do not record egress IP or the upstream decision.

Reconstructing the MangaFire live payload from provider pin `89b5979f` and each revision's packaging script gave identical contents across `2e924dd1`, `42bd6999`, `6c1615ce`, `969259c8`, and `a51ad8bd3` (eight ZIP entries; canonical name-and-content SHA-256 `f9e910c3dd8ef6a41817f4b2e80eb99e1a0d2c87784087576376846871b5619b`). That comparison does not attest to the compiled APKs, which were not archived. The initial lookup input, Host resource GET, and provider-scoped HTTP cookie jar match between the passing and first denied revisions. Android WebView is not granted in the live manifest, and Binder rebinding does not reset the Host cookie jar. The denial record identifies the lookup invocation; it cannot distinguish the initial request, redirect, or candidate-detail request.

`42bd6999` retired dead Binder leases and allowed one lookup retry only after `RUNTIME_DIED` and in the absence of a recorded denial. Its edition filter runs after lookup. `6c1615ce` enabled the MangaBall lane and added stop-on-401; the first MangaFire 403 preceded that change. Later Host changes paused requests after a recorded denial, and Reader changes occur after lookup. The newer gate requests structured lookup diagnostics, but only after the three compared 403 runs. None of these differences establishes a regression in the MangaFire transport or session lifecycle that would justify a code change or a new live run under the same conditions.

#### Network stack and request attribution

The version catalog and dependency configuration for the app and Host did not change across the compared SHAs (OkHttp `5.5.0`). The Android instrumentation constructs its own OkHttp client in the emulator; neither the host runner's `curl`/OpenSSL nor WebView is the HTTP client for this path. The Host saw an HTTP status after executing the call. A failed DNS lookup or failed TLS handshake at that hop would not itself produce that status. The presence of a response does not identify the TLS settings, the responder within the upstream infrastructure, or the reason for refusal.

The [Azure runner SBOM](https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20261116.376) lists kernel `7.0.0-1017-azure`, `systemd-resolved` `255.4-1ubuntu8.18`, glibc `2.39-0ubuntu8.12`, OpenSSL `3.0.13-0ubuntu3.16`, and `libslirp0` `4.7.0-1ubuntu3.1`. The official manifest for the AWS image reported in the runs was unavailable, so equivalent package versions cannot be compared. All four logs show Netsim Wi-Fi initialization, but none preserves the effective host or guest DNS configuration. On API 35 the client uses the platform's TLS provider; the packaged Conscrypt override applies only below Android 10. The active Android DNS and Conscrypt module versions, negotiated TLS/ALPN, and WebView package version were not recorded.

All four runs retained only sanitized `verdicts.txt`, with elapsed time per work rather than per request. `LOOKUP_IDENTITY_NOT_UNIQUE` in the baseline means evaluation reached the identity check; that revision used the same code for zero and multiple exact matches. In the denied runs no Binder lookup retry was observed, and the provider returns a terminal lookup page. The first lookup invocation is therefore localized; the first outbound request is not. Neither the per-hop correlation ID nor the redirect depth was exported, so the actual requests and responses cannot be compared to distinguish a denial on search, on a candidate detail, or after a redirect.

The Host and the reviewed native extension both use OkHttp for HTTP. The Host can carry a documented HTTPS contract with scoped origins and credentials, but the public browser UI and progress sync do not supply a supported automated contract for the complete CI journey. No supported network, TLS, or session change follows from the archived evidence.

If a supported live experiment becomes available, keep the runner conditions, adapter, Host, Android image and work fixed. In the existing denial sink, retain only the first denial's logical request ordinal, redirect depth, and status. In this pinned lookup, the first logical request is search and subsequent ones are candidate details; either can follow a redirect. An adapter-only denial with no matching Host observation has unknown attribution. This would locate the failing operation without changing the wire request or preserving URLs, credentials, or bodies. It would not establish a causal relationship to the runner image. A fixture-only replay cannot recover the old transactions.
