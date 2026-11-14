# MangaAll / ICE — provisional source cursor continuation

Status: **Host PR #200 (2026-11-14)**; no provider release or physical APK.

## Problem and scope

A corroborated MangaAll work can expose many source-specific chapters without proving an editorial edition. `CollectProviderReadingEvidence.collectUnresolvedWorkSources` runs each provisional HID under a 5.8-second budget. Before this change, each refresh restarted `RefreshProviderReadingChapters` at null cursor; earlier source chapters could be reconciled additively, but later pages were never reached. A complete preferred binding's replacement/snapshot policy remains unchanged.

## Durable checkpoint identity

Local SQLDelight table `tsuzuki_provider_provisional_cursors` uses **canonical title + Provider ID + facet/language + exact source HID + installed Provider version code + active configuration fingerprint**, with pending next cursor, completed-page count and update timestamp. On new configuration/version/facet/HID, old checkpoints are invisible. Titles are deleted by FK cascade. Source HIDs are not preferred bindings.

At every refresh the Host must independently corroborate the work again, including verified **canonical** author identity and source-attested authors/detail + chapter coverage. A different author, qualified alternate edition, absent metadata, disabled Provider or changed configuration cannot advance a checkpoint. A work association from another canonical title is rejected by the existing source-ownership store. Neither chapter numbers nor matching titles alone grant trust.

## Acceptance and commit order

1. Query a corroborated HID's exact checkpoint, if any. A fresh scan starts at `cursor=null`; a continuation starts at the recorded `nextCursor`.
2. Validate each page under existing chapter-ID and cursor checks and a hard per-round page limit. Source pages stay *provisional*; the preferred binding alone may publish a complete snapshot.
3. On a temporary failure/timeout, retain individually validated pages. A structural/policy failure discards the current source batch and never advances its cursor.
4. Return a **pending** cursor advance alongside provisional evidence. The Host first completes `reconcileStage(partialEvidence)`. **Only after that succeeds** may it save cursor progress. A process death between reconciliation and checkpoint causes an idempotent page replay, not a skipped chapter. An unresolved or incomplete inventory cannot detach prior support.
5. Use compare-and-swap against the previous saved cursor so delayed concurrent refreshes cannot overwrite newer progress. A source reaching the final page clears its checkpoint; it still does **not** establish editorial completeness.

A 256-page cumulative defensive cap prevents unconstrained loops. Full completeness and every-reader-pixel validation must not be inferred from reaching an end cursor on a provisional source.

## Verification

TDD additions in `RefreshProviderReadingChaptersTest`, `CollectProviderReadingEvidenceTest`, and SQLDelight persistence tests cover starting cursor, 32-page continuation, interrupted second-page transport, preservation of prior provisional evidence, source identity, configuration/version isolation, compare-and-swap, and repository reinitialization. CI v2.1 on PR #200 is the code gate.

Pending A54 physical acceptance still covers real MangaFire challenge/CDN/WebView behavior, Reader pixels, process restart, crash regression (#198), and canonical-author cold cache (#199). The signed MangaAll `app.tsuzuki.mangaall` 0.5.2/code7 at publisher sequence 33 is unchanged.
