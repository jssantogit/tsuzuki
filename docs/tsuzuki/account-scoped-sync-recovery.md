# Safe migration of unowned legacy cloud state — draft execution contract

**Status:** PR #141 implements a strict pause and safe read-only diagnostics. It
does **not** yet provide a user-approved legacy migration or resolve the original
owner. Do not merge as a completed account-switching feature.

## Why automatic owner adoption is forbidden

The pre-account-scope database indexes cursor, pending mutation batch, accepted
base, outbox and conflicts only by `SyncDocumentKind`, not Supabase `auth.uid()`.
A saved cursor or mutation ID cannot prove which user owns it. In particular:
- A device can sign out of account A and authenticate as B without clearing the
  installation-local state.
- A pending batch may contain canonical library/progress data from A; retrying it
  as B would send A's data to B's cloud.
- An accepted remote base may have A's title IDs and revisions. Applying B's
  delta over that base may corrupt local data.
- A dirty outbox **without** a cursor is also ambiguous; ignoring it allows
  uploading unsent A-origin local edits when B signs in.
- A new bearer token for B proves only B's authorization, **not** ownership of
  A's local state. Comparing a cloud snapshot or email string cannot reliably
  establish the owner of arbitrary unscoped local edits.

## Already implemented in PR #141

1. A durable SHA-256 owner marker under app-private `noBackupFilesDir`
   authorizes a single account for existing installation-local cloud state.
2. Before initial enrollment, evaluate all Supabase durable document kinds for
   cursor, pending batch, accepted base, conflict **and outbox**.
3. If any unscoped state exists without a reliable owner marker, refuse to
   adopt the current account. Never delete, reset, replay or upload that state.
4. If the owner does not match, refuse every authenticated sync RPC, normal
   reconciliation and conflict resolution. UI diagnostics are hidden from the
   mismatching account; no conflicted records may be resolved from that screen.
5. Local reading/library operations remain usable; there is no automatic
   conversion and no migration flag or destructive button.

## Prerequisites for enabling recovery

Before adding an 'Adopt local library' or 'Switch cloud account' button:
- Implement and verify a versioned **canonical Tsuzuki backup**, including
  titles, library, chapter inventory/overrides, reading checkpoints, collections,
  integrations, preferences, provider bindings, and offline metadata as
  applicable. Test restore in clean install, legacy restore compatibility and
  corrupted/incomplete archive rejection.
- Store cloud account ownership in **all** durable sync state, not merely one
  installation marker, with an online-safe schema migration that quarantines
  legacy unowned rows (and preserves original bytes on disk).
- Separate the user-specific sync state from globally local canonical content;
  preserve local IDs and progress while presenting an explicit import/copy
  choice to the current account, with privacy warning and rollback path.
- Do not claim ownership from an authenticated token alone. For any upload of
  legacy local records, require explicit user opt-in after a verified backup.
  Import into a fresh B-owned outbox and use B's own remote cursor; NEVER replay
  old mutation IDs or accepted bases from A into B.
- Prevent account changes while a cycle is applying local merges or serialize
  the cycle by authenticated identity and abort when auth identity changes.
- Verify two dummy accounts (A/B), offline edits, process death, device restore,
  remote conflicts, and interrupted migrations in Android instrumentation and
  server-backed tests.

## Safe outcomes

- **New empty install**: bind to first authenticated account, sync normally.
- **Same owner marker**: continue same-account sync; an unrelated account
  cannot consume the stored pending batch or conflict records.
- **Different account**: sync paused, user-local library still accessible;
  diagnostics and conflict actions are not attributed to B.
- **Legacy unowned or corrupt marker**: sync paused, all data preserved.
  No guessed owner and no hidden reset. Recovery awaits prerequisites above.

**Related:** [PR #141](https://github.com/jssantogit/tsuzuki/pull/141),
[canonical resolver PR #140](https://github.com/jssantogit/tsuzuki/pull/140).
