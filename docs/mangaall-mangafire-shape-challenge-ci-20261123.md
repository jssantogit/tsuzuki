# MangaALL — MangaFire shape challenge CI experiment (2026-11-23)

> **Nonshipping investigation only.** This document does not authorize publication, canonical binding, chapter downloads, or promoting the signed package. The operator prioritized MangaFire availability above the larger 30-work suite.

## Why this exists

- The **unchanged published signed MangaALL code10** (0.5.5, P-256 index sequence 36, SHA-256 `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f`) called the MangaFire signed API successfully in Android CI [#45103852507](https://github.com/jssantogit/tsuzuki/actions/runs/45103852507): 3/3 `HTTP200` JSON responses and source-backed chapter inventories: Punpun 147, Koe 70, Akame 292. The **combined three-source** verdict stayed Red: Koe's lookup continuation failed after three pages. Do not equate 509 observed chapters with canonical/Reader approval.
- The same environment returned `HTTP403` for MangaFire in all three works on [#45108738483](https://github.com/jssantogit/tsuzuki/actions/runs/45108738483). The source is **intermittently accessible** from GitHub-hosted CI, not reliably available.
- The existing signed adapter responds to `HTTP403` by invoking `tsuzuki.browser.readText(signedApiUrl, "body")`, but the production Android Browser Host deliberately rejects challenged main-frame HTTP 4xx/5xx before reading body text.
- The current Keiyoushi MangaFire extension, which this source already credits under Apache-2.0, uses a distinct **user-configurable**, origin-scoped WebView shape-challenge path and then repeats its request. The signer used by our published provider has the same 3 stages and keys as the current Keiyoushi implementation in the recorded comparison; changing VRF without evidence would be unsound.

## Strict isolation

The proof is isolated to **debug + androidTest** on [PR #272](https://github.com/jssantogit/tsuzuki/pull/272) (DRAFT), with no changes to production `AndroidProviderBrowserHostService`, the signed `.tsz` package, or release manifest:

- `app/src/debug/assets/mangafire-ci-solver.html` copies the Keiyoushi Apache-2.0 asset with upstream link/blob attribution. The original ten rounds of ten concurrent trials are capped to **two rounds of two trials** (at most four shape attempts).
- `MangaFireCiShapeSolverActivity` requires a separate explicit opt-in, uses the exact isolated provider WebView profile, and sends only `SOLVED`, `UNSOLVED` or a closed error category across a trusted origin-scoped bridge. Never persist/export URLs, WAF images, challenge tokens, headers, clearance cookies, user identities or raw bodies. Navigation and resource origins are restricted; TLS errors fail closed.
- Independent offline `OFFLINE_ACK` mode invokes `bridge.postMessage("probe")` from a synthetic local document with all subresource/network requests denied. It proves only the Android bridge; it does **not** attest access to MangaFire.
- The debug solver is **not** invoked by the ordinary `api`, `chapters`, `three`, or `fire` test modes.
- The separate `shape` run is limited to the **same three original works** Oyasumi Punpun, Koe no Katachi, Akame ga Kill! and a single consented manifest change. The CI preflight verifies exact work list, both explicit authorization strings and debug-only/release exclusion, as well as the signed code10 digest. No automatic retries.
- A `SOLVED` event is diagnostic **only**. The existing signed adapter must still parse live JSON, return a single MangaFire candidate, terminate its chapter cursor and attest chapter one. Global three-source lookup completeness remains a **separate** strict gate. Source-only PASS is always marked `unified=NOT_PROVEN`, not a global PASS.
- A failed shape session reports `INCONCLUSIVE` and aborts before further external source lookups. No Cloudflare bypass is declared from page-loaded status or a browser callback alone.

## Current evidence and verification

- [#45109619532](https://github.com/jssantogit/tsuzuki/actions/runs/45109619532) — **GREEN**: closed source JSON and HTTP403 diagnostics.
- [#45141960734](https://github.com/jssantogit/tsuzuki/actions/runs/45141960734) — **GREEN**: full debug APK + androidTest, 27 Python contract checks, actual Android emulator whitelist and origin-scoped WebView bridge with `network=NOT_STARTED`.
- [#45143274547](https://github.com/jssantogit/tsuzuki/actions/runs/45143274547) — first bounded live shape trial **initiated**, outcome not claimed in this document until artifacts/logs are checked.

Do not silently replace this CI with a solver farm, rotating proxies, transferred cookies, stored CAPTCHA solutions, or unlimited network retries. If the upstream challenge is not the Keiyoushi-supported shape challenge, classify it accurately and stop rather than manufacturing successful source evidence.

## Follow-up blocking gates

1. Establish real repeatable MangaFire signed JSON + chapter cursor proof in Android CI, not intermittent HTTP200.
2. Preserve the independent MangaDot lookup page-four budget error until its exact continuation is addressed; an unfinished source is neither an empty match set nor a completed global search.
3. Only after integrated three-source success resume original granular-pagination metadata parity, collector/Refresh/SQLDelight, Reader pixels and eventual 30-work batch. No merge, signing, or publication without authorization.
