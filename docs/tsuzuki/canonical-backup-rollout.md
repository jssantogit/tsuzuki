# Tsuzuki canonical backup rollout

Status: schema/validation + core exporter only, in draft PR #141. The production .tachibk writer and restorer are NOT connected to these new components yet. The existing legacy Mihon backup does NOT include the canonical Tsuzuki library; do not describe it as complete.

## Backward compatibility

Optional protobuf field 107 (Backup.canonicalSnapshot) is absent in legacy archives. Sections have independent version keys, SHA-256 checksums, JSON validation, and core graph referential checks. Checksums identify corruption, not a trusted author.

## Core graph already modeled/exportable

- All canonical titles, identities (including unverified), source mappings, library entries
- Canonical chapters (including provisional state), source chapter variants and raw metadata
- Chapter progress, last variant/page and aggregate reading history

The exporter deliberately does not apply cloud-sync portability filters. A backup must preserve local-only chapters even when remote sync would omit them.

## Gates before enabling writer and restore

1. Read a transaction-consistent snapshot; validate it and independently reopen the written backup.
2. Implement atomic, idempotent import in SQLite foreign-key order (titles, source mappings, chapters, variants, library, progress, history).
3. Preserve EXACT reading duration on restore: the normal history operation is additive, so it cannot safely restore a snapshot.
4. Cover remaining durable canonical stores: collections, preferences, provider/content bindings, source observations, overrides, reader preferences and update states.
5. Do not export authenticated secrets, or replay another account's outbox/cursors/pending mutations during recovery.
6. Prove legacy format compatibility, corrupt archive rejection, interrupted restore rollback, missing extensions, two-account isolation and an actual Android clean-install roundtrip.

An offline file path does not imply the downloaded file's bytes are in a backup. State that limitation explicitly.

Related: account-scoped-sync-recovery.md and resolver PR #140.
