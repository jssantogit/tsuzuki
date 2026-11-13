# MangaAll: Host→Reader Android acceptance gate

**Release status (2026-11-13).** The Host was squash-merged into main as
[PR #197](https://github.com/jssantogit/tsuzuki/pull/197),
commit `22db2bdd94d993b239d3d1e5e07cab8a62ebc043`.
The original MangaAll code7 was squash-merged into the provider repo via
[PR #64](https://github.com/jssantogit/providers-tsuzuki/pull/64).
The official provider repository publication
[CI #43500638681](https://github.com/jssantogit/providers-tsuzuki/actions/runs/43500638681)
succeeded, using the **existing P-256 root** `tsuzuki-provider-root-2026-02`.
The real signed index is now **sequence 33**, with exactly one upgrade:
`app.tsuzuki.mangaall` **0.5.1/code6 → 0.5.2/code7**,
archive SHA-256 `cbc7e890fda26138a20358a332c8a700b3aec19ba14c47c53834dc35001673f0`.
The signed index was verified by the publisher, all four descriptor identities
and the other three descriptor hashes were retained. No new repository or
Provider ID was enrolled or created.

## Physical Galaxy A54 acceptance — still pending, not solved by offline CI

1. On the same Galaxy A54 that has the existing library, install the
   **signed `main` release APK in place**. Keep app data, settings, and
   the existing `app.tsuzuki.providers` signed enrollment. Do not uninstall
   Tsuzuki, clear app data, switch to `app.tsuzuki.dev`, or re-enroll.
   The current Host release application ID is `app.tsuzuki`,
   version `0.20.4` / versionCode `29`; installation requires the existing
   signing certificate. The APK build workflow checks it.
2. Open **Providers → Installed**, refresh the *existing* Tsuzuki Providers
   repository, and confirm the single MangaAll becomes **UPDATE_AVAILABLE**
   from `0.5.1 (6)` to `0.5.2 (7)`. Update it; no separate MangaDex,
   MangaDot, or MangaFire provider should be needed for MangaAll.
3. With existing **Oyasumi Punpun** and **Koe no Katachi** library entries,
   refresh chapters. Verify actual canonical chapter counts, first/last
   number, exact source work ID (`mangafire:zk12` and `mangafire:7p56`
   when selected), language, author/alias evidence, and correct mapping
   when tapping **choose reading edition**. A complete-empty preferred
   `MangaDex/en` inventory must not suppress *verified provisional*
   MangaFire chapter evidence or silently replace the preferred binding.
4. Open the Reader and select available **reading sources**. Verify the
   exact canonical chapter opens, decoded images render on the real
   MangaFire/CDN connection, retry after a failure, and change sources
   without opening the wrong title, chapter zero, or resetting progress.
   Verify no empty/duplicate/unavailable source rows are displayed.
5. Close and reopen the app, then check the old library entry, preferred
   edition, canonical chapter order, last-page position, installed provider
   version, and repository fingerprint are preserved. If any check fails,
   export **Tsuzuki Logs** with verbose diagnostic capture for the exact
   attempted chapter and source. Mask/redact private tokens or session
   material. For Provider failures, use the supported rollback to code6;
   **never downgrade the signed repository index**.

**This release merely enables the first physical code7 acceptance.** The
earlier Android acceptance [#43454459767](https://github.com/jssantogit/tsuzuki/actions/runs/43454459767)
was **9/9 PASS** with synthetic HTTP/Reader pixels and ephemeral key
for a simulated code7 upgrade; the physical MangaFire HTTP challenge, CDN
pixels, real update, and Galaxy A54 data survival remain unverified.



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

Neither of these gates talks to the network, creates a second real repository, publishes a signed code7 index, changes the official pinned key or touches device installations. It proves **production Host trust/install/update/rollback semantics with the original binaries**, but not a real *official-key-signed* code7 distribution. The separate original-code7 Reader test covers canonical/HTTP/pixels with synthetic responses. The extended [SQLite-continuity rerun #43426396395](https://github.com/jssantogit/tsuzuki/actions/runs/43426396395) also **SUCCESS 9/9**: the upgrade/rollback phase creates an actual canonical title/chapter, favorite READING library entry, existing USER_CONFIRMED MangaAll/MangaDex/en work binding and page-3 canonical progress inside the disposable app's real SQLite database. It re-reads every one of those records before installation and after code6, tampered code7 rejection, code7 activation, file-backed Host manager recreation and rollback. No canonical ID, library membership, saved preferred edition or page position changes. This is **SQLite continuity across the simulated provider upgrade**, not proof of full app-process-death or physical A54 persistence. A54 acceptance is still necessary for real signed code7 rollout, remote MangaFire WebView/challenges and CDN.
