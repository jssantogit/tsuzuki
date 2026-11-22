# MangaALL — future code11-only Host chapter snapshot bridge (NONSHIPPING)

This is **preparatory Host infrastructure only**, not an authorized code11 Provider package, a signed release or a production app feature. Official P-256 MangaALL 0.5.5/code10 seq36 SHA256 `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f` stays byte-identical. No real network requests, Reader pixels, user-data migration or 30-title smoke are performed by this change.

## Why this is a separate permission

The current JS Host `http.request/get` must keep its 192KiB HTTP text and 256KiB IPC budgets. The internal `MangaAllFutureHostChapterSnapshot` added in [Host #253](https://github.com/jssantogit/tsuzuki/pull/253) can privately obtain bounded 16MiB source arrays, compact to 512KiB/provider and emit ≤48KiB pages; [Host #254](https://github.com/jssantogit/tsuzuki/pull/254) proves Host broker transport for >2MiB offline with origin/403 enforcement. Neither capability was exposed to installed Provider JS.

The future manifest v1 gains optional `permissions.mangaAllChapterSnapshot.enabled` (default false). When true, **package validation rejects** anything except:

- provider ID exactly `app.tsuzuki.mangaall`
- proposed *future* `version.code >= 11`, `minHostApi >= 2`
- explicit MangaDot `https://mangadot.net` in the declared network origin set
- local network disabled

This is a **separate, narrower permission than generic storage**. It does not grant `tsuzuki.storage` or arbitrary large HTTP bodies. Merely passing an injectable native Host object into `ProviderScriptPackageRuntime.invoke` never suffices: that runtime checks manifest ID, version, Host API, exact source origin, and permission before adding `tsuzuki.mangaAllChapterSnapshot` to the QuickJs environment. Signed code10 and all other packages lack it.

A Host caller may in the future explicitly inject `ProviderMangaAllChapterSnapshotHostService`, with `start(workId, language)` and `next(workId, language, cursor)` that return bounded JSON. `MangaAllFutureChapterSnapshotHostBridge` adapts the internal typed pager to this shape; the QuickJs binding independently enforces ≤48KiB UTF-8/characters, strict JSON lexical grammar (not merely permissive JsonElement parsing) and the exact `items[]` / `nextCursor` envelope. Unquoted tokens, malformed JSON values, wrong outer structure and oversized pages fail as Host errors. It cannot change existing reading identities or implicitly authorize source selection.

## Still missing before production

- The installed app must explicitly construct the private service from a verified signed future package and its capability grants. **No production factory wires the service in this change**.
- Future unsigned Provider source code must call the new bridge only after reviewing exact MangaDot normalization/ID/date parity; original signed code10 continues its old code unchanged. Prior tests found a legacy numeric-ID dedup difference that needs reviewed correction in future Provider code.
- Host API >=2, new permission policy and signature/index updates require separate security and operator approval; no release/publication is allowed here.
- Further multi-process locking, real Host Kotlin/SQLite work-level editorial corroboration, Android Reader CDN pixels and physical A54 validation remain.
- Only after integrated three-source Naruto/Death Note/Bleach functional evidence may the existing explicit opt-in 30-work runner be executed, without weakening identity, provenance or rate-limit gates.

## 2026-11-22 — full core/common QuickJs-to-private-Host continuation fixture

A new offline JUnit test creates a **synthetic 4,341-row (>2MiB) MangaDot-shaped source JSON array**, obtains it only through `ProviderHttpHostService.getResource`, writes an internal `MangaAllFutureHostChapterSnapshot`, and exposes its typed `MangaAllFutureChapterSnapshotHostBridge` only by explicit test injection into `ProviderScriptPackageRuntime`. A future-only code11 manifest with exact source network permission and `mangaAllChapterSnapshot.enabled=true` invokes `reading.chapters` through **fresh QuickJs isolates for every V1 continuation page**.

The regression checks 4,341 unique source IDs in correct order, 17–32 pages at <=256 rows and <=48KiB UTF-8 + character output per page, **one source request for the complete snapshot**, signed-code10 denial of the same injected bridge, and failure of an expired-generation cursor after a second start. This connects the previously separate isolated Kotlin broker and JS tests, without any app/Binder service or external requests.

**Still NONSHIPPING:** The Android production process uses `IProviderHostBridge.aidl` and explicit `ProviderHostModule` filters; neither currently contains this new snapshot capability. The checked synthetic QuickJs invocation does not mean Android app-side Binder routing, artifact-signature approval, canonical work identity, MangaFire transport, Reader pixels or a physical A54 is working. A distinct reviewed future-contract change is needed before production injection, followed by three-source acceptance. No original P-256 code10 package, manifest or publication changes occur.

## 2026-11-22 — raw QuickJs evaluation never gains code11 snapshot privilege

The independent raw JavaScript path `ProviderQuickJsRuntime.evaluate` has no parsed/signed Provider manifest and therefore cannot authorize future chapter snapshots. Even if its caller accidentally injects `ProviderHostServices.mangaAllChapterSnapshot`, the raw VM strips this service before installing QuickJs bindings. Only `ProviderScriptPackageRuntime.invoke`, which separately checks exact future code11 manifest ID, API, source origin and independent permission, may use the native bridge. This protects the future Android Binder rollout from accidentally exposing snapshots through the legacy `evaluate()` endpoint.

An offline test passes a maliciously injected native service and verifies `typeof tsuzuki.mangaAllChapterSnapshot` remains undefined, JS invocation fails without contacting native Host, and the historical raw QuickJs VM tests are explicitly typed as `Unit` so JUnit Jupiter truly discovers them. This is a **defensive nonshipping core boundary**, not authorization of any code11 package or change to the signed code10 artifact.
