# Provider/Torrent Acceptance Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic Android acceptance gate proving canonical torrent discovery through the real SCRIPT runtime, exact chapter mapping, host-owned loopback P2P acquisition, managed archive promotion and Reader page enumeration without public Internet dependencies.

**Architecture:** Reuse production runtime, gateway, mapper, acquisition, managed-file and Reader archive components. Add only test fixtures/helpers for a minimal conforming SCRIPT torrent Provider, deterministic torrent/CBZ generation and loopback seeding. The acceptance path stays provider-neutral; Nyaa-specific parser semantics remain in `providers-tsuzuki`.

**Tech Stack:** Kotlin, Android instrumentation, QuickJS runtime, MockWebServer, jlibtorrent, existing ArchiveReader/ArchivePageLoader, GitHub Actions API 35 x86_64 emulator.

**Spec:** `docs/superpowers/specs/2026-10-06-provider-torrent-acceptance-harness-design.md`

## Global Constraints

- No required CI dependency on Nyaa, public trackers, DHT, LSD, public peers or external DNS.
- Preserve Provider Platform V1 canonical identity and fail-closed mapping rules.
- Do not increase Host wall-clock or JavaScript execution limits.
- Direct P2P remains Host-owned and explicitly policy-gated.
- Do not duplicate production matching, acquisition or Reader archive logic in test helpers.
- Keep release x86_64 native packaging policy unchanged; the instrumentation lane may use the existing debug-only native artifact on API 35 x86_64.
- No local Gradle validation; use repository CI/workflow gates.

## Review Focus

- Provider returns zero candidates: no mapper/P2P work should start.
- Ambiguous chapter archives: automatic acquisition must fail closed.
- P2P job returns a managed token for an invalid/corrupt archive: managed-file validation must reject it.
- Loopback seeder disappears: acquisition must terminate boundedly and clean work files.
- Reader archive contains non-image entries/out-of-order names: only valid images are exposed in natural deterministic order.

---

### Task 1: Extract reusable deterministic torrent/CBZ instrumentation fixtures

**Files:**
- Create: `app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/ProviderTorrentAcceptanceFixtures.kt`
- Modify: `app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/JlibtorrentProviderP2pDownloadEngineTest.kt`
- Test: `app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/JlibtorrentProviderP2pDownloadEngineTest.kt`

**Interfaces:**
- Produces: `ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)` returning torrent metadata, selected chapter index/path and three-page CBZ bytes.
- Produces: `ProviderTorrentAcceptanceFixtures.localOnlyParams(port)` and loopback seeder lifecycle helper.

- [ ] **Step 1: Write the failing fixture-consumer test**

Refactor the existing P2P instrumentation test to consume the new fixture API before the helper exists. Keep assertions that only the selected archive completes and bytes match exactly.

- [ ] **Step 2: Push and verify RED in Provider Platform Android gate**

Trigger the branch with `[ci-full]`.

Expected: instrumentation compile fails because `ProviderTorrentAcceptanceFixtures` is missing.

- [ ] **Step 3: Implement only the test helper**

Generate `chapter-011.cbz`, `chapter-012.cbz`, `chapter-013.cbz`; `chapter-012.cbz` contains `001.png`, `002.png`, `003.png` as tiny valid PNGs. Disable DHT/LSD and use loopback-only listening.

- [ ] **Step 4: Verify GREEN**

Expected: existing `JlibtorrentProviderP2pDownloadEngineTest` passes in Provider Platform Android gate.

- [ ] **Step 5: Commit**

Commit message: `test(provider): extract deterministic torrent acceptance fixtures`

### Task 2: Prove real SCRIPT torrent Provider runtime bridge

**Files:**
- Create: `app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/ProviderTorrentRuntimeBridgeTest.kt`
- Create or extend test helper: `ProviderTorrentAcceptanceFixtures.kt`
- Production code only if a RED test proves an inaccessible existing boundary.

**Interfaces:**
- Consumes: deterministic fixture utilities from Task 1.
- Produces: a minimal `.tsz` fixture with `torrent.search@1`, network permission for the local MockWebServer origin and a real invocation through `ProviderRuntimeClient` -> `ScriptProviderCapabilityExecutor` -> `ScriptProviderTorrentGateway`.

- [ ] **Step 1: Write a failing runtime bridge acceptance test**

The test must:
1. start `MockWebServer` on loopback;
2. build a minimal SCRIPT Provider package whose `torrent.search` calls `tsuzuki.http.request` and returns one controlled candidate;
3. register that Provider as enabled SCRIPT with `torrent.search@1`;
4. invoke production `ScriptProviderTorrentGateway.search` with titles `Test Manga`, `Test Manga Alt`, chapter `12`, volume `2`;
5. assert the server observed the serialized input semantics;
6. assert the returned domain `TorrentCandidate` contains the expected identity and three files.

- [ ] **Step 2: Verify RED in Android gate**

Expected: FAIL until the fixture/package/runtime wiring exists; failure must be at the missing test orchestration boundary, not by weakening Host policy.

