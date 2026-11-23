# MangaALL — MangaFire real signed API on GitHub Actions Android WebView

**Status (2026-11-22): CI real Android signed-API gate PASSED for 2/2 works.** This is a source access proof, **not** a canonical/chapter/Reader acceptance or a green 30-work smoke.

## CI execution and exact evidence

The operator authorized an opt-in two-title workflow on the experimental Host branch; GitHub Actions built `app.tsuzuki.dev` and AndroidTest on `ubuntu-24.04`, launched a disposable Android 35 Google APIs emulator using `ReactiveCircus/android-emulator-runner`, and executed the debug-only `mangaAllCode10CiWebviewSameProfile_checksRealSignedApi` instrumentation.

- [Real CI run #45094285225](https://github.com/jssantogit/tsuzuki/actions/runs/45094285225): **SUCCESS** from the strict real-source gate, not merely infrastructure green. [Sanitized verdict artifact #14783056311](https://github.com/jssantogit/tsuzuki/actions/runs/45094285225#artifacts).
- Original unchanged P-256 signed MangaALL code10 `0.5.5`, sequence36, package SHA256 `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f`; only the AndroidTest APK stages the archive.
- The original signed code10 **unified three-source** lookup (MangaDex, MangaDot, MangaFire) generated the exact MangaFire `/api/titles` signed request, captured only in memory. The real `AndroidProviderBrowserHostService.readText` requested it using the same isolated `ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall")` as the passive warmup Android WebView.
- **Oyasumi Punpun**: `api=VALID_JSON|candidates=1`.
- **Koe no Katachi**: `api=VALID_JSON|candidates=1`.
- Consolidated: `MANGAALL_CI_WEBVIEW|outcome=CI_SIGNED_API_ACCEPTED|signedJson=2|works=2|providerCandidateWorks=2|canonical=NOT_TESTED|chapters=NOT_TESTED|readerPixels=NOT_TESTED`.
- CI gate: `MANGAALL_CI_BROWSER_GATE|outcome=PASS|signedCode10=UNCHANGED|canonical=NOT_TESTED|chapters=NOT_TESTED|readerPixels=NOT_TESTED`.

**Notable discovery:** the passive warmup homepage reported `MANGAALL_CI_WEBVIEW|phase=WARMUP|outcome=HTTP_REJECTED`, yet both subsequent **real signed API GETs succeeded with validated JSON**. Thus, a homepage challenge or page-load success flag is **not an adequate proxy** for API availability; the API must be tested directly. No user action was required in the CI, no A54 was connected, and no cookies/URLs/challenge payloads were transferred or persisted as artifacts.

## Boundaries and security

This lives in [Host draft PR #272](https://github.com/jssantogit/tsuzuki/pull/272), not production. The new Activity is non-exported, present only in the **debug manifest**. Workflow `.github/workflows/mangaall-ci-browser-host-live.yml` requires an exact explicit opt-in and limits the initial test to two works. Runner `.github/scripts/run_mangaall_ci_webview_android.sh` refuses non-emulators and parses only closed categorical verdicts. If either work lacks a valid JSON `items` response and a MangaFire candidate, the gate is **RED**, even if APK compilation succeeded.

This does **not** establish that:
- the site homepage was normally accessible,
- the same method will always work from every hosted IP or after challenge changes,
- the release app sandbox already shares this experimental debug session,
- MangaFire chapters, chapter cursor pagination, canonical bindings, SQLite evidence or Reader CDN pixels work,
- a 30-work tri-source smoke has been accepted.

## Next required proof before 30 works

Expand the **same CI Android Browser Host setup** to MangaFire chapter retrieval and pagination (with bounded cursors/bytes and explicit missing vs blocked classifications), then run tri-source work identity/chapter mapping. Keep third-party source failures visible instead of silently accepting only MangaDex/MangaDot. Only scale the corpus after the MangaFire chapter gate is confirmed and the existing >64KiB Host result limit has a proven solution.

The earlier [test-only Playwright/Keiyoushi experiment in PR #271](https://github.com/jssantogit/tsuzuki/pull/271) returned Cloudflare HTTP403 from GitHub-hosted Node/Chromium; it is a separate harness and was **not** needed to make the Android Browser Host CI API gate pass. Do not merge CI results from these environments or treat one as proof of the other.
