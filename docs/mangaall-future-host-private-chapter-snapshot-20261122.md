# MangaALL — future Host-private large chapter snapshot (not shipping)

**Status:** isolated Host Kotlin proof only (2026-11-22). This work is **not** callable from QuickJs, does not grant a signed Provider any new permission, does not change the live app, and does not constitute MangaDot editorial identity or Reader authorization. Official P-256 code10/0.5.5/seq36 SHA `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f` stays byte-identical.

## Why this path

Real fixed, unverified MangaDot lists measured by [Provider streaming diagnostic](https://github.com/jssantogit/providers-tsuzuki/actions/runs/45025654466):

| Source candidate | Rows | Upstream decoded bytes |
| --- | ---: | ---: |
| Naruto `mangadot:109` | 1,628 | 826,386 |
| Bleach `mangadot:131` | 4,341 | 2,406,271 |

MangaDot ignores tested `page/offset/limit` queries and HTTP `Range` requests. The public Host `http.request/get` bridge intentionally caps text at **192KiB**, JS HTTP IPC at **256KiB**, and script output at **64KiB**. Merely raising them would widen every Provider's attack surface.

Existing `DefaultProviderHttpHostService.getResource` already uses the normal origin/DNS/redirect/cookie safety rules and stores **up to 16MiB of bytes inside a per-invocation Host resource store**, without serializing the body into JavaScript. The new `MangaAllFutureHostChapterSnapshot` uses that **private** acquisition path to parse a bounded JSON array and write compact metadata to per-Provider storage. This is *bounded full-response acquisition in the Host*, **not a true incremental network stream**. Up to 16MiB may be temporarily held during acquisition.

## Current internal contract

- Source scope locked to signed provider ID `app.tsuzuki.mangaall`, numeric `mangadot:<id>`, English/Portuguese facet, generated MangaDot URL. No URL from Provider input is fetched directly; network policy remains active in the injected HTTP broker.
- Source JSON root must be an array, at most **16MiB input / 16,000 observations**. Invalid JSON, HTTP failure, giant bodies or foreign-only chapter language **fail closed**, never become a completed empty inventory.
- Compact seven-field metadata models source IDs, number, upload/chapter kind, title, language, scanlator and timestamp. Dedupe now uses **kind + numeric chapter ID**, preserving an upload and non-user chapter with the same numeric ID. **Important:** this intentional future-only fix differs from current signed code10's numeric-ID dedupe; a future unsigned Provider must be adapted and separately parity-reviewed. The snapshot does **not** verify work, edition, author or chapter canonical status; selected signed-JS normalization edge cases (numeric IDs, JS-number chapter labels, ISO-8601 dates) now have matching Kotlin regression fixtures. Full parity for ambiguous timezone-less dates, malformed fields and the intentional upload/chapter dedup difference remains outstanding.
- Each storage chunk ≤**48KiB JSON chars and UTF-8 bytes**; ≤**16 chunks**; compact data ≤**440KiB**; each persisted wrapped value ≤**64KiB**, with **512KiB provider total** including **all retained unrelated keys**. A specialized **Host-private** atomic replacement on `FileProviderStorageHostService` preflights the entire resulting state and swaps it with a single same-filesystem atomic file replacement. If overquota or write failure occurs, the previous valid snapshot and unrelated keys remain intact; no clear-then-write gap. This internal operation is NOT available through QuickJs or the generic `ProviderStorageHostService` interface.
- Page output ≤**256 items AND 48KiB JSON chars / UTF-8 bytes**. Cursors bind work HID, language, 16-hex generation and position; 5-minute TTL. Per-process mutex serializes generation and reads; single active snapshot/provider means another work invalidates old cursors, rather than accidentally cross-loading chapters.
- JVM regression: synthetic **4,341** source rows in a body above **2MiB** reconstruct with exact source ID order in ≤32 pages from one Host resource acquisition; foreign/stale/expired cursors and a 50×3,900 CJK overbudget inventory fail. Additional regressions retain two distinct source kinds with the same numeric ID, and preserve an old cursor plus six unrelated provider storage keys when a replacement exceeds the **combined** 512KiB quota.
- On-demand branch-scoped offline workflow runs JVM tests and Spotless; there is no live network, CDN, Android Reader or database grant in these tests.

## Remaining gates before a future Provider version

1. Add a narrowly granted Host-to-script continuation interface **after** independent manifest/capability/permission review; the current signed code10 MUST NOT gain access implicitly.
2. Prove source adapter normalization parity end-to-end, including dates and malformed fields. Current Host prototype does not yet guarantee exact JS adapter parity in every edge case.
3. Add multi-process concurrency/version locking and crash recovery. A single atomic provider-state file swap now preserves whole-store consistency, but the current mutex protects only one process and independent Host storage instances do not share a process/file lock. Validate crash/process races before production use.
4. Verify Host collection/refresh/reconciliation on actual SQLDelight/SQLite and preserve independent MangaUpdates author/edition authority. `mangadot:109` / `mangadot:131` remain unverified source HIDs.
5. Verify actual signed-future-Provider Reader chapters and pixels on Android, then physical Galaxy A54, and only then execute the requested **30-title** combined three-source live acceptance. No signature or publication without explicit operator approval.

Keep the original 30-work opt-in test plan [Host PR #252](https://github.com/jssantogit/tsuzuki/pull/252) untouched. **Offline Kotlin GREEN means host capacity machinery works under synthetic conditions, not that 30 titles work in production.**

## 2026-11-22 — next nonshipping source-normalization regression

The Host's early `JsonPrimitive.longOrNull` chapter-ID shortcut was not equivalent to source JavaScript `Number.isSafeInteger`: a numeric JSON `9007199254740992` must be **rejected**, but the same 16-digit text in a JSON string may be retained as the exact source ID. Numeric `1e3` and `1000.0` must normalize to `1000` where the signed JS adapter accepts them. Chapter-number parsing also compares the JavaScript stringified numeric value, not the raw JSON exponent token.

The offline JVM fixture additionally covers English facet case-folding, title trimming, UTC date-only `2026-11-22`, explicit time-zone offset `2026-11-22T10:00:00+03:00`, and pre-1970 dates returning null. This aligns stable explicit formats with the current source JS without changing the original signed package. Ambiguous local-time formats deliberately remain unrecognized: accepting them without an exact agreed timezone would risk inventing a different release timestamp. The future-only `kind + ID` dedup remains a documented divergence from signed code10's `ID`-only rule, pending separately reviewed future source changes.

These regressions use **controlled synthetic records, never actual upstream**, and carry no editorial authority, Reader access, signature, code11 publication or 30-title live-run claim.

## 2026-11-22 — bounded multi-process storage coordinator (future only)

The future `FileProviderStorageHostService` now coordinates **all get/set/remove/snapshot-replace operations for provider ID exactly `app.tsuzuki.mangaall`** across independent service instances and Android OS processes. Every read-modify-write cycle acquires a stable `.app.tsuzuki.mangaall.snapshot.lock` sidecar file, with a JVM-wide monitor keyed to its canonical path and a Java NIO OS file lock. This locks the *sidecar inode*, not the provider JSON inode that is replaced atomically. The shared provider JSON retains its existing quotas and atomic rename. Lock acquisition is bounded to 3 seconds; contention, interruption or filesystem failure rejects the operation without overwriting an existing snapshot. The coordination applies only to the future MangaALL provider ID; other providers' storage behavior is unchanged. This is advisory locking and assumes all readers/writers use the same Host service.

A second process may replace the single-active-snapshot inventory between `start` publishing and reading the first page. `MangaAllFutureHostChapterSnapshot.start` therefore validates **work ID, language, and generation** of the committed index before returning any first-page rows. Subsequent part reads continue to check snapshot generation and fail closed if they race with replacement. The single-active-snapshot model intentionally makes stale continuations invalid rather than mixing work inventories.

Offline JVM tests cover independent storage service instances racing on the same JSON file, a **real second JVM process** holding the OS sidecar lock while a Host operation waits, lock-path errors retaining the previous committed state, and all prior 4,341 synthetic-record capacity, cursor and byte quota cases. This is **not** app injection of the code11 QuickJs bridge, not a live MangaDot fetch, not Reader acceptance or a signed publication. The original P-256 0.5.5/code10/seq36 remains unchanged.
