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

## Native implementation checkpoint — 2026-10-24

**Delivered in PR #156 on `feat/issue-155-independent-chapter-inventory`** (not release-approved):

- Native Android `MangaUpdatesInventoryProducer` reads public `POST /v1/releases/search`; `MangaDexInventoryProducer` reads public `GET /manga/{uuid}/feed`. Both preserve releases as `RELEASE_RECORD` shadow observations. The MU `metadata.series.series_id` and each MD manga relationship must match the requested verified external work; incomplete or mismatched records reject the page.
- The read-only native transport only permits the exact TLS hosts and metadata paths. Requests are manually confirmed per title/source; three pages at most per action, serialized/spaced 1.2 seconds apart, with a short in-memory cache and a response byte ceiling. 403/429 and other non-success statuses are terminal for that attempt, not automatic retries.
- The detail view exposes explicit manual buttons and consent. MangaUpdates additionally requires its integration to be enabled; both sources require a canonical title to have an already-verified exact external work ID. Disabling/revoking during a call drops late results. No automatic source query runs when merely opening a title or refreshing usual metadata.
- The local inventory ledger retains batch outcomes and claims across transient failures and app restarts. Subsequent *manual* requests resume from the next persisted partial cursor of the exact scope, and failed pages may be retried, subject to the same bounded budget and loop guard. Exhausted chains restart on a later explicit refresh, without implying editorial completeness.
- Native acquisition and provenance UI remain separate from canonical chapter creation, Reader, downloads, v1 sync, progress and backup. No `READING_PROGRESS_V2` or canonical backup switch was enabled.

**Verified checkpoints:** [CI #40152447946](https://github.com/jssantogit/tsuzuki/actions/runs/40152447946) GREEN at `c7958733` for App/Format; [CI #40152943002](https://github.com/jssantogit/tsuzuki/actions/runs/40152943002) GREEN at `6b7af07f` for Domain/App/Format/CI Gate. The comprehensive cross-module checkpoint must be checked on its exact subsequent HEAD.

**Still open under issue #155:** optional source setup/identity linking for works without verified external IDs (especially MangaDex); independent **primary editorial** sources with verified edition/language to promote *actual* chapters safely; HTTP fixture coverage of 403/429/backoff and source configuration changes; regression matrix for Reader, existing count IDs, v1 sync/tombstones and backup; manual physical-device UX acceptance. MangaUpdates and MangaDex release feeds are **not** primary editorial evidence and do not solve the full chapter coverage requirement by themselves. No mass third-party queries, merge, or APK have been performed.

## Verified MangaDex work association and offline shadow acceptance — 2026-10-25

**Implementation (one branch, PR #156 Draft):** a user may enter a MangaDex work UUID in the Title Detail to preview a *work-level* external identity crosswalk against already verified MangaUpdates, MyAnimeList or Kitsu identifiers. Remote manga ID and all present trusted crosswalks must be exact and conflict-free. The user explicitly confirms the candidate; confirmation repeats the remote metadata request **without using the in-memory cache**, revalidates identity and title ownership, and only then records the verified MangaDex **work** link. Cancel, failed request, revoked crosswalk or a different title cannot persist a stale candidate. These checks do **not** verify edition, language, editorial chapter numbering or read availability.

**Separation of responsibilities:** the newly verified MangaDex work ID can enable a later explicit bounded release-metadata refresh without any installed reading provider. The resulting claim is still `RELEASE_RECORD`, even for an isolated numeric release; ranged `10-12` and reupload labels remain non-promotable. The independent ledger has no access to the chapter writer, Reader, v1 sync or backup. The dialog and messages were added in English and PT-BR; opening Title Detail itself does not launch an HTTP call.

**Deterministic tests:** real-source network calls are mocked. Cover correct/mismatching MAL crosswalk JSON, competing source IDs, ownership conflicts, remote crosswalk revocation during the two-step user flow, uncached confirm, title switching/cancellation, failed persistence feedback, and the integrated work-link -> ledger shadow refresh without reading providers. HTTP 403/429, cache timestamp, redirect and size tests from the previous native checkpoint remain in place.

**Validation checkpoint:** [CI #40185661318](https://github.com/jssantogit/tsuzuki/actions/runs/40185661318) was successful at `9e7554db` for App–Tsuzuki, Format and CI Gate. The subsequent integrated acceptance test also passed App, Domain, Data, Core Common, SQLDelight and Supabase checks in the first full run, but that run hit an automated formatter issue in the test's original multiline name; the test naming has been normalized and the new `[ci-full]` run is required before claiming exact-HEAD completion. Physical device smoke is **not** performed.

**Still blocked by evidence, not solved by work linking:** no adopted, independently validated primary editorial producer with an edition-specific inventory. Thus no automatic promotion to a real CanonicalChapter, universal chapter coverage or editorial completeness. Continue conservative source/edition validation and the outstanding legacy count-ID / Reader / progress / sync-v1 tombstone / backup regression matrix. Keep the issue open and PR Draft; no merge, APK, progress-v2 activation, backup rollout, deletion or synthetic chapters.
