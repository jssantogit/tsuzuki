# Issue #155 — ICE: interrupted implementation checkpoint

**State:** implementation stopped at the user's explicit request. Issue remains **PARTIAL**; PR [#156](https://github.com/jssantogit/tsuzuki/pull/156) remains **Draft**. This is a factual checkpoint, not release approval or a replacement for the updated issue/design.

## Repository and authority

- Root: `/root/projects/Tsuzuki`, independent checkout; no new worktree or product clone.
- Branch: `feat/issue-155-independent-chapter-inventory`.
- Initial HEAD: `d442c9e4c2295868cbc119f8716bbdf3e2df6ac6`.
- Published product checkpoint: `abf750fb1581e1eb72fd7dc3e7bf65db28e4d56a`.
- The commit containing this document is documentation-only, after that product checkpoint. Do not describe CI on the product SHA as an executed run on the later documentation SHA.
- Working tree was clean before saving this document. All product changes were already committed and pushed.
- No merge, APK workflow, live-provider sweep, adoption policy activation, sync-v2 or backup cutover was performed.
- Do not resume implementation without a new user instruction. No session-boundary transfer was armed.

## Delivered and evidence

| Increment | Published SHA | Evidence and limits |
| --- | --- | --- |
| MangaDex asynchronous authorization | `d1d9857533942a9c4cfdb5d77780f9fcbc780366` | [CI 40215757308](https://github.com/jssantogit/tsuzuki/actions/runs/40215757308) SUCCESS: App Tsuzuki, Format, CI Gate. Original two RED tests and additional revocation/fingerprint/cancellation/title-operation regressions passed. |
| Persisted consent and transactional work association | `589f2b4a031f16489d3aa71eae8d0ee4674900dc` | [CI 40218313242](https://github.com/jssantogit/tsuzuki/actions/runs/40218313242) SUCCESS: App Tsuzuki, Data Tsuzuki, Format, CI Gate. All nine named SQLite association cases were observed PASSED, including rollback after an actual INSERT, cancellation, exact settings/crosswalks, ownership refusal and idempotency. |
| Selected-source shadow candidates and provenance | `1e02a000b3b21b765f8edc327c8b4f02e695d7df` | [CI 40221710105](https://github.com/jssantogit/tsuzuki/actions/runs/40221710105): Data, App Tsuzuki and SQLDelight Migrations SUCCESS. Domain had one Unicode-range-target failure; Format had one continuation-indentation failure. Not a green checkpoint. |
| Narrow correction of those two failures | `abf750fb1581e1eb72fd7dc3e7bf65db28e4d56a` | [CI 40222274522](https://github.com/jssantogit/tsuzuki/actions/runs/40222274522) was **IN_PROGRESS** at interruption: Change Planner, Domain Tsuzuki and Format SUCCESS; App Tsuzuki still running. Final workflow conclusion was not yet observed. |

### Functional scope

- MangaDex preview/confirmation require opt-in, stable configuration and a current operation generation. Cancellation and title changes invalidate stale callbacks.
- Authorized transport guards recheck persisted consent after serialization/spacing, before HTTP dispatch and after response. Legacy transport entry points remain available to their unrelated callers.
- The linker uses a fail-closed conditional association API. The database transaction revalidates persisted consent, verified identity snapshot and ownership; revocation/cancellation roll back mutation.
- Deterministic selected Wikimedia revision and EPUB3 navigation adapters produce only `COMMUNITY_CURATED` / `USER_SUPPLIED_EDITION_TOC` shadow claims. They perform no network acquisition, file scanning or page reading and are not registered for production acquisition.
- Adapters validate exact input SHA-256, bounded bytes/entries, page/revision identity and supported structures. EPUB parsing rejects DTD/entities, namespace-spoofed/out-of-list anchors and unsafe local references. Auxiliary matter is not automatically classified as a chapter.
- Additive migration `47.sqm` adds nullable provenance JSON. Reads, writes and title-merge queries preserve provenance. Offline examples retain revision/origin/attribution pointers and unresolved label conflicts. No source payload is stored.
- Candidates, releases, ranges and totals do not become canonical chapters, Reader targets or progress entries. No existing chapter ID or protocol was changed.

## Verification caveats and mistakes not to repeat

- CI-first was followed: no local Gradle verification was run.
- Association tests initially were not discovered because of inferred non-Unit return signatures. Run [40216670763](https://github.com/jssantogit/tsuzuki/actions/runs/40216670763) is **not** evidence for them. Explicit Unit signatures produced actual nine-case RED in [40217097014](https://github.com/jssantogit/tsuzuki/actions/runs/40217097014), then GREEN at `589f2b4a0`.
- Candidate tests produced actual RED in [40219616533](https://github.com/jssantogit/tsuzuki/actions/runs/40219616533). One test fixture also had a claim timestamp newer than its enclosing batch; that fixture error was corrected, not treated as a product defect.
- When changing negative-test input bytes, recompute the digest unless testing digest mismatch. Otherwise a security/identity test can pass without reaching its intended parser boundary.
- Do not weaken range assertions. The final correction permits bounded en/em dashes in internal targets and never expands a range.
- Several format/test-fixture correction cycles consumed avoidable time. Verify discovery, fixture validity and trivial formatting before spending another remote cycle.
- Full CI was **NOT_RUN**: its Native Package Gate and Release Compile invoke `assembleRelease`, generating APKs. This conflicts with the explicit no-APK instruction for this execution. No workflow was modified to evade that restriction.
- Skipped Android jobs, compilation and JVM tests are not executed Android behavior. Physical acceptance is **PENDING**.

## Acceptance state and remaining work

- Current MangaDex RED: **FIXED** at the accepted P0 checkpoint. Consent, revocation, late-response and default-off regressions: **PASS within the tested scope**.
- ICE Phase A: P0 accepted; ICE Phase B: **PARTIAL**, implemented shadow adapters/persistence awaiting the final affected run and further product integration.
- Offline candidate persistence: passed Data tests at `1e02a000b`; no canonical writes from these adapters. Existing IDs/Reader/progress/sync-v1/backup were not rewritten; do not claim the entire requested compatibility matrix is complete.
- Editorial reconciliation: **not implemented in this session**. The authorized synthetic Host-to-reconciler transactional proof and conservative edition identity bridge remain open.
- Real editorial producer: permission, edition identity and an admissible acquisition/feed remain external blockers. Synthetic fixtures do not prove real content access, licensing or commercial coverage.
- Production Wikimedia acquisition/licensing/attribution and EPUB file-selection integration remain unverified/unwired. No real-source collection was enabled.
- USER_ADOPTED policy, preview/receipts/rollback proposal, remaining UI/provenance work, realistic acceptance corpus and complete compatibility matrix remain open. Production candidate materialization is still unauthorized.
- Full CI and physical UI/Reader acceptance remain pending. Do not close #155 or mark the PR ready/merge it.

**First safe action if the user later resumes:** inspect run `40222274522` and current PR HEAD/status. If GREEN, accept only the bounded shadow-candidate checkpoint; then choose one coherent remaining increment. If failed, inspect the exact failure before editing. Do not automatically repeat broad checks or restart implementation.

## Orchestra 1.5 field-test record — separate from Tsuzuki acceptance

- Source: `/root/projects/Orchestra-1.5-rc`, verified clean at `3c9ff12caf58dcdebe92cce4135f1b2aa6267e63`; runtime `1.5.0-rc.1`; Codex CLI `0.164.0`.
- Session/Git/install target: `/root/projects/Tsuzuki`. Canonical v2 metadata records installation before saved session bootstrap. No extra Codex model session, runtime installation/update/rollback or global configuration change was performed.
- **Static Doctor PASS:** documented source command `node scripts/orchestra-codex-project.mjs doctor /root/projects/Tsuzuki --json` returned HEALTHY, with Codex/delegation payload hashes and v2 metadata matching. An earlier legacy-manager UNHEALTHY result was an incorrect entrypoint and was explicitly corrected in the PR; do not repeat that diagnosis.
- Provider hook context supplied CONTROL, generation 0; lease-only diagnostic found a valid lease with CONTROL/WORKSPACE_EDIT. The diagnostic was not given an inferred session ID and is not actor-authority proof by itself.
- Native Luna Max spawn, product implementation, normal worker result, root web access and Sol diff review were observed. Native hooks demonstrably processed/denied tools; explicit hook-trust approval and an independently observed SessionStart event were not established by Doctor.
- Child-to-root `collaborationsend_message` was denied: `CODEX_DELEGATION_DENIED:CHILD_DELEGATION_FORBIDDEN`; normal worker completion worked. Notion search and `clock.sleep` were denied `CODEX_PROVIDER_EVENT_DENIED:UNKNOWN_OPERATION`. No CLI/session or authority bypass was attempted.
- Real product writes were observed. Dedicated WORKSPACE_EDIT/PostToolUse-release event proof was not established; do not infer it merely from successful subsequent calls. Runtime sidecars observed TOOL_REQUEST events.
- Orchestrator provider reported `gpt-6.1-sol`; project config requests `gpt-6-sol` medium. Do not claim exact model-profile parity from configuration alone.
- Field test: **PARTIAL**. Hook trust/complete operational gate coverage and Orchestra release approval remain **PENDING**. This checkpoint is not an Orchestra bug fix or release certification.
