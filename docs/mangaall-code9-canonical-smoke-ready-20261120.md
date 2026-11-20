# MangaAll 0.5.4/code9 — canonical/chapter/SQLite smoke connected (READY, not live-executed)

**20 November 2026.** This milestone prepares the full 3-work **manual**
validation; **it does not initiate a three-work real-world experiment**.
After a successful inspected 3-work smoke, the same executable workflow
supports 10–30 titles per explicitly approved invocation.

## What was disconnected

The existing historical
[`mangaall-real-host-decoder.yml`](../.github/workflows/mangaall-real-host-decoder.yml)
replayed original signed **code8 index sequence34**. Host's newer code9 tests
proved real **P-256 source package installation and three-source V2 QuickJS**
but only with **synthetic HTTP**. The actual source-level V2 live run covered
Naruto / Death Note / Bleach but all were partial, and was not connected
to independent catalog, chapter reconciliation, SQLDelight or the Reader.
Treating such observations as canonical bindings is prohibited.

## New manual pipeline (one title at a time, three sources at once)

Workflow:
[`mangaall-code9-canonical-smoke.yml`](../.github/workflows/mangaall-code9-canonical-smoke.yml).

1. **Manual opt-in, no surprises:** requires `workflow_dispatch`,
   `acknowledge=I_ACCEPT_CODE9_CANONICAL_SMOKE`, and `tier=isolated`
   **exactly three input lines**, or `tier=scale` **10–30 lines**.
   Input examples: `manga :: Naruto`, `manga :: Death Note`,
   `manga :: Bleach`. These are planning examples only — **not a run**.
   Strict NFKC/deduplicated case IDs, length and input boundaries, position
   preserved. No scripted or default network work is allowed on PR/commit.
