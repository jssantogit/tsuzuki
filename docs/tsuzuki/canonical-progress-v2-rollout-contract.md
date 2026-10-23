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

There is currently no authoritative per-account/per-device protocol epoch. New clients could otherwise write v2 while old clients continue modifying v1, leading to two authoritative histories. An isolated namespace or client feature flag cannot solve this.

A future activation design **must** require all of the following, in this order, before shipping:

1. **Server-owned account epoch and capabilities.** Authenticate the account; register device/client protocol capabilities with a bounded lifetime and server timestamps, not editable user metadata or caller-supplied "approved" booleans. The server must report which epoch it will accept. An unknown device, expired capability or conflicting active device must block activation.
2. **Read-only inventory.** Read a stable v1 snapshot, its cursor, accepted-base frontier and pending mutation state. Enumerate every remote checkpoint and the full canonical inventory, including untracked same-number siblings and ambiguous fractions. Never infer `12.05` from a legacy `12.5` integer part.
3. **Reviewed mapping.** Resolve every v1 remote checkpoint to exactly one evidence-backed v2 key and vice versa. Record whether remote tombstones, legacy variants, absent chapters and previously synchronized downloads/history need explicit human review. Refuse incomplete or ambiguous mapping; never silently skip a checkpoint.
4. **Atomic server cutover.** Serialize per account with a server-side transaction/lock and compare-and-swap on the expected v1 cursor and protocol epoch. Ensure no pending mutation can be replayed across epochs. Materialize approved v2 state, store provenance and a durable migration receipt, and change the server epoch atomically. No provisional dual-write period is allowed.
5. **Old-client downgrade fence.** After cutover, **reject legacy `READING_PROGRESS` mutation requests server-side**, including retries and idempotent replays; do not silently accept v1 writes that the new client cannot see. Other document domains may continue unaffected where their schemas remain compatible.
6. **Explicit failure/rollback.** A failed cutover must leave v1 authoritative. Any rollback after successful cutover needs an independently reviewed export/reconciliation and an epoch transition; changing a preference or installing an older APK must not silently roll back remote progress.

None of steps 1–6 is implemented or approved for live use by this PR.

## Test and sign-off gates

| Gate | Current evidence | Status |
|---|---|---|
| Local v2 protocol refusal | Domain regressions for direct and background sync | Automated |
| Remote v2 refusal; no idempotency/events leaked | pgTAP namespace and private-implementation privilege regressions | Await exact-head CI |
| Legacy v1 unchanged | pgTAP original conflict/RLS/replay tests and HTTP transport tests | Await exact-head CI |
| Capability discovery | Authenticated server RPC, strict client decode, malformed/future-status tests | Await exact-head CI |
| Offline legacy→v2 mapping preview | Pure domain test cases for valid legacy, forged/missing, unnumbered evidence, source-variant checkpoints, decimal and volume collisions | Await exact-head CI |
| Real account migration with historical keys | Requires signed-off server epoch transition and real remote snapshots | **Not run / not enabled** |
| Older-client write fence and post-cutover downgrade | Requires future account-epoch server implementation | **Not implemented** |
| Android physical reader and user backup preservation | Separate device acceptance; do not duplicate PR #141 backup work | **Not run here** |

No tests may use private user snapshots in public CI. Keep PR #140 in **draft**, do not merge or enable v2 until the remaining gates are independently approved.

## Account epoch ledger and fail-closed downgrade foundation (pre-activation)

- A new private, account-scoped protocol ledger is enrolled lazily at epoch 1 by the legacy progress RPC only. Discovery remains read-only and does not create a ledger row. No client has direct table privileges or an epoch-switching API.
- Legacy `READING_PROGRESS` writes hold a per-account `FOR SHARE` row lock through the underlying v1 mutation, and reject every epoch other than 1 **before idempotency/replay processing**. A future cutover must use `FOR UPDATE` on this exact row in the same atomic transaction as its cursor CAS, migration receipt and v2 state materialization.
- The v2 write namespace remains unconditionally rejected. An artificially elevated account epoch causes existing clients' protocol discovery to fail closed, rather than falsely advertising v1 readiness. Library and other compatible sync domains remain unchanged.
- pgTAP simulates a privileged epoch transition for one synthetic account: it verifies rejected new v1 writes, rejected previously accepted replay, rejected v2 writes, unchanged checkpoints/receipts, private ledger permissions and unaffected second account. **A simulated epoch is not a real cutover**; the project still lacks device registration, capability leases, migration/backup, atomic state conversion and physical mixed-version validation.
 
## Device-presence registry — server-owned v1 foundation (still pre-activation)

- A private per-account registry now records a hash of each registered client's persisted UUID, first/last server timestamps and a **30-day server-set lease**. Direct table reads/writes are unavailable to authenticated clients.
- `sync_register_progress_device(p_client_id)` is an authenticated **v1-only** heartbeat. It acquires the same account-epoch row lock as a progress mutation, refuses epoch != 1 and always returns `migrationReady=false` / `exactProgressEnabled=false`. It does not accept client-advertised v2 capability flags, authorize activation, or write reading progress.
- The HTTP transport has an explicit opt-in `registerLegacyProgressDevice` method and strict response decoder; it is **not wired into ordinary production sync** until the server migration is deployed. This is intentionally not a negotiated rollout.
- **Critical caveat:** device IDs are self-asserted by an authenticated client, not hardware attestation; a registry without prior historical observations cannot prove there are no offline/old clients. A future rollout must implement a separate approved activation/consent policy, bind client/build capabilities, back up the remote frontier, and apply a real atomic migration with old-client write fencing. Expiring a lease alone must never authorize cutover.
