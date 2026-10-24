# Canonical progress v2 — coordinated rollout contract (pre-activation)

Status: **defensive foundation in draft PR #140; no production activation**.
This contract documents verifiable barriers, not permission to ship a migration.

## Validated current behavior

- Production canonical progress uses `READING_PROGRESS` and schema 1. Its keys, accepted frontiers, pending mutations, account state and local progress are not migrated.
- The optional exact-numbering codec uses different `READING_PROGRESS_V2` record IDs; its isolation prevents collisions but **does not** synchronize v1 and v2 clients.
- Both direct and background `SupabaseSyncOrchestrator` entry points reject `READING_PROGRESS_V2` with `UNSUPPORTED_SCHEMA` before remote traffic for that domain. The opt-in adapter must not be installed in production composition.
- A forward-only Supabase migration moves the previously exposed `sync_apply_mutation_batch(jsonb)` body unchanged to the non-exposed `tsuzuki_private` schema. Only its authenticated public wrapper may call it. The wrapper rejects v2 **before** the old idempotency or mutation logic and normalizes the domain exactly like the old RPC.
- Authenticated `sync_get_progress_protocol()` is **read-only**, returning an epoch-1, legacy-only capability response: `schemaVersion=1`, `protocolEpoch=1`, `activeDomain=READING_PROGRESS`, `exactProgressEnabled=false`, `migrationReady=false`. The client decoder accepts only that combination. It does not authorize an epoch transition, and no activation RPC exists.
- `PreviewCanonicalProgressV2Migration` inspects a remote **v1** progress snapshot against *all* locally known title siblings and optional stable evidence. It produces an old-ID → new-ID **review mapping only**, without copying, deleting or importing any progress. It rejects malformed/tombstone records, wrong document versions, unmatched keys, volume-number resets with ambiguous old keys, variant-specific checkpoints without independently reviewed source mappings, and exact decimals requiring guessed provenance. A `Ready` review is **never** a migration permission.

## Why the server must own the epoch

The account now has a server-owned epoch-1 ledger and a downgrade write fence, but still has no trusted per-device compatibility proof or approved cutover. Without the full transaction, new clients could otherwise write v2 while old clients continue modifying v1, splitting history. An isolated namespace or client feature flag cannot solve this.

A future activation design **must** require all of the following, in this order, before shipping:

1. **Server-owned account epoch and capabilities.** Authenticate the account; register device/client protocol capabilities with a bounded lifetime and server timestamps, not editable user metadata or caller-supplied "approved" booleans. The server must report which epoch it will accept. An unknown device, expired capability or conflicting active device must block activation.
2. **Read-only inventory.** Read a stable v1 snapshot, its cursor, accepted-base frontier and pending mutation state. Enumerate every remote checkpoint and the full canonical inventory, including untracked same-number siblings and ambiguous fractions. Never infer `12.05` from a legacy `12.5` integer part.
3. **Reviewed mapping.** Resolve every v1 remote checkpoint to exactly one evidence-backed v2 key and vice versa. Record whether remote tombstones, legacy variants, absent chapters and previously synchronized downloads/history need explicit human review. Refuse incomplete or ambiguous mapping; never silently skip a checkpoint.
4. **Atomic server cutover.** Serialize per account with a server-side transaction/lock and compare-and-swap on the expected v1 cursor and protocol epoch. Ensure no pending mutation can be replayed across epochs. Materialize approved v2 state, store provenance and a durable migration receipt, and change the server epoch atomically. No provisional dual-write period is allowed.
5. **Old-client downgrade fence.** After cutover, **reject legacy `READING_PROGRESS` mutation requests server-side**, including retries and idempotent replays; do not silently accept v1 writes that the new client cannot see. Other document domains may continue unaffected where their schemas remain compatible.
6. **Explicit failure/rollback.** A failed cutover must leave v1 authoritative. Any rollback after successful cutover needs an independently reviewed export/reconciliation and an epoch transition; changing a preference or installing an older APK must not silently roll back remote progress.

Parts of step 1 (account epoch ledger and observational v1 device leases) and step 5 (a pre-activation downgrade fence) are implemented. The rest, especially a trusted cross-device handshake and atomic cutover, remains missing. **None of steps 1–6 is approved for live migration or v2 activation.**

