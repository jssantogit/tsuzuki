# Issue #155 — Independent Chapter Inventory (Architecture A)

Status: implementation contract; no release approval. Primary authority: [issue #155](https://github.com/jssantogit/tsuzuki/issues/155), [Report A](https://github.com/jssantogit/tsuzuki/blob/docs/issue-154-architecture-research/docs/research/issue-154/architecture-research.md) and [Report C](https://github.com/jssantogit/tsuzuki/blob/docs/issue-154-architecture-research/docs/research/issue-154/empirical-validation.md).

## Ground truth and scope

A reported count is a scalar estimate, not a set of chapter identities. A MangaUpdates or MangaDex release is evidence of a release, not automatically an editorial chapter. A source HTTP 200, cursor exhaustion, or success with zero items is not evidence of editorial completeness. Kitsu chapter resources may be generated from a count; Bangumi sampled book episodes did not enumerate editorial book chapters. No provider or integration is mandatory for already-known chapters to remain visible and usable.

The 149 controlled Report C queries are *endpoint observations*, not verified ground truth of chapter completeness. A synthetic, labelled corpus is the acceptance fixture; live probes are not required for CI.

## Boundaries

1. Discovery: independent inventory producers return typed batches of **claims**. They require a verified work/edition crosswalk and must never require `reading.pages`. Unknown edition/language is not a wildcard.
2. Interpretation: classify each claim as individually admissible, aggregate/range, declared count, derived/placeholder, ambiguous, correction/retraction, or operational availability. Numbering/parts/volumes/edition/decimal specificity and original upstream derivation matter more than a numeric confidence or `EDITORIAL` tag.
3. Projection: persist claims, provenance, source scope, fetch result and observation time locally. A technical snapshot can be complete for its request while editorial inventory remains partial or unknown. A failed, empty or disabled refresh never deletes historical claims. Identical replays are idempotent and an older response cannot replace newer evidence.
4. Materialization: only individually admissible, identity-safe observations may be reconciled as canonical. Initial producer rollout is shadow-only, explicitly separate from the active `ChapterEvidence` reconciler. Promote incrementally only after exact identity gates and CI fixtures pass.
5. Reading: Reader, resolver, history, downloads, metadata and existing source/provider paths continue unchanged. Inventories do not promise a readable source.

## Legacy protection / rollback

- Keep **all existing** `tsuzuki:count:*` chapter rows and their IDs, history, progress, variants, downloads, overrides, update state, and remote v1 sync keys. An old count ID may be supported by real subsequent evidence.
- Do not create any *new* count-derived chapter slots from reported totals. A view should show already materialized legacy rows and observed chapters, with a separate `ReportedChapterCount` summary; never generate missing ordinals.
- Never delete or rekey chapter rows to accomplish this transition. Never silently filter v1 export: `materializeLocalTombstones` can turn missing exports into remote deletions.
- No new sync namespace/transport, no `READING_PROGRESS_V2` activation, no backup writer/restore activation. New additive local tables are retained on feature rollback.
- No Nyaa/P2P/provider matching or #153 UI changes.

## Contract for bounded optional producers

A batch includes canonical work ID, producer, verified external work ID, edition/language scope, claim class, source release key and label, observed-at, request page/cursor, request outcome, freshness and upstream derivation where known. Do not expand ranges or convert release IDs into canonical chapter IDs. Stable observation keys include producer *and* work/edition scope. Duplicate pages/reuploads, different editions and late responses must be safe. HTTP 429 obeys retry-after/backoff; no uncontrolled sweeps. Attribution and API terms must be reviewed before enabling real network collection.

## Verification fixtures and gates

| Fixture | Ground truth / gate |
| --- | --- |
| 120 reported, 80 individually observed | Exactly 80 known individual units; count remains 120 as metadata; editorial completeness unknown |
| Count only; Kitsu generated 5000 | Zero new canonical chapters |
| Already materialized `tsuzuki:count:*` | Existing IDs remain addressable and retain references |
| Release 246-251, 1v2, reupload | Range cannot expand; duplicates cannot increase canonical count |
| 0, 0.01 vs 0.1, 12.05 vs 12.5, volumes/parts | No identity collapse |
| Missing page 2, HTTP 429, empty refresh, edition switch | Partial/failed technical status, no deletion, no cross-edition promotion |
| Independent producer without reading provider | Known, individually admissible chapters can be visible independent of reading availability |
| v1 sync / backup | No export filters, tombstones, key changes or destructive migration |

**Execution order**: RED fixture and count-outline gate -> typed claim acquisition/classifier -> additive persistent shadow ledger -> independent optional adapters -> safe promotion and read model -> acceptance tests, CI and manual smoke gate. CI v2.1 affected on every code checkpoint, `[ci-full]` only after integrated cross-module code. Device acceptance required before release-readiness; do not generate APK for pure domain/SQL work.
