# MangaALL — Test-only MangaFire shape CAPTCHA experiment (2026-11-22)

**State: experimental.** This is neither a production app feature nor proof that MangaFire works on the GitHub runner.

The only modified code is in the Host CI harness. The signed MangaALL Provider 0.5.5/code10, P-256 index sequence 36, its JS modules, Android Browser Host/Binder and the application remain unchanged.

## How it works

- The existing Node HTTP bridge sends signed-code10 upstream GET requests. Only a **MangaFire GET /api/** response with HTTP 403 and JSON `{"error":"captcha_required"}` may invoke a disposable Playwright Chromium session.
- The browser loads a **pinned Apache-2.0 Keiyoushi MangaFire solver.html** at the original origin, and uses OpenCV.js to attempt the shape-selection CAPTCHA. It is adapted in memory from the upstream 10 rounds x 10 concurrent tries to **2 rounds x 2 tries** (maximum four), once per shard.
- A server-issued `waf_pass` cookie is kept inside the same ephemeral browser context. The originally signed API URL must then return **HTTP 200 JSON**. A solver success signal, a clearance cookie alone, a browser page load or a response from another source is *not* proof.
- Subsequent MangaFire API GETs reuse the same disposable browser context. Cookies, tokens, HTML challenges, images, and browser state are never exported into CI artifacts.
- **`cf-mitigated: challenge` is classified separately**. It may initiate one bounded, passive Chromium visit to the site's normal home page, letting standard browser-side Cloudflare JavaScript run; no visual CAPTCHA solver, challenge clicking, fingerprint spoofing or proxy changes are attempted. Only a `cf_clearance` cookie in that disposable context followed by signed-API JSON 200 can validate the session. An unresolved challenge remains inconclusive. Other 403s, invalid responses, timeouts and unsupported origins/methods remain blocked. No IP rotation, stealth or publication.
- This is only evidence of source HTTP availability. It does not grant canonical work identity, editorial edition, complete chapters, SQLite acceptance or Reader CDN pixels.

## Execution

- Offline contract: `node .github/scripts/test_mangaall_shape_test_core.mjs`.
- One-shot live probe: `.github/workflows/mangaall-mangafire-shape-experiment.yml`, with **Oyasumi Punpun** and **Koe no Katachi** in one real, three-source signed-code10 probe. A manual dispatch must include the exact acknowledgement `I_ACCEPT_TEST_ONLY_AUTO_SHAPE`, or an explicitly scoped commit request on `probe/mangaall-mangafire-shape-*`. Browser state is discarded at job completion.
- The 30-work smoke retains its default no-solver mode; an experimental solver/browser-session option, if enabled, must be explicitly operator selected and must not falsely promote 2/3 sources to tri-source PASS.

## Attribution

Keiyoushi `extensions-source`, Apache License 2.0: <https://github.com/keiyoushi/extensions-source/blob/5d54f0d02e2a7dbdda08e823de444f7527cc883c/LICENSE>. Pinned solver <https://github.com/keiyoushi/extensions-source/blob/5d54f0d02e2a7dbdda08e823de444f7527cc883c/src/all/mangafire/assets/solver.html> at commit `5d54f0d02e2a7dbdda08e823de444f7527cc883c` and blob SHA `f975cc6b3951f1793e932a11f0422f2573ef3f26`. Downloaded and Git-hash verified at test runtime, not bundled with the app.

## Acceptance criteria

1. Offline tests pass with exact Cloudflare-vs-JSON distinction, bounded solve attempts, opt-in and no session leakage.
2. The two-work experiment yields real sanitized source evidence and explicitly reports the shape attempt outcome.
3. Only an observed successful session should justify enabling the full 30-work experiment. An unresolved Cloudflare challenge, the independent >64 KiB Host result limit and real Reader pixels remain separate blockers.

## Live observations (2026-11-22)

- [Run #45085281359](https://github.com/jssantogit/tsuzuki/actions/runs/45085281359): CI SUCCESS, offline shape suite SUCCESS; 2 inconclusive source cases; 6 MangaDex/5 MangaDot HTTP200, MangaFire 1 HTTP403 `cf-mitigated: challenge`. Shape solver correctly not invoked (0 shape attempts). Browser challenge not tried.
- [Run #45085541343](https://github.com/jssantogit/tsuzuki/actions/runs/45085541343): CI SUCCESS, 2 inconclusive cases; one native headless Chromium JavaScript challenge probe, no `cf_clearance` (`CLOUDFLARE_BROWSER_UNRESOLVED`). This result does not show that the shape CAPTCHA solver was exercised.
- Follow-up compares headed Chromium on Xvfb, permits origin-scoped Cloudflare challenge POST, and requires parsed non-error JSON from the original signed API. If unresolved, stop CI retries and validate access on physical Android. Neither attempt validates Reader pixels or Host's 64-KiB bound.
