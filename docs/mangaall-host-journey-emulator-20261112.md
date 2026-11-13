# MangaAll: Host→Reader Android acceptance gate

Stacked Draft PR #197 above Host #196. No main merge, signed Provider publication, trust-pinning bypass, or real user data.

## Gates

1. `ScriptProviderReadingGatewayIsolationTest.mangaAllSyntheticJourney_reconcilesCanonicalChaptersAndReaderPages`: offline isolated SCRIPT runtime with MangaAll ID, canonical refresh/reconciliation, source-chapter revalidation and byte loading through the Reader HTTP loader. This **does not** exercise a ReaderActivity or decode pixels.
2. `MangaAllReaderSourceCardInstrumentedTest.tappingMangaAllSourcePassesExactVerifiedChapterOptionWithoutCrossSourceSelection`: Android rendering and tap callback for the production source-row Compose component.
3. **New continuous journey** `CanonicalReaderSourceSwitchInstrumentedTest.mangaAllScriptSourceTapFailureAndRetryPublishesDecodedReaderPixels`: single disposable `ReaderActivity` and real `ReaderViewModel`. The test-scoped resolver uses an installed-like MangaAll SCRIPT package served by a local MockWebServer; the existing synthetic canonical Chapter 1 undergoes the real Provider chapter refresh and reconciliation. The test taps the **MangaFire** row in the Reader selector, gets a simulated HTTP 503 for `reading.pages`, asserts the previous Reader page/session survived, retries on the same Reader screen and checks the decoded PNG's expected green pixels in an Android screenshot. The test injects only a local registry/resolver into the disposable AndroidTest process, never changes production DI or the official signed trust root.
4. Legacy `CanonicalReaderSourceSwitchInstrumentedTest.sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition`: independent Mihon fixture for UI source switching, canonical page position and pixel rendering. The helper was updated to the bottom **Source** rail from the old removed overflow action.

`.github/scripts/run_mangaall_host_journey_android.sh` runs all stages offline with `app.tsuzuki.dev`, clears data between methods, preserves the full instrumented outputs in a short-lived GitHub Actions artifact, and captures screenshot/UI hierarchy/limited AndroidRuntime log after a failure. A green workflow is required **after** adding the continuous journey (not inherited from older green fixture runs).

## Typed source-click diagnostics

`ReaderViewModel.selectProviderReadingOption` writes scoped `reader_source_selected` events for STARTED, READY, typed provider failure, cancellation and unexpected failure. Typed failure includes provider error code, optional closed page-failure stage, retryable flag, requested language and MangaAll internal source family; no arbitrary URLs, exception text, auth headers or raw response payloads. READY means Reader session publication, not a rendered pixel. The companion sanitizer regression test rejects untrusted fields.

## Still not proven by this synthetic gate

- The **actual distributed**, signed `app.tsuzuki.mangaall` code7 implementation and its external MangaFire/MangaDex/MangaDot pages. Local SCRIPT reproduces the contract, not a complete real provider package.
- Real remote anti-bot/403, CDN changes, MangaDex@Home delivery, signed repository updates and persistent upgraded provider state.
- Actual Samsung A54 physical display/performance and behavior with established user progress.

Only regard the Host-level click/recovery as verified after the new continuous Android test is green. Treat remote source cases as inconclusive rather than creating guessed chapter mappings.


## New gate: original code7 .tsz inside Android isolated SCRIPT runtime

The CI checks out Provider commit e74a0ebb43ce27b3a237a80bf9d1c81df1397ee3 and uses the **same** tooling/build_provider.py that prepares publishing packages. It verifies that all main.js, modules and assets bytes match the pinned source (only the source-side publication manifest flag is removed by the production packager). The resulting code7-package.tsz is embedded as an **AndroidTest-only asset** and is not published or signed through the official repository.

ScriptProviderReadingGatewayIsolationTest.mangaAllRealCode7Package_executesOfflineHttpCanonicalAndPages loads that actual archive with ProviderPackageParser, checks id/version/manifest, and invokes its **unmodified** unified MangaAll JS through the actual isolated QuickJS ProviderRuntimeClient.invokePackage. The Host HTTP broker still enforces the archive's own HTTPS origin allowlist, but the isolated test supplies an OkHttp application interceptor that synthesizes bounded HTTP responses and **never calls the network** (chain.proceed). Unrecognized origins/paths fail closed. No trust-root override, provider source rewrite, browser bypass or external CDN request is involved.