2. **Cryptographic source gate:** Provider source pinned to merge
   `c35b1bcac1ce02062f80e812517239e39adbcfd8`
   (Provider PR #86), official published **P-256 signed index 35**,
   unique unified MangaAll **0.5.4/code9** SHA-256
   `d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622`.
   Real `verify_index.mjs`, reviewed source byte parity, strict literal
   source opt-in `I_ACCEPT_SIGNED_CODE9_CANONICAL_SMOKE`. Any mismatch
   **stops before HTTP**.
3. **Real Provider V1 capture:** `mangaall_live_matrix.mjs` runs the
   **same signed code9 unified MangaAll router** in English. For each
   title, MangaDex, MangaDot and MangaFire run together; the runner
   preserves their original separately attributed source IDs, per-source
   HTTP status, cursor continuation and incomplete challenge states.
   It also observes chapters and page metadata where actually accessible.
   This is **V1 source evidence from code9**, *not* converting V2 neutral
   observations into V1 items or Reader authority.
4. **Independent catalog on Android:** production MangaUpdates integration
   queried in a separate disposable Android emulator, not an AI title guess.
   Sanitized cross-check of real catalog owner, authors/aliases/credits,
   ambiguity/incomplete status, stable exact work case IDs; no guessed
   author or inferred canonical binding.
5. **Host Kotlin and SQLDelight:** captured request/response tapes replay
   through the actual production V1 Host decoder, the production
   `CollectProviderReadingEvidence`, `RefreshChapterEvidence`,
   `ReconcileChapterEvidence` and SQLite/SQLDelight repositories.
   Missing responses, unfinished cursor, duplicate identities or a source
   403 remain **inconclusive**, never `NO_CHAPTERS`/a completed empty
   snapshot or a false binding.
6. **Cross-stage reconciliation by case ID:** the new
   `mangaall_code9_canonical_smoke_report.py` requires exactly identical
   work sets across signed source, source matrix, independent catalog,
   Kotlin collector and SQLite tests. Its bounded JSON report keeps input
   order, per-case ID, reason and confirmed-chapter counts. It rejects
   missing, swapped or duplicate evidence. A third source returning 403
   prevents a success label even if other sources produced chapters.
7. **Reader regression:** after the collector, an isolated Android
   `ReaderActivity` regression checks canonical chapter selection/source
   switching/navigation **against synthetic local pages**. It does NOT
   render real source CDN pixels or equate synthetic Reader acceptance
   with the 3-work HTTP chapter results. This separation prevents a false
   green status for an unverified source/editorial chapter.

Each live invocation uses **one ordered shard**, rather than separately
timed per-source jobs. No three-source conflict is hidden by running each
adapter on a different Android session. Scaling to 10–30 is gated by a
separate `scale` tier and requires the first isolated run to be inspected
before the operator chooses to execute a larger one.

## Offline validation and readiness

On PR/push, only
[`mangaall-code9-canonical-smoke-contract.yml`](../.github/workflows/mangaall-code9-canonical-smoke-contract.yml)
executes: pure parser/reporter/security tests, genuine P-256 signature
verification on the **already signed** artifact, 3-work **--plan** and
Host Kotlin source/canonical/SQLite **offline** regression tests.
It runs **no upstream HTTP, independent catalog remote requests, Android
Reader pixels, signing or publication**.

After the operator explicitly triggers the isolated real smoke, inspect
`mangaall-code9-canonical-smoke-per-work-summary` and source/catalog/SQLite
evidence. `CHAPTER_EVIDENCE_OBSERVED_NEEDS_READER` is still **not a Reader
pass**. A true release-quality Reader acceptance additionally requires a
separate source-level chapter/edition approved by independent editorial
corroboration, authenticated installed Host code9 source binding, live
source-specific page delivery and real CDN pixels in a continuous Android
Reader session. Cloudflare 403 **must never** be bypassed or reported as
no matching work, empty chapters or successful pages.

Progress tracked in [Host #207](https://github.com/jssantogit/tsuzuki/issues/207)
and [Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72).

## First live-run regression — 2026-11-20

[Host #44759250361](https://github.com/jssantogit/tsuzuki/actions/runs/44759250361) was genuinely started from owner [issue #230](https://github.com/jssantogit/tsuzuki/issues/230). P-256 index seq35 and original immutable code9 artifact passed. The source journey incorrectly sent `diagnosticsVersion:2` (a **V2-only diagnostic contract**) and then required V1 `items`; Naruto, Death Note and Bleach consequently failed as `INVALID_CONTRACT` on the runner. This was an instrumentation **protocol mistake, not evidence of missing manga**. [Provider #87](https://github.com/jssantogit/providers-tsuzuki/pull/87) merged the fix in **harness only**: explicit V1 request in the code9 canonical smoke, preserving the genuine published code9 signed archive unchanged. This Host follow-up pins the reviewed runner commit `c35b1bcac1ce02062f80e812517239e39adbcfd8` and adds offline V1/V2 regression coverage. Do not re-use the failed response as a canonical pass.

The same first run's independent Android MangaUpdates catalog returned `CATALOG_UNAVAILABLE` for all three titles. The Android runner marked that stage failing; **no independently corroborated canonical binding** was created. These statuses are not proof of absent titles or chapters, and must be diagnosed without acquiring user credentials, fake editorial evidence, or bypassing upstream restrictions.

## Second live run — source and catalog PASS; canonical replay-gap defect

[Host real 3-work run #44763491195](https://github.com/jssantogit/tsuzuki/actions/runs/44763491195) used P-256 seq35/code9 and found Naruto 5, Death Note 3 and Bleach 7 distinct source-scoped candidate HIDs across MangaDex/MangaDot; MangaFire returned HTTP403. All lookups remain incomplete/ambiguous. Host production V1 decoder 3/3 PASS with 0 gaps, and the independent Android MangaUpdates catalog resolved all three identities with real author/artist evidence.

The actual **canonical collector and SQLite** then reported `REPLAY_GAP=1` per work and `confirmed=0`. A green Gradle exit does not override this semantic failure. Root causes: the source capture requested `editorialEvidenceVersion:1` which **the real Host V1 DTO cannot request**, and test-only registry unnecessarily advertised `pt-br` although only `en` source calls were captured. This change pins Provider [PR #88](https://github.com/jssantogit/providers-tsuzuki/pull/88) to the exact Host-compatible V1 request and limits the **test-only replay registry** to captured English; original signed code9 archive and production en/pt-br support remain unchanged. These fixes require a new real smoke run before any claims of canonical success. The real CDN reader pixels remain UNTESTED.
