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

## Android restore checkpoint — 2026-10-27

- [Android Acceptance #40478626060](https://github.com/jssantogit/tsuzuki/actions/runs/40478626060) **SUCCESS: 7/7 real instrumented cases** on Android 35 disposable emulator at `dc4fb686`. Production `BackupCreateJob` writes canonical-only `.tachibk` with adopted receipts. The production `BackupRestoreJob`/ `BackupRestorer` accepts a standalone gzip/Protobuf archive on a **clean canonical SQLite graph**, preserving chapters, progress, history and an idempotence receipt. The same worker **refuses an occupied canonical graph** without changing the personal title or materializing a foreign chapter/receipt. SAF selection, Reader lacking source pages, and rejected malformed input are also green. No external network or personal device.
- A RED run [#40477200712](https://github.com/jssantogit/tsuzuki/actions/runs/40477200712) found that instrumentation-created MediaStore Downloads URIs may be unreadable from the restore worker on Android 35. The test now supplies an app-owned, ContentResolver-readable `content://` archive through the existing FileProvider (`dc4fb686`); this changes test setup only, not production restore permissions or transaction logic. The later run passed both new worker cases.
- [CI v2.1 #40477200692](https://github.com/jssantogit/tsuzuki/actions/runs/40477200692) passed on the initial test-only commit; [CI #40478626068](https://github.com/jssantogit/tsuzuki/actions/runs/40478626068) exposed **Spotless-only** violations after the FileProvider change, corrected by `dfeeee46`. Do not treat this as full CI green on the latest HEAD until the resulting `[ci-full]` execution finishes.
- Scope remains strictly **standalone canonical-only**. No Mihon legacy sections mixed into this transaction, no destructive reconciliation onto a nonempty personal graph, no claim of physical-device acceptance, no commercial primary editorial source or automatic adoption. Reader pages are still provider-owned.

## External and manual blockers — not solved by code

1. No verified production publisher feed/license + exact edition identity + independent Host verification. MangaUpdates/MangaDex remain **release evidence**, Wikimedia/EPUB remain **candidate-only**, not `PRIMARY_EDITORIAL`. Therefore no legally validated live commercial chapter projection is enabled.
2. `USER_ADOPTED` supports explicit local EPUB3 document selection through SAF and a bounded ledger-backed edition candidate browser (keyset pages of 32, up to 128 selected IDs per confirmation), proven with synthetic EPUB3 data and actual DocumentsUI on the emulator. It is still **not** a publisher-authorized primary editorial feed, commercial EPUB user-device smoke, or a Wikimedia API acquisition path. Verify remaining base/en localization and actual device UX separately. No implicit/automatic promotion.
3. Physical Android smoke for catalog-empty defaults, offline inventory, Details, Reader no-pages behavior, update/restore and account/sync lifecycle has **not** been performed; approval of an identified APK is required.
4. Title merge of already materialized editorial editions remains blocked pending audited provenance rehoming. Do not silently rekey chapter IDs or resurrect revoked grants.
5. Existing v1 devices do not negotiate edition-aware progress IDs; local-only progress remains nonportable and the v2 protocol is disabled.

**Conclusion:** Core ICE engineering, manual EPUB3 metadata selection, paginated user adoption, and the isolated canonical-only backup/restore workers have emulator evidence, but complete product DoD and release sign-off remain **BLOCKED** by independently verifiable primary editorial authority (for commercial automated inventory), physical-device smoke with a real file, any outstanding localization/official Wikimedia acquisition, and final integrated CI. Do not mark the PR merge-ready or issue done on emulator-only tests.