## Test and sign-off gates

| Gate | Current evidence | Status |
|---|---|---|
| Local v2 protocol refusal | Domain regressions for direct and background sync | Automated |
| Remote v2 refusal; no idempotency/events leaked | pgTAP namespace and private-implementation privilege regressions | Await exact-head CI |
| Legacy v1 unchanged | pgTAP original conflict/RLS/replay tests and HTTP transport tests | Await exact-head CI |
| Capability discovery | Authenticated server RPC, strict client decode, malformed/future-status tests | Await exact-head CI |
| Offline legacy→v2 mapping preview | Pure domain test cases for valid legacy, forged/missing, unnumbered evidence, source-variant checkpoints, decimal and volume collisions | Await exact-head CI |
| Real account migration with historical keys | Requires signed-off server epoch transition and real remote snapshots | **Not run / not enabled** |
| Older-client write fence and post-cutover downgrade | Pre-activation epoch1 gate rejects hypothetical epoch2 v1 writes; no live cutover or downgrade acceptance | **Foundation automated; live transition blocked** |
| Android physical reader and user backup preservation | Separate device acceptance; do not duplicate PR #141 backup work | **Not run here** |

No tests may use private user snapshots in public CI. Keep PR #140 in **draft**, do not merge or enable v2 until the remaining gates are independently approved.

## Account epoch ledger and fail-closed downgrade foundation (pre-activation)

- A new private, account-scoped protocol ledger is enrolled lazily at epoch 1 by the legacy progress RPC only. Discovery remains read-only and does not create a ledger row. No client has direct table privileges or an epoch-switching API.
- Legacy `READING_PROGRESS` writes hold a per-account `FOR SHARE` row lock through the underlying v1 mutation, and reject every epoch other than 1 **before idempotency/replay processing**. A future cutover must use `FOR UPDATE` on this exact row in the same atomic transaction as its cursor CAS, migration receipt and v2 state materialization.
- The v2 write namespace remains unconditionally rejected. An artificially elevated account epoch causes existing clients' protocol discovery to fail closed, rather than falsely advertising v1 readiness. Library and other compatible sync domains remain unchanged.
- pgTAP simulates a privileged epoch transition for one synthetic account: it verifies rejected new v1 writes, rejected previously accepted replay, rejected v2 writes, unchanged checkpoints/receipts, private ledger permissions and unaffected second account. **A simulated epoch is not a real cutover**; the project now has observational v1 device registration and bounded leases, but still lacks trusted capability proof, migration/backup, atomic state conversion and physical mixed-version validation.
 
## Device-presence registry — server-owned v1 foundation (still pre-activation)

- A private per-account registry now records a hash of each registered client's persisted UUID, first/last server timestamps and a **30-day server-set lease**. Direct table reads/writes are unavailable to authenticated clients.
- `sync_register_progress_device(p_client_id)` is an authenticated **v1-only** heartbeat. It acquires the same account-epoch row lock as a progress mutation, refuses epoch != 1 and always returns `migrationReady=false` / `exactProgressEnabled=false`. It does not accept client-advertised v2 capability flags, authorize activation, or write reading progress.
- The HTTP transport has an explicit opt-in `registerLegacyProgressDevice` method and strict response decoder; it is **not wired into ordinary production sync** until the server migration is deployed. This is intentionally not a negotiated rollout.
- **Critical caveat:** device IDs are self-asserted by an authenticated client, not hardware attestation; a registry without prior historical observations cannot prove there are no offline/old clients. A future rollout must implement a separate approved activation/consent policy, bind client/build capabilities, back up the remote frontier, and apply a real atomic migration with old-client write fencing. Expiring a lease alone must never authorize cutover.

## Read-only server-observed progress preflight (still not a cutover)

