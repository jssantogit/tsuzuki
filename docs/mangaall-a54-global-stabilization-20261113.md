# MangaAll — Galaxy A54 0.5.2 real-world acceptance and systemic gaps

Observed 2026-11-13, Host `app.tsuzuki 0.20.4 (c1609f3)`,
Android 14 Samsung SM-A546E, official signed repository sequence **33**
and installed MangaAll **code7**. Source: user-supplied
`tsuzuki_logs (2).txt` (2,459 structured diagnostic events) and
`tsuzuki_startup_crash.txt`. The raw logs are user data and **are not
committed**. No opaque installation identifiers, source cookies or URLs
from the logs were copied.

## What is observed, not what is claimed solved

| Work | Observed canonical count after refresh | Refresh result | Relevant boundary |
| --- | ---: | --- | --- |
| Oyasumi Punpun | 147 | succeeded | Original preferred MangaDex/en complete-empty; a MangaFire English source produced 147 chapters and Reader reported 27 pages for chapter 1 |
| Koe no Katachi | 70 | partial | MangaFire's bounded lookup observed 133 entries while chapter collection reached 70; Reader reported 57 pages for chapter 1 |
| Boku no Hero Academia | 891 | partial | Multiple HIDs and source chapter evidence; count must be verified for duplicates/edition overlap, not treated as proof of 891 correct canonical chapters |
| Attack on Titan | 151 | partial | `AMBIGUOUS_EDITION`, source snapshots retained but not editorially complete |
| Gachiakuta | 0 | partial | MangaFire source observed 200 chapter entries with more available; `CANONICAL_METADATA_UNVERIFIED` and `MULTIPLE_PRIMARY_IDENTITIES` block trust |
| Kimi no Na wa. | 0 | partial | Real `TIMEOUT` (partial upstream discovery), no safe canonical publication |
| Akame ga Kill! | 0 | partial | `CANONICAL_METADATA_UNVERIFIED`, competing source HIDs and lookup timeout |
| 5-toubun no Hanayome | 26 | succeeded | Selected source identity; chapters observed, not proof every edition is complete |
| Dungeon ni Deai wo Motomeru no wa Machigatteiru Darou ka | 0 | partial | Multiple source/edition candidates and only provisional evidence |
| Re:Zero kara Hajimeru Isekai Seikatsu: Dai-1 Shou | 0 | succeeded | `NO_MATCH` for the searched source titles/editions; 0 is not a success for reader coverage |
| Mirai Nikki | 61 | partial | One chosen source plus unresolved ambiguous identities |
| Make Heroine ga Oosugiru! @comic | 16 | partial | Selected candidate and competing facet/edition decisions |

The session also contains three *cancelled* chapter refreshes due to
navigation/HTTP call cancellation, two `app_frame_stall` events, and
20 WARN-level events (mostly upstream HTTP cancellation). The logs
identify four Reader source selections with `reader_pages_ready`,
but **ready is not proof of decoded pixels**. Production crash:
`ProviderHttpPageLoader.onFailure` raised a
`ProviderHttpPageException` from OkHttp `IOException: Canceled`,
uncaught on a `DefaultDispatcher` worker. This is a **Host runtime bug**,
independent of which work/Provider supplied the page.

## A global fix must satisfy these cross-work invariants

1. **Page lifecycle and error ownership**: any source's ordinary HTTP,
   DNS, origin-policy, redirect or size error becomes a retryable
   `Page.State.Error` (while retaining strict network safety), never
   an uncaught `StandaloneCoroutine`; lifecycle cancellation is not an
   image error. Run both callback and recycle-race tests against the real
   Reader page loader. This regression is addressed by PR #198.
2. **Work identity is not edition identity**: named-work match alone,
   search ranking and chapter count cannot silently establish a
   canonical work, source edition, language, or preferred binding.
   Explicit edition variants and competing HIDs require positive
   evidence or an honest unresolved state. Keep source-scoped provisional
   chapters separate and never map chapter 0 to another number.
3. **Missing independently verified author metadata is a systematic
   dependency**, not an invitation to weaken the trust check for an
   individual title. Build an authoritative catalog metadata/author
   availability gate and test its interaction with source discovery for
   the entire matrix. Investigate whether verified author provenance is
   systematically missing for MangaAll titles rather than adding
   work-ID allowlists.
4. **Incomplete != empty**: a timed-out source, a partially decoded page
   or an unfinished cursor must not commit a complete-empty snapshot.
   Preserve already verified per-source observations with explicit
   `partial` and `hasMore` metadata. Require iterative, bounded paging,
   absence of duplicate chapter IDs, and first/last-number assertions.
   A newly readable edition must not invalidate persisted library,
   binding, or reading progress.
5. **Real-provider acceptance, not only synthetic fixtures**: retain the
   existing 65-case offline corpus and original code7 JS→Host→Reader
   Android replay, but supplement with bounded real upstream checks for
   each failure *category* and a portable sanitized replay corpus from
   physical traces. No CI claiming real CDN/403 success because
   mocked responses passed.

## Status/next actions

- PR #198 isolates the **generic Host crash fix** and adds RED→GREEN
  callback-failure, recycle-race, redirect, origin-policy, oversized
  response and retry tests. Do not merge without clean Gradle/CI.
- **Unresolved systemic work** (NOT fixed by this PR): canonical author
  provenance; source/edition ambiguity; MangaAll fragmented language
  facets; partial upstream pagination and timeouts; real image pixels.
  Diagnose these at the work/source boundary using per-source provenance
  and category-level invariants, not special cases for Punpun, Koe,
  Gachiakuta or Akame.
- User-device revalidation is still necessary after a signed Host APK
  is produced and installed on top of the current `app.tsuzuki`.
  MangaAll code7 is already signed and distributed; no new Provider
  package, signing key or repository is needed for this Host fix.