For source-observed A54 identities Punpun mangafire:zk12 and Koe mangafire:7p56, it tests rich and legacy lookup, editorial detail, bounded chapter preview, canonical refresh from a separately user-confirmed binding, chapter-scoped Reader option and page URL/Referer; negative cases include MangaDex/en complete-empty distinct from provisional MangaFire coverage, cross-source chapter rejection without an HTTP call, 403 chapters/lookup incompleteness and 503 pages without destroying reconciled chapters.

This is a **packaged code7 Android SCRIPT→Host integration** gate; it does NOT exercise installing an officially signed feed, the full code7 package inside a continuous ReaderActivity image-rendering session, or actual remote MangaFire/CDN bytes. The independent prior ReaderActivity gate still uses a synthetic script and PNG fixture. The GitHub Actions result must be green before calling this new gate validated.


## 2026-11-12: original packaged code7 and continuous ReaderActivity **GREEN**

[Android run #43320361877](https://github.com/jssantogit/tsuzuki/actions/runs/43320361877) completed **SUCCESS, 7/7 phases PASS**. The new `CODE7_REAL_TSZ_CONTINUOUS_READER_TAP_503_RETRY_PIXELS` phase is **not** a transport replay or JS reimplementation: it opens an actual `ReaderActivity`, attaches the original pinned code7 `.tsz` through the isolated SCRIPT package runtime, reconciles an A54-observed Punpun source identity, taps the production MangaFire row, verifies the first HTTP 503 leaves the old red page/session untouched, taps again, and checks that the Host-produced `https://mfcdn.cc/replay/punpun/ch1.png` + `Referer` result downloads through the Reader's HTTP page loader and renders a decoded green PNG on screen.

Only the **network bytes** are test doubles: a Host HTTP OkHttp interceptor synthesizes MangaFire responses and an independently injected Reader HTTP client supplies the PNG for the exact original HTTPS URL. Neither interceptor calls `chain.proceed`; unexpected hosts/paths fail. The source `.tsz`, imported modules, Host origin policy, canonical reconciliation, Viewer, source tap and session rollback/retry are real application paths. The production loader factory defaults to the original no-injection behavior. The older synthetic and code7 vector stages remain independent regression gates and also pass.

This validates the complete **original code7 package → canonical chapter → ReaderActivity source selection → 503/retry → rendered pixels** path with controlled HTTP. It does **not** verify official signed repository installation/upgrade, remote MangaFire/CDN/WebView behavior, Galaxy A54 physical performance or persistence after a real update. Punpun/Koe physical defects must remain open until A54 acceptance.

## 2026-11-13: official signed code6 + isolated code7 update/rollback — **9/9 GREEN**

[Android run #43424395073](https://github.com/jssantogit/tsuzuki/actions/runs/43424395073): **SUCCESS; nine independent emulator phases PASS**. The prior seven package, Host, canonical and Reader/pixel stages remain green. Two additional tests now exercise the **real Android Host repository manager and persistent artifact store** with the exact `.tsz` archives, not fabricated manifests:

1. **Official signed code6:** immutable public Provider repo commit `3e0b0bc813910929c2215ad26bc46ff5a6a73706`, repository `app.tsuzuki.providers`, key `tsuzuki-provider-root-2026-02`, signed index sequence 32 and original published code6 SHA-256 `aa9c101e0764019bc0d09774aeea1600e6bb9f02e12e9c9096f37d0549720320`. CI verifies the official envelope with its real committed public key, then Android verifies trust and installs code6 and reopens the file-backed manager without losing accepted trust or version.
2. **Code6 → code7 update simulation:** two offline test indexes with sequence 32 → 33 signed by **one newly generated disposable P-256 key** (never the official private key), containing original 0.5.1/code6 and original 0.5.2/code7 archives. Production Host verifier shows `UPDATE_AVAILABLE`, rejects wrong SHA without replacing code6, activates code7 with preserved test-root fingerprint and rollback code6, recovers state after manager recreation, rejects signed old-sequence replay and foreign root repinning, then rolls back safely to code6.

Neither of these gates talks to the network, creates a second real repository, publishes a signed code7 index, changes the official pinned key or touches device installations. It proves **production Host trust/install/update/rollback semantics with the original binaries**, but not a real *official-key-signed* code7 distribution. The separate seven-gate original-code7 Reader test covers canonical/HTTP/pixels with synthetic responses; it does not prove reading-progress or binding storage survives a real installed-package upgrade. A54 acceptance is still necessary for real signed code7 rollout, physical persistence, MangaFire WebView/challenges and CDN.
