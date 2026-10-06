# Provider/Torrent Acceptance Harness — Design

Status: **proposed implementation contract**

Date: 2026-10-06

## Goal

Replace the current repetitive physical-debug loop for Provider/torrent reading with a deterministic automated acceptance path that proves the same production boundaries before a device smoke:

```text
canonical title/chapter
-> SCRIPT torrent Provider in the real Tsuzuki runtime
-> controlled Host HTTP fixture
-> exact TorrentChapterMapper selection
-> Host acquisition policy
-> host-owned direct P2P against a local loopback peer
-> managed CBZ/ZIP resource
-> Reader-facing page extraction/readiness
```

The physical Android-device smoke remains required for final sanity around real process/network/platform behavior, but it must no longer be the primary way to discover Provider search, runtime-budget, torrent mapping, P2P acquisition, archive or Reader-bridge regressions.

This design extends the approved Provider Platform V1 architecture. It does not change Provider capabilities, canonical identity, TorrentChapterMapper semantics, acquisition policy, Host Service authority or Reader contracts.

## Why now

Wave 6 is merged and the current physical failure boundary is Nyaa discovery/parsing before exact mapping, P2P or materialization. The codebase already contains most of the required test primitives in isolation:

- Provider-side Nyaa fixture tests and runtime-budget probes in `providers-tsuzuki`;
- domain torrent contract/mapping/acquisition tests in `tsuzuki`;
- Android isolated Provider runtime + Host Service tests against `MockWebServer`;
- a real jlibtorrent loopback test that seeds locally and downloads only the selected archive;
- Reader preparation tests that already accept `PreparedChapterContent.LocalArchive`.

The missing piece is a coherent acceptance harness that composes those boundaries instead of validating each one independently and relying on a phone for the final wiring.

## Non-goals

This work does **not**:

- make live Nyaa, public trackers or public peers a required CI dependency;
- weaken exact/ambiguous torrent chapter mapping rules;
- increase Provider Host wall-clock or JavaScript timeouts;
- replace current focused unit/contract tests;
- remove the final physical-device smoke;
- create a vendor-specific Nyaa path inside Tsuzuki core;
- change Debrid or P2P product policy;
- add progressive reading from partially downloaded archives;
- redesign Reader UI or canonical chapter identity.

## Design principles

### 1. Deterministic first, live smoke second

Required CI must run entirely from controlled fixtures and loopback networking. External indexers, trackers and peers are too variable to gate pull requests.

An optional live Provider smoke may exist separately, but it is observational and non-blocking.

### 2. Production boundaries, not duplicated reimplementations

The harness must exercise the same production components used by the app wherever practical:

- `ProviderRuntimeService` / SCRIPT execution;
- Host HTTP bridge and runtime limits;
- `TorrentSearchGateway` serialization/deserialization;
- `ResolveProviderChapterTorrent`;
- `TorrentChapterMapper`;
- `ProviderTorrentAcquisitionCoordinator` / acquisition policy;
- privileged host-owned P2P engine;
- `ProviderManagedFileStore`;
- the Reader-facing archive path.

A test-only adapter is acceptable only for fixture injection or orchestration. It must not duplicate production matching, acquisition or page-extraction logic.

### 3. Reuse existing proof instead of building a parallel framework

Existing tests remain the lower levels of the pyramid. The new work should factor reusable fixtures/helpers out of them only when the acceptance path needs the same capability.

### 4. Fail at the narrowest boundary

The harness should make failures attributable to a stage:

```text
PROVIDER_SEARCH
RUNTIME_BRIDGE
TORRENT_MATCH
ACQUISITION_POLICY
P2P_TRANSFER
MANAGED_RESOURCE
READER_ARCHIVE
```

This is test reporting, not a new product-level error vocabulary.

## Acceptance pyramid

### Level 1 — Provider contract fixtures

Repository: `jssantogit/providers-tsuzuki`.

Purpose: verify Provider behavior without Android.

Existing Nyaa tests stay authoritative for parsing/search behavior. They should cover at minimum:

- narrow `title + chapter` query;
- required primary title-only fallback;
- alias budget cutoff;
- raw RSS with zero items;
- raw RSS with accepted items;
- raw RSS items rejected by validation;
- magnet-link + matching infoHash acceptance;
- malformed/mismatched magnet rejection;
- exact single CBZ/ZIP detail-page mapping;
- multi-file torrent remaining unmapped/fail-closed;
- deterministic elapsed/request-count expectations for budget regressions.

The current live failure also justifies bounded diagnostics in Provider fixtures: per-attempt phase, raw RSS item count, accepted candidate count and elapsed time. Tests may assert these diagnostics without persisting raw titles or URLs.

### Level 2 — Real runtime torrent bridge integration

Repository: `jssantogit/tsuzuki` Android instrumentation.

Purpose: prove that a real packaged SCRIPT Provider can cross the actual isolated Android runtime/Host bridge and become a Tsuzuki torrent candidate.

The test must:

