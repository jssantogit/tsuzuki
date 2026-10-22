# Canonical chapter decimal identity: compatibility and migration plan

Status: **v2 foundation and read-only parser preview implemented; production activation deferred**. Migration 43 adds nullable exact-numbering columns without backfilling old rows. New in-memory numbering kinds and decimal fractions are opt-in. `ParseCanonicalChapterLabel.previewExactNumbering` recognizes only explicit chapter-marked decimals and ordinal parts, rejects ambiguous compact labels, and does not affect the live parser/reconciler. No persisted identity rewrite or sync protocol revision is approved.

## Current invariant and failure modes

The stored canonical chapter currently represents identity as `type + baseNumber + part + alphaSuffix`, with `volume` as a separate disambiguator. The chapter parser previously converted the text after a decimal point directly to an integer. This erased significant leading zeroes: `12.05` and `12.5` both yielded `part = 5`. Fraction strings exceeding `Int` could also throw during display construction.

The branch **fails closed** for fractional components beginning with a significant leading zero, and for fractional integers that overflow, while preserving the original raw label in evidence. New exact numbering fields in the database default to NULL for every legacy chapter. No existing canonical ID, progress row, variant or portable key is rewritten. This is not yet an activated exact fractional identity pipeline.

Persisted v2 metadata now has a fail-closed read guard in `CanonicalChapterRepositoryImpl`: a DECIMAL kind requires both numeric fields, rejects fractional values outside the exact model range and must agree with the legacy `part`; a legacy or ordinal record must not carry orphan decimal fields. All v2 base numbers, parts and volumes are also checked before narrowing SQLite INTEGER values to Kotlin Ints. Corrupt or half-written rows trigger an error instead of silently becoming another chapter identity. This is an integrity check, **not** an automatic repair or permission to promote legacy rows.

Additional unresolved problems:
- **Write-side guard implemented:** the canonical SQLDelight upsert now uses an atomic conditional `ON CONFLICT ... DO UPDATE ... WHERE` clause to refuse changes to existing exact identities **and** unreviewed in-place promotion of existing legacy v1 IDs, while the Kotlin repository checks for conflicts inside its write transaction and reports them to callers. The repository's batched validation loads existing ID metadata in one query instead of one read per chapter. This preserves metadata-only refreshes and legitimate `canonical_title_id` changes. A direct arbitrary SQL `UPDATE` bypasses this guard; support for a database-wide trigger must be designed separately because SQLDelight cannot parse `OLD`/`NEW` references inside the `.sq` schema. Migration 44 was removed before rollout, so migration 43 remains the only additive v2 schema change. The read guards still detect corruption; neither guard authorizes a legacy identity rewrite.
- `12.50` currently stores `part = 50`, while `12.5` stores `part = 5`: numerically equivalent decimal labels may be treated as separate chapters.
- `12 Part 5` and `12.5` use the same `part = 5` identity, although source conventions may distinguish ordinal parts from decimal fractions.
- Compact volume/chapter notation such as `9.46` may mean volume 9 / chapter 46, especially when the source's numeric hint corroborates chapter 46. Raw hints are *not* universally authoritative.
- Existing progress, reading history, downloaded variants, evidence mappings, and portable sync keys reference previously created canonical IDs. Reinterpreting identities in place is dangerous.

## Proposed v2 identity

Represent regular chapter numbering using explicit, separately tagged components:

- An integer chapter number (`baseNumber`).
- A numeric **fraction** with exact decimal numerator and scale, normalized with trailing zeroes: e.g. `05 -> numerator 5, scale 2`, `5 -> numerator 5, scale 1`, `50 -> numerator 5, scale 1`. A decimal fraction of zero must have an explicit policy against the unsuffixed whole chapter.
- An **ordinal part** (from `Part 2` / `Pt 2`), stored as a separate kind from decimal fractions.
- An alphabetic suffix, kept distinct from both forms.
- A semantic type (`REGULAR`, `EXTRA`, etc.). Unnumbered specials still need independent stable external evidence rather than type-only equality.
- Volume-aware matching remains contextual: a number shared by two explicitly different volumes is *not* one canonical chapter.

Use exact parsing/ordering rather than floating point. Avoid treating the raw source order or source-provided numeric hints as chapter identity.

## Compatibility sequence

1. Write behavior tests covering `12.5`, `12.05`, `12.50`, `12 Part 5`, compact `9.46`, numeric hint disagreement, very long decimals, explicit volume prefixes, and two volumes with restarting chapter numbers.
2. **Implemented in migration 43:** add nullable `numbering_kind`, `decimal_numerator`, and `decimal_scale` columns without rewriting existing rows. The canonical SQLDelight upsert and Kotlin repository now reject unsafe exact-identity rewrites; raw SQL writers require separate protection. Preserve the original `part`, canonical chapter ID, FK relationships, raw label and reading/download state. Verify historical snapshots and actual device upgrades before enabling upstream v2 writes.
3. A read-only, conservative `previewExactNumbering` now interprets explicitly marked decimals and ordinal parts, but is not wired to inventory writes. Future new observations can use exact v2 identities only once the trust gate, historical evidence compatibility, and collision handling are designed and verified; a legacy row without enough evidence for unique remapping must remain unresolved. Do **not** turn an old `part = 5` row into `12.05` or `12.5` by guessing from the value alone.
4. Build an explicit reconciliation/migration layer: for each legacy row, compare trusted historical raw evidence, volume, stable source keys, variants and protected progress; only promote unambiguously. On collisions, preserve both IDs and flag the case. Never merge protected reading progress or history automatically.
5. Version portable chapter sync keys. Do not change an existing record ID in place or silently publish two chapters under the same old key. Use feature/version gating and evidence-backed compatibility resolution; blocked/unportable records must remain local until an explicit safe mapping exists.
6. Gate rollout on focused RED/GREEN tests, SQLDelight migrations, historical sample audit, full Gradle CI, and real-device Reader/next/previous/download/sync regression checks.

