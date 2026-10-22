# Canonical chapter v2 — Android and mixed-device acceptance

Status: **NOT EXECUTED / NOT RELEASE-APPROVED**. This is an acceptance matrix, not evidence that these flows work on a physical device. The exact-numbering migration writer and sync-v2 mode are internal opt-in pilots and are not connected to production.

## Preconditions

1. Take an exportable/restorable backup of the existing Tsuzuki library, reading progress, chapter history, downloads, title mappings and signed-in sync state. Do not run on the only copy of a user's data.
2. Capture the starting app version, SQLite schema/version, Git commit and Android device version. Retain a reproducible database snapshot representative of the version **before** SQLDelight migration 43.
3. Prepare independent editorial and source fixtures for **12.05**, **12.5**, **12 Part 5**, **Vol. 1 Ch. 12**, **Vol. 2 Ch. 12**, multiple unnumbered Extras and mixed scanlation sources.
4. Keep the v2 writer and v2 sync codec **disabled in production**. Both sides of a sync test must use explicitly isolated development accounts and disposable data.
5. Reject release if any failed test causes silent chapter-ID reassignment, progress loss or a portable-key collision.

## Acceptance matrix

| Area | Procedure | Pass criteria | State |
| --- | --- | --- | --- |
| Historical database upgrade | Upgrade a real pre-43 snapshot with progress, downloads, history and source variants; compare rows and FKs before/after | All IDs, FKs, reading checkpoints, history and downloads retained; v2 columns NULL on old rows | **Not run** |
| Reader identity | Open chapters 12.05, 12.5, 12 Part 5 and ordinary 12 in a controlled inventory | Four distinct chapter identities, correct order and no accidental reassignment | **Not run** |
| Volume reset | Browse chapter 12 in two volumes, then select Next/Previous across volume boundary | Both chapters retained and correctly navigated, no duplicate progress key | **Not run** |
| Source switching | Read a chapter using one source and switch to a different variant/language | Exact canonical ID stable; page position not reused across incompatible variants | **Not run** |
| Downloads | Download variants, restart app, re-open offline, switch sources and return online | Existing downloads and associated canonical IDs unchanged | **Not run** |
| Unnumbered extras | Import two Extras without stable common identifiers | Extras remain separate; no guessed cross-source merge | **Not run** |
| Conflicting historical evidence | Change a trusted exact label from 12.05 to 12.5 while preserving source key | Previous exact identity not silently downgraded or rehomed | **Not run** |
| Android app recovery | Force-stop/relaunch during background refresh and retry | No partial identity write or lost reading state | **Not run** |
| Same-version sync pilot | Isolated v2-to-v2 progress exchange on two development devices with distinct local IDs | Fraction + volume keys resolve exactly once; checkpoints correct | **Not run** |
| Mixed-version sync | Attempt v1-to-v2 and v2-to-v1 transfer in an isolated test environment | Incompatible documents rejected without writing progress, with clear recovery path | **Not run** |
| Downgrade and restore | Roll back app from a backed-up snapshot without v2 in-place mutation | No data loss, corruption or stale-identity promotion | **Not run** |

## Additional automated gates (2026-10-22)

- `.github/scripts/test_chapter_migration_43.py`: exercises migration 43 against a disposable pre-43-shaped SQLite fixture with two distinct chapter IDs sharing the legacy numeric part, preserved progress, history, downloads, mapped evidence and variants. This is a **synthetic compatibility smoke test**, not a restored Android user database.
- `.github/workflows/ci-v2.yml`: runs that fixture in the planner plus Kotlin domain/data/app tests and SQLDelight's schema migration verification. Final full CI verdict must be read from the exact tested commit.
- `.github/workflows/canonical-chapter-v2-android.yml`: separate opt-in-guarded emulator test lane with a disposable debug package, exercising Reader source switching and invalid navigation. It requires a successful instrumentation report and clears app data afterward. This gate is **not** evidence for real user backups or cross-device sync until the GitHub run completes successfully.
- The Supabase orchestration code now **rejects unnegotiated v2 documents** and incompatible accepted-base versions **before using the legacy unversioned transport**. This protects data but deliberately leaves live sync v2 unavailable, pending server-side protocol negotiations, v1 remote record migration and older-client downgrade protection.

## Existing automated checks

- Domain and Data tests cover decimal precision, volume-aware identities, conflicting evidence, SQLDelight schema migrations, protected state and compare-and-swap writer behavior.
- The ordinary reading-progress sync adapter **remains schema v1**. The opt-in version-2 path is not connected to live Supabase sync or the user's account.
- A green GitHub Actions release compile or native package gate demonstrates buildability, **not** completion of the physical-device acceptance matrix above.

## Release decision

Do **not** merge or enable migration on existing user accounts until the Android matrix has documented results, legacy database snapshots have been upgraded and restored successfully, and the remote sync protocol supports a coordinated rollout across devices. Capture test build SHA, emulator/physical-device identity, DB comparison evidence and any failure logs alongside the release sign-off.
