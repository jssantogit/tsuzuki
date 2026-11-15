# MangaAll Host — Galaxy A54 signed APK `a059a89` smoke and cursor handoff

Date: 2026-11-14. Physical environment: Samsung Galaxy A54, Android 14, `app.tsuzuki` 0.20.4 (versionCode 29), Host commit `a059a89`, signed MangaAll `app.tsuzuki.mangaall` 0.5.2/code7. The smoke log is a user-supplied Tsuzuki diagnostic export, not a synthetic replay.

## Observations (physical, not inferred from green CI)

- 5,558 structured events, 297 workflows, 36 failures, 30 warnings, zero invariant violations, zero dropped history events. Detailed capture was disabled at export; a bounded capture window is present in the history.
- The persistent uncaught crash record remains the **older** `c1609f3` Reader `ProviderHttpPageException(IOException: Canceled)`; no new uncaught crash was recorded under `a059a89`. This is positive evidence for #198, not exhaustive reproduction of every cancellation.
- Author provenance includes `VERIFIED_FETCHED` and `VERIFIED_CACHED` under #199. `FETCH_TIMEOUT` and `UNVERIFIED_FETCHED` still occur on other titles; they must not authorize an edition.
- Chapter reconciliations are sometimes complete and sometimes provisional: Sword Art Online 34 succeeded, Shin Seiki Evangelion 123 succeeded, Gachiakuta 193 partial, Mirai Nikki 194 partial, Dr. Stone 485 partial. These **partial evidence item counts are not a promise of complete canonical chapter coverage**.
- Akame ga Kill! remains 0/partial with `AUTHOR_CONFLICT` and `MULTIPLE_PRIMARY_IDENTITIES` despite verified canonical author cache. Ao no Hako remains 0/partial with unverified canonical metadata and timeout. `AUTHOR_CONFLICT` does not prove a software bug or that the source is incorrect; do not bypass the identity gate by title or popularity.
- Seven MangaFire chapter-1 sessions emitted `reader_pages_ready: READY` (40/74/14/41/12/17/42 page URLs); three explicit MangaDex/pt-br source selections independently emitted `reader_source_selected: READY`. Akame's Nyaa fallback emitted `SOURCE_UNAVAILABLE`. READY means URLs/session prepared, **not** verified pixels or correct edition.
- Some updates timed out, and two frame stalls were recorded. No new `403` is established by these particular chapter source results.

## Code defects revealed while checking the Host #200 checkpoint contract

1. **Preferred-empty fallback drops cursors.** `CollectProviderReadingEvidence.collectTarget` forwarded only `partialEvidence` from `collectEmptyBindingAlternatives`; its `cursorProgress` was silently omitted. Consequently, valid alternate-source pages were reconciled but the next refresh restarted that source from page one.
2. **Global Provider deadline drops prior ready cursors.** After a source finished with validated additive pages, an additional slow source could exhaust the 20-second outer `withTimeoutOrNull`. The caller retained the additive `partialEvidence` callback buffer but treated all pending `cursorProgress` as absent when the target returned null.

Both bugs were reproduced with regression tests (RED Domain CI for commits `af9c8e5` and `959d23c`; the expected `cursorProgress.single()` failed with `NoSuchElementException`). Host [PR #201](https://github.com/jssantogit/tsuzuki/pull/201) forwards the fallback's cursor, and preserves completed source progress across the outer deadline **only after** validated pages have entered the additive reconciliation buffer. Cursor advancement remains after durable evidence reconciliation and re-verification of provider/version/configuration/attested HID with SQL compare-and-swap. A sanitized `PERSIST/REUSED` record with cumulative completed-page count makes a future physical resume observable without recording raw cursor strings.

This is a Host-only patch: the official signed MangaAll package/code7, one-provider/three-source architecture, preferred editorial binding, chapter authority, and Reader eligibility remain unchanged.

## Acceptance still open

The current device log alone cannot certify cursor persistence across process death/restart, total canonical edition inventories, actual rendered pixels, CDN/challenge behavior for every title, or the semantics of source-author conflicts. Next physical smoke should test a source with a second page that deliberately times out, then refresh again and inspect `PERSIST/REUSED` plus the next page, without changing providers or losing the user's progress. Run CI before requesting another APK.
