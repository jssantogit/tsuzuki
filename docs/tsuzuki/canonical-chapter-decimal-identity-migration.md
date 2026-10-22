# Canonical chapter decimal identity: compatibility and migration plan

Status: **design only**. No schema migration, persisted identity rewrite, or sync protocol revision is approved by this note.

## Current invariant and failure modes

The stored canonical chapter currently represents identity as `type + baseNumber + part + alphaSuffix`, with `volume` as a separate disambiguator. The chapter parser previously converted the text after a decimal point directly to an integer. This erased significant leading zeroes: `12.05` and `12.5` both yielded `part = 5`. Fraction strings exceeding `Int` could also throw during display construction.

The branch now **fails closed** for fractional components beginning with a significant leading zero, and for fractional integers that overflow, while preserving the original raw label in evidence. It does **not** change any stored identity or rewrite chapter progress. This is intentionally not a complete fractional identity model.

Additional unresolved problems:
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
2. Add **nullable additive** storage fields for the v2 numbering kind, numerator and scale. Preserve the original `part`, canonical chapter ID, all FK relationships, original label, and existing reading/downloading state. SQLDelight migration tests must run on an actual database snapshot compatible with pre-v2 data.
3. New observations can use exact v2 identities, but a legacy row without enough evidence for unique remapping must remain unresolved. Do **not** turn an old `part = 5` row into `12.05` or `12.5` by guessing from the value alone.
4. Build an explicit reconciliation/migration layer: for each legacy row, compare trusted historical raw evidence, volume, stable source keys, variants and protected progress; only promote unambiguously. On collisions, preserve both IDs and flag the case. Never merge protected reading progress or history automatically.
5. Version portable chapter sync keys. Do not change an existing record ID in place or silently publish two chapters under the same old key. Use feature/version gating and evidence-backed compatibility resolution; blocked/unportable records must remain local until an explicit safe mapping exists.
6. Gate rollout on focused RED/GREEN tests, SQLDelight migrations, historical sample audit, full Gradle CI, and real-device Reader/next/previous/download/sync regression checks.

## Current release gate

Safe partial mitigation is in `fix/canonical-chapter-resolver-hardening`. The v2 model, migration, and sync key rollout are **not implemented**. Do not merge with a claim that decimal identity or historical duplicates have been fully repaired.