- A separate authenticated `sync_preview_progress_rollout()` returns account-scoped v1 `legacyRecordCount`, `legacyTombstoneCount`, `legacyEventCursor`, registered device count and unexpired **observed v1** lease count. The function is `SECURITY DEFINER` because the epoch/device ledgers are private; it discloses only aggregates, not IDs or checkpoint content. It does not create a ledger row or modify progress, even if no devices have been enrolled.
- Every response explicitly states `migrationReady=false`, `exactProgressEnabled=false`, `historicalDeviceCoverageProven=false` and `pendingLocalWritesObservable=false`. A cursor is a point-in-time observation, **not a frozen frontier**. A zero count does **not** prove any offline device or pending local write is absent.
- The Android HTTP method `previewLegacyProgressRollout()` is manual/opt-in and strictly validates v1 epochs, nonnegative coherent counts and all four fail-closed flags. It is not wired into production sync or treated as approval to activate v2. pgTAP tests verify account isolation, tombstones, accepted cursor, no enrollment on reads, anon refusal and simulated epoch2 refusal.
- The future server-side cutover still needs trusted client compatibility, human approval, local pending write reconciliation, complete canonical mapping, backup, an atomic cursor-CAS migration receipt and physical mixed-version testing.

## Device-registry bounds and monotonicity (pre-activation)

- A subsequent forward-only migration makes enrollment acquire `FOR UPDATE` on the same account epoch ledger (serializing concurrent distinct UUIDs), enforces a **64 distinct observed-installations-per-account limit**, and never prunes expired records to create a false impression that historical clients disappeared. Re-registrations of existing IDs remain possible at the limit. This is operational abuse protection, **not** an active-device eligibility threshold.
- Successful heartbeat renewals now preserve monotonic `last_seen_at` and `lease_expires_at` using `greatest`, so overlapping calls cannot make a previously observed lease older. The existing v1-only epoch and no-activation invariants remain intact; no production scheduler calls this endpoint.
- pgTAP covers full registry, 65th rejection, same-device renewal at cap, monotonic last-seen and lease, and account isolation. A synthetic 64-device cap is not evidence that all devices are known and must never authorize cutover.

## Old-client READ downgrade fence (pre-activation)

- The epoch read fence now covers `sync_pull_delta(READING_PROGRESS)`, `sync_get_cursor(READING_PROGRESS)` and `sync_snapshot_domain(READING_PROGRESS)`, not only writes. A hypothetical epoch2 account must not let an old client import stale v1 checkpoints locally before its next write fails.
- Each protected reader calls account-scoped `sync_get_progress_protocol()` before its legacy query. It takes `FOR SHARE` on existing ledger rows; a future approved cutover must take `FOR UPDATE` inside the same transaction as cursor CAS and migration receipt.
- Read operations for `LIBRARY` and other compatible domains retain previous behavior. Unknown/un-enrolled accounts still read v1 without creating ledger rows. pgTAP covers epoch2 refusal across all three readers, other-account isolation and anon denial.
- The active epoch remains 1. Neither migration authorization, data copying nor v2 activation is introduced.

## Old-client progress conflict fence (pre-activation)

- Even without progress writes, historical conflict metadata/acknowledgment can mutate legacy sync bookkeeping. Account-epoch protection therefore covers `sync_get_mutation_conflicts` when its scoped mutation contains a `READING_PROGRESS` conflict, and `sync_ack_conflict` when the scoped conflict belongs to `READING_PROGRESS`.
- These RPCs consult the same authenticated epoch reader and its ledger `FOR SHARE` lock before returning or changing v1 conflict state. `LIBRARY` conflict resolution and the other account's v1 progress remain available. Tests use synthetic conflicting account records and simulate epoch2; no real migration or data rewrite occurs.

## Unnegotiated v2 read gate and preflight hazard counters — still pre-activation

- The exact-progress namespace `READING_PROGRESS_V2` is now rejected on **all three read RPCs** (`sync_pull_delta`, `sync_get_cursor`, `sync_snapshot_domain`) even for epoch1 accounts and for artificially elevated accounts. This complements the unconditional v2 write refusal. The read gate rejects before looking up stored v2 rows; legacy v1 and `LIBRARY` reads remain unchanged. No client can interpret a domain name or manually altered epoch as an activation token.
- The read-only v1 preflight additionally reports `legacyIncompleteRecordCount`: active legacy progress records missing any one of the required stored fields (`canonicalTitleId`, `chapterKey`, `read`, `lastPageRead`), excluding soft-deleted records which remain separately counted as tombstones. It reports `unresolvedProgressConflictCount` for outstanding *same-account* legacy-progress conflicts; unrelated domains and accounts do not contribute.
- These are **server-observable risk indicators, not complete proof of validity**. The presence of required fields does not establish correct JSON values, stable canonical mapping, accepted-base equivalence, remote mutation receipt completeness or the absence of pending writes on disconnected devices. The preflight still always returns `historicalDeviceCoverageProven=false`, `pendingLocalWritesObservable=false`, `exactProgressEnabled=false`, and `migrationReady=false`.
- Android's explicit, not-wired `previewLegacyProgressRollout()` accepts only coherent nonnegative counts (incomplete active records cannot exceed non-tombstones), and refuses incomplete older responses. The older v1 production synchronization path does not invoke this optional preflight. 23 synthetic pgTAP assertions and added HTTP decoder regressions cover these boundaries.