- [ ] **Step 3: Add minimal package/runtime fixture wiring**

Reuse the `.tsz` ZIP construction pattern already present in `ProviderPackageIsolationTest`; do not publish/install a repository sequence.

- [ ] **Step 4: Add typed negative cases**

Add tests for malformed Provider JSON and Host timeout returning existing typed `ProviderCallResult.Failure` without entering mapper/acquisition.

- [ ] **Step 5: Verify GREEN**

Expected: runtime bridge tests pass on API 35 x86_64 instrumentation.

- [ ] **Step 6: Commit**

Commit message: `test(provider): cover real torrent runtime bridge`

### Task 3: Compose mapping, acquisition policy and managed loopback P2P

**Files:**
- Create: `app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/ProviderTorrentAcquisitionAcceptanceTest.kt`
- Modify test helper: `ProviderTorrentAcceptanceFixtures.kt`
- Modify production only if RED proves composition is impossible through existing interfaces.

**Interfaces:**
- Consumes: `TorrentCandidate` from Task 2 shape and deterministic torrent fixture from Task 1.
- Produces: accepted managed `content://...` local CBZ resource through production `TorrentChapterMapper`, acquisition policy/coordinator, `JlibtorrentProviderP2pDownloadEngine`, `ProviderP2pJobManager`, and `ProviderManagedFileStore`.

- [ ] **Step 1: Write a failing end-to-end acquisition test**

Given canonical chapter 12 and candidate files 11/12/13, require production mapper to select only `chapter-012.cbz`, `P2P_ONLY` with explicit consent to choose direct P2P, and the real loopback engine to finish with a managed local archive URI.

- [ ] **Step 2: Verify RED**

Expected: FAIL until existing production pieces are composed by the test harness.

- [ ] **Step 3: Implement minimal orchestration**

Use production policy/coordinator/job manager/store. Poll only through existing P2P pending/ready semantics; do not add a test-only synchronous acquisition API.

- [ ] **Step 4: Add fail-closed cases**

Assert ambiguous files do not start P2P; no consent prevents transport; unavailable seeder yields bounded failure and deletes working files.

- [ ] **Step 5: Verify GREEN**

Expected: all acquisition acceptance cases pass and unrelated torrent files remain incomplete.

- [ ] **Step 6: Commit**

Commit message: `test(provider): accept deterministic torrent acquisition`

### Task 4: Close the Reader archive acceptance path

**Files:**
- Create: `app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/loader/ProviderTorrentReaderAcceptanceTest.kt`
- Modify visibility only if required by RED: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ArchivePageLoader.kt`
- Modify fixture helper as needed for valid PNG generation.

**Interfaces:**
- Consumes: managed CBZ URI/file created by Task 3.
- Produces: proof that the production archive Reader path exposes exactly three ordered ready pages whose streams decode as images.

- [ ] **Step 1: Write failing Reader acceptance test**

Open the managed `chapter-012.cbz` using the production archive reader/loader path. Assert page count 3, indexes 0/1/2, natural filename order, `Page.State.Ready`, and each page stream passes existing image detection/decoding.

- [ ] **Step 2: Verify RED**

Expected: FAIL only if the production Reader archive seam is not reachable from instrumentation; do not clone its filtering/sorting logic into the test.

- [ ] **Step 3: Expose the narrowest existing seam if required**

Prefer package-local test placement over widening production visibility. Any production visibility change must not change runtime behavior.

- [ ] **Step 4: Add corrupt archive case**

Require `ProviderManagedFileStore` or Reader preparation to fail cleanly without exposing pages.

- [ ] **Step 5: Verify GREEN**

Expected: exactly three ordered valid image pages are reachable through the production Reader archive path.

- [ ] **Step 6: Commit**

Commit message: `test(reader): accept managed torrent archive pages`

### Task 5: Make the acceptance scenario a blocking CI gate

**Files:**
- Modify: `.github/workflows/provider-runtime-android.yml`
- Modify if needed: `.github/scripts/provider-runtime-android-gate.sh`
- Test: existing `.github/scripts` CI planner/gate tests if workflow selection logic changes.

**Interfaces:**
- Consumes: Tasks 1-4 instrumentation tests.
- Produces: one logical blocking Provider Platform Android acceptance gate covering runtime bridge + loopback P2P + Reader archive path.

- [ ] **Step 1: Add a CI-selection regression test if path/command selection must change**

Ensure this branch pattern and new instrumentation package are selected by the Provider Platform Android gate.

- [ ] **Step 2: Verify RED if workflow currently skips the branch/test**

Expected: planner/workflow test demonstrates the missing selection before workflow code changes.

- [ ] **Step 3: Apply minimal workflow/gate update**

Run the complete Provider instrumentation package on API 35 x86_64; no live network smoke in the blocking job.

- [ ] **Step 4: Trigger `[ci-full]` and verify complete GREEN**

Expected: compile + emulator instrumentation pass; ordinary CI v2.1 relevant lanes are green.

- [ ] **Step 5: Commit**

Commit message: `ci(provider): gate torrent Reader acceptance`
