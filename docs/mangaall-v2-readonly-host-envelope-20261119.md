# MangaAll future V2 — Host read-only envelope validation

Date: 2026-11-19. State: **inactive/experimental, never authorized for binding**.

## Requirement and provenance

The currently signed MangaAll 0.5.3/code8 remains a single provider
with three internal adapters. When an upstream fails its bounded retry
in the original router, its global error obscures the source identity.

Real [Android run #44547565977](https://github.com/jssantogit/tsuzuki/actions/runs/44547565977)
observed 403/challenge for Naruto and Bleach, and an unclassified
lookup error for Death Note. None had completed three-source lookup,
chapters or rendered pixels; the HTTP403 must not be treated as
a missing work, missing chapter or failure of all three adapters.

The unsigned future evidence transport in
[providers-tsuzuki PR #73](https://github.com/jssantogit/providers-tsuzuki/pull/73)
uses one original router invocation and a V2 envelope containing
three source statuses and actual bounded observations.

## Host code added, but NOT wired to runtime

`MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly` is a strict,
read-only validator in the Host. It is **not used** by
`ScriptProviderReadingGateway.lookup`, production collectors,
canonical reconciler, SQLite repositories or Reader UI.

It rejects V1 pages outright, rejects unknown keys and oversized
payloads, requires exactly three ordered sources, valid source-scoped
IDs and bounded safe metadata, and preserves only observed evidence.
It rejects forged `eligibleForReader`, `eligibleForCanonicalBinding`
or candidate `bindingEligible` flags. No method exposes a
`ProviderBindingRef`, `ProviderWorkCandidate` or
`ProviderChapterObservation`, and it never creates work/chapter
associations.

A complete-single-page claim is valid only with three successful,
fully consumed sources and no pending cursor. An observation
after a continuation remains incomplete unless the future
source-page accumulator has preserved and verified earlier pages.
If a source fails, the other successful source observations
remain diagnostic only.

The only accepted provenance at this stage is
`UNSIGNED_CANDIDATE_NOT_LIVE`, which is **not direct-live evidence**.
A future signed V2 provider and Host contract would use a separate
verified provenance and release version, never silently promote this
test-only payload to a production reading capability.

## Offline acceptance

The Host Android instrumentation test
`MangaAllLookupV2ReadOnlyDiagnosticsTest` uses synthetic JSON only.
The code8-only CI runner executes it on an offline disposable emulator
before the existing signed code8 Host/Reader 503-retry pixel regression.

It verifies good complete and partial scenarios, challenge status
origin, no synthetic binding authorization, rejection of V1 payload,
unknown fields, cross-source IDs, candidate promotion and misleading
completeness. **No network or CDN pixels** in the V2 parser test.

## Future uncompleted work

- Integrate reviewed V2 responses into a new, explicitly signed
  code9+ provider; the original code8 remains byte-for-byte immutable.
- Add a real Host V2 gateway entrypoint and multi-page source accumulator
  that checks source cursor histories and cannot promote partial
  snapshots to canonical.
- Obtain real three-source evidence from a permitted network and
  verify no challenge evasion. Link identities to the independent
  catalog before reconciling chapters. Separately test source switches
  on one actual Reader session and real CDN pixels.
- Until then, [Host issue #207](https://github.com/jssantogit/tsuzuki/issues/207)
  and [Provider issue #72](https://github.com/jssantogit/providers-tsuzuki/issues/72)
  remain open.
