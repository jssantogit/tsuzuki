# MangaALL — granular chapter pagination before any 30-work live CI

Status (2026-11-22): **in development and offline validation; not yet an active signed provider capability.** No 30-work live run is authorized by this document.

## Current live baseline

The real Android WebView Browser Host inside GitHub Actions, not the physical Galaxy A54, has already accepted the original signed MangaALL 0.5.5/code10 sequence36 API search for both Oyasumi Punpun and Koe no Katachi: [#45094285225](https://github.com/jssantogit/tsuzuki/actions/runs/45094285225), exact signed SHA `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f`.

The next independent chapter gate [#45097870278](https://github.com/jssantogit/tsuzuki/actions/runs/45097870278) identified:
- **Oyasumi Punpun:** one real MangaFire candidate, completed lookup, **147 actual source chapter IDs across two pages**, chapter 1 observed, `READY`.
- **Koe no Katachi:** one observed MangaFire candidate and valid signed API JSON, but the unified three-source lookup ended after three pages with `UNCLASSIFIED_ERROR`, so source chapter collection was not attempted. Global `LOOKUP_INCOMPLETE` is not equivalent to missing MangaFire chapters.

The new three-work CI mode uses **Punpun, Koe no Katachi and Akame ga Kill!**. For every work it runs one original combined three-source signed-code10 lookup, publishes 3 separate redacted source candidate/complete-vs-partial statuses, and examines MangaFire chapter pages even when a deferred unrelated source failure occurred. It **cannot** mark a globally incomplete lookup as `READY`, grant a canonical binding or infer that a partial chapter list is complete. The strict three-work parser independently verifies nine source records, three original signed API observations and chapter completion. The workflow may finish RED while yielding useful partial coverage.

## The specific large-list failure

MangaDot's real `/api/manga/:id/chapters/list` ignores tested `limit`, `page`, `offset` and HTTP Range variations:
- Naruto source HID 109: 1,628 rows / 826,386 decoded bytes;
- Bleach source HID 131: 4,341 rows / 2,406,271 decoded bytes.

The unchanged signed code10 cannot serialize these lists through Host's 192 KiB HTTP-text, 256 KiB HTTP-IPC and 64 KiB script-output budgets. In fact, the prior [30-work code10 live run #45079717634](https://github.com/jssantogit/tsuzuki/actions/runs/45079717634) revealed **five** real oversized results (425, 548, 653, 753, 477 rows), which [merged Host PR #270](https://github.com/jssantogit/tsuzuki/pull/270) now classifies honestly as `INCONCLUSIVE_HOST_RESULT_LIMIT` instead of falsely declaring them decoded. Artificially raising global caps is **not** pagination and risks every provider. These HIDs are source candidates, **not** authoritative canonical editions.

## Existing implemented, nonshipping granular foundation

The current Host main branch already has a **future-code11 gated** `MangaAllFutureHostChapterSnapshot` module and a capability/manifest-scoped QuickJs Binder transport. The provider repository's pinned future research candidate (`89b5979fff297c2a099359258c9f9249e0089db4`) generates an **ephemeral unsigned code11 overlay** of the original real three-source JS router: only MangaDot's `reading.chapters` is redirected to the Host-private native snapshot broker; MangaDex, MangaFire, lookup and Reader source adapters remain the originals.

The intended granular contract is:
- At most 16 MiB source body, 16,000 rows; one Host acquisition per snapshot, no fresh full-list download on every output page.
- Up to **256 rows and 48 KiB UTF-8 AND JS text per Host page**; at most 32 returned pages; no global 64 KiB increase.
- Source HID, facet, unique generation, monotonic offset, TTL and snapshot concurrency bound to each cursor; invalid/stale/foreign cursors and partial/failing sources fail closed.
- Compact normalized source metadata remains under the 440 KiB snapshot budget (512 KiB total including other retained Provider keys); atomic replacement, cross-process file locking and strict generation validation.
- No signed code10 permission changes: the private bridge is forbidden for code10 and every other provider ID/capability.

[Dedicated offline CI](../.github/workflows/mangaall-granular-pagination-readiness.yml) now exercises the provider's original three-source future-code11 JS route with 4,341 **synthetic** chapter rows, the lossless compact codec and cursor tests, as well as Host's actual JVM snapshot/cross-invocation behavior. The work remains **nonshipping** until further acceptance.

## Missing before 30 — blocking gates

1. **Three-work live CI diagnostics:** verify all three source contributions per work, distinguish deferred source failure, HTTP/challenge failure, ambiguous candidate identity and MangaFire chapter pages; no false two-source PASS.
2. **Native full list integration:** connect a future reviewed code11 adapter and real Host snapshot in Android CI, verify one actual large MangaDot source response can be acquired within limits and paginated to the end. The offline 4,341-row test is a capacity proof, not real upstream data acceptance.
3. **Exact data parity:** independently verify normalization for source chapter type+ID, language, chapter number, timestamp, title and duplicates; ensure source completeness before committing a snapshot. Revisit the known signed-code10 ID-only versus future kind+ID dedup behavior as an explicit version change.
4. **Live source → Host → collector and SQLDelight:** the independent [merged Host PR #269](https://github.com/jssantogit/tsuzuki/pull/269) already proves an **offline synthetic** 4,341-row, 17-page production Kotlin collector → refresh → canonical reconciler → real SQLDelight/JDBC round-trip, retaining provisional source-only records without editorial grants. What remains is connecting this proven downstream capacity to **genuinely acquired live MangaDot rows** through the reviewed Host-native code11 continuation, and proving interrupted/stale cursors never erase accepted rows. Host still caps 32 pages/16,000 observations.
5. **Signed future migration authorization:** require a separate explicit review/approval before any code11 P-256 signing, publishing or app integration. No unsigned test package may become a trusted production provider.
6. **Only after those checks:** run one on-demand 30-work integrated MangaDex+MangaDot+MangaFire CI, keeping per-source ambiguity, incompleteness and Reader CDN pixels as independent reported gates.

Neither a successful WebView API request nor a green offline 4,341-row regression by itself authorizes the 30-work live suite.
