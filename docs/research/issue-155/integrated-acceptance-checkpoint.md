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
- Title convergence conservatively refuses editorial ownership or consent rows rather than discarding them.

## Proof and verification

- Earlier checkpoint: [#40258315472](https://github.com/jssantogit/tsuzuki/actions/runs/40258315472) App/Format/Gate green (backup consent must not be portable).
- New Domain orchestration tests cover missed v1 progress records and stopping before network when local progress is nonportable; a dedicated Data regression injects synthetic authority to create chapters with no reading source.
- Last affected CI after Host-binding hardening had Domain and Data green; formatting required a one-line break. This checkpoint requests **full CI v2.1** with the `[ci-full]` commit token. **Record the final SHA and actual full CI result before claiming validation.**

## External and manual blockers — not solved by code

1. No verified production publisher feed/license + exact edition identity + independent Host verification. MangaUpdates/MangaDex remain **release evidence**, Wikimedia/EPUB remain **candidate-only**, not `PRIMARY_EDITORIAL`. Therefore no legally validated live commercial chapter projection is enabled.
2. No accepted, independently specified `USER_ADOPTED` materialization policy. Do not implement auto-promotion as a substitute for a licensed publisher.
3. Physical Android smoke for catalog-empty defaults, offline inventory, Details, Reader no-pages behavior, update/restore and account/sync lifecycle has **not** been performed; approval of an identified APK is required.
4. Title merge of already materialized editorial editions remains blocked pending audited provenance rehoming. Do not silently rekey chapter IDs or resurrect revoked grants.
5. Existing v1 devices do not negotiate edition-aware progress IDs; local-only progress remains nonportable and the v2 protocol is disabled.

**Conclusion:** Core ICE engineering can be validated in CI, but complete product DoD and release sign-off must remain **BLOCKED** by verifiable external-source authority, user policy decision, and physical smoke. Do not mark the PR merge-ready or issue done on synthetic tests alone.
