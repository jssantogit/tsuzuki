# Issue #155 — ICE integrated acceptance checkpoint

**Branch:** `feat/issue-155-independent-chapter-inventory` / [PR #156](https://github.com/jssantogit/tsuzuki/pull/156) (Draft).

**Decision:** This is an implementation and CI checkpoint, **not** production authorization or an acceptance certificate. Never close issue #155 without all gates listed in its DoD. No catalog seed, no enabled source by default, no automatic candidate promotion, no progress-v2 cutover, no merge, no physical-device sign-off.

## What is implemented and exercised

- Optional ICE producer registry and manual per-title refresh, zero reading providers required for synthetic canonical projection.
- Editorial provenance/edition ownership locally in additive migration 48, committed transactionally with canonical chapters and checked against collision, replay, precision and revocation.
- Local user consent for exact title/producer/work/edition/language in additive migration 49, scoped to the current enabled integration-settings revision; disabled or changed integration invalidates the consent.
- Host ownership no longer supplied by caller to `EditorialUnitCommitRepository.commit`. The only production binding is `DisabledEditorialEditionHostAuthority`, explicitly returning no grant until a genuinely independent and legally authorized edition verifier is available.
- Portable canonical backup preserves editorial chapter lineage but does **not** copy host authority or consent; sync v1/v2 reading-progress keys cannot pretend that an edition-blind number is portable.
- `SupabaseSyncOrchestrator` cannot synthesize v1/v2 reading-progress deletion tombstones from omitted records. If an editor-owned reading-progress record is unportable, v1 progress sync fails closed *before* transport/pending-mutation replay; other document kinds are unaffected. The merger refuses incomplete accepted frontiers rather than fabricating a deletion. This is an intentional temporary interoperability limitation until a negotiated edition-aware progress protocol exists.
- Title convergence conservatively refuses editorial ownership, consent, and personal user-adoption receipts rather than discarding them.
- **USER_ADOPTED approved by the user (2026-10-26)** as a separate *non-editorial* route. Strict per-edition/language manual candidate selection and explicit final confirmation re-read the ledger under the chapter mutation gate and SQLite transaction. Additive migration 50 retains one provenance receipt per adopted canonical chapter with source origin/revision/snapshot digest, bound to a full-review fingerprint; source changes, existing canonical identity collisions and mixed origins fail closed. No Publisher/Host grant is minted and no reader pages are synthesized.
- Canonical portable-core backup exports and transactionally restores USER_ADOPTED provenance without copying permissions. V1 progress for locally adopted chapters remains nonportable and cannot silently create remote tombstones. Recipients can identify adopted chapters offline, but a confirmed chapter is **not** a readable ContentBinding.

## Proof and verification

- Earlier checkpoint: [#40258315472](https://github.com/jssantogit/tsuzuki/actions/runs/40258315472) App/Format/Gate green (backup consent must not be portable).
- New Domain orchestration tests cover missed v1 progress records and stopping before network when local progress is nonportable; a dedicated Data regression injects synthetic authority to create chapters with no reading source.
- Full baseline before adoption: [#40260883453](https://github.com/jssantogit/tsuzuki/actions/runs/40260883453) **SUCCESS** at `5fe7d49d`.
- RED Domain regression for mixed source origins: [#40298827286](https://github.com/jssantogit/tsuzuki/actions/runs/40298827286), then Domain and Format **SUCCESS** at `7f59f74d` within [#40299423223](https://github.com/jssantogit/tsuzuki/actions/runs/40299423223) (subsequent push cancelled the remaining job).
- RED App regression for mixed-origin portable receipts: [#40299733133](https://github.com/jssantogit/tsuzuki/actions/runs/40299733133), fixed at `9bcbcc21`, with [affected App/Format/CI Gate #40300562890](https://github.com/jssantogit/tsuzuki/actions/runs/40300562890) **SUCCESS** on that exact SHA. Tests also refuse duplicate edition-scoped identities in portable receipts.
- The current documentation checkpoint triggers **Full CI v2.1** with `[ci-full]`; its exact final SHA and actual all-gate result must be recorded separately before calling it green.

## External and manual blockers — not solved by code

1. No verified production publisher feed/license + exact edition identity + independent Host verification. MangaUpdates/MangaDex remain **release evidence**, Wikimedia/EPUB remain **candidate-only**, not `PRIMARY_EDITORIAL`. Therefore no legally validated live commercial chapter projection is enabled.
2. `USER_ADOPTED` was expressly approved and a **synthetic local adoption path** is implemented, but real user selection/import of an EPUB or Wikimedia edition is not yet demonstrated end-to-end. The current Detail selection consumes only eight preview candidates (`GetIndependentInventorySnapshot.EXAMPLE_LIMIT=8`), not a full edition-scoped browse/paging API. UI strings of the adoption flow are also Portuguese-only pending base/en localization. Neither limitation justifies implicit or automatic promotion.
3. Physical Android smoke for catalog-empty defaults, offline inventory, Details, Reader no-pages behavior, update/restore and account/sync lifecycle has **not** been performed; approval of an identified APK is required.
4. Title merge of already materialized editorial editions remains blocked pending audited provenance rehoming. Do not silently rekey chapter IDs or resurrect revoked grants.
5. Existing v1 devices do not negotiate edition-aware progress IDs; local-only progress remains nonportable and the v2 protocol is disabled.

**Conclusion:** Core ICE engineering and explicit local user-adoption policy are implemented, but complete product DoD and release sign-off remain **BLOCKED** by independently verifiable production-source authority and/or proven real-file acquisition, complete candidate selection/localization, final integrated CI, and physical-device smoke. Do not mark the PR merge-ready or issue done on synthetic tests alone.
