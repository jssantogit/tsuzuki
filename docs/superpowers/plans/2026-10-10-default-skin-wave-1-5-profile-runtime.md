# Default Skin Wave 1.5: Perfil runtime foundation

Status: implementation added; GREEN CI pending.
Branch: `design/default-skin-consolidation`
Starting HEAD: `f5156380fe619857988be96e9273cd7123b1428e`

## Scope and contract

Profile is a cold, lifecycle-owned domain read model. Reuse `AccountState`,
`ObserveUnifiedLibrary`, persisted canonical chapter progress, and the existing
`TrackerManager` registry and each tracker's `isLoggedInFlow`.
Account remains account/auth only. Profile writes no state and performs no authentication.
Settings remains the fourth root. No UI, Provider/P2P changes, backend schema or APK.

Measured values:

- Currently reading: count unified canonical titles whose `statusesForOrigin(null)`
  contains `LibraryStatus.READING`, once per canonical title across all memberships.
- Chapters read: SQL aggregate over existing `tsuzuki_chapter_progress` rows where
  `read = 1`, observed through `CanonicalReadingRepository`.
- Tracker states: registered tracker ID/name and the existing login-state Flow,
  including disconnected trackers.

Unsupported values:

- Lifetime pages read, display name and avatar are absent, represented by null.
- Personal Favorites and completion history are explicitly unsupported capabilities.
- Never derive identity from email, cumulative pages from checkpoints, personal
  Favorites from legacy library membership, or completion time from generic timestamps.

## Implementation slices

1. Define failing reading projection, SQL aggregate, snapshot and tracker-adapter tests.
2. Confirm RED through affected CI, then add the minimal pure projection and SQL API.
3. Compose existing Flows in `ObserveProfile`; adapt the app tracker registry through
   a small read-only `ProfileTrackerSource` port. No parallel source of truth.
4. Verify GREEN with affected CI and review the full diff against the semantic guards.

## Repository findings

- Progress already lives in `CanonicalReadingRepositoryImpl` and
  `tsuzuki_chapter_progress.sq`; no new table or migration is needed.
- Trackers are app-layer objects, outside the domain module. A narrow read-only
  adapter is required; no tracker-auth changes are required.
- `AccountState.EmailConfirmationRequired` is real and remains visible unchanged.
- Existing reading-repository test fakes lack a global aggregate. A default explicit
  unsupported exception preserves compatibility without emitting a fabricated zero;
  the production SQL implementation supplies the measured count.

## Verification record

- Required gate: CI v2.1 affected mode, planner-selected jobs.
- RED: commit `784f550e6`, [CI run 38099554623](https://github.com/jssantogit/tsuzuki/actions/runs/38099554623). Format passed; domain/data/app test compilation failed because the Profile APIs and read-chapter aggregate did not exist yet. This is compile-time RED, not executed failing assertions.
- Implementation CI: [38099845391](https://github.com/jssantogit/tsuzuki/actions/runs/38099845391), commit `4d696985e`. Domain/Data Tsuzuki tests, Format and SQLDelight migrations passed. App production compilation passed; App test compilation revealed the mock helper must return `BaseTracker`, matching the actual inferred registry list type. Correction cycle 1 changes only that test helper type; final affected gate pending.
- Domain/API review: independent review found no actionable defects. Full diff and semantic-guard searches confirmed no email inference, checkpoint statistics, legacy Favorites semantics, generic completion timestamps, new tracker auth, second library projection, or ScreenModel traversal.
- Provider/P2P and root navigation: must remain unchanged.

## Implemented API and test coverage

- `ObserveProfile.subscribe(): Flow<ProfileSnapshot>` composes existing account state,
  a distinct currently-reading count, SQL read-chapter count and tracker projection.
  Each subscription is cold and cancellation releases its upstream collectors.
- `ProfileSnapshot` contains `AccountState`, `ProfileReadingStats`, and a list of
  `ProfileTrackerState(id, name, isLoggedIn)`. `EmailConfirmationRequired` is retained.
- Fixed unavailable getters: `displayName`, `avatarUrl`, `pagesRead` return null;
  `supportsPersonalFavorites` and `supportsCompletionHistory` return false. They
  expose no empty-but-authoritative collections and cannot accept fixture values.
- `ProfileTrackerSourceImpl` contributes the read-only domain port through Metro,
  maps each registered tracker's existing login Flow, and has no credential writes.
- 16 new tests across `ProfileReadingProjectionTest` (6), `ObserveProfileTest` (5),
  `ProfileTrackerSourceImplTest` (3), and `CanonicalReadingRepositoryImplTest` (2).
  Coverage includes measured zero, local/external deduplication, reactive persisted
  updates, repeated upserts, guest/logout/confirmation, unavailable capabilities,
  registered/disconnected trackers, login/logout changes and upstream cancellation.
