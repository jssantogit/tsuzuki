# Default Skin Wave 1.5: Perfil runtime foundation

Status: complete; affected CI accepted after one focused test correction.
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
- Implementation CI: [38099845391](https://github.com/jssantogit/tsuzuki/actions/runs/38099845391), commit `4d696985e`. Domain/Data Tsuzuki tests, Format and SQLDelight migrations passed. App production compilation passed; App test compilation revealed the mock helper must return `BaseTracker`, matching the actual inferred registry list type. Correction cycle 1 changes only that test helper type; final affected gate passed in [38100131203](https://github.com/jssantogit/tsuzuki/actions/runs/38100131203) at `f05edcb90`. App Tsuzuki tests, Format and CI Gate passed. Production code is unchanged from the Domain/Data/migrations-verified `4d696985e` revision.
- Domain/API review: independent review found no actionable defects. Full diff and semantic-guard searches confirmed no email inference, checkpoint statistics, legacy Favorites semantics, generic completion timestamps, new tracker auth, second library projection, or ScreenModel traversal.
- Provider/P2P, UI/root navigation, account/auth and persistence schema: unchanged, verified by full diff review and semantic-guard searches.

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

## Executed verification

CI executes Gradle; no local Gradle or APK was requested. Commands and results:

- `./gradlew :domain:testDebugUnitTest --tests '*tsuzuki*'`: PASS at `4d696985e`.
- `./gradlew :data:testDebugUnitTest --tests '*tsuzuki*'`: PASS at `4d696985e`.
- `./gradlew :app:testDebugUnitTest --tests '*tsuzuki*'`: PASS at `f05edcb90`,
  including test compilation/execution after the mock-type correction. Existing
  account and unified-library contracts are covered by these affected shards.
- `./gradlew spotlessCheck`: PASS on both implementation and corrected test revisions.
- `./gradlew verifySqlDelightMigration`: PASS at `4d696985e`.
- Test tasks compiled their affected production modules; App production Kotlin
  compiled successfully. Dedicated release/native/Android jobs were not selected.
- `git diff --check`: PASS. Baseline-to-final scope review: 12 files, no Provider/P2P,
  UI, account/auth, new migration or backend schema changes.

Commits: `784f550e6` tests/plan; `4d696985e` implementation; `f05edcb90` test-type
correction; final documentation commit records this acceptance without code changes.
No product-semantic discrepancies required widening the wave. Repository details
requiring adaptation were the app-layer tracker registry, its `BaseTracker` type,
and compatibility with existing reading-repository fakes.

Next: a separately authorized Perfil UI wave can consume this read model. It must
collapse unsupported modules and must not synthesize their values. Settings remains
the fourth root; no APK was generated and no Provider/P2P behavior was touched.
