# Tsuzuki canonical backup rollout

Status: schema/validation + transactional core exporter and importer, in draft PR #141. The production .tachibk writer and restorer are NOT connected to these new components yet. The existing legacy Mihon backup does NOT include the canonical Tsuzuki library; do not describe it as complete.

## Resource safety for existing backup files

Automatic backups now use timestamped filenames with a random 128-bit suffix,
so retries within one minute cannot reuse the prior archive path. Retention
continues to recognize legacy minute-only filenames and newer unique filenames.
This reduces accidental overwrite/deletion of previously validated automatic
backups. It does not promise filesystem-level atomicity for every SAF provider.

**Verified-only automatic retention:** Before pruning older copies, the writer
now reopens and validates each sibling backup through the real bounded
decoder/validator. Matching filenames alone cannot certify completed gzip
writes, including archives currently being written by another attempt.
Unreadable, corrupt, or still-in-progress archives are excluded from the
retirable set AND from the count of verified copies, and are preserved.
An older attempt also cannot retire an archive whose filename is newer than
its own validated output. To bound repeated large-file decoding, retention
skips all pruning when the matching directory listing has more than 16
candidates; manual cleanup may be needed in that case. Unknown, duplicate
or temporarily inaccessible file metadata also requires conservative
handling; these guards do not supply cross-process SAF atomicity or make
the retention listing a transactional snapshot. Cancellation is propagated,
not mistaken for an archive validation failure.

The legacy `.tachibk` decoder now reads both plain and gzip archives with a
256 MiB **uncompressed** size ceiling before protobuf decoding. This avoids
unbounded gzip expansion in the application process. Oversized files fail
without partial restore; they are not truncated or silently accepted.
The writer applies the same cap before opening the destination output stream,
so it does not overwrite a manual backup with a newly generated archive that
the decoder would refuse.
Failure cleanup removes **only** an output created by that automatic
backup attempt which **has not yet passed archive validation**. Once validated,
the automatic archive is retained even if later retention or persistence of the
last-backup timestamp fails; the catch block must not erase the only newly
verified copy after older archives may already have been pruned. A caller-
supplied manual destination is never deleted.
This prevents loss of a preexisting manual backup on failures that occur
before opening the destination. Manual backups launched via `ACTION_CREATE_DOCUMENT` now stage the entire
gzip archive in app-private cache, decode/validate that staged file, and check
that the selected SAF document is readable and **empty** before ever opening
its output stream. A populated or unreadable manual destination is refused,
not truncated or deleted. After copying, the final SAF URI is independently
decoded and validated. Private staging files are removed on ordinary success,
failure and cancellation.

This is **not** an atomic rename/replace and does not protect against a
malicious/racy provider changing the document between the empty check and
the write. A crash or storage failure during the final copy may leave the
**newly created** document incomplete; no valid-backup success is reported.
SAF cannot promise universally atomic replacement of an existing file, so
existing nonempty manual files must never be accepted as overwrite targets.
Android physical tests (including process death, constrained storage and
cloud-backed SAF providers) remain a pre-release requirement. Cache files
left by abrupt process termination may persist until cache cleanup.
Because protobuf parsing still requires resident memory below this ceiling,
on-device large-file tests remain an acceptance requirement. This size bound
is not a checksum or authentication mechanism.

## Backward compatibility

Optional protobuf field 107 (Backup.canonicalSnapshot) is absent in legacy archives. Sections have independent version keys, SHA-256 checksums, JSON validation, and core graph referential checks. Checksums identify corruption, not a trusted author.

**Unsupported canonical-archive restore fails closed:** The current production
`BackupRestorer` handles legacy Mihon records only. It now refuses every
archive declaring `canonicalSnapshot` **before performing legacy mutations**,
including an empty snapshot, until an end-to-end verified canonical
writer/importer pair ships. Otherwise a purportedly successful restore could
silently discard Tsuzuki's canonical books, progress and collections. Archives
without field 107 remain on the normal legacy restore path.

## Core graph already modeled/exportable

- All canonical titles, identities (including unverified), source mappings, library entries
- Canonical chapters (including provisional state), source chapter variants and raw metadata
- Producer chapter evidence: observation origin, authority, raw label/number and canonical chapter mapping, including unmapped evidence
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

**Fail-closed coverage guard:** the core exporter continues to refuse unsupported opaque provider runtime BLOBs, nonempty third-party chapter evidence metadata BLOBs, and account-linked external library memberships. Unknown formats or untrusted data cannot silently disappear from a purportedly complete archive. The recognized legacy Mihon content-binding **v1** payload has a dedicated typed portable representation: it retains addon/source identity, source URL/language, confidence, verification, and original availability, but **never** exports the Mihon manga row ID or raw BLOB. Its export rejects inconsistent provider keys, unsupported versions, URLs with query strings/userinfo/fragments, malformed URLs, and non-HTTPS absolute schemes. Such unsafe bindings continue to block export rather than being dropped. This conservative policy may reject some otherwise readable extension URLs; it is intentional until an extension-specific portable contract exists.

On import, Mihon content bindings are recreated **dormant** (`availability=UNAVAILABLE`) with an intentionally invalid local ID (`mihonMangaId=-1`). They preserve the identity needed for later manual re-link but **must not be treated as operational or readable** until the installed, eligible source materializes a new local record. Subsequent backups may carry these dormant bindings, but they cannot resurrect the original device's operational row ID. Provider-native SCRIPT runtime BLOBs remain unsupported, not implicitly interpreted as Mihon payloads. External memberships must still retain owner/account provenance and cannot be imported until an isolated, user-consented recovery contract exists.

## Gates before enabling writer and restore

1. Export under a transaction-consistent SQLite snapshot and independently reopen/validate the eventual written archive. Core export currently uses one transaction, but the production writer is not yet wired.
2. Core restore is implemented atomically and idempotently in SQLite foreign-key order. An already-present collection can be reused **only** when it has `SYSTEM` origin, its archived entry exists with the same ID and every persisted metadata field identical, and it has no local folders or lists. It is not upserted. An unmatched, modified, user-owned, or locally populated collection fails closed before any canonical writes. Equivalent local source priority defaults can be reused without changes; conflicting priorities fail closed. Category reuse is allowed only for one unambiguous matching name/order/flags and the built-in row zero. A freshly initialized collection with different timestamps or auto-created child folders still needs a separate explicit import UX/reconciliation contract: do not delete it automatically.
3. Core restore sets the EXACT reading duration rather than adding it; keep this guarantee during complete end-to-end restore.
4. Cover remaining durable records: the typed legacy Mihon content-binding v1 codec is now modeled (with dormant import), but other provider/SCRIPT delivery bindings are still opaque and blocked; external list memberships/account provenance, integration metadata snapshots if not recomputable, and other evidence/preferences still need dedicated representations. Never silently serialize opaque secrets. Support missing extensions and explicit rebinding instead of reusing foreign local IDs.
5. Do not export authenticated secrets or blindly restore cross-account linked membership; never replay another account's outbox/cursors/pending mutations during recovery. For new-device restore, imported Mihon numeric row IDs are deliberately cleared and require reconciliation.
6. Prove legacy format compatibility, corrupt archive rejection, interrupted restore rollback, missing extensions, two-account isolation and an actual Android clean-install roundtrip.

An offline file path does not imply the downloaded file's bytes are in a backup. State that limitation explicitly.

Related: account-scoped-sync-recovery.md and resolver PR #140.