## Transport-layer exact progress defense (pre-activation)

- The runtime orchestrator, SQL writer and SQL readers already reject v2; as an independent in-process safeguard, the concrete Android `SupabaseSyncHttpTransport` also refuses `READING_PROGRESS_V2` on direct `pullDelta`, `snapshot` and `push` **before building or sending any network request**. Caller-provided mutation domains are trimmed before comparing with the unnegotiated v2 namespace.
- This is a hard-coded pre-activation gate, not a toggle, persisted approval or a new protocol handshake. The rule must only be reconsidered with reviewed server epoch coordination, compatible remote conversion and old-client recovery. Legacy v1/library traffic is unchanged.
- Three MockWebServer tests assert failure with `UNSUPPORTED_SCHEMA` and **zero HTTP requests**. The transport does not rely on the server's `0A000` response to be safe; backend guards remain necessary for older clients.

## Malformed remote v1 values — extra preflight evidence (still non-authorizing)

- The authenticated `sync_preview_progress_rollout()` now also returns `legacyInvalidValueRecordCount`: the number of active v1 progress records with at least one present but invalid known field value. This includes blank/non-string canonical title and chapter keys, non-boolean `read`, negative, fractional or out-of-signed-64-bit-range `lastPageRead`, and non-string/non-null `lastVariantEvidenceKey`. Deleted records are not included. It is a **per-record** count, not a count of fields; a record can contribute to both this counter and `legacyIncompleteRecordCount`.
- The Android optional/manual preflight requires the new counter and rejects negative counts or counts greater than the number of active records. An older backend response missing the field is rejected rather than interpreted as a clean inventory. This preview is **not wired into normal sync**; production v1 transport is unaffected.
- Even zero incomplete, invalid and conflict counts **cannot** establish a safe remote mapping: identifier lineage, title-sibling collisions, offline outboxes, current frontier stability, device compatibility, server-side atomic cursor CAS, durable migration receipt and physical acceptance are still unproven. Flags for historical coverage, offline writes, exact progress and migration readiness remain false. No activation or migration endpoint exists.
- Regressions: synthetic pgTAP tests in `tsuzuki_sync_progress_preflight_invalid_values.sql`, strict Android MockWebServer response tests, and the existing v1/v2 refusal suites. Exact-head CI remains the certification gate.

## Remote progress import type safety — v1 and experimental v2

- The local `CanonicalReadingProgressSyncAdapter.applyDocument` is a separate trust boundary from the read-only server preflight. It now refuses non-string or blank `canonicalTitleId` and `chapterKey`, quoted/not-boolean `read`, string/fractional/negative/out-of-range `lastPageRead`, and non-string or blank `lastVariantEvidenceKey` values (JSON null remains allowed).
- Validate **all records before invoking** the single transactional `upsertProgressBatch`. Invalid later records must not partially overwrite earlier checkpoints. The existing key/record ID matching and exact chapter uniqueness checks remain unchanged. Both schema-1 v1 and the isolated experimental schema-2 decoder obey the same validation; this change does not install the v2 adapter, open v2 RPCs or authorize cutover.
- Focused tests intentionally reproduced two failures with the prior importer in [CI RED #40090195115](https://github.com/jssantogit/tsuzuki/actions/runs/40090195115) (875 Domain tests, exactly two new failures, other 873 passed). Code and test formatting are under follow-up CI. A successful test does not establish real-device acceptance, cross-account mapping or migration readiness.
