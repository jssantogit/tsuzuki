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
   `749101cb9bfe92e2ecdcc61a9248546f0b1f4626`
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
