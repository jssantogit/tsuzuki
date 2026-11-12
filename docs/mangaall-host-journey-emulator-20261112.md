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