1. start a `MockWebServer`/equivalent local controlled server;
2. load a minimal conforming test Provider artifact using `torrent.search@1` through the real Provider runtime path;
3. grant only the Host capabilities/network origin needed by the fixture;
4. invoke the production `ScriptProviderTorrentGateway` contract;
5. pass a canonical chapter request containing title aliases, chapter number and volume where applicable;
6. assert the Provider request serialization is correct;
7. return a controlled Provider-specific HTTP fixture response;
8. assert the runtime result becomes the expected `TorrentCandidate`;
9. feed the candidate through production `TorrentChapterMapper` and require one exact file;
10. assert malformed/timeout/oversized Provider responses fail through the existing typed runtime boundary.

This closes the current gap between `PrepareProviderChapterTorrentTest`'s fake gateway and `ProviderHostServicesIntegrationTest`'s generic runtime invocation.

The app-side test Provider must remain provider-neutral. It may parse a tiny purpose-built fixture format, but it must not copy the production Nyaa RSS/detail parser into the app test suite.

### Level 3 — Deterministic P2P acquisition acceptance

Repository: `jssantogit/tsuzuki` Android instrumentation.

Purpose: prove the production Host-owned direct-P2P path can acquire the exact mapped archive without Internet infrastructure.

Reuse the already-proven loopback jlibtorrent shape:

- generate a tiny deterministic torrent at test runtime or from a checked fixture;
- seed it from `127.0.0.1` with DHT/LSD/public trackers disabled;
- include at least one unrelated archive so selected-file priority is tested;
- use the production P2P engine and managed-file path;
- require the selected file bytes/hash/size to match;
- require unrelated pack files not to complete;
- require the returned URI/resource to be Tsuzuki-managed and Reader-compatible;
- clean up sessions and files deterministically after success/failure.

The canonical acceptance fixture should prefer one torrent containing:

```text
chapter-011.cbz
chapter-012.cbz
chapter-013.cbz
```

with `chapter-012.cbz` containing three tiny valid image pages. That proves mapping and selective acquisition in one scenario.

### Level 4 — Reader acceptance

Repository: `jssantogit/tsuzuki` Android instrumentation.

Purpose: prove the acquired managed CBZ crosses the same archive/Reader abstraction used by production and exposes readable pages.

The test does not need full Compose/UI screenshot automation in V1. The acceptance criterion is the Reader data path, not pixel rendering.

Given the managed `chapter-012.cbz`, assert:

- the production Reader-facing preparation returns ready content rather than an error/empty state;
- the archive is recognized as CBZ/ZIP;
- exactly three pages are enumerated;
- all three page resources can be opened/decoded enough to prove they are valid images;
- page ordering is deterministic;
- closing/cancelling the Reader path releases temporary resources according to existing lifecycle rules.

A later UI/instrumentation smoke may verify actual rendered page widgets, but it is not required to establish this harness.

## Canonical end-to-end scenario

The primary automated acceptance scenario is:

```text
CanonicalTitle
  displayTitle = "Test Manga"
  aliases = ["Test Manga Alt"]

CanonicalChapter
  baseNumber = 12
  displayNumber = "12"

        ↓
minimal conforming SCRIPT torrent Provider
  torrent.search@1

        ↓
controlled Host HTTP fixture
  one matching torrent candidate

        ↓
controlled torrent metadata
  chapter-011.cbz
  chapter-012.cbz
  chapter-013.cbz

        ↓
TorrentChapterMapper
  exact chapter-012.cbz

        ↓
TorrentAcquisitionPolicy
  P2P_ONLY
  direct P2P explicitly allowed in test policy

        ↓
local loopback jlibtorrent seeder
  127.0.0.1 only

        ↓
ProviderManagedFileStore
  managed chapter-012.cbz

        ↓
Reader archive path
  3 ordered valid pages
```

No external DNS, Nyaa, public tracker, DHT, LSD or public peer participates in this required acceptance scenario.

## Fixture ownership

### Provider repository fixtures

`providers-tsuzuki` owns fixtures that describe Provider-specific remote formats and search semantics: Nyaa RSS/detail HTML, parser acceptance/rejection and Provider query timing behavior.

### App repository fixtures

`tsuzuki` owns platform fixtures: test Provider package/artifact, canonical chapter input, deterministic torrent metadata, tiny CBZ images, Host/runtime policies and loopback P2P orchestration.

Do not copy full Nyaa production parser semantics into the app test suite. The app test needs a conforming `torrent.search@1` SCRIPT Provider fixture; Nyaa-specific parser coverage remains in the Provider repository.

## Test Provider strategy

The app acceptance harness should use a **minimal conforming SCRIPT torrent Provider fixture**, not the production Nyaa artifact as the only test subject.

Reasoning:

- Host/runtime acceptance must remain stable if Nyaa changes remote HTML/RSS semantics;
- the fixture can target `MockWebServer`/local origins explicitly under instrumentation policy;
- it proves third-party Provider compatibility against the public capability contract;
- production Nyaa remains covered independently by `providers-tsuzuki` contract fixtures and optional live smoke.

The fixture should exercise the same script/manifest/runtime loading boundary used by an installed Provider, but it does not need signed repository enrollment or a published repository sequence. Repository supply-chain conformance remains covered by its existing tests.