## Read-only historical promotion assessment

`AssessLegacyExactChapterPromotion` is an opt-in, pure **review helper**, not a migration, reconciliation writer or permission to rekey a chapter. It returns a typed candidate only when stored legacy integer fields match the precise preview, every mapped evidence item has a stable source key and explicit unambiguous numeric label, all evidence agrees on exact fraction and volume, and provenance is editorial or comes from at least two distinct producers. Conflicting labels, unsupported compact forms, a competitor within the same identity/volume, protected reader state, or portable sync state cause a typed rejection. An existing exact chapter is never proposed for re-promotion.

The helper accepts evidence snapshots and protected-state flags from a caller; it **does not** query historic history, downloads, or sync itself. No production entry point calls it, no existing canonical ID is mutated, and no automatic bulk migration is authorized. Before any future caller treats a candidate as a promotion decision, independently verify source provenance, historical variants and volume ambiguity, reader state, sync portability, and database transaction boundaries on real migration snapshots.

## Database-backed historical migration preflight

`LegacyExactChapterMigrationPreflight.inspect` (Data, **internal**, not injected or called by production code) now performs a read-only SQLDelight transaction over the current canonical row, complete title siblings and mapped evidence, protected progress/history/download/update state, and source variants. A missing row, competing sibling, variant, protected state or inconsistent evidence yields a typed rejection. If portable sync state cannot be independently confirmed absent, the default is `REMOTE_SYNC_UNVERIFIED`; the caller cannot infer cross-device safety from local SQLite tables. With an explicit external audit flag and no known blockers, the result is still only `ReviewOnly`, never approval to promote a legacy ID. Repeating inspection after evidence changes re-reads the database rather than trusting a cached candidate.

**There is still no migration write endpoint.** In particular, there is no statement to bypass the canonical upsert guard, no automatic backfill and no mechanism for changing an old portable key. The next implementation stage requires versioned sync compatibility, a revalidated compare-and-swap write transaction with protected-state policies, and device-level migration testing. An audit result returned after the read transaction has ended must never be used as authority for a later write.

## Targeted regression checkpoint (2026-10-22)

The prior full CI on `a2156caf` passed all lanes except a Data test that exposed a per-chapter exact-identity preflight read. The preflight was replaced with one batched SQLDelight query and a 40-chapter query-count regression. Targeted Data tests, SQLDelight migration checks, Spotless and App compile on `f14d07c2` passed. New read-only historical assessment and the atomic no-in-place-promotion guard require complete Domain/Data/App/release/native acceptance at the latest checkpoint.

## Explicit migration writer and portable sync v2 pilot (2026-10-22)

An internal, **unwired** `LegacyExactChapterMigrationWriter` is now available for a controlled test-only migration. An explicit reviewer approval and an independently supplied remote-sync-absent audit are both required, defaulting to refusal. In one SQLite transaction, it re-reads the current chapter, evidence and competing siblings, rejects any protected progress/history/download/update or queued reader state and all unreviewed source variants, then performs a narrowly scoped SQLDelight compare-and-swap update of only `numbering_kind`, `decimal_numerator` and `decimal_scale`. It verifies the resulting precise identity, preserving the canonical chapter ID and source evidence FKs. Existing `upsert` APIs still cannot perform this conversion. **No user interface, service injection, batch upgrader or production call site invokes the writer.** Its remote-audit Boolean is not proof of cross-device safety and must not be surfaced as a user-toggle.

`CanonicalChapterSyncKey.fromVersion2` now defines a deterministic length-delimited key that includes numbered semantic type, base, part, alpha suffix, exact numbering kind, fractional numerator/scale and volume (with explicit absent-value markers). The reading-progress adapter has a **test-only opt-in** `exactVersion2Enabled` parameter (default false), exporting schema version 2 to the strictly separate `READING_PROGRESS_V2` Supabase document domain, resolving exact progress across differing local IDs and volumes, and rejecting any document of the wrong kind or version before applying progress. The Supabase orchestrator permits schema 2 only under that domain name; all other domains remain schema 1, and a mismatched accepted-base schema fails before contacting the remote transport. Old clients know only `READING_PROGRESS`, so the backend's domain-scoped record keys and delta queries cannot mix v1 and v2 events. The default production composition and its v1 keys remain unchanged. This codec is **not yet a safe user migration**: the new server namespace intentionally separates old and new clients instead of silently merging their progress. Before enabling v2, remote v1 records need an explicit reviewed mapping to v2 keys, careful accepted-frontier/cursor handling, rollback policy and mixed-device acceptance testing; old devices can still update the legacy domain independently. The sync backend now has an isolation regression in `supabase/tests/tsuzuki_sync_v2_namespace.sql`, but production activation remains disabled.

**Critical non-production gates:** regular existing chapters generally have source variants, which this migration pilot refuses until their raw labels and mappings can be independently validated. Real user-owned progress is deliberately not migrated. Historical migrations on actual Android installations, Reader navigation, downloads, source switching, offline/online sync and mixed-version devices have not been accepted. Do not merge this as a fully enabled chapter-identity v2 release.

## Current release gate

Safe mitigation and the **opt-in v2 model + additive migration** are in `fix/canonical-chapter-resolver-hardening`. Full v2 parser/reconciler activation (the read-only preview exists), safe reconciliation against existing v1 evidence, historical identity promotion, portable sync v2, and real-device database-upgrade validation are **not implemented**. The current v1 sync deliberately leaves opt-in exact rows local/unportable. Do not merge with a claim that decimal identity or historical duplicates have been fully repaired.
