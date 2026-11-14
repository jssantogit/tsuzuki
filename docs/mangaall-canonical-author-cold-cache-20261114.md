# MangaAll / ICE — verified canonical-author bootstrap for cold chapter refresh

Status: implementation on Host PR (2026-11-14), **no new MangaAll distribution**.

## Evidence and root cause

The 2026-11-13 Galaxy A54 MangaAll 0.5.2/code7 acceptance found
`CANONICAL_METADATA_UNVERIFIED` for works whose provider lookup already
contained source-attested chapter previews. Before this change,
`CollectProviderReadingEvidence.execute` read **only**
`CanonicalMetadataSnapshotRepository.get(canonicalTitleId)`. If the
metadata snapshot was absent (common when a chapter refresh races the first
catalog/Details metadata resolution), author corroboration was permanently
unavailable during that run. Even 200 upstream chapters could not be
collected provisionally without independently verified authors.
There is no evidence that the missing information could be safely inferred
from title similarity, source rank, coverage count or a mismatched author.

## New source-neutral behavior

On each canonical chapter refresh, the Host first tries the existing
`ResolveCanonicalMetadata.cached(canonicalTitleId)` API. This API checks
the **active integration configuration fingerprint**, unlike consuming
an arbitrary legacy snapshot directly. On **cache miss only**, the Host
calls `ResolveCanonicalMetadata.execute(canonicalTitleId)` through a
**4,000 ms bound** and its existing concurrent-resolution coalescer.
This resolver reads the catalog's **verified external identities**,
never MangaAll's untrusted source titles.

The author list is accepted **only** when
`authors.externalId != null` and
`metadata.externalIds[authors.providerId] == authors.externalId`.
One source's creator and an unrelated provider's ID do not authorize a
work or edition. If the new catalog lookup fails, times out, has no
authors, or returns insufficient provenance, the Host stays unverified
and preserves the existing binding/chapters and explicit ambiguity policy.
It does **not** auto-rebind, invent missing chapters, infer editorial
equivalence or weaken the Reader's chapter-identity checks.

## Closed diagnostic

`provider_reading_evidence` at `SUMMARY/STARTED` now emits only the
validated enum `provider_canonical_author_state`:
`VERIFIED_CACHED`, `VERIFIED_FETCHED`, `UNVERIFIED_CACHED`,
`UNVERIFIED_FETCHED`, `METADATA_MISSING`, `FETCH_FAILED`,
`FETCH_TIMEOUT`. The structured sanitizer **drops unknown keys,
free-text author names, and arbitrary string codes**. This lets the
next A54 log distinguish absent catalog authority from a partial
source/network response without exposing identity data.

## Validation / constraints

The first RED run
[Host CI #43593187892](https://github.com/jssantogit/tsuzuki/actions/runs/43593187892)
reproduced the cold-cache gap: **1,203 domain tests, exactly two new
failures**. The initial source patch
[CI #43593545813](https://github.com/jssantogit/tsuzuki/actions/runs/43593545813)
passed domain tests and App Compile; a trivial format issue was corrected.
The diagnostic regression extends the corpus to cached and cold verified
authors, mismatched work identities and sanitizer rejection of
free-form authors. Final CI status belongs to the PR head.

**Not fixed in this change:** independently verified catalog IDs may be
missing entirely, or all enabled metadata providers may lack author
information. This improvement only closes the case where verification
was available **but the snapshot had not been loaded yet**. Competing
legitimate source editions remain distinct; incomplete upstream lookup
and the 5.8-second provisional-per-HID collection budget still need a
separate, cursor-aware continuation design. Do not increase timeouts
blindly or treat a partial source as a complete canonical inventory.

Target next proof: a *cross-work* live/cassette CI category suite for
cold valid authors, no canonical IDs, stale/disabled integration,
author conflict, competing editions, partial cursor and real
`reading.pages` response failure. Do not rely on title allowlists.
