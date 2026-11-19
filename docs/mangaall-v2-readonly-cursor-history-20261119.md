# MangaAll V2 — bounded read-only Host cursor history

Date: 2026-11-19. **NOT ACTIVE IN PRODUCTION; NO CODE9 IS SIGNED.**

Tracks [Host #207](https://github.com/jssantogit/tsuzuki/issues/207)
and [Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72).

## Goal

The provider-side experimental V2 wrapper (merged in
[providers #73](https://github.com/jssantogit/providers-tsuzuki/pull/73))
observes one MangaAll router invocation, including all three adapters.
Each page records its own source-scoped error/candidates but does not
remember previous page results. To preserve ambiguity and conflicting
editions across pages the Host must accumulate **read-only** evidence.

## Accumulator behavior

`MangaAllLookupV2ReadOnlyCursorAccumulator` consumes only JSON that
first passes the strict V2 read-only decoder. It is created per title
and limited to the **en** facet (the only one common to all three
sources), eight pages and 240 observed work IDs. Its caller supplies
the **exact** previously returned cursor.

For a new audit the first page must query MangaDex, MangaDot and
MangaFire together. Continuations must query precisely those sources
named in the opaque V1 router cursor. Source prefixes are never
interchanged. The signed router's JSON cursor is validated to contain
only the documented `v=1, remaining` structure, source IDs,
per-source bounded cursor/offset or bounded retry (r=1). Repeated
cursors, unauthorized source resurrection, a third retry, forged
completeness and conflicting metadata for one source-scoped work
identity permanently invalidate that audit.

A failed source with a retry cursor does not erase candidates already
observed from healthy sources. When its retry succeeds, the Host can
report that the **diagnostic pages** finished and preserve the
transient 403 history. When it fails again, the Host records
`INCONCLUSIVE` with prior safe observations preserved; it cannot
claim the source is empty. Unsafe/truncated observations prevent
a complete diagnosis even when later pages succeed.

Final results:
- `IN_PROGRESS` — a valid bounded continuation still exists
- `INCONCLUSIVE` — no continuation, but one or more sources have
  unverified/incomplete coverage
- `COMPLETE_OBSERVED` — all source cursors ended and all validated
  observations were consumed

**`COMPLETE_OBSERVED` is not a production PASS.** It does not prove
that candidates share an editorial edition, that the title is
canonical, or that any chapter/CDN image is readable. The accumulator
does not return `ProviderBindingRef`, `ProviderWorkCandidate` or
Reader grants. It is not called by the installed code8 gateway, and
the experimental JS is not distributed in a .tsz.

## Validation

The Android offline fixture
`mangaAllUnsignedV2CursorHistory_retainsThreeSourcesAcrossRetryAndRejectsConflicts`
checks one 403 and two healthy sources, successful retry while
preserving other source results, exhausted retry preserving
inconclusive status, cursor substitution, unexpected source calls
and cross-page identity conflicts. CI executes it inside the
disposable emulator alongside strict decoder and original code8
offline Reader 503/retry pixel regression.

## Remaining delivery gates

1. Promote a *reviewed, versioned* future provider code9+ with signed
   package + manifest/release provenance and a matching runtime
   contract. Neither the original code8 JS nor its trust root may
   be silently changed.
2. Add a production-grade Host V2 invocation channel that proves
   package version/provenance, bounds output size and runs the
   accumulator per title, facet and source-cursor session.
3. Compare observed work identities with independent canonical
   editorial evidence and enforce chapter/listing grants.
4. Validate all three options in one real Reader session, with
   direct-live CDN pixels. Previous code8 offline fixture pixels
   are not proof of live CDN accessibility. Never bypass HTTP403
   challenges or reuse device cookies.
