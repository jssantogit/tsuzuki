# Tsuzuki canonical backup rollout

Status: schema/validation + transactional core exporter and importer, in draft PR #141. The production .tachibk writer and restorer are NOT connected to these new components yet. The existing legacy Mihon backup does NOT include the canonical Tsuzuki library; do not describe it as complete.

## Backward compatibility

Optional protobuf field 107 (Backup.canonicalSnapshot) is absent in legacy archives. Sections have independent version keys, SHA-256 checksums, JSON validation, and core graph referential checks. Checksums identify corruption, not a trusted author.

## Core graph already modeled/exportable

- All canonical titles, identities (including unverified), source mappings, library entries
- Canonical chapters (including provisional state), source chapter variants and raw metadata
- Chapter progress, last variant/page and aggregate reading history
- Collections including soft-deleted records, nested folders and query-driven lists
- Provider work/facet bindings including verification and availability
- Reader automatic-fallback preferences and durable chapter repair overrides
- Chapter acknowledgement/update flags and Continue Reading visibility
- Per-title content language/add-on preference and language-specific source priorities
- Title artwork, name/alias and format observations from every provider
- Canonical library category membership by portable name/order/flags; old numeric category IDs are only provenance, and duplicate names with different identities fail closed
- Integration enabled state and non-secret configuration. The shared recursive sanitizer removes credentials, tokens, passwords, cookies and related sensitive keys; restore refuses secret-bearing JSON

The exporter deliberately does not apply cloud-sync portability filters. A backup must preserve local-only chapters even when remote sync would omit them.

## Gates before enabling writer and restore

1. Export under a transaction-consistent SQLite snapshot and independently reopen/validate the eventual written archive. Core export currently uses one transaction, but the production writer is not yet wired.
2. Core restore is implemented atomically and idempotently in SQLite foreign-key order, but a clean install may already contain system collections or source priority defaults; introduce explicit safe reconciliation rather than overwriting or clearing them. Category reuse is allowed only for one unambiguous matching name/order/flags and the built-in row zero.
3. Core restore sets the EXACT reading duration rather than adding it; keep this guarantee during complete end-to-end restore.
4. Cover remaining durable records: content delivery binding runtime payload (currently opaque, may contain device-local IDs or secrets); external list memberships/account provenance; integration metadata snapshots if not recomputable; other user-owned preferences or evidence required to preserve chapter identities. Never silently serialize opaque secrets. Support missing extensions and explicit rebinding instead of reusing foreign local IDs.
5. Do not export authenticated secrets or blindly restore cross-account linked membership; never replay another account's outbox/cursors/pending mutations during recovery. For new-device restore, imported Mihon numeric row IDs are deliberately cleared and require reconciliation.
6. Prove legacy format compatibility, corrupt archive rejection, interrupted restore rollback, missing extensions, two-account isolation and an actual Android clean-install roundtrip.

An offline file path does not imply the downloaded file's bytes are in a backup. State that limitation explicitly.

Related: account-scoped-sync-recovery.md and resolver PR #140.