## Timing and budget assertions

Timing tests must avoid brittle real-time thresholds wherever a virtual/fake clock or controlled server delay can prove the same behavior.

Required Provider-level assertions include:

- bounded request count under many aliases;
- primary narrow query does not suppress its required primary title-only fallback;
- later aliases stop once the Provider soft discovery budget is exhausted;
- the Host hard wall-clock budget remains authoritative;
- one blocking HTTP request cannot escape the Host invocation deadline;
- cancellation closes in-flight Host work.

The harness must not introduce a new larger timeout to make CI pass.

## Diagnostics in tests

Acceptance failures should print a compact stage trace including:

- Provider ID/capability;
- request/attempt ordinal;
- stage;
- raw item count where the Provider fixture exposes one;
- accepted candidate count;
- match outcome;
- selected file index/path basename;
- acquisition route;
- bytes/page count;
- elapsed bucket or milliseconds where deterministic.

Normal product privacy rules remain in force: do not dump raw user titles, private URLs, cookies, tokens or secrets.

## CI integration

### Pull-request blocking gates

1. Provider repository: existing Provider CI runs all contract fixtures, including Nyaa budget/search regressions.
2. Tsuzuki JVM/domain lanes: continue to run mapping/acquisition/Reader contract tests.
3. Tsuzuki Android Provider Platform lane: add the runtime bridge + deterministic loopback P2P + Reader acceptance scenario on the supported API 35 x86_64 emulator/debug environment where the native artifact is intentionally available for instrumentation.

The acceptance scenario should be one logical CI gate even if Gradle executes multiple focused instrumentation test classes.

### Non-blocking live smoke

A live Nyaa smoke may run manually or periodically. It may answer questions such as:

- does the real endpoint still return the expected RSS shape?;
- do current public responses still produce candidates?;
- are remote latency/availability characteristics materially different?

It must **not** make ordinary pull requests red when Nyaa, a tracker or external peers are unavailable.

## Implementation decomposition

This design spans two repositories but should ship through two independently reviewable tracks:

### Track A — Provider-side discovery observability

Repository: `providers-tsuzuki`.

Owns Nyaa fixture coverage and bounded diagnostics for raw-vs-accepted discovery counts and attempt timing. It can land independently and directly improves the current live investigation.

### Track B — Provider-neutral app acceptance harness

Repository: `tsuzuki`.

Owns the real runtime bridge, exact mapping, loopback P2P, managed resource and Reader archive acceptance. It must not depend on production Nyaa behavior or a published Provider repository sequence.

Track B is the structural solution to the repetitive device-debug loop. Track A is the Provider-specific observability improvement for the currently unresolved Nyaa discovery issue.

## Failure expectations

The harness must include negative acceptance coverage for at least:

- Provider returns zero candidates -> clean empty result, no P2P attempted;
- Provider runtime timeout -> typed failure, no mapper/acquisition attempted;
- candidate has ambiguous matching archives -> fail closed, no automatic acquisition;
- selected-file metadata does not match candidate -> fail closed;
- P2P unavailable/no consent -> policy prevents transport;
- local seeder unavailable -> bounded acquisition failure and cleanup;
- acquired archive invalid/corrupt -> Reader preparation fails without corrupting canonical/progress state;
- cancellation during Provider HTTP or P2P -> work stops and managed temporary resources are cleaned up.

## Relationship to physical smoke

After this harness is green, physical smoke becomes a final platform sanity check for:

- real Android process isolation;
- actual device ABI/native packaging;
- OS networking/permissions/lifecycle;
- user-visible consent/settings wiring;
- one real Provider/network path when useful.

A physical live-Nyaa failure with the deterministic harness green should be investigated first as external Provider/search/runtime behavior, not as evidence that the entire torrent-to-Reader pipeline is broken.

## Acceptance criteria

This design is complete when CI can prove, without public Internet dependencies:

1. a canonical chapter request reaches a real SCRIPT `torrent.search@1` Provider through the isolated runtime and Host HTTP bridge;
2. controlled Provider output becomes a valid `TorrentCandidate`;
3. production `TorrentChapterMapper` selects exactly `chapter-012.cbz` from a multi-file torrent;
4. production acquisition policy authorizes only the configured route;
5. host-owned jlibtorrent downloads only the selected archive from a loopback peer;
6. the acquired file is promoted/exposed as a managed local resource;
7. the production Reader archive path enumerates and can open exactly three ordered image pages;
8. negative cases fail closed at their owning boundary with cleanup;
9. no required CI step depends on Nyaa, public trackers or public peers;
10. the existing Provider Platform V1 security, privacy and canonical-identity invariants remain unchanged.

## Implementation boundary

This harness is a testing/validation subsystem. Production refactoring is allowed only when necessary to expose existing behavior cleanly to tests or to remove duplicated fixture logic. No production behavior change is implied by this spec.

If implementation discovers that the current production path cannot be composed without adding a new runtime/product abstraction, stop and amend the design rather than hiding the gap behind a test-only duplicate implementation.
