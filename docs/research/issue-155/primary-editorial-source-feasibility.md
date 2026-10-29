# Issue #155 — Primary editorial inventory source feasibility

Status: research checkpoint (2026-10-25), **not** a producer-implementation approval.
Authority: [issue #155](https://github.com/jssantogit/tsuzuki/issues/155) and [issue #154's Reports A/C](https://github.com/jssantogit/tsuzuki/issues/154). Architecture A remains the implementation direction.

## Why publisher chapter indexes matter

The native MangaUpdates and MangaDex producers currently provide **distribution/release observations**. Their numbered labels cannot by themselves establish a particular original chapter, edition, language, complete sequence or independent editorial identity. A publisher/licensed-edition chapter index could in principle provide first-party evidence for *each explicitly listed individual unit*, unlike an aggregated release count. That is a scoped opportunity, **not** a guarantee of universal inventory.

## Research: no new network producer authorized yet

### VIZ / Shonen Jump — technically promising, contractually blocked

- The publisher's public Black Clover chapter page individually lists chapters, including real gaps, instead of synthesizing an unbroken 1..N sequence. See <https://www.viz.com/shonenjump/chapters/black-clover>.
- Its published terms prohibit use of robots, retrieval/indexing or scraping applications to obtain content/site structure without **prior written consent**. See <https://www.viz.com/terms>, section on prohibited automated access.
- Its English edition and geographical availability cannot be assumed to represent the original Japanese edition or users outside supported regions. See <https://www.viz.com/company-faq>.

**Decision for #155:** do not add an automated VIZ HTML/API collector without appropriate permission. Do not circumvent access restrictions. An observable public chapter list is not a license to scrape it.

### MANGA Plus by SHUEISHA — primary publisher, incomplete public listings

- Official publisher FAQ says the service normally publishes some opening and latest chapters, may omit middle chapters and is region/licensing dependent. It explicitly acknowledges different chapter display between browser and app. See <https://mangaplus.shueisha.co.jp/faq/eng/>.
- Third-party connectors use undocumented `title_detail` protocol/API routes and generally expose only first/last chapter lists, e.g. <https://github.com/manga-download/hakuneko/blob/master/src/web/mjs/connectors/ShueishaMangaPlus.mjs>. Their code and observed behavior are **not** permission/contract evidence from Shueisha.

**Decision for #155:** do not activate undocumented automated requests or derive completeness from these limited lists. Before choosing any integration, separately establish use permission, durable identifiers, response shape, revision semantics, identity/edition mapping, language/territory policy and bounded request budget.

## Minimum contract before an editorial adapter or promotion

1. **Authorized acquisition.** A documented, permitted first-party endpoint, partner feed or other licensed/explicitly authorized means to collect the metadata. No scraping prohibited by publisher terms; no protected/readable page extraction.
2. **Exact work + edition crosswalk.** Verified work ID must map to a precise publisher work/edition ID; translated, reissued, color, serialization and region-specific editions remain distinct. A user-entered URL/title match alone is not an independently verified crosswalk.
3. **Individual identity.** Each claimed chapter comes from an upstream unit identifier or a stable, publisher-defined chapter/volume structure. Ranges, totals, reuploads and aggregate release records are not eligible; do not fill absent ordinal gaps.
4. **Strict provenance.** Producer ID, publisher endpoint, original upstream ID, work/edition/language, observation timestamp and observed-only coverage preserved in the local additive ledger. Retractions/changes do not delete legacy chapter state or produce v1 tombstones.
5. **Shadow-first.** Synthetic positive and adversarial negative fixtures for editions, chapters 0 and 0.01 vs 0.1, specials/volumes/parts, duplicate revisions, false works, partial pages and offline replay. Keep all new primary claims non-writing until a separate, reviewed reconciliation gate is proved.
6. **No Reader promise.** Publisher metadata must not fetch readable pages, mutate provider configuration, activate reading progress v2, change canonical backup or imply download availability.

## Current disposition

The full issue #155 definition of done remains **open**: native release collection plus verified MangaDex *work-level* linking and local provenance are not an independently verified publisher *edition-level* source. This research rejects a tempting but noncompliant VIZ collector and limits MANGA Plus to future authorization/feasibility work. Prefer an authorized structured publisher feed or a separate explicitly designed verifiable manual editorial-evidence workflow, not an opportunistic HTML scrape.
