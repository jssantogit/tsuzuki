# #176 — Multi-HID work association and chapter-source portfolios (design draft)

> **Status: investigation / proposed implementation, not approved or shipped.** This is a continuation of #155 Architecture A and #176. The current Host PR #182 and MangaFire PR #55 remain Draft. The existing single-binding reader behavior and all existing library data must be preserved until each migration/Reader gate is proven.

## Physical evidence that constrains the design

Galaxy A54, Host `0.20.4 5d1865e`, MangaFire signed `0.1.7/code8`, 2026-11-01: four works without a usable automatic chapter inventory, due to `AMBIGUOUS_EDITION` or `MULTIPLE_PRIMARY_IDENTITIES`. Source search HTTP 200 for the initial queries, then secondary title queries can consume the 20s budget. Vagabond `4mx` returns 101 observed chapters in page 1 while page 2 times out, and the user reports that work improved. Never equate that partial page to a complete snapshot.

A single work may be represented by multiple MangaFire HIDs with different English chapter ranges: the public pages for Chainsaw `kx1p6` and `kkyzv` show through chapter 97, while `0w5k` shows through 232. **This is site-page evidence, not verified on-device API data**. Choosing a single rank/count-based HID risks missing real chapters; equating every HID risks mixing editions, specials and incorrect Reader pages.

## Current implementation boundaries (verified in the Host PR branch)

* `ProviderReadingBindingRepository.get(canonicalTitleId, providerId, facetId)` and SQLDelight `insertTsuzukiProviderBindingIfAbsent` reserve **one** exact HID per provider/facet. Do not weaken this atomic preferred-edition slot or repurpose `verification=EXACT` as verified editorial equivalence.
* `ProviderBindingRef.evidenceProducerId()` and `ProviderChapterEvidenceAdapter` already scope individual provider chapters by **provider + facet + externalWorkId + providerChapterId**. Preserve this opaque producer identity in every path.
* `RefreshProviderReadingChapters` publishes a full snapshot only after final pagination; incomplete pages are additive, not authoritative. `ReconcileChapterEvidence.executeProviderSnapshot` detaches stale support only for the exact producer and a newer verified complete snapshot.
* `ResolveProviderChapterReading.options()` currently admits only chapter evidence whose producer ID matches a persisted **available** binding, then re-parses chapter identity before delivering pages. Additional source HIDs cannot become Reader options simply because a chapter number matches.
* Host #182 and Provider #55 implement source-attested authors and opt-in bounded first-page coverage. Coverage is **observation**, not total count or automatic edition identity. For current single binding, the only multi-HID positive selection is one author-corroborated readable HID when all rivals are author-corroborated and explicitly empty in English.

## Proposed minimal architecture: keep preferred edition, add independently verified work-source associations

1. **CanonicalWork**: existing canonical title, identity from active integrations. Never use a source HID as canonical identity.
2. **ProviderWorkSourceCandidate (read-only)**: exact HID, source-record title/aliases, author/year/format **if present and HID-verified**, facet/language, provenance and bounded coverage profile. Unknown, failure, challenge, unprobed, and missing fields remain unknown, never exclusion.
3. **ProviderWorkSourceAssociation**: a separate *per-HID* association to a canonical work, storing which independent facts corroborated the **work**, and a separate editorial-variant status (`UNRESOLVED`, `BASE`, `SPECIAL`, etc.). Do not treat same work as same edition. A source with only title similarity or matching chapter count cannot be automatically associated. Manual confirmation remains exceptional and may corroborate one work source, not all rivals.
4. **SourceChapterObservation**: use existing per-HID `ProviderBindingRef` producer identity, with stable provider chapter ID, exact number/label, volume, language, variant and observed page. Evidence from verified work associations can contribute incrementally to a canonical chapter *without asserting source edition equality*. Unverified/qualified sources remain shadow observations, not promoted canonical chapters.
5. **ReaderSourceOption**: explicit binding to the same provider/facet/HID/providerChapterId that produced the mapped evidence; require current association, provider enablement, chapter identity match and variant/volume/language compatibility at resolve **and** delivery. Do not derive an external chapter ID from canonical number.
6. **PreferredBinding**: preserve existing single-slot table for explicit user-selected/default reading source and backward compatibility. Source associations do **not** overwrite it; multiple associated HIDs may offer chapter evidence or Reader options independently.

## Implementation gates (incremental, TDD)

**Gate A — evidence and eligibility, no writes:** Add a domain work-association decision object with closed rejection/review reasons: `CANONICAL_ID_UNVERIFIED`, `AUTHOR_MISSING`, `AUTHOR_CONFLICT`, `EDITORIAL_VARIANT`, `COVERAGE_UNKNOWN`, `SOURCE_CHALLENGED`, `SOURCE_INVALID`, `WORK_CORROBORATED_EDITION_UNRESOLVED`. Use catalog identifiers where the source actually provides them; otherwise require appropriately strong independent work corroboration. Test Chainsaw 3-HID overlapping/extended range, Fullmetal translated alias vs work without chapters, Naruto 2 homonyms, Bleach 3, and Vagabond color exclusion. Don't invent source facts.

**Gate B — source association storage:** Add versioned local SQLDelight schema and data repository for per-HID associations without modifying preferred binding uniqueness. Tests: atomic parallel insert, staleness/disabled provider, legacy data migration, source revision and explicit user preference. Evaluate backup/sync compatibility separately; do not sync unverified identities.

**Gate C — bounded full pagination:** For each **work-verified** HID independently, collect chapter pages with provider/facet guard, per-HID deadline, shared fair network budget, bounded page count; persist complete snapshots only when complete and partial pages additively. Distinct HID producer IDs prevent one source from detaching another. Avoid redundant alias lookup once a work candidate portfolio exists, but do not skip later candidates that might reveal a distinct edition.

**Gate D — canonical projection and Reader:** Reconcile only trustworthy numbered chapter identities through existing Architecture A, leaving specials, decimals, volumes and variants distinct unless verified equivalent. Reader must resolve exact source identity for each evidence. Test negative cross-HID IDs (`same chapter key under different work`), chapter 84 misread as chapter 1, colored-vs-normal, conflicting volume/number, source removal, page failure. Never regress progress/downloads/backup or Supabase tombstones.

**Gate E — verification/release:** Provider code9 remains Draft/unpublished until producer conformance is green and source API is exercised from the actual device (GitHub runners currently receive challenge 403). Host #182 remains Draft, no merge or new APK from this document. Run Domain/Data/Reader/Android/Format/full CI then signed **updatable** `app.tsuzuki` Release smoke preserving data, provider repo/grants, and progress. Accept only if Fullmetal, Chainsaw, Naruto, Bleach demonstrate real canonical chapters and correct Reader pages without manual intervention.

## Required diagnostics for the next smoke

For each candidate and associated source, only when explicitly opted into detailed capture: `provider_id`, `facet_id`, exact `external_work_id`, `query_index`, profile outcome, `page_index`, observed number range, `has_more`, rejection/association reason, `producer_id` digest, elapsed, HTTP/Browser phase and closed failure type. Normal Logs remain pseudonymized. Never include cookies, tokens, headers, HTTP bodies, signed URLs or arbitrary exception text.

**No shortcut:** The proposed work-source portfolio is *not* automatic merging of editions. If independent identity is insufficient, return a typed unresolved result and preserve existing chapters. Never manufacture chapter numbers to satisfy a count target.
