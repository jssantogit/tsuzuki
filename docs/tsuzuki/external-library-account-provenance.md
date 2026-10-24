# Account-scoped external library memberships

**Status (2026-10-24):** implemented on [audit PR #141](https://github.com/jssantogit/tsuzuki/pull/141), pending exact-head full CI and physical A/B acceptance. No automatic adoption of old records, no main-branch feature enablement, and no canonical backup activation.

## Architecture and migration

- Resolver PR #140 owns migration `43.sqm` (canonical chapter numbering). The audit receipt migration was moved to `44.sqm`. Migration `45.sqm` creates `tsuzuki_external_library_memberships_scoped` and its provider/owner index. The old `tsuzuki_external_library_memberships` schema is kept unchanged and its records remain **UNOWNED_LEGACY**, invisible and never auto-adopted or deleted.
- An account-owned row is keyed by `(provider, owner_key, external_id, list_key)`, with `owner_key = SHA-256(provider || NUL || provider_issued_authenticated_account_id)`. Raw account identifiers, passwords, cookies and bearer tokens never go into the scoped table. Distinct A/B owners can persist the same remote work/list key independently.
- `ExternalLibraryRepository.replaceOwned` validates provider consistency and transactionally deletes/inserts only the requested owner. The former `replaceProvider` fails closed; `clearProvider` never deletes another account's snapshot or legacy bytes. On account logout, records remain stored but are not visible.
- `observeAll` subscribes only to each **currently connected and authenticated owner** and maps only that owner's scoped query. It re-evaluates both on tracker credential/connection emissions and on IntegrationRegistry configuration changes. Unknown, disconnected or unverifiable identities produce no account-linked list rows; other local canonical library records remain intact.
- `RefreshUserLibraries` verifies the provider's current authenticated identity before network fetch, again after fetch, and before the scoped transaction. A→B changes fail without writing fetched A records to B's scope. SQL rows also remain scoped to the original identity if a session transition occurs immediately after verification. Repeated identity checks may add profile requests during refresh; these should later be benchmarked without weakening authority boundaries.
- MAL `/users/@me` and Bangumi `/v0/me` use numeric user IDs; MangaUpdates `/v1/account/profile` uses `user_id`; Kitsu `currentAccount.id` is used directly. A missing/unparseable ID is a hard failure, never a fallback to display name or login state. All identities originate from authenticated provider requests.
- `CanonicalCoreBackupExporter` refuses portable export whenever either legacy unowned or account-scoped external memberships exist. **No provider-account records may silently disappear from a purported complete backup.** Owner-safe export, portable restore and detached recovery require a separate design and explicit consent before enabling canonical backup.

## Boundaries and acceptance

The scope protects local confidentiality and prevents account collision, but the following **pre-release validations remain mandatory**:

1. Real authenticated A→B→A transitions and switching while a remote fetch/reconciliation is in flight (MAL, Kitsu, Bangumi, MangaUpdates); confirm filtered UI and correct account-owned records after process restart.
2. Session token refresh/logout/relogin and provider disabling while observing lists; confirm no transient exposure of the old account. No remote owner verification means the account lists stay hidden (fail closed), including offline startup.
3. Migration from a physical database containing legacy unowned rows; confirm 43→44→45 preserve them byte-for-byte and run successfully across app upgrades. Nothing should be silently assigned to whichever user logs in first.
4. Crash and SQLite rollback during per-owner replacement. Confirm failed writes preserve A/B snapshots and do not partially replace records.
5. Cross-device restore and SAF interruption gate separately. The canonical backup writer/importer are still not active in production, and scoped external lists remain unexportable by that standalone exporter.

## Automated regression matrix

| Case | Expected |
| --- | --- |
| A and B share provider/work/list key | Separate persisted rows, scoped by hashed authenticated ID |
| Remote fetch starts as A but identity changes to B | No wrong-owner commit |
| New account B snapshot replaces old B | A rows untouched, even for identical list keys |
| Owner A logs out | No account rows visible, none deleted |
| Existing provider-only legacy row | Quarantined; never shown or adopted as A/B |
| Missing/invalid authenticated user ID | No fetch admission or trusted write |
| Integration enabled/disabled while observing | Active owner scope recomputed |
| Canonical backup sees scoped or legacy lists | Explicit incomplete-backup error, no silent omission |

**Merge discipline:** do not present a green CI alone as Android physical acceptance. Keep PR #141 in draft until code integration gates are verified, and do not enable the canonical backup writer without a dedicated portable account-list recovery contract.
