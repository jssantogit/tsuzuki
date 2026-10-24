# External library memberships — owner-safe portability contract (draft)

**Status:** Audit PR #141 design and audit evidence only. No schema migration, account cutover, backup import, or user-facing recovery is enabled by this document. The production canonical backup writer/restorer remain disconnected.

## Confirmed current boundary

`tsuzuki_external_library_memberships` is keyed by `(provider, external_id, list_key)` and does **not** retain the authenticated external account identity that produced the membership. The domain `ExternalLibraryMembership` likewise has no owner field. `ExternalLibraryRepositoryImpl.replaceProvider(provider, memberships)` deletes all existing rows for that provider before inserting the new snapshot, while `observeAll()` returns every row with no account filter. Thus a successful login to a different account under the **same provider** cannot establish that persisted rows belong to the new user. The current canonical exporter correctly refuses backup when any external membership exists.

Do not treat the provider name, display name, integration toggle, local canonical ID, token value, email string, or time of last sync as reliable ownership evidence.

## Required invariants before account-scoped memberships

1. Classify each membership source explicitly: `PUBLIC` non-authenticated discovery, or `ACCOUNT` data linked to a verified remote account. This classification must come from provider identity capabilities, not heuristics. Account-linked records need a stable provider-issued subject identifier verified by a fresh authenticated identity request, and a local owner key that cannot be silently adopted by another login. Never serialize bearer tokens, refresh tokens or cookies as provenance.
2. Persist owner scope *with each durable membership*. Reads, observed UI lists, import/reconciliation, replacement and deletion must all be keyed by `(provider, owner_scope)`. The index/uniqueness constraint must include owner scope. Switching accounts cannot surface, overwrite, delete, or reinterpret another owner's records.
3. Treat pre-migration rows as `UNOWNED_LEGACY`. Quarantine them from authenticated current-account views and writes. Never infer owner from the current session; never silently migrate or delete them. A verified backup and explicit recovery decision are prerequisites to an adoption path.
4. Capture account identity for the entire refresh operation. Re-check identity/epoch immediately before commit; abort rather than attributing an in-flight A fetch to a subsequently signed-in B account. A failed re-authentication must never fall back to showing stale A membership as current B membership.
5. Provider-specific public lists and account-linked private lists must not share a provider-only replacement operation. Missing providers and offline devices retain quarantined records but expose no read/write authority to a different account.
6. Portable backups must not promote A-origin memberships to B merely because the canonical title ID or external work matches. Export only versioned, credential-free provenance records with an explicit detached status; restore as quarantined until the same remote owner is verified or the user authorizes a separate **copy** of portable library data. Never replay A's remote mutations or cursors into B.
7. Carry original canonical work identity and external list key for reconciliation; do not use local Mihon numeric row IDs for identity. Retain distinct provenance for merged works rather than assuming an old account link applies to a new title.

## Migration and integration constraints

- Add a new SQLDelight migration *only after* the existing resolver PR #140 vs audit PR #141 collision at `43.sqm` is resolved. Do not allocate conflicting version numbers on parallel branches or overwrite #140's migration.
- Introduce the new repository contract and queries atomically with the migration. A partially updated caller that uses provider-only `replaceProvider` is a data-leak risk. Verify all callers and integration adapters before enabling the new tables/queries.
- Carry a compatibility/quarantine path for existing on-device rows that are not yet owner-scoped, including offline users. The existing `ExternalLibraryRepository` API cannot unambiguously represent a provider account and needs an explicit scope parameter or new typed interface; do not simply append an optional nullable owner.
- Once implemented, the canonical backup importer must refuse an account-linked archive unless it can perform a separate explicitly approved, fully verified owner-safe restore. Preserve atomicity and restore receipts.

## Acceptance matrix (must be automated before activation)

| Case | Expected result |
| --- | --- |
| Account A fetches a list, then B logs into the same provider | B never sees or overwrites A rows |
| A refresh is in flight while session switches to B | No A data committed under B |
| Legacy unowned rows exist before migration | Quarantined and preserved byte-for-byte; no guessed adoption |
| B has an unrelated list key with A's external work ID | No cross-owner collision |
| Provider goes offline, disabled, or uninstalled | No cross-owner visibility or deletion |
| Fresh device imports A's canonical backup without A login | Account-linked lists remain detached; no remote requests or outbox replay |
| Fresh device verifies same provider-issued A account subject | Only specifically approved same-owner recovery allowed |
| B explicitly copies canonical works from A | New B-owned sync state; no old remote cursor, mutation ID, or secrets |
| Duplicate/malformed owner scope or crafted account claims | Fail closed before database mutations |
| Process dies mid-migration or mid-refresh | All changes rolled back or quarantined; no partial ownership |

**Next action:** inventory every external-library writer/reader and the provider authentication identity surfaces, then define a typed `ExternalMembershipScope` and a migration plan **in the integration branch after version conflicts are resolved**. Until then, canonical export must continue refusing external memberships.
