# Grouped remaining-six review — 2026-11-25

Prior exact SHA 240f7d47: eight PR workflows green, one skipped. Historical 24/30 restores/first-page decodes across different SHAs, not a final cohort pass.

- SSS-Class Revival Hunter and Raeliana: alternate-name retrieval correction is already implemented. The new remaining6 gate reuses strict collection, SQLite restore and first-page decoding criteria.
- Lookism and Teenage Mercenary: source ComicSeries JSON-LD was authorless in the previous observation. Expanded extraction supports explicit typed roles and exact-HID semantic HTML; duplicate markup must fail closed. Whether these works expose the new structures is unproven. Never substitute an absent source credit with a catalog credit.
- The Remarried Empress: Yen Press credits Alphatart; the official adaptation trailer credits Alpha Tart for the original work.
- Cheese in the Trap: official WEBTOON credits Soonkki; Vancouver Public Library catalogs the Korean comic as 순끼 / Sunkki. Retail bibliographic listing of Wisdom House printed season 1 (ISBN 9791162205310) credits Soon Ki; this is secondary evidence, not a publisher API.
- Reviewed public citations are independent research metadata inputs in the plan, only accepted after exact fresh catalog owner ID, native title, original credited name and role verification. Source must independently supply the matching author. No fuzzy matching, title-specific conditional, editorial confirmation or production reference feed. The default app receives no reviewed references. Generalizing metadata acquisition for production requires separate review.
- Keep locked corpus, unsigned research package and separate remaining6 artifact scope. No full 30 live or shipping until consolidated offline verification and a focused real test.
## Independent catalog evidence (nonshipping)

Wire audit #45613879462 confirmed exact MangaUpdates series references for frozen indices 15 and 21 after independently completed catalog search and detail verification. The explicit experimental owner plan now uses MangaUpdates for these two unresolved works, not an automatic fallback or a source-derived ID. The cohort stays locked. The code11 research lookup carries bounded source reference IDs only with the new opt-in. The collector compares them with the one verified, consented MangaUpdates identity already in the canonical repository and rechecks that exact identity before persisting `CATALOG_CORROBORATED` or `CATALOG_REFERENCE_CONFLICT`. The new proof is metadata-only: no source authors are invented, no provisional chapter or Reader permission is granted, no signed package changes. Conflicting links cannot enter the native preview group; both new states are blocked by explicit source selection. This does not disambiguate Remarried Empress or certify any new work.

## Native provisional metadata after catalog corroboration

The nonshipping code11 collector now allows a creditless, exact catalog-linked record to contribute source-scoped provisional chapter metadata only after a valid native page. It rechecks the same independent identity and current consent before and after the native request, before publishing provisional metadata and before committing cursors. Cursor fingerprints include a hash of the independently verified catalog identity and active configuration. Existing author-based cursor keys remain unchanged. No schema change, no authoritative snapshot, no automatic edition selection, no Reader eligibility for the new catalog-only classification. Regressions use synthetic chapter metadata and exercise consent revocation, precise reference matching and version gating.

## Independent identity results for the remaining catalog cases

Metadata-only CI #45619010687 verified exact MangaUpdates identities for indices 15, 21, 27 (native query), 28 and 29. Index 27's first examined MangaDot record linked to the verified catalog work; the second linked to a different series. This is negative evidence for that competing record, not authority to select a chapter release or a preferred edition. The explicit research plan uses MangaUpdates and the native title for index 27, preserving the same fixed corpus and all other owner assignments. Plan changes now trigger the offline audit; no live Reader workflow is dispatched.

## Stale binding negative-evidence regression

The offline Reader resolver excludes catalog-only and catalog-conflicting work associations even when a stale automatic or user-confirmed edition binding exists. This is a fail-closed metadata decision; no evidence, chapter progress or preference is deleted. The no-match audit uses the same exclusion. Synthetic regressions cover both classifications and persistence of the prior preference; the offline gate explicitly runs both Reader resolver and source-selection tests. No change grants reading authority to catalog links.

## Persistence after incomplete metadata refresh

A durable catalog-reference conflict must not disappear merely because a later lookup lacks catalog references or supplies only creator evidence. The local work-association UPSERT retains that conflict against weaker observations, including newer ones. A fresh catalog-corroborated assessment may replace the conflict but remains non-authoritative for reading; creator-only assessments cannot resolve a bibliographic disagreement. No schema migration, canonical identity transfer, binding deletion, or Reader permission is introduced. The in-memory test repository mirrors the SQLite policy, with a regression for the newer weaker observations and later catalog reassessment.
