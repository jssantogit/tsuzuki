# Canonical progress v2 — coordinated rollout contract (pre-activation)

Status: **defensive foundation in draft PR #140; no production activation**.
This contract documents verifiable barriers, not permission to ship a migration.

## Validated current behavior

- Production canonical progress uses `READING_PROGRESS` and schema 1. Its keys, accepted frontiers, pending mutations, account state and local progress are not migrated.
- The optional exact-numbering codec uses different `READING_PROGRESS_V2` record IDs; its isolation prevents collisions but **does not** synchronize v1 and v2 clients.
- Both direct and background `SupabaseSyncOrchestrator` entry points reject `READING_PROGRESS_V2` with `UNSUPPORTED_SCHEMA` before remote traffic for that domain. The opt-in adapter must not be installed in production composition.
- A forward-only Supabase migration moves the previously exposed `sync_apply_mutation_batch(jsonb)` body unchanged to the non-exposed `tsuzuki_private` schema. Only its authenticated public wrapper may call it. The wrapper rejects v2 **before** the old idempotency or mutation logic and normalizes the domain exactly like the old RPC.
- Authenticated `sync_get_progress_protocol()` is **read-only**, returning an epoch-1, legacy-only capability response: `schemaVersion=1`, `protocolEpoch=1`, `activeDomain=READING_PROGRESS`, `exactProgressEnabled=false`, `migrationReady=false`. The client decoder accepts only that combination. It does not authorize an epoch transition, and no activation RPC exists.
- `PreviewCanonicalProgressV2Migration` inspects a remote **v1** progress snapshot against *all* locally known title siblings and optional stable evidence. It produces an old-ID → new-ID **review mapping only**, without copying, deleting or importing any progress. It rejects malformed/tombstone records, wrong document versions, unmatched keys, volume-number resets with ambiguous old keys and exact decimals requiring guessed provenance. A `Ready` review is **never** a migration permission.

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
| Offline legacy→v2 mapping preview | Pure domain test cases for valid legacy, forged/missing, unnumbered evidence, decimal and volume collisions | Await exact-head CI |
| Real account migration with historical keys | Requires signed-off server epoch transition and real remote snapshots | **Not run / not enabled** |
| Older-client write fence and post-cutover downgrade | Requires future account-epoch server implementation | **Not implemented** |
| Android physical reader and user backup preservation | Separate device acceptance; do not duplicate PR #141 backup work | **Not run here** |

No tests may use private user snapshots in public CI. Keep PR #140 in **draft**, do not merge or enable v2 until the remaining gates are independently approved.
